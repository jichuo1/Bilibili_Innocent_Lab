package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidPerformancePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRealtimeCapturePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidPerformancePolicyTest {
    @Test
    fun `none and light thermal pressure preserve requested refresh rate`() {
        assertEquals(
            120f,
            LiquidPerformancePolicy.targetRefreshRate(
                120f,
                LiquidPerformancePolicy.THERMAL_STATUS_NONE
            ),
            0f
        )
        assertEquals(
            120f,
            LiquidPerformancePolicy.targetRefreshRate(
                120f,
                LiquidPerformancePolicy.THERMAL_STATUS_LIGHT
            ),
            0f
        )
    }

    @Test
    fun `thermal pressure progressively caps realtime capture`() {
        assertEquals(
            90f,
            LiquidPerformancePolicy.targetRefreshRate(
                120f,
                LiquidPerformancePolicy.THERMAL_STATUS_MODERATE
            ),
            0f
        )
        assertEquals(
            60f,
            LiquidPerformancePolicy.targetRefreshRate(
                120f,
                LiquidPerformancePolicy.THERMAL_STATUS_SEVERE
            ),
            0f
        )
        listOf(
            LiquidPerformancePolicy.THERMAL_STATUS_CRITICAL,
            LiquidPerformancePolicy.THERMAL_STATUS_EMERGENCY,
            LiquidPerformancePolicy.THERMAL_STATUS_SHUTDOWN
        ).forEach { status ->
            assertEquals(
                30f,
                LiquidPerformancePolicy.targetRefreshRate(120f, status),
                0f
            )
        }
    }

    @Test
    fun `thermal policy never raises a lower requested refresh rate`() {
        assertEquals(
            60f,
            LiquidPerformancePolicy.targetRefreshRate(
                60f,
                LiquidPerformancePolicy.THERMAL_STATUS_MODERATE
            ),
            0f
        )
        assertEquals(
            24f,
            LiquidPerformancePolicy.targetRefreshRate(
                24f,
                LiquidPerformancePolicy.THERMAL_STATUS_CRITICAL
            ),
            0f
        )
    }

    @Test
    fun `thermal pressure also shrinks the sampling budget`() {
        val base = LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS
        val none = LiquidPerformancePolicy.samplePixelBudget(
            thermalStatus = LiquidPerformancePolicy.THERMAL_STATUS_NONE
        )
        val light = LiquidPerformancePolicy.samplePixelBudget(
            thermalStatus = LiquidPerformancePolicy.THERMAL_STATUS_LIGHT
        )
        val moderate = LiquidPerformancePolicy.samplePixelBudget(
            thermalStatus = LiquidPerformancePolicy.THERMAL_STATUS_MODERATE
        )
        val severe = LiquidPerformancePolicy.samplePixelBudget(
            thermalStatus = LiquidPerformancePolicy.THERMAL_STATUS_SEVERE
        )
        val critical = LiquidPerformancePolicy.samplePixelBudget(
            thermalStatus = LiquidPerformancePolicy.THERMAL_STATUS_CRITICAL
        )

        assertEquals(base, none)
        assertEquals(base, light)
        assertTrue(moderate < none)
        assertTrue(severe < moderate)
        assertTrue(critical < severe)
        assertTrue(critical >= LiquidRealtimeCapturePolicy.MIN_SAMPLE_PIXELS)
    }

    @Test
    fun `sampling budget never rises above the caller base and clamps invalid status`() {
        val small = 300_000L
        assertEquals(
            small,
            LiquidPerformancePolicy.samplePixelBudget(
                basePixelBudget = small,
                thermalStatus = LiquidPerformancePolicy.THERMAL_STATUS_NONE
            )
        )
        assertTrue(
            LiquidPerformancePolicy.samplePixelBudget(
                basePixelBudget = small,
                thermalStatus = LiquidPerformancePolicy.THERMAL_STATUS_CRITICAL
            ) <= small
        )
        assertEquals(
            LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS,
            LiquidPerformancePolicy.samplePixelBudget(thermalStatus = 99)
        )
    }

    @Test
    fun `invalid inputs fail open to stable defaults`() {
        assertEquals(
            LiquidPerformancePolicy.THERMAL_STATUS_NONE,
            LiquidPerformancePolicy.normalizeThermalStatus(-1)
        )
        assertEquals(
            LiquidPerformancePolicy.THERMAL_STATUS_NONE,
            LiquidPerformancePolicy.normalizeThermalStatus(99)
        )
        assertEquals(
            120f,
            LiquidPerformancePolicy.targetRefreshRate(120f, 99),
            0f
        )
        assertEquals(
            60f,
            LiquidPerformancePolicy.targetRefreshRate(Float.NaN, 99),
            0f
        )
    }
}
