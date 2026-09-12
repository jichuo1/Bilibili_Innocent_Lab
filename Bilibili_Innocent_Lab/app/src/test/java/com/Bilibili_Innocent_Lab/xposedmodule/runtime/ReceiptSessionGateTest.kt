package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Test

class ReceiptSessionGateTest {
    @Test fun `publication sequence rejects zero duplicates and late snapshots`() {
        assertFalse(ReceiptSessionGate.isNewerSequence(0, 0))
        assertTrue(ReceiptSessionGate.isNewerSequence(0, 1))
        assertFalse(ReceiptSessionGate.isNewerSequence(3, 2))
        assertFalse(ReceiptSessionGate.isNewerSequence(3, 3))
        assertTrue(ReceiptSessionGate.isNewerSequence(3, 4))
    }
    @Test fun `old process cannot replace a newer registered process`() {
        val gate = ReceiptSessionGate<String>()
        assertTrue(gate.accept(100, 1, "old"))
        val old = requireNotNull(gate.current())
        assertTrue(gate.accept(200, 2, "new"))
        assertFalse(gate.accept(100, 1, "old"))
        assertFalse(gate.matches(old))
        gate.remove(old)
        assertEquals("new", gate.current()?.endpoint)
    }

    @Test fun `death removes current evidence and late registration cannot resurrect it`() {
        val gate = ReceiptSessionGate<String>()
        gate.accept(100, 1, "old")
        gate.remove(requireNotNull(gate.current()))
        assertNull(gate.current())
        assertFalse(gate.accept(100, 1, "old"))
        assertTrue(gate.accept(200, 2, "new"))
    }

    @Test fun `same live endpoint is idempotent but equal start with another endpoint is rejected`() {
        val gate = ReceiptSessionGate<String>()
        assertFalse(gate.accept(0, 1, "bad"))
        assertFalse(gate.accept(100, 0, "bad"))
        assertTrue(gate.accept(100, 1, "a"))
        assertTrue(gate.accept(100, 1, "a"))
        assertFalse(gate.accept(100, 2, "b"))
        assertFalse(gate.accept(100, 1, "b"))
    }

    @Test fun `module restart does not restore an online session from historical state`() {
        val old = ReceiptSessionGate<String>()
        old.accept(100, 1, "old")
        assertNull(ReceiptSessionGate<String>().current())
    }
}
