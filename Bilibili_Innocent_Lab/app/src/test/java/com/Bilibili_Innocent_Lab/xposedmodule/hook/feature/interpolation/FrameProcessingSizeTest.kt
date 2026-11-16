package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameProcessingSize.Capability
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameProcessingSize.Size
import org.junit.Assert.*
import org.junit.Test

class FrameProcessingSizeTest {
    // 2026-10-19 真机：1/4 光流档 1080p 可持续，1/8 光流档 1440p 可持续；面板 3168×1440。
    private val phone = Capability(standard = 1920L * 1080L, high = 2560L * 1440L)
    private fun choose(w: Int, h: Int, capability: Capability = phone, long: Int = 3168, short: Int = 1440) =
        FrameProcessingSize.choose(w, h, capability, long, short)

    @Test fun `sources within the panel and capability keep their size`() {
        assertEquals(Size(1280, 720), choose(1280, 720))
        assertEquals(Size(1920, 1080), choose(1920, 1080))
        assertEquals(Size(2560, 1440), choose(2560, 1440))
    }

    @Test fun `4k is reduced to the panel and never processed above what the screen shows`() {
        assertEquals(Size(2560, 1440), choose(3840, 2160))
        val dci = choose(4096, 2160)!!
        assertTrue("$dci", dci.pixels <= 2560L * 1440L && dci.height <= 1440)
        assertEquals(4096.0 / 2160.0, dci.width.toDouble() / dci.height, 0.01)
        val portrait = choose(2160, 3840)!!
        assertTrue(portrait.height <= 3168 && portrait.width <= 1440)
        assertEquals(2160.0 / 3840.0, portrait.width.toDouble() / portrait.height, 0.01)
    }

    @Test fun `a device without the high flow class falls back to the standard budget`() {
        assertEquals(Size(1920, 1080), choose(3840, 2160, Capability(1920L * 1080L, 0L)))
        assertEquals(Size(1280, 720), choose(3840, 2160, Capability(1280L * 720L, 0L)))
    }

    @Test fun `a 1080p source on a device where only 720p passes the standard class is reduced to 720p`() {
        // 1440p 在 1/8 档可持续不代表 1080p 在 1/4 档可持续：1080p 源不能借用高档预算。
        assertEquals(Size(1280, 720), choose(1920, 1080, Capability(1280L * 720L, 2560L * 1440L)))
    }

    @Test fun `a 4k panel with 4k capability processes 4k`() {
        assertEquals(Size(3840, 2160), choose(3840, 2160, Capability(1920L * 1080L, 3840L * 2160L), 3840, 2160))
    }

    @Test fun `no capability or degenerate input yields no processing size`() {
        assertNull(choose(1920, 1080, Capability(0L, 0L)))
        assertNull(choose(0, 1080))
        assertNull(choose(32, 32))
    }

    @Test fun `fit keeps even dimensions and the pixel budget`() {
        for ((w, h) in listOf(1921 to 1081, 3840 to 1634, 1366 to 768, 2880 to 2160)) {
            val size = FrameProcessingSize.fit(w, h, 1280L * 720L, 0, 0)!!
            assertTrue("$size", size.width % 2 == 0 && size.height % 2 == 0)
            assertTrue("$size", size.pixels <= 1280L * 720L)
            assertEquals(w.toDouble() / h, size.width.toDouble() / size.height, 0.01)
        }
    }

    @Test fun `odd source dimensions never round up beyond the source`() {
        assertEquals(Size(64, 64), FrameProcessingSize.fit(65, 65, Long.MAX_VALUE, 0, 0))
        assertEquals(Size(1918, 1080), FrameProcessingSize.fit(1919, 1081, Long.MAX_VALUE, 0, 0))
        val large = FrameProcessingSize.fit(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE, 0, 0)!!
        assertEquals(Int.MAX_VALUE - 1, large.width)
        assertEquals(Int.MAX_VALUE - 1, large.height)
    }

    @Test fun `rounding never exceeds odd display edges in either orientation`() {
        val landscape = FrameProcessingSize.fit(1920, 1080, Long.MAX_VALUE, 1441, 811)!!
        assertTrue("$landscape", landscape.width <= 1441 && landscape.height <= 811)
        val portrait = FrameProcessingSize.fit(1080, 1920, Long.MAX_VALUE, 1441, 811)!!
        assertTrue("$portrait", portrait.width <= 811 && portrait.height <= 1441)
    }

    @Test fun `valid results stay within source display and pixel bounds`() {
        val random = java.util.Random(0x6d656775L)
        repeat(1000) {
            val w = 64 + random.nextInt(4096)
            val h = 64 + random.nextInt(4096)
            val long = 64 + random.nextInt(4096)
            val short = 64 + random.nextInt(long - 63)
            val budget = 4096L + random.nextInt(8_000_000)
            val result = FrameProcessingSize.fit(w, h, budget, long, short) ?: return@repeat
            val widthLimit = if (w >= h) long else short
            val heightLimit = if (w >= h) short else long
            assertTrue("$w x $h -> $result", result.width in 64..minOf(w, widthLimit))
            assertTrue("$w x $h -> $result", result.height in 64..minOf(h, heightLimit))
            assertTrue("$result", result.width % 2 == 0 && result.height % 2 == 0 && result.pixels <= budget)
        }
    }
}
