package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/**
 * 呈现恢复闭环：把呈现结果、Surface 丢失与原生错误归入一个有界状态机，并决定是否退回原生播放。
 *
 * - INTERPOLATING：允许生成并呈现插帧。
 * - DEGRADED：滑动窗口失败率越过降级线；只呈现原帧（不再提交推理），窗口恢复干净后回到 INTERPOLATING。
 * - FALLBACK：失败率越过回退线、连续失败、时钟反复失效、Surface 丢失或 worker 超时；外层必须立即撤销 epoch、
 *   归还全部帧并恢复宿主原生呈现。会话只在退避期后重开 epoch；宿主桥交回原生后会关闭本桥。
 * - DISABLED：致命原生错误或重试次数用尽；本会话内不再尝试，始终原生播放。
 *
 * 纯状态，不取时钟；所有时间由调用方传入单调纳秒。阈值来自桌面推演，真机门限尚未标定。
 */
internal class FramePresentationRecovery(
    private val window: Int = 60,
    private val degradeRatio: Double = 0.10,
    private val fallbackRatio: Double = 0.25,
    private val maxConsecutiveFailures: Int = 3,
    private val maxRetries: Int = 3,
    private val baseBackoffNs: Long = 1_000_000_000L,
    /** 每次 start 后前若干个结果不计入（真机：新分辨率的首批推理会分配显存、编译着色器而超时）。 */
    private val warmupSamples: Int = 0,
    /** 时钟拒绝会清空时间线；必须单独计数，不能被其间成功呈现的数百帧稀释。 */
    private val maxClockRestarts: Int = 3,
    private val clockRestartWindowNs: Long = 5_000_000_000L,
    /** 开启强制指定插帧方式时关闭因迟到/丢帧/超时触发的自动回退。 */
    private val fallbackOnFrameDrops: Boolean = true
) {
    enum class State { INACTIVE, INTERPOLATING, DEGRADED, FALLBACK, DISABLED }
    enum class Cause {
        NONE, STARTED, LATE_FRAMES, DROPPED_FRAMES, RECOVERED, SURFACE_LOST, WORKER_TIMEOUT,
        NATIVE_TRANSIENT, NATIVE_FATAL, RETRIES_EXHAUSTED, RETRY, STOPPED, UNVERIFIED_CLOCK
    }
    data class Report(val state: State, val cause: Cause, val failures: Int, val samples: Int,
                      val retries: Int, val nativeStatus: FrameInterpolationBackend.Status? = null) {
        val interpolationAllowed: Boolean get() = state == State.INTERPOLATING
        /** DEGRADED 仍由本管线按时呈现原帧；FALLBACK/DISABLED 必须交回宿主。 */
        val pipelinePresents: Boolean get() = state == State.INTERPOLATING || state == State.DEGRADED
    }

    private val outcomes = BooleanArray(window)
    private var cursor = 0
    private var samples = 0
    private var failures = 0
    private var consecutive = 0
    private var retries = 0
    private var retryAfterNs: Long? = null
    private var warmupLeft = 0
    private var lastFailure = Cause.NONE
    private var report = Report(State.INACTIVE, Cause.NONE, 0, 0, 0)
    private val listeners = ArrayList<(Report) -> Unit>(1)
    private val clockRestarts = ArrayDeque<Long>()

    init {
        require(window in 8..600 && degradeRatio > 0.0 && fallbackRatio > degradeRatio && fallbackRatio <= 1.0 &&
            maxConsecutiveFailures >= 1 && maxRetries >= 0 && baseBackoffNs > 0L && warmupSamples in 0..window &&
            maxClockRestarts in 1..60 && clockRestartWindowNs > 0L)
    }

    @Synchronized fun addListener(listener: (Report) -> Unit) { listeners += listener }
    @Synchronized fun report(): Report = report

    /**
     * 新 epoch 开始时调用；返回是否允许本 epoch 走插帧管线。FALLBACK 只在退避结束后重试，
     * 并计入重试次数；DISABLED 永远拒绝。
     */
    @Synchronized fun start(nowNs: Long): Boolean = when (report.state) {
        State.DISABLED -> false
        State.FALLBACK -> {
            val after = retryAfterNs
            if (after == null || nowNs < after) false
            else if (retries >= maxRetries) { transition(State.DISABLED, Cause.RETRIES_EXHAUSTED); false }
            else { retries++; clearWindow(); transition(State.INTERPOLATING, Cause.RETRY); true }
        }
        State.INACTIVE -> { clearWindow(); transition(State.INTERPOLATING, Cause.STARTED); true }
        State.INTERPOLATING, State.DEGRADED -> true
    }

    /** 退避已过、可以在不换 epoch 的情况下重新尝试；真正的重试仍经 [start] 计数。 */
    @Synchronized fun retryDue(nowNs: Long): Boolean =
        report.state == State.FALLBACK && retryAfterNs?.let { nowNs >= it } == true

    /** presentedNs 是 Presenter 回报的实际呈现时刻（或其最佳估计）；超过 lateBudgetNs 计为迟到。 */
    @Synchronized fun onPresented(dueNs: Long, presentedNs: Long, lateBudgetNs: Long): Report {
        val late = presentedNs - dueNs > lateBudgetNs
        return record(!late, if (late) Cause.LATE_FRAMES else Cause.NONE, presentedNs)
    }

    @Synchronized fun onDropped(nowNs: Long): Report = record(false, Cause.DROPPED_FRAMES, nowNs)

    @Synchronized fun onSurfaceLost(nowNs: Long): Report = fallback(Cause.SURFACE_LOST, nowNs)

    /** 只用于被准入检查拒绝的时钟快照；正常 seek/暂停/Surface 换代不消耗此预算。 */
    @Synchronized fun onClockRejected(nowNs: Long): Report {
        if (!report.pipelinePresents) return report
        while (clockRestarts.firstOrNull()?.let { nowNs - it > clockRestartWindowNs } == true) clockRestarts.removeFirst()
        clockRestarts += nowNs
        return if (clockRestarts.size >= maxClockRestarts) fallback(Cause.UNVERIFIED_CLOCK, nowNs) else report
    }
    /**
     * 单次超时只计一次失败：真机偶发单次推理超过槽位 100 ms（GPU 被宿主占用），立即回退会让整段视频失去插帧；
     * 连续超时或失败率越线仍按常规回退。
     */
    @Synchronized fun onWorkerTimeout(nowNs: Long): Report = record(false, Cause.WORKER_TIMEOUT, nowNs)

    @Synchronized fun onNativeStatus(status: FrameInterpolationBackend.Status, nowNs: Long): Report = when {
        status == FrameInterpolationBackend.Status.OK -> report
        status in FATAL -> {
            if (report.state != State.DISABLED) transition(State.DISABLED, Cause.NATIVE_FATAL, status)
            report
        }
        else -> {
            record(false, Cause.NATIVE_TRANSIENT, nowNs)
            report.copy(nativeStatus = status).also { report = it }
        }
    }

    /** 会话拆除：状态归 INACTIVE 但保留重试计数与 DISABLED，防止反复拆建绕过上限。 */
    @Synchronized fun stop(): Report {
        if (report.state == State.INTERPOLATING || report.state == State.DEGRADED)
            transition(State.INACTIVE, Cause.STOPPED)
        return report
    }

    private fun record(success: Boolean, cause: Cause, nowNs: Long): Report {
        if (report.state != State.INTERPOLATING && report.state != State.DEGRADED) return report
        if (warmupLeft > 0) { warmupLeft--; return report }
        if (!success) lastFailure = cause
        // 由成功样本触发的转换沿用最近一次失败原因，报告不出现 NONE。
        val effective = if (success && cause == Cause.NONE) lastFailure else cause
        if (samples == window && !outcomes[cursor]) failures--
        outcomes[cursor] = success
        if (!success) failures++
        cursor = (cursor + 1) % window
        if (samples < window) samples++
        consecutive = if (success) 0 else consecutive + 1
        val ratio = failures.toDouble() / samples
        val frameDropCause = effective == Cause.LATE_FRAMES || effective == Cause.DROPPED_FRAMES ||
            effective == Cause.WORKER_TIMEOUT
        when {
            report.state == State.DEGRADED && samples == window && failures == 0 ->
                transition(State.INTERPOLATING, Cause.RECOVERED)
            !fallbackOnFrameDrops && frameDropCause ->
                report = report.copy(failures = failures, samples = samples)
            consecutive >= maxConsecutiveFailures || samples >= MIN_SAMPLES && ratio >= fallbackRatio ->
                fallback(effective, nowNs)
            report.state == State.INTERPOLATING && samples >= MIN_SAMPLES && ratio >= degradeRatio ->
                transition(State.DEGRADED, effective)
            else -> report = report.copy(failures = failures, samples = samples)
        }
        return report
    }

    private fun fallback(cause: Cause, nowNs: Long): Report {
        if (report.state == State.DISABLED) return report
        transition(State.FALLBACK, cause)
        armRetry(nowNs)
        return report
    }

    private fun armRetry(nowNs: Long) {
        val shift = retries.coerceAtMost(6)
        retryAfterNs = runCatching { Math.addExact(nowNs, baseBackoffNs shl shift) }.getOrDefault(Long.MAX_VALUE)
    }

    private fun clearWindow() {
        outcomes.fill(true); cursor = 0; samples = 0; failures = 0; consecutive = 0; retryAfterNs = null
        warmupLeft = warmupSamples; lastFailure = Cause.NONE
        clockRestarts.clear()
    }

    private fun transition(state: State, cause: Cause, status: FrameInterpolationBackend.Status? = null) {
        report = Report(state, cause, failures, samples, retries, status)
        val snapshot = report
        listeners.toList().forEach { runCatching { it(snapshot) } }
    }

    companion object {
        private const val MIN_SAMPLES = 8
        val FATAL = setOf(
            FrameInterpolationBackend.Status.UNSUPPORTED_BACKEND, FrameInterpolationBackend.Status.GPU_UNAVAILABLE,
            FrameInterpolationBackend.Status.MODEL_TOO_LARGE, FrameInterpolationBackend.Status.INVALID_MODEL,
            FrameInterpolationBackend.Status.MODEL_LOAD_FAILED, FrameInterpolationBackend.Status.NOT_LOADED,
            FrameInterpolationBackend.Status.ALLOCATION_FAILED, FrameInterpolationBackend.Status.NATIVE_UNAVAILABLE
        )
    }
}
