package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyTopologyDoubleTapGateTest {

    private var nowMs = 0L
    private val gate = ReplyTopologyDoubleTapGate(
        doubleTapTimeoutMs = 300L,
        uptimeMillis = { nowMs }
    )

    @Test
    fun firstTapOnlySelectsAndSecondTapOnSameRpidLocates() {
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
        nowMs = 300L
        assertEquals(ReplyTopologyTapResult.LOCATE, gate.registerTap(101L))
    }

    @Test
    fun differentRpidReplacesCandidate() {
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
        nowMs = 100L
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(202L))
        nowMs = 200L
        assertEquals(ReplyTopologyTapResult.LOCATE, gate.registerTap(202L))
    }

    @Test
    fun expiredTapStartsANewPair() {
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
        nowMs = 301L
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
        nowMs = 400L
        assertEquals(ReplyTopologyTapResult.LOCATE, gate.registerTap(101L))
    }

    @Test
    fun tripleTapCanOnlyLocateOnce() {
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
        nowMs = 100L
        assertEquals(ReplyTopologyTapResult.LOCATE, gate.registerTap(101L))
        nowMs = 200L
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
    }

    @Test
    fun resetClearsCandidateForGraphOrSessionChangesAndRelease() {
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
        gate.reset()
        nowMs = 100L
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
        gate.reset()
        nowMs = 200L
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
    }

    @Test
    fun clockRollbackCannotBecomeADoubleTap() {
        nowMs = 200L
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
        nowMs = 100L
        assertEquals(ReplyTopologyTapResult.SELECT_ONLY, gate.registerTap(101L))
    }
}
