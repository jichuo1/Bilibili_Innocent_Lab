package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

/** 分页资源预算。使用 elapsedRealtime 对应的单调毫秒值，由调用方传入以便纯 JVM 测试。 */
internal data class ReplyTopologyPagingBudget(
    val maxPages: Int = 60,
    val maxUniqueNodes: Int = 1_500,
    val maxElapsedMs: Long = 12_000L,
    val maxConsecutiveNoProgressPages: Int = 2
) {
    init {
        require(maxPages > 0)
        require(maxUniqueNodes > 0)
        require(maxElapsedMs > 0L)
        require(maxConsecutiveNoProgressPages > 0)
    }
}

internal enum class ReplyTopologyPagingStopReason {
    COMPLETE,
    REPEATED_OFFSET,
    NO_PROGRESS,
    PAGE_LIMIT,
    NODE_LIMIT,
    TIME_LIMIT,
    INVALID_PAGE
}

internal sealed interface ReplyTopologyPagingDecision {
    data class LoadNext(val offset: String) : ReplyTopologyPagingDecision

    data class Stop(
        val reason: ReplyTopologyPagingStopReason,
        val complete: Boolean
    ) : ReplyTopologyPagingDecision
}

/**
 * 只管理 offset、防循环和预算，不持有 ReplyInfo 或页面对象。调用方完成 rpid 去重后传入
 * newUniqueNodes/totalUniqueNodes，避免在 Guard 内再维护一份节点集合。
 */
internal class ReplyTopologyPagingGuard(
    private val startedAtMs: Long,
    private val budget: ReplyTopologyPagingBudget = ReplyTopologyPagingBudget()
) {
    private val seenOffsets = HashSet<String>()
    private var pageCount = 0
    private var consecutiveNoProgressPages = 0
    private var lastTotalUniqueNodes = 0

    fun onPage(
        currentOffset: String?,
        nextOffset: String?,
        newUniqueNodes: Int,
        totalUniqueNodes: Int,
        nowMs: Long
    ): ReplyTopologyPagingDecision {
        if (
            newUniqueNodes < 0 || totalUniqueNodes < 0 || newUniqueNodes > totalUniqueNodes ||
            totalUniqueNodes < lastTotalUniqueNodes || nowMs < startedAtMs
        ) {
            return stop(ReplyTopologyPagingStopReason.INVALID_PAGE)
        }

        val normalizedCurrent = currentOffset?.takeIf(String::isNotBlank)
        if (normalizedCurrent != null && !seenOffsets.add(normalizedCurrent)) {
            return stop(ReplyTopologyPagingStopReason.REPEATED_OFFSET)
        }
        pageCount++
        lastTotalUniqueNodes = totalUniqueNodes

        val normalizedNext = nextOffset?.takeIf(String::isNotBlank)
            ?: return ReplyTopologyPagingDecision.Stop(
                reason = ReplyTopologyPagingStopReason.COMPLETE,
                complete = true
            )
        if (normalizedNext == normalizedCurrent || normalizedNext in seenOffsets) {
            return stop(ReplyTopologyPagingStopReason.REPEATED_OFFSET)
        }

        consecutiveNoProgressPages = if (newUniqueNodes == 0) {
            consecutiveNoProgressPages + 1
        } else {
            0
        }
        return when {
            totalUniqueNodes >= budget.maxUniqueNodes -> stop(ReplyTopologyPagingStopReason.NODE_LIMIT)
            pageCount >= budget.maxPages -> stop(ReplyTopologyPagingStopReason.PAGE_LIMIT)
            nowMs - startedAtMs >= budget.maxElapsedMs -> stop(ReplyTopologyPagingStopReason.TIME_LIMIT)
            consecutiveNoProgressPages >= budget.maxConsecutiveNoProgressPages ->
                stop(ReplyTopologyPagingStopReason.NO_PROGRESS)
            else -> ReplyTopologyPagingDecision.LoadNext(normalizedNext)
        }
    }

    private fun stop(reason: ReplyTopologyPagingStopReason) =
        ReplyTopologyPagingDecision.Stop(reason = reason, complete = false)
}
