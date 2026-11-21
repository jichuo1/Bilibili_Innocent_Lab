package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FrameVsyncMeterTest {
    private fun measure(periodNs: Long, dropEvery: Int = 0): Float? {
        val meter = FrameVsyncMeter(warmup = 10, samples = 60)
        var t = 0L
        for (i in 0 until 200) {
            t += if (dropEvery > 0 && i % dropEvery == 0) periodNs * 2 else periodNs
            meter.add(t)?.let { return it }
        }
        return null
    }

    fun `reports the vsync rate once after warm up`() {
        assertEquals(60f, measure(16_666_667L)!!, 0.2f)
        assertEquals(120f, measure(8_333_333L)!!, 0.2f)
        assertEquals(185f, measure(5_405_405L)!!, 0.2f)
    }

    fun `occasional skipped vsyncs do not lower the estimate`() {
        assertEquals(120f, measure(8_333_333L, dropEvery = 7)!!, 0.2f)
    }

    fun `only one result and non increasing timestamps are ignored`() {
        val meter = FrameVsyncMeter(warmup = 0, samples = 3)
        assertNull(meter.add(0L)); assertNull(meter.add(10L)); assertNull(meter.add(10L))
        assertNull(meter.add(20L)); assertNotNull(meter.add(30L)); assertNull(meter.add(40L))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameVsyncMeterTest) -> Unit) {
            val s = FrameVsyncMeterTest()
            block(s)
        }
        runIsolated { s -> s.`reports the vsync rate once after warm up`() }
        runIsolated { s -> s.`occasional skipped vsyncs do not lower the estimate`() }
        runIsolated { s -> s.`only one result and non increasing timestamps are ignored`() }
    }
}
