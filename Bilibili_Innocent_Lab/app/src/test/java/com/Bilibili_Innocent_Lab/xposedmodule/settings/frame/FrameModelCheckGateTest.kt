package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

import org.junit.Assert.*
import org.junit.Test

class FrameModelCheckGateTest {
    fun serviceEventsDuringAReadCoalesceAndDiscardItsStaleResult() {
        val gate = FrameModelCheckGate()
        gate.attach(true)
        val old = gate.begin()!!
        repeat(4) { gate.invalidate(); assertNull(gate.begin()) }
        assertFalse(gate.current(old))
        assertTrue(gate.finish(old))
        val latest = gate.begin()!!
        assertTrue(gate.current(latest))
        assertFalse(gate.finish(latest))
    }

    fun detachedRowsRejectLateResultsAndReattachmentSchedulesAFreshRead() {
        val gate = FrameModelCheckGate()
        assertNull(gate.begin())
        gate.attach(true)
        val old = gate.begin()!!
        gate.attach(false)
        assertFalse(gate.current(old))
        gate.attach(true)
        assertNull(gate.begin())
        assertTrue(gate.finish(old))
        assertTrue(gate.current(gate.begin()!!))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameModelCheckGateTest) -> Unit) {
            val s = FrameModelCheckGateTest()
            block(s)
        }
        runIsolated { s -> s.serviceEventsDuringAReadCoalesceAndDiscardItsStaleResult() }
        runIsolated { s -> s.detachedRowsRejectLateResultsAndReattachmentSchedulesAFreshRead() }
    }
}
