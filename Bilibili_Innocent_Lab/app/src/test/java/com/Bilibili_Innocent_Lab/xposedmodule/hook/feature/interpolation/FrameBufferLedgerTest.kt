package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameBufferLedger.Holder
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FrameBufferLedgerTest {
    fun `frame identity is unique across player sessions sharing a GPU cache`() {
        val first = FrameBufferLedger(1)
        val second = FrameBufferLedger(1)
        assertTrue(first.configure(4, 2))
        assertTrue(second.configure(4, 2))
        assertNotEquals(first.acquire()!!.frameId, second.acquire()!!.frameId)
    }
    private var allocations = 0
    private fun ledger(max: Int = 3) = FrameBufferLedger(max) { allocations++; ByteBuffer.allocate(it) }
        .also { assertTrue(it.configure(4, 2)) }

    fun `allocation is bounded and exhausted pool applies backpressure`() {
        val ledger = ledger(2)
        val first = ledger.acquire()!!
        val second = ledger.acquire()!!
        assertNull(ledger.acquire())
        assertEquals(1L, ledger.stats().exhausted)
        assertEquals(2, ledger.stats().peakLive)
        assertEquals(2, allocations)
        assertEquals(4 * 2 * 3, ledger.buffer(first.frameId, Holder.CAPTURE)!!.capacity())
        assertNotEquals(first.frameId, second.frameId)
    }

    fun `last holder recycles buffer under a fresh frame id that is never reused`() {
        val ledger = ledger(1)
        val slot = ledger.acquire()!!
        assertTrue(ledger.retain(slot.frameId, Holder.TIMELINE))
        assertTrue(ledger.release(slot.frameId, Holder.CAPTURE))
        assertNull(ledger.acquire())
        assertTrue(ledger.release(slot.frameId, Holder.TIMELINE))
        val next = ledger.acquire()!!
        assertTrue(next.frameId > slot.frameId)
        assertEquals(1, allocations)
        assertEquals(1L, ledger.stats().recycled)
    }

    fun `released id cannot be accessed, retained or released again`() {
        val ledger = ledger()
        val slot = ledger.acquire()!!
        assertTrue(ledger.release(slot.frameId, Holder.CAPTURE))
        assertNull(ledger.buffer(slot.frameId, Holder.CAPTURE))
        assertFalse(ledger.retain(slot.frameId, Holder.WORKER))
        assertFalse(ledger.release(slot.frameId, Holder.CAPTURE))
    }

    fun `only current holders get a view and exclusive holders cannot double retain`() {
        val ledger = ledger()
        val slot = ledger.acquire()!!
        assertNull(ledger.buffer(slot.frameId, Holder.WORKER))
        assertFalse(ledger.retain(slot.frameId, Holder.CAPTURE))
        assertTrue(ledger.retain(slot.frameId, Holder.WORKER))
        assertFalse(ledger.retain(slot.frameId, Holder.WORKER))
        assertNotNull(ledger.buffer(slot.frameId, Holder.WORKER))
    }

    fun `presenter holds stack and each release counts once`() {
        val ledger = ledger()
        val slot = ledger.acquire()!!
        assertTrue(ledger.retain(slot.frameId, Holder.PRESENTER))
        assertTrue(ledger.retain(slot.frameId, Holder.PRESENTER))
        ledger.release(slot.frameId, Holder.CAPTURE)
        assertTrue(ledger.release(slot.frameId, Holder.PRESENTER))
        assertEquals(setOf(Holder.PRESENTER), ledger.holders(slot.frameId))
        assertTrue(ledger.release(slot.frameId, Holder.PRESENTER))
        assertFalse(ledger.release(slot.frameId, Holder.PRESENTER))
        assertEquals(0, ledger.stats().live)
    }

    fun `format change drops idle buffers now and held buffers on last release`() {
        val ledger = ledger(2)
        val held = ledger.acquire()!!
        val idle = ledger.acquire()!!
        ledger.release(idle.frameId, Holder.CAPTURE)
        assertEquals(1, ledger.stats().idle)
        assertTrue(ledger.configure(8, 2))
        assertEquals(0, ledger.stats().idle)
        assertEquals(1, ledger.stats().allocated)
        ledger.release(held.frameId, Holder.CAPTURE)
        assertEquals(0, ledger.stats().allocated)
        assertEquals(8 * 2 * 3, ledger.buffer(ledger.acquire()!!.frameId, Holder.CAPTURE)!!.capacity())
    }

    fun `close stops allocation and drains only after outstanding holders return`() {
        val ledger = ledger()
        val presented = ledger.acquire()!!
        ledger.retain(presented.frameId, Holder.PRESENTER)
        ledger.release(presented.frameId, Holder.CAPTURE)
        ledger.release(ledger.acquire()!!.frameId, Holder.CAPTURE)
        ledger.close()
        assertNull(ledger.acquire())
        assertFalse(ledger.drained())
        assertEquals(listOf(presented.frameId), ledger.outstanding(Holder.PRESENTER))
        ledger.release(presented.frameId, Holder.PRESENTER)
        assertTrue(ledger.drained())
        assertFalse(ledger.configure(4, 2))
    }

    fun `invalid formats and failed allocation are rejected without accounting drift`() {
        val ledger = FrameBufferLedger(2) { throw OutOfMemoryError() }
        assertFalse(ledger.configure(0, 1))
        assertFalse(ledger.configure(4, 2, stride = 11))
        assertFalse(ledger.configure(4096, 4096))
        assertNull(ledger.acquire())
        assertTrue(ledger.configure(4, 2))
        assertNull(ledger.acquire())
        assertEquals(0, ledger.stats().allocated)
        assertEquals("allocation failure is not pool exhaustion", 0L, ledger.stats().exhausted)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameBufferLedgerTest) -> Unit) {
            val s = FrameBufferLedgerTest()
            block(s)
        }
        runIsolated { s -> s.`frame identity is unique across player sessions sharing a GPU cache`() }
        runIsolated { s -> s.`allocation is bounded and exhausted pool applies backpressure`() }
        runIsolated { s -> s.`last holder recycles buffer under a fresh frame id that is never reused`() }
        runIsolated { s -> s.`released id cannot be accessed, retained or released again`() }
        runIsolated { s -> s.`only current holders get a view and exclusive holders cannot double retain`() }
        runIsolated { s -> s.`presenter holds stack and each release counts once`() }
        runIsolated { s -> s.`format change drops idle buffers now and held buffers on last release`() }
        runIsolated { s -> s.`close stops allocation and drains only after outstanding holders return`() }
        runIsolated { s -> s.`invalid formats and failed allocation are rejected without accounting drift`() }
    }
}
