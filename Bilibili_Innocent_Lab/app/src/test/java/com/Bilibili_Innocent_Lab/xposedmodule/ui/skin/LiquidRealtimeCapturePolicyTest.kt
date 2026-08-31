package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRealtimeCapturePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LiquidRealtimeCapturePolicyTest {
    @Test
    fun `uses high quality bounded triple buffering`() {
        val common = LiquidRealtimeCapturePolicy.resolveSize(1080, 2400)
        assertEquals(778, common.width)
        assertEquals(1728, common.height)
        assertEquals(3, LiquidRealtimeCapturePolicy.BUFFER_COUNT)

        val large = LiquidRealtimeCapturePolicy.resolveSize(4000, 3000)
        assertTrue(large.width.toLong() * large.height.toLong() <= 2_400_000L)
        assertTrue(abs(large.width.toFloat() / large.height - 4f / 3f) < 0.01f)
    }

    @Test
    fun `support and failure boundaries stay explicit`() {
        assertFalse(LiquidRealtimeCapturePolicy.isSupported(30, hardwareAccelerated = true))
        assertFalse(LiquidRealtimeCapturePolicy.isSupported(33, hardwareAccelerated = false))
        assertTrue(LiquidRealtimeCapturePolicy.isSupported(33, hardwareAccelerated = true))
        assertFalse(LiquidRealtimeCapturePolicy.shouldSuspend(3))
        assertTrue(LiquidRealtimeCapturePolicy.shouldSuspend(4))
    }

    @Test
    fun `frame pacing targets thirty fps without negative delay`() {
        assertEquals(33L, LiquidRealtimeCapturePolicy.nextFrameDelayMs(0L))
        assertEquals(17L, LiquidRealtimeCapturePolicy.nextFrameDelayMs(16L))
        assertEquals(0L, LiquidRealtimeCapturePolicy.nextFrameDelayMs(60L))
        assertTrue(LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA in 0xC0..0xF0)
    }
}
