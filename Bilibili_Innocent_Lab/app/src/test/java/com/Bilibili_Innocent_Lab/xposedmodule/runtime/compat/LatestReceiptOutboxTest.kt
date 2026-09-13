package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat
import org.junit.Assert.*
import org.junit.Test

class LatestReceiptOutboxTest {
    @Test fun lateAckCannotDeleteANewerValue() {
        val box = LatestReceiptOutbox<String>()
        box.put("d", 1, 1, "old"); box.put("d", 2, 1, "new")
        box.ack("d", 1)
        assertEquals("new", box.snapshot()["d"]?.value)
        box.ack("d", 2); assertTrue(box.snapshot().isEmpty())
    }
    @Test fun newerProviderAckCanSupersedeOlderBoundCopy() {
        val box = LatestReceiptOutbox<String>()
        box.put("d", 3, 1, "old"); box.ack("d", 4)
        assertTrue(box.snapshot().isEmpty())
    }
    @Test fun countAndTotalBytesAreBoundedWithoutDiscardingOtherChannels() {
        val box = LatestReceiptOutbox<String>(2, 8)
        assertTrue(box.put("a", 1, 4, "a")); assertTrue(box.put("b", 2, 4, "b"))
        assertFalse(box.put("c", 3, 1, "c")); assertFalse(box.put("a", 4, 5, "big"))
        assertEquals("a", box.snapshot()["a"]?.value)
        assertTrue(box.put("a", 5, 3, "small")); assertEquals(2, box.snapshot().size)
    }
    @Test fun failedOrRejectedDeliveryRetainsOnlyLatestValueForTheNextEvent() {
        val box = LatestReceiptOutbox<String>()
        repeat(100) { box.put("d", it.toLong()+1, 1, "$it") }
        assertEquals(1, box.snapshot().size); assertEquals("99", box.snapshot()["d"]?.value)
        assertFalse(box.put("d", 1, 1, "stale"))
    }
    @Test fun invalidSizesAndRevisionsCannotBypassBounds() {
        val box = LatestReceiptOutbox<String>(1, 10)
        assertFalse(box.put("d", 0, 1, "x")); assertFalse(box.put("d", 1, -1, "x"))
        assertFalse(box.put("d", 1, Int.MAX_VALUE, "x"))
    }
}
