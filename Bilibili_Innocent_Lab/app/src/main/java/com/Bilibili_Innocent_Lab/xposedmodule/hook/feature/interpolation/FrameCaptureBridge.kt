package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Choreographer
import android.view.Surface
import io.github.jichuo1.meguviz.media.MediaNative
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个被接管的宿主视频解码器：解码输出改到 [FrameGlCapture] 的 SurfaceTexture，经
 * [FrameInterpolationSession] 插帧后由 [FrameGlPresenter] 在独立呈现线程画回宿主显示 Surface。任何失败都走 [returnToNative]：
 * 先断开 EGL，再把解码器输出切回显示 Surface，本解码器实例此后保持原生播放。
 *
 * 真机（高通）：解码器无视 CPU 读用途、给 ImageReader 分配 UBWC 压缩布局而无法锁定平面，
 * 因此捕获只走 GPU：驱动负责解压与 YUV→RGB，模块只做 RGBA 读回。
 *
 * 线程：捕获、Choreographer 驱动、呈现分别使用独立线程；宿主 MediaCodec 线程只调用
 * [onFlush]/[close]，二者的会话清理投递到捕获线程。
 */
internal class FrameCaptureBridge private constructor(
    private val config: Config,
    codec: MediaCodec,
    private val display: Surface,
    /** 处理尺寸（[FrameProcessingSize]）：捕获缩放后的尺寸，推理、账本与呈现都用它。 */
    private val width: Int,
    private val height: Int,
    /** 解码输出尺寸（可达 4K）：只用于媒体准入与 epoch。 */
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val source: FrameHostMediaSignals.Values,
    private val secureDecoder: Boolean,
    private val tunneled: Boolean,
    private val protectedContent: Boolean,
    private val configureFlags: Int,
    private val engine: FrameInterpolationEngineHolder,
    private val host: Host,
    private val isCurrent: () -> Boolean
) : FramePlaybackBridge {
    private val codecReference = java.lang.ref.WeakReference(codec)
    private var changeOutput: ((Surface) -> Boolean)? = null
    private val codecId = System.identityHashCode(codec).toLong()
    /** 宿主侧回调：音频补偿目标、倍速与音频输出证据、诊断日志。 */
    interface Host {
        fun setAudioDelayUs(delayUs: Long)
        fun playbackRateReader(surface: Surface): () -> Double
        fun audio(): FrameMediaPolicy.Audio
        fun log(event: String, detail: String)
        /** 用于温控余量与 ADPF；null 时两者都不启用。 */
        fun context(): android.content.Context? = null
        /** EGL swap 完成时刻；是提交证据，不能当作屏幕实际扫描显示时间。 */
        fun onFrameSubmitted(dueNs: Long, submittedNs: Long) = Unit
        /** 用户设置的补帧帧率上限（[FrameRateTarget.CEILINGS]）。 */
        fun maxFps(): Int = 60
        /** 是否根据热余量、温控状态及电池温度自动回到原生播放；默认保留保护。 */
        fun thermalControlEnabled(): Boolean = true
        /** 本进程此前实测到的系统实际刷新上限；null 表示未发现限制。 */
        fun displayLimitHz(): Float? = null
        /**
         * 实测 vsync 明显低于目标：本实例已把解码器切回显示 Surface 并拆除，宿主应记住该上限并按它重新接管。
         * 在呈现线程调用。
         */
        fun onDisplayLimited(codec: MediaCodec, hz: Float) = Unit
        /** 引擎过载、本实例的处理尺寸已不可用：已切回原生并拆除，宿主应按当前能力重新接管。在呈现线程调用。 */
        fun onRebuild(codec: MediaCodec) = Unit
        /** 仅 debug 验证构建：开启额外的逐帧诊断（GPU 完成耗时）。 */
        fun diagnostics(): Boolean = false
        /** 仅 debug 验证构建：在线调整提交提前量（测定取帧相位）；null 用默认。 */
        fun presentLeadOverrideNs(): Long? = null
        /** 仅验证使用：向真实时间线注入不可信时钟，仍走正式的有界恢复与 Surface 交还路径。 */
        fun rejectClockForVerification(): Boolean = false
        /** 插帧状态提示：不接管、首个插值帧已呈现、或播放中回到原生。可在任意线程调用。 */
        fun onStatus(status: FrameStatus) = Unit
    }

    /**
     * 真机 720p：与 GL 捕获共用 GPU 时推理平均约 27 ms。规划需至少提前 minLead，而被插值的后一帧在规划时
     * 必须已到达，故呈现延迟 ≥ 提前量 + 一个源帧间隔（30 fps 为 33 ms）；音频按同一延迟补偿。
     */
    data class Config(
        val targetFps: Int = 60,
        // 宿主内真机：帧到达抖动大于测试应用，80 ms 时约 5 槽/秒等不到后一帧；放宽到 100 ms / 提前 60 ms。
        val presentationDelayNs: Long = 100_000_000L,
        val maxLeadNs: Long = 60_000_000L,
        val minLeadNs: Long = 32_000_000L,
        /** 接管的解码输出上限（4K）；处理尺寸另由能力与屏幕决定，见 [FrameProcessingSize]。 */
        val maxSourcePixels: Long = 4096L * 2160L,
        /** 时间线窗口 4 + 推理输出 1 + Presenter 临近交付前持有的约 3 帧 + 余量；RGB8 每帧 720p 2.7 MB、1440p 11 MB。 */
        val maxBuffers: Int = 12,
        val frameWindow: Int = 4,
        val stageUploads: Boolean = true,
        /** 播放中实测 vsync，系统实际刷新低于目标时降档重建（显式验证档位关闭）。 */
        val measureVsync: Boolean = true,
        /** 正式播放：源帧率已达目标时回原生（验证构建的显式档位可做纯复制测试）。 */
        val interpolationRequired: Boolean = true,
        /** 宿主格式给出的源帧率，仅用于状态提示。 */
        val sourceFps: Float? = null,
        /** 开启「强制指定插帧方式」时跳过帧率吞吐与掉帧回退门槛。 */
        val bypassFrameBudget: Boolean = false
    )

    private val presenter = FrameGlPresenter(display, config.targetFps, config.stageUploads) { onNativeRestored(it) }.apply {
        onSubmitted = host::onFrameSubmitted
        measureGpu = host.diagnostics()
    }
    private val capture = FrameGlCapture(width, height, sourceWidth, sourceHeight)
    private val playbackSpeed = host.playbackRateReader(display)
    private val clock = FrameHostClock()
    private val ledger = FrameBufferLedger(config.maxBuffers)
    private val gate = FrameMediaGate()
    // 真机：起步约 1/3 s 内 GL 线程仍在做首次纹理与读回分配，偶发连续 3 帧迟到即触发回退（10 次中 2 次）；
    // 延长预热并放宽连续失败门限，持续性问题仍由 25% 失败率判定。
    private val recovery = FramePresentationRecovery(maxConsecutiveFailures = 6, warmupSamples = WARMUP_SAMPLES,
        fallbackOnFrameDrops = !config.bypassFrameBudget)
    private val timeline = FrameTimeline(config.targetFps, presentationDelayNs = config.presentationDelayNs,
        maxLeadNs = config.maxLeadNs, minLeadNs = config.minLeadNs, originalToleranceUs = 1_000_000L / config.targetFps / 4, phaseLock = true,
        maxClockUncertaintyUs = MAX_HOST_CLOCK_UNCERTAINTY_US, frameWindow = config.frameWindow)
    private val session = FrameInterpolationSession(config.targetFps, ledger, engine.synthesizer(), engine.worker(),
        presenter, System::nanoTime, recovery, timeline = timeline,
        // 只认近乎完全相同的画面：宿主真机慢速摇镜被宽松的「基本静止」判为重复后连丢两帧，100 ms 间隔里每个槽位
        // 都要推理，负载翻倍并跳槽（录屏中慢镜段每半秒只有 18–23 帧）。
        duplicate = { previous, current, format ->
            MediaNative.isDuplicate(previous, current, format.width, format.height, format.stride) == 1 })
    // 捕获线程写、驱动线程读：admitted、lastArrivalNs、discontinuity；后者两线程都会递增。
    @Volatile private var admitted = false
    private val discontinuityCount = java.util.concurrent.atomic.AtomicLong()
    private val discontinuity: Long get() = discontinuityCount.get()
    private var epochCount = 0L
    @Volatile private var lastArrivalNs = Long.MIN_VALUE
    @Volatile private var lastReportNs = 0L
    @Volatile private var audioDelayUs = 0L
    /**
     * 规划/时钟驱动线程：与捕获分开。捕获线程会在 glReadPixels 上等 GPU 让出（推理约 20–30 ms），若驱动也在这里，
     * 规划就会晚于槽位时刻、越过槽位（宿主真机 skippedBehind 持续增长、每 5 s 少呈现约 30 帧）。
     */
    private val driveThread = android.os.HandlerThread("innocent-frame-drive", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        .apply { start() }
    private val driveHandler = android.os.Handler(driveThread.looper)
    private var power: FramePowerAdvisor? = null
    private val cadence = FrameSourceCadence(config.targetFps, interpolationRequired = config.interpolationRequired)
    private val native = AtomicBoolean(false)
    private val restoring = AtomicBoolean(false)
    /** 实测 vsync（帧率上限自适应）：显式验证档位不测。 */
    private val vsync = FrameVsyncMeter()
    @Volatile private var limitedHz: Float? = null
    @Volatile private var rebuild = false
    private val closed = AtomicBoolean(false)
    /** 交给宿主 configure 的替换 Surface（捕获 SurfaceTexture）。 */
    var captureSurface: Surface? = null
        private set

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (closed.get() || native.get() || restoring.get()) return
            if (codecReference.get() == null || !isCurrent()) { close(); return }
            runCatching { drive(frameTimeNanos) }.onFailure { returnToNative("drive:${it.javaClass.simpleName}") }
            if (!closed.get() && !native.get() && !restoring.get()) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /**
     * attach 非空为「迟接管」：解码器已在原生播放（冷启动时引擎尚未就绪），先建好捕获 Surface，
     * 由 attach 把运行中的解码器切过来（setOutputSurface 会断开其对显示 Surface 的连接），之后呈现器才能连接显示 Surface；
     * 任何一步失败都把解码器切回显示 Surface。
     */
    private fun start(attach: ((Surface) -> Boolean)? = null): Boolean {
        changeOutput = attach
        engine.prewarm(width, height)
        power = FramePowerAdvisor(host.context(), 1_000_000_000L / config.targetFps)
        val listener = FrameGlCapture.FrameListener { timestamp, arrival, read -> onFrame(timestamp, arrival, read) }
        val surface = capture.start(listener) ?: return false
        captureSurface = surface
        if (!isCurrent()) return false
        if (attach != null && !attach(surface)) return false
        if (!isCurrent()) return false
        if (!presenter.start()) {
            if (attach != null && isCurrent()) attach(display)
            return false
        }
        driveHandler.post {
            if (!closed.get() && !native.get() && !restoring.get()) {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
        host.log("bridge.start", "${width}x$height")
        return true
    }

    /** 诊断：会话快照与是否已回原生；供日志与真机验证使用，不参与控制。 */
    fun diagnostics(): Pair<FrameInterpolationSession.Snapshot, Boolean> = session.snapshot() to native.get()

    /** 宿主 MediaCodec.flush（seek）：旧帧与时钟作废，下一帧开新 epoch。 */
    override fun onFlush() {
        capture.handler.post {
            clock.reset()
            cadence.reset()
            discontinuityCount.incrementAndGet()
            lastArrivalNs = Long.MIN_VALUE
            previousPtsUs = Long.MIN_VALUE
            if (admitted && !native.get()) beginEpoch("flush")
        }
    }

    /** 宿主 stop/release：拆除会话、断开 EGL；音频补偿归零。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        restoring.set(true)
        driveHandler.post { runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) } }
        capture.handler.post {
            if (isCurrent()) host.setAudioDelayUs(0L)
            session.close()
            host.log("bridge.close", session.snapshot().recovery.cause.name)
        }
        presenter.close()
        capture.close()
        driveThread.quitSafely()
        power?.close()
    }

    private fun onFrame(timestampNs: Long, arrivalNs: Long, read: (ByteBuffer, FrameBufferLedger.Format) -> Boolean) {
        if (native.get() || closed.get()) return
        if (!isCurrent()) { close(); return }
        if (!admitted && !admit()) return
        val pts = timestampNs / 1_000L
        if (clock.feed(pts, arrivalNs, playbackSpeed()) == FrameHostClock.Update.DISCONTINUITY) {
            discontinuityCount.incrementAndGet()
            beginEpoch("source-discontinuity")
        }
        // 源间隔诊断：PTS 跳变（帧在宿主或解码器侧被丢弃）与到达间隔过长（PTS 连续但解码/释放迟到）分开记录，
        // 附上一帧捕获耗时与当前音频补偿，用于区分宿主丢帧、解码器缺输出缓冲与本端捕获阻塞。
        if (previousPtsUs != Long.MIN_VALUE) {
            val ptsGapUs = pts - previousPtsUs
            val arrivalGapNs = arrivalNs - lastArrivalNs
            if (ptsGapUs > SOURCE_GAP_PTS_US || arrivalGapNs > SOURCE_GAP_ARRIVAL_NS)
                host.log("bridge.gap", "pts=+%.1fms arrival=+%.1fms lastCapture=%.1fms audioDelayUs=$audioDelayUs"
                    .format(ptsGapUs / 1000.0, arrivalGapNs / 1e6, lastCaptureNs / 1e6))
        }
        previousPtsUs = pts
        lastArrivalNs = arrivalNs
        // 捕获包含阻塞式 GPU 读回；ADPF 的 CPU 提示尚未按实际 CPU 工作分离，保持关闭以免错误提频。
        cadence.add(pts)
        if (cadence.verdict() == FrameSourceCadence.Verdict.UNSUITABLE) {
            returnToNative("cadence:${cadence.describe()}")
            return
        }
        val captureStarted = System.nanoTime()
        session.capture(pts, read)
        lastCaptureNs = System.nanoTime() - captureStarted
        if (lastCaptureNs > maxCaptureNs) maxCaptureNs = lastCaptureNs
    }

    private var previousPtsUs = Long.MIN_VALUE
    @Volatile private var lastCaptureNs = 0L
    @Volatile private var maxCaptureNs = 0L

    /** 首帧时用解码器输出格式补齐颜色证据再做准入；拒绝即回原生，不呈现任何一帧。 */
    private fun admit(): Boolean {
        // 真机：宿主 configure 格式不带 profile，解码器输出格式（video/raw）也不带；解码器解析序列头后把
        // profile 填进其输入格式（AV1 profile 1 = Main8）。缺失项依次由输入格式、输出格式补齐。
        val input = runCatching { codecReference.get()?.inputFormat }.getOrNull()?.let(::values)
        val source = input?.let(this.source::fillFrom) ?: this.source
        val decoded = runCatching { codecReference.get()?.outputFormat }.getOrNull()?.let(::values) ?: FrameHostMediaSignals.Values(source.mime)
        val actualDecoded = decoded.fillFrom(source)
        // GPU 采样 OES 时由驱动按流色彩空间完成 YUV→RGB；若显式上报 P010/10-bit 则保留真实位深，只对已知 8-bit SDR BT.601/709 声称捕获已验证。
        val decodedSignal = FrameHostMediaSignals.signal(
            actualDecoded,
            FrameHostMediaSignals.decodedBitDepth(decoded, source)
        )
        val capture = if (FrameHostMediaSignals.converterStandard(decodedSignal.standard) != null &&
            decodedSignal.bitDepth == 8) {
            FrameMediaPolicy.Capture.VERIFIED_SDR_RGB8
        } else FrameMediaPolicy.Capture.OTHER
        val mergedSource = source.fillFrom(decoded)
        val isProtected = protectedContent || FrameHostMediaSignals.hasProtectedSignal(mergedSource, configureFlags)
        val isSecure = secureDecoder || FrameHostMediaSignals.hasSecureSignal(mergedSource)
        val isTunneled = tunneled || FrameHostMediaSignals.hasTunneledSignal(mergedSource)
        fun evidence(value: Boolean) = if (value) FrameMediaPolicy.Evidence.YES else FrameMediaPolicy.Evidence.NO
        val lease = gate.begin(epoch()).lease
        val resolution = gate.resolve(lease, FrameMediaPolicy.Snapshot(sourceWidth, sourceHeight,
            FrameHostMediaSignals.signal(mergedSource), decodedSignal, capture,
            evidence(isProtected), evidence(isSecure), evidence(isTunneled), host.audio()))
        host.log("bridge.admit", resolution.decision.reason.name)
        if (resolution.permit == null) {
            returnToNative("media:${resolution.decision.reason}")
            return false
        }
        admitted = true
        beginEpoch("admit")
        return true
    }

    private fun epoch() = FrameTimeline.Epoch(codecId, epochCount,
        System.identityHashCode(display).toLong(), (sourceWidth.toLong() shl 32) or sourceHeight.toLong(), discontinuity)

    private fun beginEpoch(reason: String) {
        epochCount++
        host.log("bridge.epoch", "$epochCount reason=$reason discontinuity=$discontinuity")
        if (!session.begin(epoch(), width, height)) host.log("bridge.begin", session.snapshot().recovery.state.name)
    }

    private var lastDriveNs = 0L
    private var maxDriveGapNs = 0L
    private var maxVsyncLagNs = 0L

    private fun drive(vsyncNs: Long) {
        val now = System.nanoTime()
        if (lastDriveNs != 0L) maxDriveGapNs = maxOf(maxDriveGapNs, now - lastDriveNs)
        lastDriveNs = now
        maxVsyncLagNs = maxOf(maxVsyncLagNs, now - vsyncNs)
        presenter.onVsync(vsyncNs)
        if (config.measureVsync && limitedHz == null) vsync.add(vsyncNs)?.let { measured ->
            val hz = FrameRateTarget.snap(measured, displayModes(host.context()))
            host.log("bridge.vsync", "%.1fHz target=${config.targetFps}".format(hz))
            if (hz < config.targetFps * 0.95f) {
                limitedHz = hz
                returnToNative("display:%.0fHz".format(hz))
                return
            }
        }
        // 引擎过载把 1080p 以上档标记为不可用：按 1080p 处理尺寸重建（一次短暂的原生交接）。
        if (!config.bypassFrameBudget && !rebuild && width.toLong() * height > FrameProcessingSize.STANDARD_FLOW_PIXELS &&
            engine.capability.high == 0L) {
            rebuild = true
            returnToNative("overload:${width}x$height")
            return
        }
        // 暂停时没有新帧：停止推进时钟，时间线随后因时钟过期而停止规划，屏幕保留最后一帧。
        if (!admitted || lastArrivalNs == Long.MIN_VALUE || now - lastArrivalNs > STALL_NS) return
        // 时钟快照取在 vsync 时刻：时间线以首个快照为锚，槽位网格因此与显示 vsync 对齐，
        // 每个槽位恰好落在一个 vsync 上，不会因抖动两帧挤进同一 vsync、下一 vsync 却没有新帧。
        clock.snapshot(minOf(vsyncNs, now), discontinuity)?.let { snapshot ->
            val measured = if (host.rejectClockForVerification())
                snapshot.copy(uncertaintyUs = maxOf(snapshot.uncertaintyUs, MAX_HOST_CLOCK_UNCERTAINTY_US + 1L)) else snapshot
            val state = session.updateClock(measured)
            if (state == FrameTimeline.ClockState.RESTART_REQUIRED || state == FrameTimeline.ClockState.NO_CLOCK) {
                host.log("bridge.clock-restart", "$state uncertaintyUs=${measured.uncertaintyUs} " +
                    "mediaUs=${snapshot.mediaUs} arrivalAgeMs=${(now - lastArrivalNs) / 1_000_000} audioDelayUs=$audioDelayUs window=${clock.describe()}")
                if (!session.onClockRejected(now)) return
                discontinuityCount.incrementAndGet()
                clock.reset()
                // epoch 与账本格式只在捕获线程上重建（与填充同线程，见 FrameInterpolationSession.capture）。
                capture.handler.post { if (!closed.get() && !native.get()) beginEpoch("clock:$state") }
                return
            }
        }
        // 温控：预测余量逼近严重降频阈值前回原生，避免持续高负载把整机推入降频（之后宿主本身也会卡顿）。
        FrameThermalControl.pauseHeadroom(host.thermalControlEnabled()) { power?.thermalHeadroom(now) }?.let { headroom ->
            returnToNative("thermal:%.2f".format(headroom)); return
        }
        session.tick(now)
        announceActive()
        // 音频只跟随大幅变化：相位跟踪每次对齐都会让视频延迟摆动数毫秒，若音频逐次跟随，就要在 ijk 的
        // 音频线程上频繁阻塞写静音或丢字节，宿主内真机出现音频卡顿与视频顿挫。小幅偏差（< 20 ms）不影响口型。
        val target = session.effectiveDelayUs()
        if (target > 0L && (audioDelayUs == 0L || kotlin.math.abs(target - audioDelayUs) > AUDIO_HYSTERESIS_US)) {
            host.log("bridge.audio", "${audioDelayUs}->$target")
            audioDelayUs = target
            host.setAudioDelayUs(target)
        }
        if (now - lastReportNs >= REPORT_INTERVAL_NS) {
            lastReportNs = now
            host.presentLeadOverrideNs()?.let { if (it != presenter.presentLeadNs) { presenter.presentLeadNs = it; host.log("bridge.lead", "${it / 1000}us") } }
            val snapshot = session.snapshot()
            host.log("bridge.stats", "phase=${snapshot.phase} state=${snapshot.recovery.state} presented=${snapshot.presented} " +
                "interpolated=${snapshot.interpolated} late=${snapshot.late} missed=${snapshot.missedCommit} " +
                "inferUs=${snapshot.averageInferenceUs} leadUs=${snapshot.averageLeadUs} delayUs=${session.effectiveDelayUs()} " +
                "planned=${snapshot.planned} dedup=${snapshot.deduplicated} ledger=${snapshot.ledger} source=${cadence.describe()} audioDelayUs=$audioDelayUs " +
                "staticSkips=${engine.staticSkips.get()} cutSkips=${engine.sceneCutSkips.get()} " +
                "transitionSkips=${engine.transitionSkips.get()} tilePatched=${engine.tilePatchedFrames.get()} " +
                "unreliable=${engine.lastUnreliablePermille} flow=1/${engine.flowScaleFor(width.toLong() * height)} " +
                "capability=${engine.capability.standard}/${engine.capability.high} " +
                "guardUs=${engine.lastGuardUs} nativeUs=${engine.lastNativeUs} thermal=${power?.lastHeadroom} batteryC=${power?.lastBatteryC} " +
                "captureMaxMs=%.1f latchMaxMs=%.1f driveGapMaxMs=%.1f vsyncLagMaxMs=%.1f ".format(
                    maxCaptureNs / 1e6, capture.takeMaxLatchNs() / 1e6, maxDriveGapNs / 1e6, maxVsyncLagNs / 1e6) + presenter.timingSummary())
            maxCaptureNs = 0L
            maxDriveGapNs = 0L
            maxVsyncLagNs = 0L
        }
    }

    private var announced = false
    private var announceTicks = 0

    /** 状态提示：首个插值帧实际呈现后报告一次生效（准入通过不代表已经在插帧）。只在驱动线程调用。 */
    private fun announceActive() {
        if (announced || ++announceTicks < ANNOUNCE_TICKS) return
        announceTicks = 0
        if (session.snapshot().interpolated == 0L) return
        announced = true
        host.onStatus(FrameStatus.Active(config.sourceFps, config.targetFps))
    }

    @Volatile private var nativeReason: String? = null

    /** 管线主动放弃（准入失败、驱动异常）：经 Presenter 断开 EGL 后切回原生。 */
    private fun returnToNative(reason: String) {
        if (!restoring.compareAndSet(false, true)) return
        if (native.get()) return
        nativeReason = reason
        host.log("bridge.native", reason)
        presenter.restoreNative(recovery.report())
    }

    /** 由呈现线程在显示 EGL 断开后回调；只切换一次。 */
    private fun onNativeRestored(report: FramePresentationRecovery.Report) {
        if (closed.get() || !isCurrent()) { close(); return }
        if (!native.compareAndSet(false, true)) return
        audioDelayUs = 0L
        host.setAudioDelayUs(0L)
        val decoder: android.media.MediaCodec? = codecReference.get()
        val switched = decoder != null && (changeOutput?.invoke(display) ?: runCatching { decoder.setOutputSurface(display) }.isSuccess)
        host.log("bridge.restored", "${report.state}:${report.cause}:switched=$switched")
        // 温控/准入回退后本实例不会再恢复插帧；归还 RGB 账本、GL 线程与读回内存，避免闲置资源常驻。
        if (switched || decoder == null) close()
        // 系统实际刷新低于目标：解码器已回到显示 Surface，交给宿主按实测上限重新接管。
        val limit = limitedHz
        if (!isCurrent()) return
        if (decoder != null && switched && limit != null) host.onDisplayLimited(decoder, limit)
        else if (decoder != null && switched && rebuild) host.onRebuild(decoder)
        // 降档与重建会按新目标重新接管并另报生效；其余回原生（本回调只在交还原生时发生）如实提示原因。
        else host.onStatus(FrameStatus.Inactive(nativeStatusReason(nativeReason)))
    }

    companion object {
        /** 约每 0.25 s（60 Hz）检查一次是否已有插值帧呈现。 */
        private const val ANNOUNCE_TICKS = 15

        /** 回原生原因（returnToNative 的前缀）→ 面向用户的状态原因；会话恢复闭环触发的回退没有前缀。 */
        internal fun nativeStatusReason(reason: String?): FrameStatus.Reason = when {
            reason == null -> FrameStatus.Reason.FALLBACK
            reason.startsWith("media:") -> FrameStatus.Reason.MEDIA
            reason.startsWith("cadence:") -> FrameStatus.Reason.RATE
            reason.startsWith("thermal:") -> FrameStatus.Reason.THERMAL
            else -> FrameStatus.Reason.FALLBACK
        }

        private const val STALL_NS = 200_000_000L
        private const val MAX_HOST_CLOCK_UNCERTAINTY_US = 8_000L
        private const val WARMUP_SAMPLES = 20
        private const val REPORT_INTERVAL_NS = 5_000_000_000L
        private const val AUDIO_HYSTERESIS_US = 20_000L
        /**
         * 呈现延迟 150 ms。曾试 200 ms 以吸收宿主约每分钟一次、约 100 ms 的解码迟到，但宿主真机越过槽位反而从每分钟
         * 约 3 个增至约 73 个、出现大量 200 ms 停顿，故保持 150 ms。原因须结合时钟换代与账本耗尽计数判断。
         */
        private const val DEEP_DELAY_NS = 150_000_000L
        private const val DEEP_LEAD_NS = 100_000_000L
        private const val SOURCE_GAP_PTS_US = 50_000L
        private const val SOURCE_GAP_ARRIVAL_NS = 70_000_000L
        /** 账本 16 个缓冲：窗口 + 推理输出 + 呈现器持有约 3 帧。 */
        private const val MAX_FRAME_WINDOW = 11
        private const val TUNNELED = "tunneled-playback"
        /** MediaFormat.KEY_HDR10_PLUS_INFO（API 29），以字面量引用以兼容更低的 minSdk。 */
        private const val KEY_HDR10_PLUS_INFO = "hdr10-plus-info"
        private const val KEY_CODECS_STRING_COMPAT = "codecs-string"
        private const val KEY_COLOR_TRANSFER_REQUEST_COMPAT = "color-transfer-request"

        /**
         * 宿主 configure 前调用；返回 null 表示不接管（保持宿主原样）。只接管已在主播放器登记的显示 Surface、
         * 无加密、尺寸在范围内且推理引擎已就绪的视频解码器。
         */
        fun create(config: Config, codec: MediaCodec, format: MediaFormat, display: Surface, crypto: Any?,
                   engine: FrameInterpolationEngineHolder, host: Host, requestedTargetFps: Int? = null,
                   attach: ((Surface) -> Boolean)? = null,
                   isCurrent: () -> Boolean = { true },
                   configureFlags: Int = 0): FrameCaptureBridge? {
            if (!engine.ready()) {
                host.log("bridge.skip", "engine:${engine.state}")
                host.onStatus(FrameStatus.Inactive(if (engine.state == FrameInterpolationEngineHolder.State.FAILED)
                    FrameStatus.Reason.ENGINE else FrameStatus.Reason.LOADING))
                return null
            }
            val width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull()
                ?: run { host.log("bridge.skip", "width_unknown"); host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.FORMAT)); return null }
            val height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
                ?: run { host.log("bridge.skip", "height_unknown"); host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.FORMAT)); return null }
            val configuredValues = values(format)
            val cryptoAsync = FrameHostMediaSignals.hasProtectedSignal(configuredValues, configureFlags)
            if (width <= 0 || height <= 0 || width.toLong() * height > config.maxSourcePixels || crypto != null || cryptoAsync) {
                host.log("bridge.skip", "format:${width}x$height:crypto=${crypto != null || cryptoAsync}")
                host.onStatus(FrameStatus.Inactive(if (crypto != null || cryptoAsync) FrameStatus.Reason.MEDIA else FrameStatus.Reason.FORMAT))
                return null
            }
            // 处理尺寸：不超过屏幕与本机实测能力；4K 源在捕获时一次缩到此尺寸。
            val (displayLong, displayShort) = displaySize(host.context())
            val processing = FrameProcessingSize.choose(width, height, engine.capability, displayLong, displayShort)
                ?: run {
                    host.log("bridge.skip", "capability:${width}x$height")
                    host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.CAPABILITY))
                    return null
                }
            host.log("bridge.size", "${width}x$height->${processing.width}x${processing.height} display=${displayLong}x$displayShort")
            val name = runCatching { codec.name }.getOrDefault("")
            val securePlayback = name.endsWith(".secure") || FrameHostMediaSignals.hasSecureSignal(configuredValues)
            val tunneled = FrameHostMediaSignals.hasTunneledSignal(configuredValues)
            if (securePlayback || tunneled) {
                host.log("bridge.skip", "path:secure=$securePlayback:tunneled=$tunneled")
                host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.MEDIA))
                return null
            }
            // 25 fps 源按 2 倍插到 50 fps（30/29.97 → 60）；目标随后由实测节奏复核，不符即回原生。
            val hint = sourceFpsHint(format)
            // 帧率上限自适应：用户上限、面板模式、此前实测的系统实际刷新与本机推理能力取最小，再取源帧率整数倍。
            // 源已达上限（如 60 fps 视频在 60 Hz 下）没有可补的帧，不接管。
            val modes = displayModes(host.context())
            val target = requestedTargetFps ?: FrameRateTarget.choose(hint, host.maxFps(), modes, host.displayLimitHz(),
                engine.inferenceMsFor(processing.pixels), ignoreInferenceBudget = config.bypassFrameBudget) ?: run {
                    host.log("bridge.skip", "rate:hint=$hint max=${host.maxFps()} limit=${host.displayLimitHz()}")
                    host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.RATE))
                    return null
                }
            host.log("bridge.target", "${target}fps hint=$hint max=${host.maxFps()} limit=${host.displayLimitHz()}")
            val bridge = runCatching {
                // 60 fps 源的四帧窗口只覆盖 50 ms，无法保留 100 ms 延迟管线起步所需的原帧。
                // 120 验证档扩为八源帧与十六个 RGB 缓冲，仍由账本硬上限约束。
                // 深流水线（延迟 150 ms、提前 100 ms）：让 worker 跑得更靠前以吸收抖动；音频按同一延迟补偿。
                // 起初只用于 720p 以上；宿主真机 720p 在去重（补帧比例升到约 56%）与录屏等 GPU 负载下推理约 24 ms，
                // 100 ms/60 ms 的余量不足、出现 skippedLead，故所有尺寸都用深流水线（显式验证档位除外）。
                val deep = requestedTargetFps == null || processing.pixels > 1280L * 720L
                // 源帧窗口须覆盖整个呈现延迟再加两帧余量（宿主真机：6 帧窗口配 150 ms、60 fps 源时时间线等不到后一帧、
                // 画面停住）。窗口受账本上限约束，高帧率源的延迟按窗口能容纳的上限收紧（60 fps 源 150 ms）。
                val sourceFps = (hint ?: 30f).coerceIn(10f, 120f)
                val delayNs = if (deep) minOf(DEEP_DELAY_NS, ((MAX_FRAME_WINDOW - 2) * 1e9 / sourceFps).toLong())
                    else config.presentationDelayNs
                val window = maxOf(if (target >= 120) 8 else config.frameWindow,
                    kotlin.math.ceil(delayNs / 1e9 * sourceFps).toInt() + 2).coerceAtMost(MAX_FRAME_WINDOW)
                val selected = config.copy(targetFps = target,
                    presentationDelayNs = delayNs,
                    maxLeadNs = if (deep) DEEP_LEAD_NS else config.maxLeadNs,
                    frameWindow = window,
                    maxBuffers = if (target >= 120 || deep || window > config.frameWindow) 16 else config.maxBuffers,
                    interpolationRequired = config.interpolationRequired && requestedTargetFps == null,
                    measureVsync = config.measureVsync && requestedTargetFps == null,
                    sourceFps = hint)
                FrameCaptureBridge(selected, codec, display, processing.width, processing.height, width, height,
                    configuredValues, securePlayback, tunneled, cryptoAsync, configureFlags, engine, host, isCurrent)
            }.getOrNull() ?: run { host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.ENGINE)); return null }
            if (!bridge.start(attach)) {
                bridge.close()
                host.log("bridge.skip", "presenter")
                host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.ENGINE))
                return null
            }
            return bridge
        }

        /** 面板支持的刷新率（去重）；取不到时为空。 */
        internal fun displayModes(context: android.content.Context?): List<Float> = runCatching {
            context?.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.supportedModes?.map { it.refreshRate }?.distinct()
                ?: emptyList()
        }.getOrDefault(emptyList())

        /** 面板物理分辨率（长边, 短边）；取不到时为 (0, 0)，处理尺寸只受能力约束。 */
        private fun displaySize(context: android.content.Context?): Pair<Int, Int> = runCatching {
            val manager = context?.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?: return@runCatching 0 to 0
            val modes = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.supportedModes
                ?: return@runCatching 0 to 0
            if (modes.isEmpty()) return@runCatching 0 to 0
            val width = modes.maxOf { maxOf(it.physicalWidth, it.physicalHeight) }
            val height = modes.maxOf { minOf(it.physicalWidth, it.physicalHeight) }
            width to height
        }.getOrDefault(0 to 0)

        /** ijk 在 configure 格式里给出 operating-rate（真机：29.97 fps 源为 29、23.976 fps 源为 23、30 fps 源为 30），也可能给 frame-rate。 */
        internal fun sourceFpsHint(format: MediaFormat): Float? {
            for (key in listOf(MediaFormat.KEY_FRAME_RATE, MediaFormat.KEY_OPERATING_RATE)) {
                val value = runCatching {
                    if (!format.containsKey(key)) null
                    else runCatching { format.getFloat(key) }.getOrNull() ?: format.getInteger(key).toFloat()
                }.getOrNull()
                if (value != null && value.isFinite() && value > 0f) {
                    return FrameRateTarget.adjustOperatingRateHint(value, key == MediaFormat.KEY_FRAME_RATE)
                }
            }
            return null
        }

        internal fun targetFpsFor(sourceFps: Float?, requested: Int? = null): Int {
            require(requested == null || requested in setOf(15, 30, 45, 50, 60, 120, 144, 165, 185))
            return requested ?: if (sourceFps != null && sourceFps in 24.5f..25.5f) 50 else 60
        }

        internal fun values(format: MediaFormat): FrameHostMediaSignals.Values {
            fun integer(key: String) = runCatching { if (format.containsKey(key)) format.getInteger(key) else null }.getOrNull()
            fun string(key: String) = runCatching { if (format.containsKey(key)) format.getString(key) else null }.getOrNull()
            fun feature(key: String) = runCatching { format.getFeatureEnabled(key) }.getOrDefault(false)
            fun bytes(key: String): ByteArray? = runCatching {
                if (format.containsKey(key)) format.getByteBuffer(key)?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                } else null
            }.getOrNull()
            val codecs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                string(MediaFormat.KEY_CODECS_STRING)
            } else {
                string(KEY_CODECS_STRING_COMPAT)
            }
            val colorTransferRequest = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                integer(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
            } else {
                integer(KEY_COLOR_TRANSFER_REQUEST_COMPAT)
            }
            return FrameHostMediaSignals.Values(
                mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull(),
                colorStandard = integer(MediaFormat.KEY_COLOR_STANDARD),
                colorTransfer = integer(MediaFormat.KEY_COLOR_TRANSFER),
                colorRange = integer(MediaFormat.KEY_COLOR_RANGE),
                hdrStaticInfo = FrameHostMediaSignals.hdrMetadataPresent(bytes(MediaFormat.KEY_HDR_STATIC_INFO)),
                hdr10PlusInfo = FrameHostMediaSignals.hdrMetadataPresent(bytes(KEY_HDR10_PLUS_INFO)),
                profile = integer(MediaFormat.KEY_PROFILE),
                codecs = codecs,
                colorFormat = integer(MediaFormat.KEY_COLOR_FORMAT),
                colorTransferRequest = colorTransferRequest,
                securePlayback = feature(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback),
                tunneledPlayback = feature(TUNNELED) || runCatching { format.containsKey(MediaFormat.KEY_AUDIO_SESSION_ID) }.getOrDefault(false)
            )
        }
    }
}
