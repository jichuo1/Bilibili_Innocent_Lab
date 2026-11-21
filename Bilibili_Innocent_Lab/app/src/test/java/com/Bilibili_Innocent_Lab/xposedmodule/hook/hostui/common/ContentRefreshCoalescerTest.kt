package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.ContentRefreshCoalescer.Companion.ARMED
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.ContentRefreshCoalescer.Companion.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRefreshCoalescerTest {
    private val interval = 28L
    private val coalescer = ContentRefreshCoalescer(interval)

    fun `the first change and any change after a quiet interval flush immediately`() {
        assertEquals(NOW, coalescer.onChange(1_000, coalesce = true))
        assertFalse(coalescer.armed)
        assertEquals(NOW, coalescer.onChange(1_000 + interval, coalesce = true))
        assertFalse(coalescer.armed)
    }

    fun `a burst arms exactly one trailing timer`() {
        assertEquals(NOW, coalescer.onChange(1_000, coalesce = true))
        assertEquals(interval, coalescer.onChange(1_011, coalesce = true))
        assertTrue(coalescer.armed)
        assertEquals(ARMED, coalescer.onChange(1_022, coalesce = true))
        assertEquals(ARMED, coalescer.onChange(1_033, coalesce = true))
    }

    fun `the timer waits for the burst to go quiet and then flushes once`() {
        coalescer.onChange(1_000, coalesce = true)
        coalescer.onChange(1_011, coalesce = true)
        coalescer.onChange(1_033, coalesce = true)
        // The last change was at 1033; the timer armed at 1011 fires at 1039 and must wait 22 ms more.
        assertEquals(22L, coalescer.onTimer(1_039))
        assertTrue(coalescer.armed)
        assertEquals(NOW, coalescer.onTimer(1_061))
        assertFalse(coalescer.armed)
    }

    fun `without coalescing every change flushes and a stale timer is disarmed`() {
        coalescer.onChange(1_000, coalesce = true)
        coalescer.onChange(1_011, coalesce = true)
        assertTrue(coalescer.armed)
        // The caller reads armed first to know it must cancel the posted callback.
        assertEquals(NOW, coalescer.onChange(1_012, coalesce = false))
        assertFalse(coalescer.armed)
        assertEquals(NOW, coalescer.onChange(1_013, coalesce = false))
    }

    fun `a late timer does not double flush after an isolated change already did`() {
        coalescer.onChange(1_000, coalesce = true)
        coalescer.onChange(1_011, coalesce = true)
        assertEquals(NOW, coalescer.onChange(1_011 + interval, coalesce = true))
        assertFalse(coalescer.armed)
    }

    fun `cancel drops the pending trailing flush`() {
        coalescer.onChange(1_000, coalesce = true)
        coalescer.onChange(1_011, coalesce = true)
        coalescer.cancel()
        assertFalse(coalescer.armed)
        // A change right after cancelling arms again rather than assuming a timer still runs.
        assertEquals(interval, coalescer.onChange(1_012, coalesce = true))
    }

    fun `the interval must be positive`() {
        ContentRefreshCoalescer(0L)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ContentRefreshCoalescerTest) -> Unit) {
            val s = ContentRefreshCoalescerTest()
            block(s)
        }
        runIsolated { s -> s.`the first change and any change after a quiet interval flush immediately`() }
        runIsolated { s -> s.`a burst arms exactly one trailing timer`() }
        runIsolated { s -> s.`the timer waits for the burst to go quiet and then flushes once`() }
        runIsolated { s -> s.`without coalescing every change flushes and a stale timer is disarmed`() }
        runIsolated { s -> s.`a late timer does not double flush after an isolated change already did`() }
        runIsolated { s -> s.`cancel drops the pending trailing flush`() }
        runIsolated { s -> try { s.`the interval must be positive`(); org.junit.Assert.fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {} }
    }
}
