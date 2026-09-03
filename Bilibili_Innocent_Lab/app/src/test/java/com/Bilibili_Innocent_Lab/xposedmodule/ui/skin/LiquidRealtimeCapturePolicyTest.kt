package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRealtimeCapturePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LiquidRealtimeCapturePolicyTest {
    @Test
    fun `sampling follows a pixel budget instead of a fixed scale`() {
        assertEquals(3, LiquidRealtimeCapturePolicy.BUFFER_COUNT)
        val budget = LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS

        // 1080p 面板原本就接近预算，采样倍率几乎不变（仍在 0.70..0.72 之间）。
        val common = LiquidRealtimeCapturePolicy.resolveSize(1080, 2400)
        assertTrue(common.pixels <= budget)
        assertTrue(common.width / 1080f > 0.70f && common.width / 1080f <= 0.72f)

        // 1440p 面板不再随分辨率平方增长：缓冲尺寸与 1080p 基本一致，倍率自动降到约 0.53。
        val dense = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200)
        assertTrue(dense.pixels <= budget)
        assertTrue(dense.width / 1440f < 0.6f)
        assertTrue(abs(dense.pixels - common.pixels) < budget / 20)

        // 低分屏不会被反向放大到超过原有清晰度。
        val small = LiquidRealtimeCapturePolicy.resolveSize(720, 1280)
        assertEquals(0.72f, small.width / 720f, 0.01f)

        val large = LiquidRealtimeCapturePolicy.resolveSize(4000, 3000)
        assertTrue(large.pixels <= budget)
        assertTrue(abs(large.width.toFloat() / large.height - 4f / 3f) < 0.01f)
    }

    @Test
    fun `tighter budgets shrink the buffer and never fall below the floor`() {
        val tight = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200, pixelBudget = 600_000L)
        assertTrue(tight.pixels <= 600_000L)

        val floored = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200, pixelBudget = 1L)
        assertTrue(floored.pixels <= LiquidRealtimeCapturePolicy.MIN_SAMPLE_PIXELS)
        assertTrue(floored.width > 0 && floored.height > 0)
    }

    @Test
    fun `only large surfaces drop to reduced scatter taps`() {
        assertFalse(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1080, 620))
        assertFalse(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1300, 900))
        assertTrue(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1440, 3200))
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
