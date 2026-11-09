package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import org.junit.Assert.*
import org.junit.Test

class RestartProbeStateTest {
    private val absent = RootRestartAccess.Snapshot(RootRestartAccess.Status.MISSING)
    private val root = RootRestartAccess.Snapshot(RootRestartAccess.Status.READY, "/system/bin/su")
    private val shizuku = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.READY, 2000)

    @Test fun openingDialogDuringWarmupJoinsTheSameFlightAndWaitsForRoot() {
        val state = RestartProbeState()
        val token = state.begin(100)!!
        repeat(20) { assertNull(state.begin(101, force = true)) }
        assertTrue(state.shizuku(token, shizuku, 110))
        assertTrue(state.snapshot.checking)
        assertNull(state.snapshot.preferred)
        assertTrue(state.root(token, root, 120))
        assertEquals(RestartRoute.ROOT, state.snapshot.preferred)
        assertFalse(state.snapshot.checking)
    }
    @Test fun completedWarmupIsReusedForFiveSecondsButNotAcrossAnExplicitRefresh() {
        val state = RestartProbeState()
        val token = state.begin(0)!!
        state.root(token, absent, 10)
        state.shizuku(token, shizuku, 20)
        assertNull(state.begin(5_019))
        assertNotNull(state.begin(5_020))
        assertTrue(state.snapshot.checking)
        assertNull(state.snapshot.preferred)
    }
    @Test fun foregroundRefreshDiscardsTheCachedChoiceUntilBothChecksFinish() {
        val state = RestartProbeState()
        val first = state.begin(0)!!
        state.root(first, root, 1)
        state.shizuku(first, shizuku, 2)
        assertNotNull(state.begin(3, force = true))
        assertNull(state.snapshot.preferred)
    }
    @Test fun timeoutPreservesCompletedShizukuButLeavesRootUnknownAndRejectsLateSuccess() {
        val state = RestartProbeState()
        val token = state.begin(0)!!
        state.shizuku(token, shizuku, 10)
        assertTrue(state.timeout(token, 2_000))
        assertEquals(RootRestartAccess.Status.UNKNOWN, state.snapshot.root?.status)
        assertEquals(RestartRoute.SHIZUKU, state.snapshot.preferred)
        assertFalse(state.root(token, root, 2_001))
        assertFalse(state.timeout(token, 2_002))
        assertEquals(RestartRoute.SHIZUKU, state.snapshot.preferred)
    }
    @Test fun binderDeathInvalidatesInFlightResultsEvenWhenTheNewProbeFinishesFirst() {
        val state = RestartProbeState()
        val old = state.begin(0)!!
        state.root(old, absent, 1)
        state.invalidate()
        val current = state.begin(2, force = true)!!
        state.root(current, absent, 3)
        state.shizuku(current, ShizukuAvailability.Snapshot(ShizukuAvailability.Status.STOPPED), 4)
        assertFalse(state.shizuku(old, shizuku, 5))
        assertEquals(RestartRoute.MANUAL, state.snapshot.preferred)
    }
    @Test fun duplicateOrOldChannelCallbacksCannotOverwriteCurrentResults() {
        val state = RestartProbeState()
        val first = state.begin(0)!!
        assertTrue(state.root(first, absent, 1))
        assertFalse(state.root(first, root, 2))
        state.timeout(first, 2_000)
        val next = state.begin(2_001, force = true)!!
        assertFalse(state.shizuku(first, shizuku, 2_002))
        state.root(next, root, 2_003)
        state.shizuku(next, shizuku, 2_004)
        assertEquals(RestartRoute.ROOT, state.snapshot.preferred)
    }
}
