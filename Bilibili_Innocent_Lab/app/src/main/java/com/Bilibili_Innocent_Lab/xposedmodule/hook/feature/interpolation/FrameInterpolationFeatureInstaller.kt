package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.app.Activity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameAudioClockCompensation.Mode as AudioMode
import android.view.Surface
import android.view.SurfaceHolder
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire

/**
 * 主播放器插帧。只接管主播放器设置给 IjkMediaPlayer 的显示 Surface 所对应的视频解码器；
 * 广告、开屏、动图、编辑器等其他 ijk/MediaCodec 使用者不受影响。
 *
 * 真机：coreV2 主播放器的 IjkMediaPlayer 与 MediaCodec 运行在独立进程 tv.danmaku.bili:ijkservice
 * （清单中该进程只承载 IjkMediaPlayerService），Surface 经 Binder 传入后由
 * services.IjkMediaPlayerClient.setSurface 直接交给原生 _setVideoSurface；广告/开屏在主进程本地创建播放器。
 * 真机还表明：解码器 configure 拿到的是主进程 TextureView 的 SurfaceTexture，并不经过上述 Java 设置器。
 * 因此在该专用进程里，凡带 Surface 的视频解码器 configure 都视为主播放器（仍受加密、尺寸、准入约束），
 * 引擎在进程启动后即预热；主进程仍只认 IjkMediaPlayAdapter 设置的 Surface。
 *
 * 启用条件：用户开关且 [FrameInterpolationEntryGate.entryOpen]（原生库、模型、能力与媒体准入仍在播放时逐次检查，任一不满足即保持原生输出）。
 * 仅 debug 验证构建额外允许：宿主外部私有目录存在 innocent-frame-verify/enable 标记时在该进程启用，
 * 模型同样只从该目录按固定哈希读取，不随包分发。
 */
internal class FrameInterpolationFeatureInstaller(
    private val enabled: Boolean,
    private val verificationAllowed: Boolean,
    private val moduleApk: File?,
    /** 打开模块写入的 LSPosed 远程文件（模型）；null 时只能使用调试验证目录。 */
    private val remoteFile: ((String) -> android.os.ParcelFileDescriptor?)? = null,
    /** 用户设置的补帧帧率上限；实际目标还受系统实际刷新与本机能力约束（[FrameRateTarget]）。 */
    private val maxFps: Int = 60,
    /** 「插帧状态提示」：每个视频提示插帧是否生效与补帧目标帧率。 */
    private val statusToast: Boolean = false,
    /** 仅关闭模块自己的自动温度暂停，不改变系统温控或其他准入条件。 */
    private val disableThermalControl: Boolean = false,
    /** 开启后锁定「插帧方式」，不跨方式或按帧数门槛自动回退。 */
    private val forceMode: Boolean = false,
    /** 指定的插帧方式（"" / "qcom" / "rife"）。 */
    private val forcedMode: String = FrameInterpolationMode.AUTO_DEFAULT
) : FeatureInstaller {
    override val id: String = ID

    private val mainSurfaces = FrameSurfaceOwnership<Any, Surface>()
    private val bridges = Collections.synchronizedMap(WeakHashMap<MediaCodec, FramePlaybackBridge>())
    private val audioTracks = Collections.synchronizedMap(WeakHashMap<AudioTrack, FrameAudioDelay>())
    private val inMainDisplay = FrameCallScope()
    private val inMainOp = FrameCallScope()
    private val inConfigure = FrameCallScope()
    private val changingOutput = FrameCallScope()
    private val decoderGeneration = FrameDecoderGeneration<MediaCodec>()
    private val activationScheduled = java.util.concurrent.atomic.AtomicBoolean()
    private val pendingDrainScheduled = java.util.concurrent.atomic.AtomicBoolean()
    private val activationExecutor = HostReceiptWire.executor("bil-frame-activation")
    private val mediaRetryHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val writingSilence = ThreadLocal<Boolean>()
    private val playerClocks = FramePlayerClockProbe(::log, ::clockProbeEnabled)
    @Volatile private var audioDelayUs = 0L
    @Volatile private var active: Boolean? = null
    @Volatile private var nativeOnly = false
    @Volatile private var gpuDiagnostics = false
    @Volatile private var routes: FramePlaybackRoutes? = null
    private var playerProcess = false
    private lateinit var environment: HookEnvironment
    private val displayRequests = WeakHashMap<Activity, Triple<Int, Float, Float?>>()
    private val displayProbes = WeakHashMap<Activity, AutoCloseable>()
    /** 已接管解码器的 configure 参数：实测系统刷新低于目标时按新上限重新接管。 */
    private val configured = Collections.synchronizedMap(WeakHashMap<MediaCodec, PendingDecoder>())
    /** 本进程实测到的系统实际刷新上限（帧率上限自适应）；null 表示未发现限制。 */
    @Volatile private var displayLimit: Float? = null

    /** Explicit debug opt-in, shared by the UI and dedicated decoder processes. */
    private fun verificationTarget(): Int? {
        if (!verificationAllowed) return null
        val external = ReflectAccess.currentApplication()?.getExternalFilesDir(null) ?: return null
        val directory = File(external, VERIFY_DIR)
        if (!File(directory, "enable").isFile) return null
        return runCatching { File(directory, "target-fps").takeIf { it.length() in 1..8 }
            ?.readText()?.trim()?.toIntOrNull() }.getOrNull()?.takeIf { it in setOf(60, 120) }
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled && !verificationAllowed) return skipped(environment, "disabled")
        val loader = environment.classLoader ?: return skipped(environment, "missing-class-loader")
        val apk = moduleApk ?: return skipped(environment, "missing-module-apk")
        this.environment = environment
        playerProcess = environment.processName == PLAYER_PROCESS
        val adapter = runCatching { Class.forName(ADAPTER, false, loader) }.getOrNull()
            ?: return skipped(environment, "missing-adapter")
        val player = runCatching { Class.forName(PLAYER, false, loader) }.getOrNull()
            ?: return skipped(environment, "missing-player")
        var hooks = 0
        val installedRoles = HashSet<String>()
        fun hook(name: String, block: () -> Unit) {
            if (runCatching(block).onFailure { environment.logError("$ID.$name", it.javaClass.simpleName) }.isSuccess) {
                hooks++
                installedRoles += name
            }
        }
        val registrar = environment.registrar
        // 帧率上限高于 60 时正式播放也需要窗口级请求：TextureView 由宿主 UI 合成，只在生产者 Surface 上投票不足以切换模式。
        if ((verificationAllowed || maxFps > 60) && !playerProcess) {
            // A TextureView is consumed by the host UI, so a producer-only Surface vote is insufficient.
            // Public, window-scoped request; restore the host's original values when it leaves playback.
            hook("display.resume") {
                registrar.exact("$ID.display.resume", Activity::class.java, "onResume") {
                    after {
                        val activity = instance as? Activity ?: return@after
                        if (activity.javaClass.name != "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity") return@after
                        val target = verificationTarget() ?: maxFps.takeIf { it > 60 } ?: return@after
                        val window = activity.window ?: return@after
                        val attributes = window.attributes
                        displayRequests.putIfAbsent(activity, Triple(attributes.preferredDisplayModeId,
                            attributes.preferredRefreshRate,
                            if (android.os.Build.VERSION.SDK_INT >= 35) window.decorView.requestedFrameRate else null))
                        attributes.preferredRefreshRate = target.toFloat()
                        @Suppress("DEPRECATION")
                        val display = activity.windowManager.defaultDisplay
                        attributes.preferredDisplayModeId = display.supportedModes.firstOrNull {
                            kotlin.math.abs(it.refreshRate - target) < .1f &&
                                it.physicalWidth == display.mode.physicalWidth &&
                                it.physicalHeight == display.mode.physicalHeight
                        }?.modeId ?: 0
                        window.attributes = attributes
                        if (android.os.Build.VERSION.SDK_INT >= 35) window.decorView.requestedFrameRate = target.toFloat()
                        displayProbes.remove(activity)?.let { runCatching { it.close() } }
                        if (verificationAllowed) runCatching {
                            Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameHostDisplayProbe",
                                false, FrameInterpolationFeatureInstaller::class.java.classLoader)
                                .getMethod("acquire", Activity::class.java, Int::class.javaPrimitiveType)
                                .invoke(null, activity, target) as? AutoCloseable
                        }.onSuccess { if (it != null) displayProbes[activity] = it }
                            .onFailure { log("display.probe", it.cause?.toString() ?: it.toString()) }
                        log("display.request", "$target mode=${attributes.preferredDisplayModeId}")
                    }
                }
            }
            hook("display.pause") {
                registrar.exact("$ID.display.pause", Activity::class.java, "onPause") {
                    after {
                        val activity = instance as? Activity ?: return@after
                        displayProbes.remove(activity)?.let { runCatching { it.close() } }
                        val previous = displayRequests.remove(activity) ?: return@after
                        activity.window?.let { window ->
                            val attributes = window.attributes
                            attributes.preferredDisplayModeId = previous.first
                            attributes.preferredRefreshRate = previous.second
                            window.attributes = attributes
                            if (android.os.Build.VERSION.SDK_INT >= 35 && previous.third != null)
                                window.decorView.requestedFrameRate = previous.third!!
                        }
                        log("display.release", activity.javaClass.simpleName)
                    }
                }
            }
        }
        hook("display") {
            val videoDisplay = Class.forName("tv.danmaku.videoplayer.coreV2.VideoDisplay", false, loader)
            registrar.exact("$ID.display", adapter, "setVideoDisPlay", videoDisplay) {
                before { inMainDisplay.enter() }
                after { inMainDisplay.leave() }
            }
        }
        hook("op") {
            val ops = Class.forName("tv.danmaku.videoplayer.coreV2.adapter.IMediaPlayAdapter\$Ops", false, loader)
            registrar.exact("$ID.op", adapter, "invokeOp", ops, Any::class.java) {
                before { inMainOp.enter() }
                after { inMainOp.leave() }
            }
        }
        hook("surface") {
            registrar.exact("$ID.surface", player, "setSurface", Surface::class.java) {
                before {
                    val owner = instance ?: return@before
                    if (mainSurfaces.bind(owner, argOrNull(0) as? Surface, playerProcess || inMainDisplay.active)) {
                        log("surface.observed", "surface")
                        activate(apk)
                    } else log("surface.skip", "unowned_or_capacity")
                }
                after { if (hasThrowable) instance?.let(mainSurfaces::release) }
            }
        }
        hook("surface.holder") {
            registrar.exact("$ID.surface.holder", player, "setDisplay", SurfaceHolder::class.java) {
                before {
                    val owner = instance ?: return@before
                    val surface = (argOrNull(0) as? SurfaceHolder)?.surface
                    if (mainSurfaces.bind(owner, surface, playerProcess || inMainDisplay.active)) {
                        log("surface.observed.holder", "holder")
                        activate(apk)
                    } else log("surface.skip.holder", "unowned_or_capacity")
                }
                after { if (hasThrowable) instance?.let(mainSurfaces::release) }
            }
        }
        hook("surface.release") {
            registrar.exact("$ID.surface.release", player, "release") {
                before { instance?.let(mainSurfaces::release) }
            }
        }
        val client = if (playerProcess) runCatching { Class.forName(CLIENT, false, loader) }.getOrNull() else null
        if (client != null) {
            if (verificationAllowed) hook("clock.client") {
                client.declaredConstructors.forEach { constructor ->
                    registrar.constructor("$ID.clock.client", constructor) {
                        after { if (!hasThrowable) instance?.let { if (clockProbeEnabled() && activate(apk)) playerClocks.attach(it) } }
                    }
                }
                registrar.all("$ID.clock.event", CLIENT, "eventHandlerForClient") {
                    before {
                        if (argOrNull(0) == 300 || argOrNull(0) == 1300) {
                            if (!clockProbeEnabled()) return@before
                            instance?.let(playerClocks::attach)
                            log("clock.event", "client=${System.identityHashCode(instance)} what=${argOrNull(0)} rate=${argOrNull(1)} position=${argOrNull(2)} " +
                                "stamp=${argOrNull(3)} elapsed=${android.os.SystemClock.elapsedRealtime()} " +
                                "uptime=${android.os.SystemClock.uptimeMillis()} nanoMs=${System.nanoTime() / 1_000_000}")
                        }
                    }
                }
            }
            // setSurface 与外部渲染等路径最终都进入原生 _setVideoSurface；两处都登记，重复登记无副作用。
            for (method in listOf("setSurface", "_setVideoSurface")) hook("client.$method") {
                registrar.exact("$ID.client.$method", client, method, Surface::class.java) {
                    before {
                        val surface = argOrNull(0) as? Surface
                        val owner = instance ?: return@before
                        if (mainSurfaces.bind(owner, surface, true) && surface != null) activate(apk)
                    }
                    after { if (hasThrowable) instance?.let(mainSurfaces::release) }
                }
            }
            hook("client.release") {
                registrar.exact("$ID.client.release", client, "release") {
                    before {
                        val owner = instance ?: return@before
                        mainSurfaces.release(owner)
                        if (verificationAllowed) playerClocks.remove(owner)
                    }
                }
            }
            hook("client.speed") {
                registrar.exact("$ID.client.speed", client, "setPropertyFloat", Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!) {
                    after {
                        if (hasThrowable || argOrNull(0) != IJK_PLAYBACK_RATE) return@after
                        val owner = instance ?: return@after
                        val rate = argOrNull(1) as? Float ?: return@after
                        mainSurfaces.updateSpeed(owner, rate, allowNew = true)
                    }
                }
            }
        }
        hook("speed") {
            registrar.exact("$ID.speed", player, "setSpeed", Float::class.javaPrimitiveType!!) {
                after {
                    if (hasThrowable || !inMainOp.active) return@after
                    val owner = instance ?: return@after
                    val rate = argOrNull(0) as? Float ?: return@after
                    mainSurfaces.updateSpeed(owner, rate, allowNew = true)
                }
            }
        }
        hook("configure") {
            val shapes = MediaCodec::class.java.declaredMethods.filter { it.name == "configure" }
                .mapNotNull { method ->
                    FrameCodecConfigureShape.cryptoIndex(method.parameterTypes.map { it.name })?.let { method to it }
                }.toMap()
            check(shapes.isNotEmpty())
            registrar.all("$ID.configure", MediaCodec::class.java.name, "configure") {
                before {
                    // Public MediaCodec.configure(...) delegates to a private 5-arg configure(...) overload on
                    // the same codec. Ignore nested calls so they cannot detach the outer registration.
                    if (inConfigure.active) return@before
                    val codec = instance as? MediaCodec ?: return@before
                    inConfigure.enter()
                    setObjectExtra(CONFIGURE_SCOPE, java.lang.Boolean.TRUE)
                    try {
                        // A reconfigure may move a previously owned codec to an ineligible display or encoder.
                        if (configured.containsKey(codec)) detachDecoder(codec)?.close()
                        log("configure.seen", "observed")
                        val cryptoIndex = (method as? java.lang.reflect.Method)?.let { shapes[it] }
                            ?: run { log("configure.skip.abi", "unsupported"); return@before }
                        val flags = (argOrNull(if (cryptoIndex == 2) 3 else 2) as? Int) ?: return@before
                        if (flags and MediaCodec.CONFIGURE_FLAG_ENCODE != 0) {
                            log("configure.skip.encoder", "encoder"); return@before
                        }
                        val display = argOrNull(1) as? Surface ?: run { log("configure.skip.surface", "missing"); return@before }
                        val format = argOrNull(0) as? MediaFormat ?: run { log("configure.skip.format", "missing"); return@before }
                        if (nativeOnly) return@before
                        if (!playerProcess && !mainSurfaces.contains(display)) {
                            log("configure.skip.owner", "unowned_surface"); return@before
                        }
                        val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull()
                            ?: run { log("configure.skip.mime", "missing"); return@before }
                        if (!mime.startsWith("video/")) { log("configure.skip.mime", "non_video"); return@before }
                        if (!activate(apk)) return@before
                        log("configure.owned", "video_decoder")
                        var previous: FramePlaybackBridge? = null
                        val ticket = decoderGeneration.begin(codec) {
                            previous = bridges.remove(codec); pending.remove(codec); configured.remove(codec)
                        }
                        previous?.let { audioDelayUs = 0L; it.close() }
                        setObjectExtra(CONFIGURE_TICKET, ticket)
                        val decoder = PendingDecoder(format, display, argOrNull(cryptoIndex), ticket)
                        decoderGeneration.commit(codec, ticket) { configured[codec] = decoder }
                    } catch (failure: Throwable) {
                        inConfigure.leave()
                        setObjectExtra(CONFIGURE_SCOPE, null)
                        throw failure
                    }
                }
                after {
                    if (getObjectExtra(CONFIGURE_SCOPE) != java.lang.Boolean.TRUE) return@after
                    try {
                        // configure 失败时宿主会放弃这个解码器：立刻拆除，避免持有显示 Surface。
                        val ticket = getObjectExtra(CONFIGURE_TICKET) as? FrameDecoderGeneration.Ticket ?: return@after
                        if (hasThrowable) (instance as? MediaCodec)?.let {
                            detachDecoder(it, ticket)?.close()
                        }
                    } finally {
                        inConfigure.leave()
                    }
                }
            }
        }
        hook("start") {
            registrar.exact("$ID.start", MediaCodec::class.java, "start") {
                before {
                    (instance as? MediaCodec)?.let { configured[it]?.let { d -> setObjectExtra(CONFIGURE_TICKET, d.ticket) } }
                }
                after {
                    val codec = instance as? MediaCodec ?: return@after
                    val ticket = getObjectExtra(CONFIGURE_TICKET) as? FrameDecoderGeneration.Ticket ?: return@after
                    if (hasThrowable) detachDecoder(codec, ticket)?.close()
                    else decoderGeneration.commit(codec, ticket) {
                        val decoder = configured[codec] ?: return@commit
                        if (!decoder.started) { decoder.started = true; awaitEngine(codec, decoder) }
                    }
                }
            }
        }
        hook("output.surface") {
            registrar.exact("$ID.output.surface", MediaCodec::class.java, "setOutputSurface", Surface::class.java) {
                before {
                    if (changingOutput.active) return@before
                    val codec = instance as? MediaCodec ?: return@before
                    val decoder = configured[codec] ?: return@before
                    decoderGeneration.commit(codec, decoder.ticket) {
                        decoder.externalChanges++
                        if (decoder.externalChanges > 1) decoder.overlappingChanges = true
                        setObjectExtra(HOST_OUTPUT_CHANGE, decoder)
                        val requested = argOrNull(0) as? Surface ?: return@commit
                        val bridge = bridges[codec] ?: return@commit
                        val input = bridge.decoderInputFor(requested) ?: return@commit
                        setObjectExtra(HOST_OUTPUT_BORROW, bridge)
                        decoder.repeatedDisplay = true
                        args[0] = input
                    }
                }
                after {
                    val borrowed = getObjectExtra(HOST_OUTPUT_BORROW) as? FramePlaybackBridge
                    try {
                        val codec = instance as? MediaCodec ?: return@after
                        val previous = getObjectExtra(HOST_OUTPUT_CHANGE) as? PendingDecoder ?: return@after
                        val changed = if (borrowed == null && !hasThrowable) argOrNull(0) as? Surface else null
                        finishHostOutputChange(codec, previous, changed)
                    } finally {
                        borrowed?.finishDecoderInput()
                    }
                }
            }
        }
        hook("flush") {
            registrar.exact("$ID.flush", MediaCodec::class.java, "flush") {
                before { (instance as? MediaCodec)?.let { bridges[it]?.onFlush() } }
            }
        }
        for (method in listOf("stop", "reset", "release")) hook(method) {
            registrar.exact("$ID.$method", MediaCodec::class.java, method) {
                before { (instance as? MediaCodec)?.let { setObjectExtra(STOP_BRIDGE, detachDecoder(it)) } }
                after { (getObjectExtra(STOP_BRIDGE) as? FramePlaybackBridge)?.close() }
            }
        }
        hook("audio.ctor") { installAudio(registrar) }
        // 专用播放进程：Application 就绪后立即决定是否启用并预热引擎，首个解码器 configure 时即可接管。
        if (playerProcess) environment.postToMain?.invoke { activate(apk) }
        environment.reportStatus(STATUS, "installed:$hooks")
        val complete = FrameHookCoverage.complete(installedRoles, playerProcess)
        log("installed", "hooks=$hooks complete=$complete process=${environment.processName}")
        return FeatureInstallResult.Installed(hooks, complete = complete)
    }

    /** ijk 经 J4A 用旧式 6 参构造创建 PCM 输出；只有这些轨道参与音画补偿。 */
    private fun installAudio(registrar: com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar) {
        val int = Int::class.javaPrimitiveType!!
        val constructor = AudioTrack::class.java.getConstructor(int, int, int, int, int, int)
        registrar.constructor("$ID.audio.ctor", constructor) {
            after {
                if (hasThrowable) return@after
                val track = instance as? AudioTrack ?: return@after
                val bytes = when (argOrNull(3) as? Int) { 2 -> 2; 3 -> 1; 4 -> 4; else -> return@after }
                val channels = Integer.bitCount((argOrNull(2) as? Int) ?: return@after)
                val rate = (argOrNull(1) as? Int) ?: return@after
                runCatching { FrameAudioDelay(rate, channels, bytes) }.getOrNull()?.let { audioTracks[track] = it }
                audioRates[track] = rate
            }
        }
        installAudioClock(registrar)
        val bytes = ByteArray::class.java
        for (parameters in listOf(arrayOf(bytes, int, int), arrayOf(bytes, int, int, int))) {
            registrar.exact("$ID.audio.write${parameters.size}", AudioTrack::class.java, "write", *parameters) {
                before {
                    // 只有找不到 ijk 读取的时钟接口时才退回写静音：静音占用固定大小的 AudioTrack 缓冲并扰动 ijk 时钟，
                    // 宿主内真机 A/B 证实会造成音频卡顿与视频顿挫。
                    if (audioMode != AudioMode.SILENCE || writingSilence.get() == true) return@before
                    val track = instance as? AudioTrack ?: return@before
                    val delay = audioTracks[track] ?: return@before
                    delay.setTargetDelayUs(audioDelayUs)
                    val size = (argOrNull(2) as? Int) ?: return@before
                    val plan = delay.onWrite(size)
                    if (plan.silenceBytes > 0) {
                        writingSilence.set(true)
                        try { track.write(ByteArray(plan.silenceBytes), 0, plan.silenceBytes, AudioTrack.WRITE_BLOCKING) }
                        finally { writingSilence.set(false) }
                    }
                    if (plan.dropBytes > 0) {
                        // 整段被追回：不调用原方法，向宿主报告全部写入，宿主自身的计数保持不变。
                        if (plan.dropBytes >= size) { result = size; return@before }
                        setObjectExtra(DROPPED, plan.dropBytes)
                        args[1] = (argOrNull(1) as Int) + plan.dropBytes
                        args[2] = size - plan.dropBytes
                    }
                }
                after {
                    val dropped = getObjectExtra(DROPPED) as? Int ?: return@after
                    val written = result as? Int ?: return@after
                    if (written >= 0) result = written + dropped
                }
            }
        }
        registrar.exact("$ID.audio.flush", AudioTrack::class.java, "flush") {
            before { (instance as? AudioTrack)?.let { audioTracks[it]?.onFlush() } }
        }
    }

    /**
     * 音画补偿改为平移 ijk 读取的音频时钟，不动音频数据：ijk 按 AudioTrack 回报的播放位置/时延推算主时钟并据此
     * 释放视频帧。把回报的播放位置前移 delay（或时延减去 delay），ijk 就提前 delay 释放视频帧，管线再延后 delay，
     * 画面与未改动的声音同步。真机先统计 ijk 实际调用哪个接口，再只平移该接口。
     */
    private fun installAudioClock(registrar: com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar) {
        runCatching {
            registrar.exact("$ID.audio.timestamp", AudioTrack::class.java, "getTimestamp", AudioTimestamp::class.java) {
                after {
                    val track = instance as? AudioTrack ?: return@after
                    val rate = audioRates[track] ?: return@after
                    timestampCalls.incrementAndGet()
                    if (result == true) (argOrNull(0) as? AudioTimestamp)?.let {
                        if (it.framePosition > 0L && it.nanoTime > 0L) timestampSuccesses.incrementAndGet()
                    }
                    if (audioMode == AudioMode.TIMESTAMP && result == true)
                        (argOrNull(0) as? AudioTimestamp)?.let { it.framePosition += shiftFrames(rate) }
                }
            }
        }.onFailure { log("hook.missing", "audio.getTimestamp") }
        runCatching {
            registrar.exact("$ID.audio.head", AudioTrack::class.java, "getPlaybackHeadPosition") {
                after {
                    val track = instance as? AudioTrack ?: return@after
                    val rate = audioRates[track] ?: return@after
                    headCalls.incrementAndGet()
                    val head = result as? Int ?: return@after
                    if (audioMode == AudioMode.HEAD) result = head + shiftFrames(rate).toInt()
                }
            }
        }.onFailure { log("hook.missing", "audio.getPlaybackHeadPosition") }
        runCatching {
            registrar.exact("$ID.audio.latency", AudioTrack::class.java, "getLatency") {
                after {
                    val track = instance as? AudioTrack ?: return@after
                    if (audioRates[track] == null) return@after
                    latencyCalls.incrementAndGet()
                    val latency = result as? Int ?: return@after
                    result = FrameAudioClockCompensation.latencyMs(latency, audioDelayUs, audioMode)
                }
            }
        }.onFailure { log("hook.missing", "audio.getLatency") }
    }

    private fun shiftFrames(rate: Int): Long = audioDelayUs * rate / 1_000_000L

    private class PendingDecoder(val format: MediaFormat, val display: Surface, val crypto: Any?,
                                 val ticket: FrameDecoderGeneration.Ticket,
                                 val attempt: FramePlaybackRoutes.Attempt = FramePlaybackRoutes.Attempt()) {
        @Volatile var started = false
        @Volatile var waitingForRife = false
        // Access under decoderGeneration's lock, including module-owned setOutputSurface calls.
        var externalChanges = 0
        var overlappingChanges = false
        var changedDisplay: Surface? = null
        var repeatedDisplay = false
    }
    private val pending = FramePendingTakeovers<MediaCodec, PendingDecoder>()

    private fun canAttempt(decoder: PendingDecoder): Boolean =
        routes?.let { !decoder.waitingForRife || it.rifeCanAdvance(decoder.attempt) } == true

    private fun awaitEngine(codec: MediaCodec, decoder: PendingDecoder) {
        decoderGeneration.commit(codec, decoder.ticket) {
            pending.await(codec, decoder, { canAttempt(decoder) }, ::schedulePendingTakeover)
        }
    }

    private fun schedulePendingTakeover() {
        if (!pendingDrainScheduled.compareAndSet(false, true)) return
        runCatching { activationExecutor.execute(HostThreadGuard.runnable("frame.late") {
            try { attachPending() }
            finally {
                pendingDrainScheduled.set(false)
                if (pending.hasReady(::canAttempt)) schedulePendingTakeover()
            }
        }) }.onFailure { pendingDrainScheduled.set(false); log("bridge.late.skip", "queue") }
    }

    private fun detachDecoder(codec: MediaCodec, ticket: FrameDecoderGeneration.Ticket? = null): FramePlaybackBridge? {
        var detached: FramePlaybackBridge? = null
        decoderGeneration.end(codec, ticket) {
            pending.remove(codec); configured.remove(codec); detached = bridges.remove(codec)
            if (detached != null) audioDelayUs = 0L
        }
        return detached
    }

    /** 引擎就绪后接管冷启动期间已原生播放的解码器：在 worker 上把其输出切到捕获 Surface。 */
    private fun attachPending() {
        val routes = routes ?: return
        val waiting = pending.takeReady(::canAttempt)
        for ((codec, decoder) in waiting) {
            if (!decoderGeneration.matches(codec, decoder.ticket)) continue
            val weakCodec = WeakReference(codec)
            val ticket = decoder.ticket
            val host = object : FrameCaptureBridge.Host by bridgeHost {
                override fun onDisplayLimited(codec: MediaCodec, hz: Float) {
                    if (!ticket.isActive()) return
                    displayLimit = minOf(displayLimit ?: hz, hz)
                    reattach(codec, ticket, "limit=${hz.toInt()}Hz")
                }
                override fun onRebuild(codec: MediaCodec) = reattach(codec, ticket, "overload")
                override fun setAudioDelayUs(delayUs: Long) {
                    if (ticket.isActive()) bridgeHost.setAudioDelayUs(delayUs)
                }
                override fun onStatus(status: FrameStatus) {
                    if (ticket.isActive()) bridgeHost.onStatus(status)
                }
            }
            val outcome = try {
                routes.attach(codec, decoder.format, decoder.display, decoder.crypto, host, decoder.attempt,
                    requestedTargetFps = verificationTarget(), isCurrent = ticket::isActive,
                    attach = { surface -> weakCodec.get()?.let { changeOutput(it, ticket, surface) } == true },
                    onWindowFailed = { backend -> weakCodec.get()?.let { reattach(it, ticket, "$backend-failed") } })
            } catch (failure: Exception) {
                log("route.error", failure.javaClass.simpleName)
                continue
            } catch (failure: LinkageError) {
                log("route.error", failure.javaClass.simpleName)
                continue
            }
            when (outcome) {
                is FramePlaybackRoutes.Result.Attached -> {
                    val published = decoderGeneration.commit(codec, ticket) {
                        bridges[codec] = outcome.bridge
                        outcome.bridge.onPublished()
                    }
                    if (!published) outcome.bridge.close()
                }
                FramePlaybackRoutes.Result.WaitForRife -> {
                    decoder.waitingForRife = true
                    awaitEngine(codec, decoder)
                }
                FramePlaybackRoutes.Result.WaitForMedia -> {
                    // MediaCodec output metadata and AudioTrack attributes can arrive after start.
                    mediaRetryHandler.postDelayed({
                        val current = weakCodec.get() ?: return@postDelayed
                        val next = configured[current] ?: return@postDelayed
                        if (next.ticket === ticket) awaitEngine(current, next)
                    }, decoder.attempt.mediaRetryDelayMs)
                }
                FramePlaybackRoutes.Result.Original -> Unit
            }
        }
    }

    private fun finishHostOutputChange(codec: MediaCodec, previous: PendingDecoder, changed: Surface?) {
        var old: FramePlaybackBridge? = null
        decoderGeneration.commit(codec, previous.ticket) {
            if (changed != null) previous.changedDisplay = changed
            if (--previous.externalChanges != 0) return@commit
            val surface = previous.changedDisplay
            val overlapping = previous.overlappingChanges
            val mixed = overlapping && previous.repeatedDisplay
            previous.changedDisplay = null
            previous.overlappingChanges = false
            previous.repeatedDisplay = false
            if (surface == null) return@commit
            // A real retarget racing a redirected repeat may leave either buffer queue on the codec.
            // Establish the successful host destination before releasing the old QCOM input.
            if (mixed && !changeOutput(codec, previous.ticket, surface)) return@commit
            decoderGeneration.replace(codec, previous.ticket) { ticket ->
                pending.remove(codec)
                old = bridges.remove(codec)
                configured.remove(codec)
                if (!overlapping && (playerProcess || mainSurfaces.contains(surface))) {
                    val decoder = PendingDecoder(previous.format, surface, previous.crypto, ticket)
                    decoder.started = previous.started
                    configured[codec] = decoder
                    if (decoder.started) awaitEngine(codec, decoder)
                }
            }
        }
        if (old != null) { audioDelayUs = 0; old?.close() }
    }

    private fun changeOutput(codec: MediaCodec, ticket: FrameDecoderGeneration.Ticket, surface: Surface): Boolean {
        var changed = false
        decoderGeneration.commit(codec, ticket) {
            if (configured[codec]?.externalChanges != 0) return@commit
            changingOutput.enter()
            try { changed = runCatching { codec.setOutputSurface(surface) }.isSuccess }
            finally { changingOutput.leave() }
        }
        return changed
    }

    /** Called after the backend has disconnected its EGL producer and restored native playback. */
    private fun reattach(codec: MediaCodec, ticket: FrameDecoderGeneration.Ticket, reason: String) {
        val previous = configured[codec]?.takeIf { it.ticket === ticket } ?: return
        decoderGeneration.replace(codec, ticket) { next ->
            val replacement = PendingDecoder(previous.format, previous.display, previous.crypto, next, previous.attempt)
            replacement.started = true
            configured[codec] = replacement
            bridges.remove(codec)
            awaitEngine(codec, replacement)
        } ?: return
        log("bridge.retarget", reason)
    }

    /** 首次需要补偿时按 ijk 已调用过的接口选择平移方式；都没有调用过才退回写静音。 */
    private fun chooseAudioMode() {
        if (audioMode != AudioMode.UNDECIDED) return
        audioMode = FrameAudioClockCompensation.choose(timestampSuccesses.get(), headCalls.get(), latencyCalls.get())
        log("audio.mode", "$audioMode timestamp=${timestampCalls.get()} valid=${timestampSuccesses.get()} " +
            "head=${headCalls.get()} latency=${latencyCalls.get()}")
    }

    @Volatile private var audioMode = AudioMode.UNDECIDED
    private val audioRates = Collections.synchronizedMap(WeakHashMap<AudioTrack, Int>())
    private val timestampCalls = java.util.concurrent.atomic.AtomicInteger()
    private val timestampSuccesses = java.util.concurrent.atomic.AtomicInteger()
    private val headCalls = java.util.concurrent.atomic.AtomicInteger()
    private val latencyCalls = java.util.concurrent.atomic.AtomicInteger()

    /** 首次见到主显示 Surface 时决定本进程是否启用，并在后台预热引擎。 */
    private fun activate(apk: File): Boolean {
        active?.let { return it }
        if (!enabled && !verificationAllowed) return false
        if (!activationScheduled.compareAndSet(false, true)) return true
        val post = environment.postToMain
        if (post == null) {
            activationScheduled.set(false)
            log("activate.wait", "dispatcher")
            return true
        }
        post {
            runCatching { activationExecutor.execute {
                try { prepareActivation(apk) }
                catch (failure: Exception) { log("activate.failed", failure.javaClass.simpleName) }
                finally { activationScheduled.set(false) }
            } }.onFailure { activationScheduled.set(false); log("activate.wait", "queue") }
        }
        return true
    }

    /** Host directories, debug opt-in files and display metadata are resolved off the Hook thread. */
    private fun prepareActivation(apk: File) {
        synchronized(this) {
            if (active != null) return
            val host = ReflectAccess.currentApplication() ?: run { log("activate.wait", "application"); return }
            val external = if (verificationAllowed) host.getExternalFilesDir(null) else null
            val verifying = verificationAllowed && external != null && File(external, "$VERIFY_DIR/enable").isFile
            val decided = enabled || verifying
            nativeOnly = verificationAllowed && external != null && File(external, "$VERIFY_DIR/native-only").isFile
            gpuDiagnostics = verificationAllowed && external != null && File(external, "$VERIFY_DIR/measure-gpu").isFile
            if (decided && !nativeOnly) {
                val debugModel = external?.let { File(it, "$VERIFY_DIR/${FrameModelSpec.NAME}") }
                // 模型来源：debug 验证目录（仅验证构建）优先，否则用模块下载后写入的远程文件，复制并校验到宿主私有目录。
                val modelSource: () -> File? = {
                    if (verifying && debugModel != null && File(debugModel, FrameModelSpec.WEIGHTS.fileName).isFile) debugModel
                    else when (val result = FrameModelCache.materialize(host.noBackupFilesDir) { name ->
                        remoteFile?.invoke(name)?.let(android.os.ParcelFileDescriptor::AutoCloseInputStream) }) {
                        is FrameModelCache.Result.Ready -> result.directory
                        else -> { log("model.unavailable", result.toString()); null }
                    }
                }
                // 能力测量到面板分辨率为止：面板达 4K 才测 3840×2160（4K 测量本身约占 500 MB 显存池）。
                val panelLong = runCatching {
                    host.getSystemService(android.hardware.display.DisplayManager::class.java)
                        .getDisplay(android.view.Display.DEFAULT_DISPLAY).supportedModes
                        .maxOf { maxOf(it.physicalWidth, it.physicalHeight) }
                }.getOrDefault(0)
                val sizes = listOf(1280 to 720, 1920 to 1080, 2560 to 1440) +
                    (if (panelLong >= 3840) listOf(3840 to 2160) else emptyList())
                val modePolicy = FrameInterpolationModePolicy(forceMode, forcedMode)
                val routeStrategy = if (!modePolicy.forceMode) {
                    FramePlaybackRoutes.Strategy.Auto
                } else {
                    val capabilities = FrameGpuCapabilityProbe.probe()
                    when (val resolved = modePolicy.resolveForcedMode(capabilities)) {
                        null -> FramePlaybackRoutes.Strategy.Unavailable
                        else -> FramePlaybackRoutes.Strategy.Forced(resolved)
                    }
                }
                val holder = FrameInterpolationEngineHolder(apk, host.codeCacheDir, modelSource,
                    log = { event, detail -> log(event, detail) }, capabilitySizes = sizes,
                    dumpDir = if (verifying) File(external, "$VERIFY_DIR/dump").apply { mkdirs() } else null,
                    bypassFrameBudget = modePolicy.bypassFrameBudget)
                holder.onReady = ::schedulePendingTakeover
                holder.onPreparationChanged = ::schedulePendingTakeover
                routes = FramePlaybackRoutes(holder, routeStrategy)
                log("mode.policy", "force=$forceMode configured=$forcedMode strategy=${routeStrategy.javaClass.simpleName}")
                schedulePendingTakeover()
            }
            log("activate", "enabled=$enabled verifying=$verifying decided=$decided")
            active = decided
        }
    }

    private val bridgeHost = object : FrameCaptureBridge.Host {
        private var faultCheckedNs = 0L
        private var rejectClock = false
        @Synchronized override fun rejectClockForVerification(): Boolean {
            if (!verificationAllowed) return false
            val now = System.nanoTime()
            if (now - faultCheckedNs >= 250_000_000L) {
                faultCheckedNs = now
                rejectClock = ReflectAccess.currentApplication()?.getExternalFilesDir(null)?.let {
                    File(it, "$VERIFY_DIR/enable").isFile && File(it, "$VERIFY_DIR/reject-clock").isFile
                } == true
            }
            return rejectClock
        }
        override fun setAudioDelayUs(delayUs: Long) {
            if (delayUs > 0L) chooseAudioMode()
            // 覆盖时间线允许的最大延迟 250 ms + 相位锁定（≤ 100 ms）；正式播放当前用 150 ms。
            audioDelayUs = delayUs.coerceIn(0L, 350_000L)
        }
        override fun playbackRateReader(surface: Surface): () -> Double = mainSurfaces.rateReader(surface, playerProcess)
        override fun audio(): FrameMediaPolicy.Audio =
            if (audioTracks.isEmpty()) FrameMediaPolicy.Audio()
            else FrameMediaPolicy.Audio(output = FrameMediaPolicy.AudioOutput.PCM,
                offloaded = FrameMediaPolicy.Evidence.NO, tunneled = FrameMediaPolicy.Evidence.NO)
        override fun log(event: String, detail: String) = this@FrameInterpolationFeatureInstaller.log(event, detail)
        override fun context(): android.content.Context? = ReflectAccess.currentApplication()
        override fun maxFps(): Int = maxFps
        override fun thermalControlEnabled(): Boolean = !disableThermalControl
        override fun diagnostics(): Boolean = gpuDiagnostics
        override fun presentLeadOverrideNs(): Long? = if (!verificationAllowed) null else runCatching {
            File(ReflectAccess.currentApplication()?.getExternalFilesDir(null), "$VERIFY_DIR/present-lead-us")
                .takeIf { it.isFile }?.readText()?.trim()?.toLong()?.coerceIn(0L, 40_000L)?.times(1_000L)
        }.getOrNull()
        override fun displayLimitHz(): Float? = displayLimit
        override fun onStatus(status: FrameStatus) = showStatus(status)

    }

    @Volatile private var lastStatusText: String? = null
    @Volatile private var lastStatusNs = 0L

    /**
     * 插帧状态提示（开关打开时）：在宿主主线程弹出短 Toast。同一文案 [STATUS_REPEAT_NS] 内只弹一次
     * （同一视频的 configure 重试、降档重建不会连弹）。专用播放进程没有 Activity，用普通文字 Toast。
     */
    private fun showStatus(status: FrameStatus) {
        val stage = when (status) {
            is FrameStatus.Active -> "active"
            is FrameStatus.Inactive -> status.reason.name.lowercase(java.util.Locale.ROOT)
        }
        log("status.$stage", status.toString())
        if (!statusToast) return
        val context = ReflectAccess.currentApplication() ?: return
        val text = runCatching { FrameStatus.text(status, if (playerProcess) InjectedUiLocale.messagesForSecondaryProcess(context) else InjectedUiLocale.messages(context)) }.getOrNull() ?: return
        val now = System.nanoTime()
        synchronized(this) {
            if (text == lastStatusText && now - lastStatusNs < STATUS_REPEAT_NS) return
            lastStatusText = text
            lastStatusNs = now
        }
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post(HostThreadGuard.runnable("frame.status") {
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun log(event: String, detail: String) {
        if (::environment.isInitialized) runCatching { environment.logInfo("$ID.$event", "[BIL] frame $event $detail") }
        // 仅 debug 验证构建：模块日志按键去重，真机验证需要逐条事件与周期统计。
        if (verificationAllowed) runCatching { android.util.Log.i(VERIFY_TAG, "$event $detail") }
    }

    private fun clockProbeEnabled(): Boolean = verificationAllowed &&
        ReflectAccess.currentApplication()?.getExternalFilesDir(null)?.let {
            File(it, "$VERIFY_DIR/enable").isFile && File(it, "$VERIFY_DIR/clock-probe").isFile
        } == true

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult {
        environment.reportStatus(STATUS, reason)
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "player_frame_interpolation"
        private const val STATUS = "player_frame_interpolation"
        private const val ADAPTER = "tv.danmaku.videoplayer.coreV2.adapter.ijk.IjkMediaPlayAdapter"
        private const val PLAYER = "tv.danmaku.ijk.media.player.IjkMediaPlayer"
        private const val VERIFY_DIR = "innocent-frame-verify"
        private const val PLAYER_PROCESS = "tv.danmaku.bili:ijkservice"
        private const val CLIENT = "tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient"
        private const val VERIFY_TAG = "InnocentFrame"
        private const val CONFIGURE_TICKET = "innocent.frame.configure.ticket"
        private const val CONFIGURE_SCOPE = "innocent.frame.configure.scope"
        private const val HOST_OUTPUT_CHANGE = "innocent.frame.host-output-change"
        private const val HOST_OUTPUT_BORROW = "innocent.frame.host-output-borrow"
        private const val STOP_BRIDGE = "innocent.frame.stop.bridge"
        private const val DROPPED = "innocent.frame.audio.dropped"
        private const val STATUS_REPEAT_NS = 4_000_000_000L
    }
}
