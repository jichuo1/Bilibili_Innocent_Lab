package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class SponsorPlaybackPolicyTest {
    private val ad = SponsorSegment("a", 10_000, 20_000, 100_000)
    private fun policy(auto: Boolean = true) = SponsorPlaybackPolicy(auto).apply {
        load(listOf(ad)); progress(12_000, 100_000, 0)
    }

    @Test fun tailGuardKeepsIntervalsThatEndAtTheVideoEndManualOnly() {
        val tail = SponsorSegment("t", 90_000, 99_000, 100_000)
        fun guarded(guard: Long) = SponsorPlaybackPolicy(true, guard).apply { load(listOf(tail)); progress(95_000, 100_000, 0) }
        assertNull(guarded(1_500).request(true, 0))
        assertEquals(99_000L, guarded(1_500).request(true, 0, manual = true)?.targetMs)
        assertEquals(99_000L, guarded(500).request(true, 0)?.targetMs)
        assertEquals(99_000L, guarded(0).request(true, 0)?.targetMs)
        // 区间终点离结尾刚好等于保护值时放行，严格小于才拦。
        assertEquals(20_000L, SponsorPlaybackPolicy(true, 80_000).apply { load(listOf(ad)); progress(12_000, 100_000, 0) }
            .request(true, 0)?.targetMs)
    }

    @Test fun manualModeAndPausedPlaybackNeverAutomaticallySeek() {
        assertNull(policy(false).request(true, 0))
        assertNull(policy().request(false, 0))
        assertEquals(20_000L, policy(false).request(false, 0, manual = true)?.targetMs)
    }

    @Test fun overlappingSegmentsMergeAndUnknownOrChangedDurationDisablesSkips() {
        val p = policy()
        p.load(listOf(ad, ad.copy(uuid = "b", startMs = 19_000, endMs = 25_000)))
        assertEquals(25_000L, p.request(true, 0)?.targetMs)
        for (duration in listOf(0L, 110_000L, 15_000L)) {
            val invalid = policy(); invalid.progress(12_000, duration, 0)
            assertNull(invalid.request(true, 0))
        }
    }

    @Test fun queuedRequestIsNotAppliedUntilTargetIsConfirmed() {
        val p = policy(); val request = p.request(true, 0)!!
        assertFalse(p.canUndo(1))
        assertNull(p.request(true, 1))
        assertFalse(p.confirm(12_000, 1))
        assertEquals(request, p.pending)
        assertTrue(p.confirm(20_000, 2))
        assertTrue(p.canUndo(3))
    }

    @Test fun undoRestoresTheOriginWithoutAnAutomaticSkipLoop() {
        val p = policy(); p.request(true, 0); p.confirm(20_000, 1)
        val undo = p.requestUndo(2)!!
        assertEquals(12_000L, undo.targetMs)
        assertTrue(p.confirm(12_000, 3))
        p.progress(12_000, 100_000, 4)
        assertNull(p.request(true, 4))
        assertNotNull(p.request(true, 4, manual = true))
    }

    @Test fun userScrubbingCancelsQueuedWorkAndPreservesTheirChosenPosition() {
        val p = policy(); p.request(true, 0)
        p.externalSeekStart(0)
        assertNull(p.pending)
        assertNull(p.request(true, 1))
        p.externalSeekComplete(15_000)
        assertNull(p.request(true, 2))
        assertNotNull(p.request(true, 2, manual = true))
    }

    @Test fun timeoutAndStaleFailureCannotOverwriteANewerRequest() {
        val p = policy(); val old = p.request(true, 0)!!
        p.progress(12_000, 100_000, 4_000)
        assertNull(p.pending)
        assertFalse(p.confirm(20_000, 4_001))
        val retry = p.request(true, 4_002, manual = true)!!
        p.fail(old.serial)
        assertEquals(retry, p.pending)
        p.fail(retry.serial)
        assertNull(p.pending)
    }

    @Test fun undoExpiresAndOnlyActiveRangesCanBeManuallySkipped() {
        val p = policy(); p.request(true, 0); p.confirm(20_000, 1)
        assertNull(p.requestUndo(6_002))
        p.progress(30_000, 100_000, 6_003)
        assertNull(p.request(true, 6_003, manual = true))
    }

    @Test fun changedDurationCancelsAPendingOrUndoableSkipEvenWhenTargetStillFits() {
        val pending = policy(); pending.request(true, 0)
        pending.progress(12_000, 110_000, 1)
        assertNull(pending.pending)
        val undo = policy(); undo.request(true, 0); undo.confirm(20_000, 1)
        undo.progress(20_000, 110_000, 2)
        assertFalse(undo.canUndo(2))
    }

    @Test fun missingSeekCompleteRecoversManualControlsWithoutResumingAutomaticSkips() {
        val p = policy(); p.externalSeekStart(0)
        assertNull(p.request(true, 3_999, manual = true))
        assertNull(p.request(true, 4_000))
        assertNotNull(p.request(false, 4_000, manual = true))
    }
}
