package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 标题/简介/评论三种长按自由复制共用的手势判定后端（纯判定，不碰 Android 类型，可在 JVM 单测直接跑）。
 *
 * 合并自原先分散的两套实现：简介/标题的 `DescriptionCopyGesture` 相位机（设备 slop + 历史采样 +
 * 指针身份 + downTime 会话身份）与评论的 `CommentLongPressTimer` 单次调度。三表面共用同一实例与
 * 同一到期 Runnable，阈值/时钟/取消语义统一，消除「各自为政」的维护负担。
 *
 * 长按判据以**事件时钟**（`ev.eventTime - ev.downTime`）为权威：短按（UP 事件时长 < 阈值）无论
 * 主线程多拥塞都交还宿主点击，不会被误判成长按。到期 Runnable 只负责「按住不动」的即时反馈，
 * 且必须通过 [claim] 的事件时钟复核；主线程拥塞时 UP 先派发即取消会话，迟到的 Runnable 不再误弹。
 *
 * 相位：IDLE → PENDING →（HANDLED | CANCELLED）。一旦让出给滑动（CANCELLED），本次触摸流不能再
 * 由任何长按入口接管；HANDLED 表示气泡已认领，保留到气泡关闭（[finishHandled]）或下一次手势。
 */
internal class FreeCopyGestureDetector(
    private val runnable: Runnable,
    /** 长按阈值默认值（ms）。实际阈值在每次 [begin] 时由调用方传入（宿主取系统长按线
     * ViewConfiguration.getLongPressTimeout()，使模块与宿主同步）；此默认值供 JVM 单测与回退。 */
    defaultThresholdMs: Long = DEFAULT_THRESHOLD_MS
) {
    private enum class Phase { IDLE, PENDING, CANCELLED, HANDLED }

    private var phase = Phase.IDLE

    /** 当前会话的长按阈值（ms）；begin 时设定，claim 与到期调度据此判定。 */
    var thresholdMs: Long = defaultThresholdMs
        private set

    /** 当前会话的 DOWN 事件时间（MotionEvent.downTime）；供到期 Runnable 做事件时钟复核。 */
    var downTime = 0L
        private set
    private var downX = 0f
    private var downY = 0f
    private var slop = 0f

    /** 模块主线程实际开始处理本次 DOWN 的时刻；定时器与武装判定不能直接用可能已积压的事件时间。 */
    var observedAtMs = 0L
        private set

    /** 会话序号：已入队的旧 Runnable 即使迟到也无法命中新会话（防串页/串手势）。 */
    var sessionId = 0L
        private set
    private var sessionSerial = 0L

    var pointerId: Int = -1
        private set

    val isTracking: Boolean get() = phase != Phase.IDLE
    val isPending: Boolean get() = phase == Phase.PENDING
    val isHandled: Boolean get() = phase == Phase.HANDLED

    fun begin(downTime: Long, observedAtMs: Long, pointer: Int, x: Float, y: Float, touchSlop: Float,
              thresholdMs: Long = this.thresholdMs) {
        this.downTime = downTime
        this.observedAtMs = observedAtMs
        this.thresholdMs = if (thresholdMs > 0L) thresholdMs else DEFAULT_THRESHOLD_MS
        pointerId = pointer
        downX = x
        downY = y
        slop = touchSlop
        sessionSerial += 1L
        sessionId = sessionSerial
        phase = if (pointer >= 0 && x.isFinite() && y.isFinite() &&
            touchSlop.isFinite() && touchSlop > 0f
        ) Phase.PENDING else Phase.CANCELLED
    }

    fun matches(downTime: Long): Boolean = isTracking && this.downTime == downTime

    /** 返回 true 只表示本次采样刚刚取消候选，不消费或重放宿主事件。 */
    fun sample(downTime: Long, pointer: Int, pointerCount: Int, x: Float, y: Float): Boolean {
        if (!matches(downTime) || !isPending) return false
        val dx = (x - downX).toDouble()
        val dy = (y - downY).toDouble()
        if (pointerCount != 1 || pointer != pointerId || !x.isFinite() || !y.isFinite() ||
            dx * dx + dy * dy > slop.toDouble() * slop
        ) {
            phase = Phase.CANCELLED
            return true
        }
        return false
    }

    fun cancel(downTime: Long): Boolean {
        if (!matches(downTime) || !isPending) return false
        phase = Phase.CANCELLED
        return true
    }

    /**
     * 事件时钟复核：仅当本次手势仍 PENDING 且真实按住时长（[eventTime] - downTime）达到阈值才认领。
     * [eventTime] 必须来自 MotionEvent（UP 的 eventTime 或到期时刻的 uptime），不是 DOWN 观测墙钟。
     */
    fun claim(downTime: Long, eventTime: Long): Boolean {
        if (!matches(downTime) || !isPending || eventTime < downTime ||
            eventTime - downTime < thresholdMs
        ) return false
        phase = Phase.HANDLED
        return true
    }

    fun reset() {
        phase = Phase.IDLE
        pointerId = -1
        downTime = 0L
        observedAtMs = 0L
        sessionId = 0L
        // 调度标记一并复位：即使某路径只 reset 未 cancelSchedule，也不会把同号新会话误判「已调度」而漏投。
        scheduledSessionId = 0L
    }

    /** 气泡关闭只释放已认领状态，不能终止退场期间刚开始的新手势。 */
    fun finishHandled() {
        if (phase == Phase.HANDLED) reset()
    }

    /**
     * 官方行为抑制的「长按候选」武装：触摸进行中且跨过 [ARM_MILLIS] 才按长按候选拦宿主弹窗。
     * 宿主长按约阈值才触发，提前武装给防双弹窗留余量；短按面板（武装前触发）不被误拦。
     */
    fun isArmed(nowMs: Long): Boolean =
        isPending && observedAtMs > 0L && nowMs - observedAtMs >= ARM_MILLIS

    // ===== 单次调度（合并自 CommentLongPressTimer）：一个 DOWN 逐层经过多个 View，只投递一次 =====
    interface Poster {
        fun remove(runnable: Runnable)
        /** 返回消息是否真正入队。 */
        fun post(runnable: Runnable, delayMillis: Long): Boolean
    }

    private var scheduledSessionId = 0L

    /** 调度计数（诊断/验收）：remove 与 post 实际调用次数。 */
    var removals = 0L
        private set
    var posts = 0L
        private set

    val isScheduled: Boolean get() = scheduledSessionId != 0L

    /** 为 [sessionId] 调度；同一会话已调度过就什么都不做。返回本次是否投递了消息。 */
    fun schedule(sessionId: Long, delayMillis: Long, poster: Poster): Boolean {
        if (sessionId == 0L || scheduledSessionId == sessionId) return false
        removals++
        poster.remove(runnable)
        posts++
        if (!poster.post(runnable, delayMillis.coerceAtLeast(0L))) return false
        scheduledSessionId = sessionId
        return true
    }

    /** 取消（手势结束/页面切换/清理）：撤销已投递消息并复位调度。 */
    fun cancelSchedule(poster: Poster?) {
        if (poster != null) { removals++; poster.remove(runnable) }
        scheduledSessionId = 0L
    }

    /** Runnable 开始执行：消息已出队，之后同一会话的新分发层可按需重新调度。 */
    fun onFired() {
        scheduledSessionId = 0L
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 400L
        const val ARM_MILLIS = 300L
    }
}
