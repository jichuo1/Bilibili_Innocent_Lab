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
    fun `frame pacing follows the fastest supported mode up to one hundred twenty hertz`() {
        assertEquals(
            120f,
            LiquidRealtimeCapturePolicy.targetRefreshRate(
                currentRefreshRate = 60f,
                supportedRefreshRates = listOf(60f, 90f, 120f, 144f)
            ),
            0f
        )
        assertEquals(
            90f,
            LiquidRealtimeCapturePolicy.targetRefreshRate(
                currentRefreshRate = 90f,
                supportedRefreshRates = listOf(60f, 90f, 144f)
            ),
            0f
        )
        assertEquals(8_333_333L, LiquidRealtimeCapturePolicy.frameIntervalNanos(120f))
        assertFalse(LiquidRealtimeCapturePolicy.isFrameDue(8_000_000L, 8_333_333L))
        assertTrue(LiquidRealtimeCapturePolicy.isFrameDue(8_333_333L, 8_333_333L))
        assertTrue(LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA in 0xC0..0xF0)
    }

    @Test
    fun `stretch optics rise smoothly and stay bounded`() {
        val idle = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0f)
        val middle = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0.09f)
        val maximum = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0.18f)

        assertEquals(1f, idle, 0f)
        assertTrue(middle > idle)
        assertTrue(maximum > middle)
        assertEquals(maximum, LiquidRealtimeCapturePolicy.stretchOpticalIntensity(1f), 0f)
    }

    @Test
    fun `stretch feedback band stays inside the visible outer gutter`() {
        assertEquals(0f, LiquidRealtimeCapturePolicy.stretchFeedbackBandDp(-1f), 0f)
        assertEquals(8f, LiquidRealtimeCapturePolicy.stretchFeedbackBandDp(8f), 0f)
        assertEquals(12f, LiquidRealtimeCapturePolicy.stretchFeedbackBandDp(34f), 0f)
    }
}
