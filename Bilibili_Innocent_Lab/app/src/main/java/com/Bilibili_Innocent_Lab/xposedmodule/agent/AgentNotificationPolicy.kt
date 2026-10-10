package com.Bilibili_Innocent_Lab.xposedmodule.agent

/** 有限状态合并，执行结果立即可见；等待事件不重置已排队的显示期限，不缓存动作队列。 */
internal class AgentNotificationPolicy {
    private var shownAt = -1L
    private var shownKey = ""
    private var queuedAt: Long? = null
    private var actual = AgentTaskState()
    private var operationAt = -1L
    private var operationKey = ""
    private var recent: AgentTaskState? = null

    fun reset() {
        shownAt = -1; shownKey = ""; queuedAt = null; operationAt = -1; operationKey = ""; recent = null
        actual = AgentTaskState()
    }
    fun offer(state: AgentTaskState, now: Long): Long? {
        actual = state
        if (!state.running) { reset(); actual = state; return 0 }
        val key = "${state.lastOperation}:${state.lastOperationStep}:${state.lastOperationSucceeded}"
        if (state.lastOperation.isNotBlank() && key != operationKey) {
            operationKey = key; operationAt = now; recent = state
        }
        val due = if (shownAt < 0 || state.phase == "stopping") now else maxOf(now, shownAt + MIN_INTERVAL_MS)
        if (queuedAt == null || due < queuedAt!!) queuedAt = due
        return (queuedAt!! - now).coerceAtLeast(0)
    }
    fun take(now: Long): AgentTaskState? {
        queuedAt = null
        if (!actual.running) return actual
        val visible = if (recent != null && actual.phase !in AgentToolCatalog.names && actual.phase != "stopping" &&
            now - operationAt in 0 until RECENT_MS) actual.copy(
            lastOperation = recent!!.lastOperation, lastOperationSucceeded = recent!!.lastOperationSucceeded,
            lastOperationStep = recent!!.lastOperationStep, showRecentOperation = true
        ) else actual.copy(showRecentOperation = false)
        val key = "${visible.phase}:${visible.lastOperation}:${visible.lastOperationStep}:${visible.lastOperationSucceeded}:${visible.showRecentOperation}"
        if (visible.showRecentOperation) queuedAt = operationAt + RECENT_MS
        if (key == shownKey) return null
        shownKey = key; shownAt = now
        return visible
    }
    fun delay(now: Long): Long? = queuedAt?.let { (it - now).coerceAtLeast(0) }

    companion object { const val MIN_INTERVAL_MS = 200L; const val RECENT_MS = 900L }
}
