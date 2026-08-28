package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTopologyPagingGuardTest {

    @Test
    fun emptyNextOffsetCompletesEvenAtNodeBudget() {
        val guard = ReplyTopologyPagingGuard(
            startedAtMs = 100L,
            budget = ReplyTopologyPagingBudget(maxUniqueNodes = 10)
        )

        val decision = guard.onPage("first", "", 10, 10, 101L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.COMPLETE, decision.reason)
        assertTrue(decision.complete)
    }

    @Test
    fun repeatedOffsetStopsWithoutRequestingLoop() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        assertEquals(
            ReplyTopologyPagingDecision.LoadNext("second"),
            guard.onPage("first", "second", 3, 3, 1L)
        )

        val decision = guard.onPage("second", "first", 2, 5, 2L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.REPEATED_OFFSET, decision.reason)
        assertFalse(decision.complete)
    }

    @Test
    fun twoPagesWithoutNewRpidStopAsNoProgress() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        guard.onPage("a", "b", 0, 10, 1L)

        val decision = guard.onPage("b", "c", 0, 10, 2L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.NO_PROGRESS, decision.reason)
    }

    @Test
    fun resourceBudgetsStopPartialLoading() {
        val nodeGuard = ReplyTopologyPagingGuard(
            0L,
            ReplyTopologyPagingBudget(maxUniqueNodes = 3)
        )
        val nodeStop = nodeGuard.onPage("a", "b", 3, 3, 1L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.NODE_LIMIT, nodeStop.reason)

        val pageGuard = ReplyTopologyPagingGuard(
            0L,
            ReplyTopologyPagingBudget(maxPages = 1)
        )
        val pageStop = pageGuard.onPage("a", "b", 1, 1, 1L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.PAGE_LIMIT, pageStop.reason)

        val timeGuard = ReplyTopologyPagingGuard(
            100L,
            ReplyTopologyPagingBudget(maxElapsedMs = 10L)
        )
        val timeStop = timeGuard.onPage("a", "b", 1, 1, 110L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.TIME_LIMIT, timeStop.reason)
    }

    @Test
    fun invalidCountersFailClosedForThisFeatureOnly() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 100L)
        val decision = guard.onPage("a", "b", -1, 0, 99L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.INVALID_PAGE, decision.reason)
        assertFalse(decision.complete)
    }

    @Test
    fun decreasingUniqueTotalIsRejectedAsStaleAccumulatorState() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        guard.onPage("a", "b", newUniqueNodes = 4, totalUniqueNodes = 4, nowMs = 1L)

        val decision = guard.onPage(
            "b",
            "c",
            newUniqueNodes = 1,
            totalUniqueNodes = 3,
            nowMs = 2L
        ) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.INVALID_PAGE, decision.reason)
    }
}
