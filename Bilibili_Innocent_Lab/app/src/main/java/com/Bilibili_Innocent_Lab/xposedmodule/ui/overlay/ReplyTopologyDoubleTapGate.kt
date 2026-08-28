package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

/**
 * 只保存稳定 rpid 和单调时钟时间的双击门控。它不持有 View、ViewHolder 或列表位置，
 * 因而可以跨 RecyclerView 复用安全地判断同一回复节点的连续点击。
 */
internal class ReplyTopologyDoubleTapGate(
    private val doubleTapTimeoutMs: Long,
    private val uptimeMillis: () -> Long
) {

    private var hasCandidate = false
    private var candidateRpid = 0L
    private var candidateUptimeMs = 0L

    init {
        require(doubleTapTimeoutMs > 0L) { "doubleTapTimeoutMs must be positive" }
    }

    fun registerTap(rpid: Long): ReplyTopologyTapResult {
        val now = uptimeMillis()
        val elapsed = now - candidateUptimeMs
        val isDoubleTap = hasCandidate &&
            candidateRpid == rpid &&
            elapsed >= 0L &&
            elapsed <= doubleTapTimeoutMs

        return if (isDoubleTap) {
            reset()
            ReplyTopologyTapResult.LOCATE
        } else {
            hasCandidate = true
            candidateRpid = rpid
            candidateUptimeMs = now
            ReplyTopologyTapResult.SELECT_ONLY
        }
    }

    fun reset() {
        hasCandidate = false
        candidateRpid = 0L
        candidateUptimeMs = 0L
    }
}

internal enum class ReplyTopologyTapResult {
    SELECT_ONLY,
    LOCATE
}
