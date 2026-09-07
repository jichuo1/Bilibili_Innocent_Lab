package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import org.junit.Assert.*
import org.junit.Test

class PendingVersionUploadTest {
    @Test fun `busy notifications coalesce without losing the first completion callback`() {
        val pending = PendingVersionUpload()
        var calls = 0
        assertTrue(pending.offer { calls++ })
        assertFalse(pending.offer { calls += 100 })
        assertEquals(0, calls)
        pending.take()!!.invoke()
        assertEquals(1, calls)
        assertNull(pending.take())
        assertTrue(pending.offer { calls++ })
    }
}
