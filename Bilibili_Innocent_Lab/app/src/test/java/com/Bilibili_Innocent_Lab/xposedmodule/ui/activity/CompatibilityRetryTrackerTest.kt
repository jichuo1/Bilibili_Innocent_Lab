package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class CompatibilityRetryTrackerTest {
    private val failed = CompatibilityRetryTracker.Outcome.CONNECTION_FAILED
    @Test fun offerAppearsOnlyAfterThreeCompletedFailures() {
        val tracker = CompatibilityRetryTracker()
        repeat(3) { index ->
            val token = checkNotNull(tracker.begin(10))
            assertEquals(index, tracker.failures)
            assertTrue(tracker.finish(token, failed))
            assertEquals(index == 2, tracker.shouldOffer(true, false))
        }
        assertFalse(tracker.shouldOffer(false, false))
        assertFalse(tracker.shouldOffer(true, true))
    }
    @Test fun rapidTapsAndDuplicateCallbacksCountOnce() {
        val tracker = CompatibilityRetryTracker()
        val token = checkNotNull(tracker.begin(10))
        assertNull(tracker.begin(10))
        assertTrue(tracker.finish(token, failed))
        assertFalse(tracker.finish(token, failed))
        assertEquals(1, tracker.failures)
    }
    @Test fun cancelledAndLateAttemptsCannotShowTheHint() {
        val tracker = CompatibilityRetryTracker()
        val first = checkNotNull(tracker.begin(10))
        tracker.cancel()
        val second = checkNotNull(tracker.begin(10))
        assertFalse(tracker.finish(first, failed))
        assertEquals(0, tracker.failures)
        assertTrue(tracker.finish(second, failed))
        assertEquals(1, tracker.failures)
    }
    @Test fun successAndStorageFailuresBreakTheFailureSequence() {
        val tracker = CompatibilityRetryTracker()
        tracker.finish(checkNotNull(tracker.begin(10)), failed)
        tracker.finish(checkNotNull(tracker.begin(10)), CompatibilityRetryTracker.Outcome.SUCCESS)
        assertEquals(0, tracker.failures)
        tracker.finish(checkNotNull(tracker.begin(10)), failed)
        tracker.finish(checkNotNull(tracker.begin(10)), CompatibilityRetryTracker.Outcome.OTHER_FAILURE)
        assertEquals(0, tracker.failures)
    }
    @Test fun aNewConsentRevisionDoesNotInheritOldFailures() {
        val tracker = CompatibilityRetryTracker()
        repeat(3) { tracker.finish(checkNotNull(tracker.begin(10)), failed) }
        assertNotNull(tracker.begin(11))
        assertFalse(tracker.shouldOffer(true, false))
        assertNull(CompatibilityRetryTracker().begin(0))
    }
    @Test fun counterIsBoundedAndResetDiscardsInFlightWork() {
        val tracker = CompatibilityRetryTracker()
        repeat(20) { tracker.finish(checkNotNull(tracker.begin(10)), failed) }
        assertEquals(3, tracker.failures)
        val token = checkNotNull(tracker.begin(10))
        tracker.reset()
        assertFalse(tracker.finish(token, failed))
        assertEquals(0, tracker.failures)
    }
    @Test fun recreationKeepsCompletedFailuresButNotAnInFlightAttempt() {
        val tracker = CompatibilityRetryTracker()
        tracker.restore(7, 2, 7)
        assertEquals(2, tracker.failures)
        val token = tracker.begin(7)!!
        assertTrue(tracker.finish(token, CompatibilityRetryTracker.Outcome.CONNECTION_FAILED))
        assertTrue(tracker.shouldOffer(true,false))
        tracker.synchronize(8)
        assertFalse(tracker.shouldOffer(true,false))
        tracker.restore(7,3,8)
        assertEquals(0,tracker.failures)
    }
}
