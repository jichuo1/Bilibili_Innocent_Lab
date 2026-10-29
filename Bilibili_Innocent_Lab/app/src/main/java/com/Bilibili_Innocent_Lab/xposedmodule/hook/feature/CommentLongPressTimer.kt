package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 评论长按的单次调度：同一手势会话只向主线程 Handler 投递一次长按 Runnable。
 *
 * 一个 DOWN 会依次经过评论根、按钮容器和最深子 View，每一层的 dispatchTouchEvent 都会走到调度点。
 * 目标弱引用仍由调用方逐层更新（最终以最深层触摸 View 为锚点），但延时任务只需要一个：
 * 它的到期时刻由会话起点固定，重复 removeCallbacks + postDelayed 只会增加消息队列操作
 * （Handler.removeCallbacks 在最坏情况下与队列长度成线性）。
 *
 * 只有 [Poster.post] 真正成功后才标记已调度；Handler 暂不可用时之后的分发层仍可重试。
 * 执行、取消、清理都会复位。只在主线程使用。
 */
internal class CommentLongPressTimer(private val runnable: Runnable) {
    interface Poster {
        fun remove(runnable: Runnable)
        /** 返回消息是否真正入队。 */
        fun post(runnable: Runnable, delayMillis: Long): Boolean
    }

    private var scheduledSessionId = 0L

    /** 调度的操作计数（诊断 / 验收）：remove 与 post 实际调用的次数。 */
    var removals = 0L
        private set
    var posts = 0L
        private set

    /** 为 [sessionId] 调度；同一会话已经调度过就什么都不做。返回本次是否投递了消息。 */
    fun schedule(sessionId: Long, delayMillis: Long, poster: Poster): Boolean {
        if (sessionId == 0L || scheduledSessionId == sessionId) return false
        removals++
        poster.remove(runnable)
        posts++
        if (!poster.post(runnable, delayMillis.coerceAtLeast(0L))) return false
        scheduledSessionId = sessionId
        return true
    }

    /** 取消（手势结束 / 页面切换 / 清理）：撤销已投递的消息并复位。 */
    fun cancel(poster: Poster?) {
        if (poster != null) { removals++; poster.remove(runnable) }
        scheduledSessionId = 0L
    }

    /** Runnable 开始执行：消息已经出队，之后同一会话的新分发层可以按需重新调度。 */
    fun onFired() {
        scheduledSessionId = 0L
    }

    val isScheduled: Boolean get() = scheduledSessionId != 0L
}
