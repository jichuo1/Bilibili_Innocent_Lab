package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 宿主进程内唯一的推理引擎：按需解包原生依赖、按固定哈希加载用户自备的模型，之后在单一 worker 上
 * 串行推理。引擎寿命等于进程；会话拿到的 synthesizer 的 close 不关闭共享引擎。
 * 预热只在后台 worker 上执行，绝不阻塞宿主主线程或解码线程。
 */
internal class FrameInterpolationEngineHolder(
    private val moduleApk: File,
    private val hostCodeCache: File,
    /** 在推理 worker 上解析模型目录（可能需要从远程文件复制约 10 MB）；null 表示模型不可用。 */
    private val modelSource: () -> File?,
    private val log: (String, String) -> Unit,
    private val protectStaticDetails: Boolean = true,
    private val workObserver: ((WorkSample) -> Unit)? = null,
    /** 能力测量尺寸；默认到 1440p（手机面板），面板达 4K 时由调用方加入 3840×2160。 */
    private val capabilitySizes: List<Pair<Int, Int>> = DEFAULT_CAPABILITY_SIZES,
    /**
     * 仅 debug 验证构建：目录内存在 armed 标记时，把之后每 15 次补帧的前帧/后帧/输出各存一份 PPM（共 12 组），
     * 用于查看真实画面中的伪影；写完删除标记。null 时完全不启用。
     */
    private val dumpDir: File? = null
) {
    constructor(moduleApk: File, hostCodeCache: File, modelDirectory: File, log: (String, String) -> Unit,
                protectStaticDetails: Boolean = true, workObserver: ((WorkSample) -> Unit)? = null) :
        this(moduleApk, hostCodeCache, { modelDirectory }, log, protectStaticDetails, workObserver)

    /**
     * 本机实测可持续插帧的像素上限（运行时能力门），按光流档分开：预热后在 [capabilitySizes] 上按宿主稳态
     * （按帧 ID 复用已上传的前一帧）各计时 3 次，取中位数不超过 [CAPABILITY_LIMIT_MS] 的最大尺寸。
     * 未实测的 GPU（Mali、Immortalis、Xclipse 等）由此自适应，处理尺寸由 [FrameProcessingSize.choose] 据此选择。
     */
    @Volatile var capability = FrameProcessingSize.Capability(0L, 0L)
        private set
    /** 两档中较大的可持续像素数；0 表示尚未测定。 */
    val maxPixels: Long get() = maxOf(capability.standard, capability.high)

    /**
     * 720p 以上、1080p 及以下的光流比例档：按 1/4 → 1/6 → 1/8 依次在宿主内实测 1080p，取第一个满足预算的。
     * 纹理场景下 1/6 与 1/4 画质相近，但动画中快速移动的细线（发丝、描边）需要更细的光流：宿主抓帧中 1/6 让
     * 呆毛在补帧里消失，合成细线真值测试 1/4 比 1/6 高 4.3 dB。能力允许时优先 1/4。
     * 2026-10-19 真机：1080p 1/4 为 22 ms、1/6 为 14 ms，两者对真值的 PSNR 相同（50.7 dB），1/8 降到 39.6 dB。
     */
    @Volatile var standardFlowScale = 4
        private set

    /** 能力测量中各尺寸（像素数）在所选光流档下的中位耗时（ms）。 */
    private val measuredMs = java.util.concurrent.ConcurrentHashMap<Long, Double>()

    /**
     * 估计给定处理尺寸的单次补帧耗时：同一光流档内取不小于它的最近实测尺寸（保守），否则按像素数线性外推。
     * 未测定时返回 0（调用方视为不可用）。
     */
    fun inferenceMsFor(pixels: Long): Double {
        val scale = flowScaleFor(pixels)
        val same = measuredMs.entries.filter { flowScaleFor(it.key) == scale }
        same.filter { it.key >= pixels }.minByOrNull { it.key }?.let { return it.value }
        val largest = same.maxByOrNull { it.key } ?: return 0.0
        return largest.value * pixels / largest.key
    }

    /** 本次插帧的光流比例：≤720p 用 1/4，≤1080p 用实测档，以上用 1/8（光流输入约 480×270 或更小）。 */
    fun flowScaleFor(pixels: Long): Int = when {
        pixels <= 1280L * 720L -> 4
        pixels <= FrameProcessingSize.STANDARD_FLOW_PIXELS -> standardFlowScale
        else -> 8
    }

    /** 引擎就绪后在推理 worker 上调用一次：用于迟接管冷启动时已开始原生播放的解码器。 */
    @Volatile var onReady: (() -> Unit)? = null
    data class WorkSample(val wallNs: Long, val cpuNs: Long, val bypassed: Boolean, val status: FrameInterpolationBackend.Status)
    enum class State { IDLE, PREPARING, READY, FAILED }

    @Volatile var state = State.IDLE
        private set
    @Volatile private var backend: FrameInterpolationBackend? = null
    /** 因静止画面而省掉的推理次数（诊断）。 */
    val staticSkips = java.util.concurrent.atomic.AtomicLong()
    val sceneCutSkips = java.util.concurrent.atomic.AtomicLong()
    /** 推理后按运动置信度判为转场、改用最近原帧的次数（诊断）。 */
    val transitionSkips = java.util.concurrent.atomic.AtomicLong()
    @Volatile var lastUnreliablePermille = 0
        private set
    /** 局部转场：引擎把部分分块换成最近原帧的插帧次数（诊断）。 */
    val tilePatchedFrames = java.util.concurrent.atomic.AtomicLong()
    @Volatile var lastGuardUs = 0L
        private set
    @Volatile var lastNativeUs = 0L
        private set
    private val warmed = java.util.Collections.synchronizedSet(HashSet<Pair<Int, Int>>())
    // 推理 worker 的 CPU 部分（命令录制、6 MB 输入输出拷贝、场景判定、分块回填）在宿主播放进程里与宿主线程竞争；
    // 以显示优先级运行，减少调度延迟。
    private val executor = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2)) { task ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
            task.run()
        }, "innocent-frame-infer").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(false) }
    private val preparationRetry = FramePreparationRetry(maxAttempts = CAPABILITY_RETRIES, capabilityDelayNs = CAPABILITY_RETRY_NS)
    private val recoveryExecutor = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "innocent-frame-recover").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var recoveryTask: java.util.concurrent.ScheduledFuture<*>? = null

    fun ready(): Boolean = state == State.READY
    fun recoveryPending(): Boolean = state == State.FAILED && preparationRetry.canSchedule()

    /**
     * 在当前条件下重新测定能力（在 worker 上执行，与推理串行），等待完成。宿主只在播放进程启动时测一次；
     * 测试用它在前台 Surface 条件下复现这一判定，而不必重建引擎（原生侧限制同时存在的引擎数）。
     */
    fun remeasureCapability(timeoutMs: Long = 30_000L): Boolean {
        val engine = backend ?: return false
        val done = java.util.concurrent.CountDownLatch(1)
        val accepted = runCatching { executor.execute { try { measureCapability(engine) } finally { done.countDown() } } }.isSuccess
        return accepted && done.await(timeoutMs, TimeUnit.MILLISECONDS) && ready()
    }

    /** No I/O here: enqueue one due recovery on the existing bounded inference worker. */
    @Synchronized fun retryIfDue(nowNs: Long = System.nanoTime(), newPlayback: Boolean = true): Boolean {
        if (state != State.FAILED) return false
        val work = preparationRetry.take(nowNs, newPlayback) ?: return false
        state = State.PREPARING
        val accepted = runCatching { executor.execute { prepareOnWorker(work) } }.isSuccess
        if (!accepted) fail("queue", work)
        else log("engine.retry", work.name.lowercase())
        return accepted
    }

    @Synchronized fun warmUp() {
        if (state != State.IDLE) return
        state = State.PREPARING
        val accepted = runCatching { executor.execute { prepareOnWorker(FramePreparationRetry.Work.LOAD) } }.isSuccess
        if (!accepted) fail("queue")
    }

    private fun prepareOnWorker(work: FramePreparationRetry.Work) {
        try {
            if (work == FramePreparationRetry.Work.CAPABILITY && backend != null) measureCapability(backend!!)
            else {
                backend?.close()
                backend = null
                warmed.clear()
                measuredMs.clear()
                load()
            }
        } catch (failure: Throwable) {
            runCatching { backend?.close() }
            backend = null
            fail("prepare:${failure.javaClass.simpleName}")
        }
    }

    private fun load() {
        val native = runCatching { FrameInterpolationNative.prepare(moduleApk, hostCodeCache) }
            .getOrDefault(FrameNativeLoader.Status.FAILED)
        if (native == FrameNativeLoader.Status.UNSUPPORTED_ABI || native == FrameNativeLoader.Status.RESTART_REQUIRED) {
            fail("native:$native", null); return
        }
        if (native != FrameNativeLoader.Status.READY) { fail("native:$native"); return }
        // fp16、GPU RGB8 转换、自适应光流（≤1080p 用 1/4，以上用 1/8，光流输入约 480×270）。
        // 2026-10-19 真机（Adreno 840，宿主稳态）：720p 11 ms、1080p 21 ms、1440p 16 ms、4K 32 ms。
        val modelDirectory = runCatching { modelSource() }.getOrNull() ?: run { fail("model:unavailable"); return }
        val largest = capabilitySizes.maxOf { (width, height) -> width.toLong() * height }
        val opened = FrameInterpolationBackend.openResearchModel(modelDirectory, FrameInterpolationBackend.Backend.VULKAN,
            1, fp16 = true, flowScale = 4, gpuIo = true, protectStaticDetails = protectStaticDetails,
            maxPixels = maxOf(FrameInterpolationBackend.MAX_PIXELS, largest), adaptiveFlow = true)
        val engine = opened.engine
        if (opened.status != FrameInterpolationBackend.Status.OK || engine == null) {
            val terminal = opened.status in setOf(FrameInterpolationBackend.Status.UNSUPPORTED_BACKEND,
                FrameInterpolationBackend.Status.INVALID_ARGUMENT, FrameInterpolationBackend.Status.MODEL_TOO_LARGE)
            fail("model:${opened.status}", if (terminal) null else FramePreparationRetry.Work.LOAD); return
        }
        backend = engine
        // 真机：首次推理某分辨率时分配显存与编译着色器，远超槽位预算；先在各尺寸上各跑一次。
        // 准备会话内共用一套 scratch（warm 与能力测量），会话结束释放引用。
        withScratch(WARM_SIZES + capabilitySizes) { arena ->
            val hint = scratchHint(WARM_SIZES + capabilitySizes)
            for ((width, height) in WARM_SIZES + capabilitySizes) warm(engine, width, height, arena, hint)
            modelName = modelDirectory.name
            measureCapability(engine, arena)
        }
    }

    /** 以 [sizes] 中最大的一帧为容量提示开一个准备会话；无论结果如何都释放引用。 */
    private inline fun <T> withScratch(sizes: List<Pair<Int, Int>>, block: (FrameScratchArena) -> T): T {
        val arena = FrameScratchArena()
        try {
            return block(arena)
        } finally {
            log("engine.scratch", "allocations=${arena.allocations} bytes=${arena.allocatedBytes} zeroed=${arena.zeroedBytes} sizes=${sizes.size}")
            arena.close()
        }
    }

    private fun scratchHint(sizes: List<Pair<Int, Int>>): Int =
        sizes.maxOfOrNull { (width, height) -> (width.toLong() * height * 3).coerceAtMost(FrameScratchArena.MAX_BYTES.toLong()).toInt() } ?: 1

    @Volatile private var modelName = ""

    /**
     * 能力测量。启动时 GPU 可能被短暂占满（宿主冷启动、另一插帧实例），测得的耗时远高于平时而判为不可用；
     * 后台有限重试保持 ≥ [CAPABILITY_RETRY_NS] 的间隔；耗尽后，仅较晚的新播放可重启恢复窗口。
     */
    private fun measureCapability(engine: FrameInterpolationBackend, arena: FrameScratchArena? = null) {
        if (arena == null) {
            withScratch(capabilitySizes) { measureCapability(engine, it) }
            return
        }
        measuredMs.clear()
        val probe = FrameCapabilityProbe(arena, scratchHint(capabilitySizes)) { left, right, output, width, height, leftId, rightId, flowScale ->
            engine.process(left, right, output, width, height, 0.5f, leftId = leftId, rightId = rightId, flowScale = flowScale)
        }
        val passing = capabilitySizes.filter { (width, height) ->
            val pixels = width.toLong() * height
            val tiers = if (pixels > 1280L * 720L && pixels <= FrameProcessingSize.STANDARD_FLOW_PIXELS) STANDARD_TIERS
                else listOf(flowScaleFor(pixels))
            var chosenMs = 0.0
            val tier = tiers.firstOrNull { scale ->
                // 预运行或任一计时样本失败（非 OK）的档位不可用，失败样本不进入耗时分布。
                val measured = robustMs(probe, width, height, scale)
                measured != null && measured.also { chosenMs = it } <= tierLimitMs(scale, tiers.size > 1) }
            if (tier != null && tiers.size > 1) standardFlowScale = tier
            if (tier != null) measuredMs[pixels] = chosenMs
            tier != null
        }.map { (width, height) -> width.toLong() * height }
        capability = FrameProcessingSize.Capability(
            standard = passing.filter { it <= FrameProcessingSize.STANDARD_FLOW_PIXELS }.maxOrNull() ?: 0L,
            high = passing.filter { it > FrameProcessingSize.STANDARD_FLOW_PIXELS }.maxOrNull() ?: 0L)
        if (maxPixels == 0L) { fail("capability", FramePreparationRetry.Work.CAPABILITY); return }
        preparationRetry.ready()
        recoveryTask?.cancel(false)
        state = State.READY
        log("engine.ready", "$modelName standard=${capability.standard} high=${capability.high} tier=1/$standardFlowScale")
        runCatching { onReady?.invoke() }.onFailure { log("engine.onReady", it.javaClass.simpleName) }
    }

    /** 为宿主实际尺寸预热（异步、每尺寸一次）；引擎未就绪时忽略。 */
    fun prewarm(width: Int, height: Int) {
        val engine = backend ?: return
        if (!warmed.add(width to height)) return
        runCatching { executor.execute { withScratch(listOf(width to height)) { warm(engine, width, height, it) } } }
    }

    private fun warm(engine: FrameInterpolationBackend, width: Int, height: Int, arena: FrameScratchArena,
                     capacityHint: Int = width * height * 3) {
        warmed += width to height
        val bytes = width * height * 3
        val started = System.nanoTime()
        val status = runCatching {
            // 三份独立缓冲：两份零内容输入 + 一份输出（与逐次 allocateDirect 的内容一致）。
            engine.process(arena.zeroed(0, bytes, capacityHint), arena.zeroed(1, bytes, capacityHint), arena.scratch(2, bytes, capacityHint),
                width, height, 0.5f, flowScale = flowScaleFor(width.toLong() * height))
        }.getOrDefault(FrameInterpolationBackend.Status.INFERENCE_FAILED)
        log("engine.warm", "${width}x$height $status ${(System.nanoTime() - started) / 1_000_000}ms")
    }

    /**
     * 本机固有速度：预运行一次后计时 5 次取第二快（启动时宿主 UI 或其他 GPU 负载的短暂争用会把 3 次中位数推过上限；
     * 真机：1080p 一次测得 1/4 与 1/6 档全部超限、整个进程退到 720p，另一次 1/4 档 24.8 ms 通过）。
     * 播放中的实际负载由 observeLoad 按推理占用降档或缩小处理尺寸，两者分工：启动看能力，运行看负载。
     * 预运行或任一样本返回非 OK 时本档作废并返回 null，不产生耗时。
     */
    private fun robustMs(probe: FrameCapabilityProbe, width: Int, height: Int, flowScale: Int): Double? =
        when (val result = probe.measure(width, height, flowScale)) {
            is FrameCapabilityProbe.Result.Measured -> {
                log("engine.capability", "${width}x$height flow=1/$flowScale robust=%.1fms all=%s".format(result.robustMs,
                    result.allMs.joinToString("/") { "%.1f".format(it) }))
                result.robustMs
            }
            is FrameCapabilityProbe.Result.Failed -> {
                log("engine.capability.failed", "${width}x$height flow=1/$flowScale step=${result.step} status=${result.status}")
                null
            }
        }

    @Synchronized private fun fail(reason: String, work: FramePreparationRetry.Work? = FramePreparationRetry.Work.LOAD) {
        preparationRetry.failed(work, System.nanoTime())
        state = State.FAILED
        log("engine.failed", reason)
        recoveryTask?.cancel(false)
        if (work != null && preparationRetry.canSchedule()) recoveryTask = recoveryExecutor.schedule(
            { retryIfDue(newPlayback = false) }, preparationRetry.delayFor(work), TimeUnit.NANOSECONDS)
    }

    companion object {
        val WARM_SIZES = listOf(854 to 480, 640 to 360)
        val DEFAULT_CAPABILITY_SIZES = listOf(1280 to 720, 1920 to 1080, 2560 to 1440)
        val STANDARD_TIERS = listOf(4, 6, 8)
        /** 不可靠像素达到此千分比即视为转场；由 FrameInterpolationModelBenchmarkTest#transitionGuard 标定。 */
        const val TRANSITION_PERMILLE = 300
        const val CUT_PERMILLE = 100
        const val CUT_HISTOGRAM_PERMILLE = 250
        private const val DUMP_SAMPLES = 12
        const val LOAD_WINDOW_NS = 4_000_000_000L
        /** 推理占用上限：其余留给捕获、呈现与宿主 UI（与 FrameRateTarget 的预算一致）。 */
        const val LOAD_LIMIT = 0.8
        const val CAPABILITY_RETRY_NS = 20_000_000_000L
        const val CAPABILITY_RETRIES = 3
        /**
         * 1080p 选 1/4 的上限略宽：细线画质收益明显（+4.3 dB），深流水线下真机端到端 1/4（约 25 ms）无迟到；
         * 1/5 实测反而劣于 1/6（与网络内部 2 的幂金字塔对不齐），不作为档位。
         */
        const val FINE_TIER_LIMIT_MS = 26.0
        fun tierLimitMs(scale: Int, tiered: Boolean) = if (tiered && scale == 4) FINE_TIER_LIMIT_MS else CAPABILITY_LIMIT_MS
        /**
         * 独立计时中位数上限。30→60 每 33.3 ms 需一次补帧；留约 9 ms 给同时进行的捕获、呈现与宿主 UI 合成。
         * 呈现延迟已放宽（用户接受更多延迟），单次偶发超时由时间线丢槽与恢复策略吸收，持续超时回原生。
         */
        const val CAPABILITY_LIMIT_MS = 24.0
        /** 能力测量用的帧 ID 区间；账本 ID 从 0 递增，不可能到达。 */
        const val CAPABILITY_ID_BASE = 1L shl 60
    }

    private var loadWindowStartNs = 0L
    private var loadWindowUs = 0L

    /**
     * 运行期降级：能力在进程启动时测得，播放中宿主 UI、弹幕、温度以及源重复帧去重（补帧比例上升）都会让 GPU 更忙
     * （宿主真机 1440p 处理约 24 ms × 35 次/秒）。按约 4 s 窗口统计推理占用（推理耗时 / 墙钟时间），超过
     * [LOAD_LIMIT] 时：1080p 档退到下一光流档，下一次补帧即生效；1080p 以上档标记为不可用，由捕获桥按 1080p
     * 处理尺寸重建。本进程内不再升回，避免来回切换。
     */
    private fun observeLoad(pixels: Long, nativeUs: Long) {
        val now = System.nanoTime()
        if (loadWindowStartNs == 0L || now - loadWindowStartNs > 2 * LOAD_WINDOW_NS) { loadWindowStartNs = now; loadWindowUs = 0L; return }
        loadWindowUs += nativeUs
        val elapsedUs = (now - loadWindowStartNs) / 1_000L
        if (elapsedUs * 1_000L < LOAD_WINDOW_NS) return
        val load = loadWindowUs.toDouble() / elapsedUs
        loadWindowStartNs = now; loadWindowUs = 0L
        if (load <= LOAD_LIMIT) return
        if (pixels > FrameProcessingSize.STANDARD_FLOW_PIXELS) {
            if (capability.high == 0L) return
            log("engine.overload", "high class off load=%.2f".format(load))
            capability = capability.copy(high = 0L)
        } else if (pixels > 1280L * 720L) {
            val next = STANDARD_TIERS.getOrNull(STANDARD_TIERS.indexOf(standardFlowScale) + 1) ?: return
            log("engine.tier", "1/$standardFlowScale->1/$next load=%.2f".format(load))
            standardFlowScale = next
        }
    }

    private var dumpCounter = 0
    private var dumped = 0
    private val dumpWriter by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "innocent-frame-dump").apply { isDaemon = true } }
    }

    private fun maybeDump(dir: File, left: ByteBuffer, right: ByteBuffer, output: ByteBuffer, format: FrameBufferLedger.Format,
                          alpha: Float, unreliable: Int, tiles: Int) {
        val armed = File(dir, "armed")
        val tileArmed = File(dir, "armed-tiles")
        if (!armed.isFile && !tileArmed.isFile) { dumped = 0; dumpCounter = 0; return }
        // armed-tiles：只抓有分块被换成原帧的补帧（检查局部转场保护是否误触发）；armed：每 15 次抓一组。
        if (tileArmed.isFile) { if (tiles == 0) return } else if (dumpCounter++ % 15 != 0) return
        fun copy(buffer: ByteBuffer) = ByteArray(format.height * format.width * 3).also { out ->
            val source = buffer.duplicate()
            for (y in 0 until format.height) { source.position(y * format.stride); source.get(out, y * format.width * 3, format.width * 3) }
        }
        val frames = listOf("a" to copy(left), "b" to copy(right), "mid" to copy(output))
        val index = dumped++
        if (dumped >= DUMP_SAMPLES) { armed.delete(); tileArmed.delete() }
        dumpWriter.execute {
            runCatching {
                val header = "P6\n${format.width} ${format.height}\n255\n".toByteArray()
                for ((name, bytes) in frames) File(dir, "%02d-$name.ppm".format(index)).outputStream().use { it.write(header); it.write(bytes) }
                File(dir, "%02d-info.txt".format(index)).writeText("alpha=$alpha unreliable=$unreliable tiles=$tiles flow=1/${flowScaleFor(format.width.toLong() * format.height)}\n")
            }.onFailure { log("engine.dump", it.javaClass.simpleName) }
        }
    }

    fun worker(): FrameInterpolationSession.Worker =
        FrameInterpolationSession.Worker { task -> runCatching { executor.execute(task) }.isSuccess }

    fun synthesizer(): FrameInterpolationSession.Synthesizer = object : FrameInterpolationSession.Synthesizer {
        override fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer, format: FrameBufferLedger.Format,
                             alpha: Float, leftId: Long, rightId: Long): FrameInterpolationBackend.Status {
            val began = System.nanoTime()
            val cpu = if (workObserver != null) android.os.Debug.threadCpuTimeNanos() else 0L
            val result = processFrame(left, right, output, format, alpha, leftId, rightId)
            workObserver?.let { observer -> runCatching {
                observer(WorkSample(System.nanoTime() - began, android.os.Debug.threadCpuTimeNanos() - cpu,
                    lastNativeUs == 0L, result))
            } }
            return result
        }

        private fun processFrame(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer, format: FrameBufferLedger.Format,
                                 alpha: Float, leftId: Long, rightId: Long): FrameInterpolationBackend.Status {
            val engine = backend ?: return FrameInterpolationBackend.Status.NOT_LOADED
            val started = System.nanoTime()
            lastNativeUs = 0L
            val content = FrameInterpolationNative.analyzeScene(left, right, format.width, format.height, format.stride)
            lastGuardUs = (System.nanoTime() - started) / 1_000L
            if (content < 0) return FrameInterpolationBackend.Status.INVALID_ARGUMENT
            val still = content == 1
            val cut = content == 2
            if (still || cut) {
                // 静止画面省掉推理；切镜保持前一帧，下一张源帧到时再切换，避免提前泄露后一幕。
                output.duplicate().also { it.clear() }.put(left.duplicate().also { it.position(0); it.limit(format.bytes) })
                if (still) staticSkips.incrementAndGet() else sceneCutSkips.incrementAndGet()
                return FrameInterpolationBackend.Status.OK
            }
            val nativeStarted = System.nanoTime()
            val result = engine.process(left, right, output, format.width, format.height, alpha,
                format.stride, format.stride, format.stride, leftId, rightId,
                flowScale = flowScaleFor(format.width.toLong() * format.height))
            lastNativeUs = (System.nanoTime() - nativeStarted) / 1_000L
            if (result != FrameInterpolationBackend.Status.OK) return result
            observeLoad(format.width.toLong() * format.height, lastNativeUs)
            // 转场保护：大面积两侧 warp 互相矛盾说明光流整体失效（切镜、闪白、超出光流范围的运动），
            // 此时任何合成都会拉扯变形；改为最近的原帧（与切镜处理一致），逐像素回退只用于局部失效。
            val unreliable = engine.unreliablePermille()
            lastUnreliablePermille = unreliable
            if (engine.patchedTiles() > 0) tilePatchedFrames.incrementAndGet()
            dumpDir?.let { maybeDump(it, left, right, output, format, alpha, unreliable, engine.patchedTiles()) }
            // 宿主真机抓帧标定：切镜颜色分布差异 0.30–0.47、不可靠 183–300‰；快速摇镜/运动模糊差异 0.04–0.22、
            // 不可靠 ≤ 148‰。摇镜整帧换原帧会退回 30 fps（掉帧感），故需颜色分布确认，或不可靠比例压倒性地高。
            val histogram = engine.histogramPermille()
            if (unreliable >= TRANSITION_PERMILLE || unreliable >= CUT_PERMILLE && histogram >= CUT_HISTOGRAM_PERMILLE) {
                val nearest = if (alpha <= 0.5f) left else right
                output.duplicate().also { it.clear() }.put(nearest.duplicate().also { it.position(0); it.limit(format.bytes) })
                transitionSkips.incrementAndGet()
            }
            return result
        }
        override fun close() = Unit
    }
}
