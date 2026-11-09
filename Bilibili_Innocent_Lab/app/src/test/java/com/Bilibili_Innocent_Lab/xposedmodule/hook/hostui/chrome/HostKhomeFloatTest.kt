package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class HostKhomeFloatTest {
    private class Box(var value: Int)

    private val ledger = HostChromeValueLedger<Box, Int>({ it.value }, { box, value -> box.value = value })

    @Test fun `offsets apply to the host base and follow a changed shift without accumulating`() {
        val list = Box(12)
        assertTrue(ledger.apply(list) { it + 300 })
        assertEquals(312, list.value)
        assertFalse("Re-applying the same shift writes nothing", ledger.apply(list) { it + 300 })
        assertTrue(ledger.apply(list) { it + 250 })
        assertEquals("A new shift is computed from the host base, not stacked", 262, list.value)
        assertTrue(ledger.restore(list))
        assertEquals(12, list.value)
        assertFalse(ledger.owns(list))
    }

    @Test fun `a host rewrite becomes the new base and is never overwritten on restore`() {
        val list = Box(0)
        ledger.apply(list) { it + 300 }
        list.value = 8
        ledger.apply(list) { it + 300 }
        assertEquals(308, list.value)
        list.value = 40
        assertFalse("Restore leaves a value the host wrote after us", ledger.restore(list))
        assertEquals(40, list.value)
    }

    @Test fun `restoreAll keeps the current owner and gives back stale keys`() {
        val old = Box(0); val current = Box(0)
        ledger.apply(old) { it - 300 }
        ledger.apply(current) { it - 300 }
        assertTrue(ledger.restoreAll(except = current))
        assertEquals(0, old.value)
        assertEquals(-300, current.value)
        assertEquals(listOf(current), ledger.keys())
        assertFalse("only the excepted key is left", ledger.restoreAll(except = current))
        assertEquals(-300, current.value)
        assertTrue(ledger.restoreAll())
        assertEquals(0, current.value)
        assertFalse("an empty ledger has nothing to restore", ledger.restoreAll())
    }

    @Test fun `restore can write an equivalent value instead of the raw base`() {
        val refresh = Box(-40)
        val mapped = HostChromeValueLedger<Box, Int>({ it.value }, { box, value -> box.value = value }, { it - 1 })
        mapped.apply(refresh) { it + 10 }
        mapped.restore(refresh)
        assertEquals(-41, refresh.value)
    }

    @Test fun `content margin cancels the expanded AppBar bottom and keeps the measured bottom`() {
        val statusBar = 96; val appBar = 336; val base = 0
        val margin = HostKhomeFloatPolicy.contentMargin(base, statusBar, appBar)
        assertEquals(-432, margin)
        // HeaderScrollingViewBehavior: top = header.bottom + margin, height grows by -margin.
        val nativeTop = statusBar + appBar; val nativeHeight = 2000
        val top = nativeTop + margin; val height = nativeHeight - margin
        assertEquals(0, top)
        assertEquals(nativeTop + nativeHeight, top + height)
    }

    @Test fun `refresh shift keeps the native drag distance and resting gap`() {
        val density = 3; val shift = 432
        val start = -40 * density; val end = 64 * density
        val (newStart, newEnd) = HostKhomeFloatPolicy.refreshOffsets(start, end, shift)
        // Native, non-custom: drag distance = end, rest = end - |start|. Custom: end - start, end.
        assertEquals(end, newEnd - newStart)
        assertEquals(end - abs(start) + shift, newEnd)
        val (restoredStart, restoredEnd) = HostKhomeFloatPolicy.restoredRefreshOffsets(start, end)
        assertEquals(start, restoredStart)
        assertEquals("Restored custom pair rests where the native pair did", end - abs(start), restoredEnd)
        assertEquals("…and drags the same distance", end, restoredEnd - restoredStart)
    }

    @Test fun `a custom refresh start shifts as a pair`() {
        assertEquals(500 to 620, HostKhomeFloatPolicy.refreshOffsets(68, 188, 432))
        assertEquals(68 to 188, HostKhomeFloatPolicy.restoredRefreshOffsets(68, 188))
    }

    @Test fun `only a list spanning its page is the feed`() {
        assertTrue(HostKhomeFloatPolicy.primaryList(1080, 1900, 1080, 2000))
        assertFalse("Carousel", HostKhomeFloatPolicy.primaryList(1080, 400, 1080, 2000))
        assertFalse("Side column", HostKhomeFloatPolicy.primaryList(500, 2000, 1080, 2000))
        assertFalse("Unlaid page", HostKhomeFloatPolicy.primaryList(1080, 2000, 0, 0))
    }
}
