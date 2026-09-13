package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** 当前等待页的手动重试状态；只结算一次结果，不把连点、后台刷新或旧回调当作新失败。 */
internal class CompatibilityRetryTracker {
    enum class Outcome { SUCCESS, CONNECTION_FAILED, OTHER_FAILURE }
    private var revision: Long? = null
    private var epoch = 0L
    private var active: Long? = null
    var failures: Int = 0
        private set

    val pendingRevision: Long? get() = revision
    fun restore(savedRevision: Long, completedFailures: Int, currentRevision: Long?) {
        reset()
        if (savedRevision > 0 && savedRevision == currentRevision) {
            revision = savedRevision
            failures = completedFailures.coerceIn(0, 3)
        }
    }
    fun synchronize(currentRevision: Long?) { if (revision != currentRevision) { reset(); revision = currentRevision } }

    fun begin(pendingRevision: Long): Long? {
        if (pendingRevision <= 0) return null
        if (revision != pendingRevision) { reset(); revision = pendingRevision }
        if (active != null) return null
        return (++epoch).also { active = it }
    }
    fun finish(token: Long, outcome: Outcome): Boolean {
        if (active != token) return false
        active = null
        failures = if (outcome == Outcome.CONNECTION_FAILED) (failures + 1).coerceAtMost(3) else 0
        return true
    }
    fun shouldOffer(acceptancePending: Boolean, modeEnabled: Boolean): Boolean =
        acceptancePending && !modeEnabled && failures >= 3
    fun cancel() { active = null; epoch++ }
    fun reset() { cancel(); failures = 0; revision = null }
}
