package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidBackdropSizingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidBackendFallbackPlan
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidCapabilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidCaptureThroughputPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidCaptureThroughputTracker
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidMemoryPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidOpticalSamplingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidPerformancePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRealtimeCapturePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceEdgePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidCapabilityPolicyTest {

    fun `api 33 hardware tries refraction blur then translucent`() {
        assertEquals(
            listOf(
                LiquidRenderBackend.REFRACTION,
                LiquidRenderBackend.BLUR,
                LiquidRenderBackend.TRANSLUCENT
            ),
            LiquidCapabilityPolicy.candidateOrder(33, hardwareAccelerated = true)
        )
    }

    fun `api 31 hardware tries blur then translucent`() {
        assertEquals(
            listOf(LiquidRenderBackend.BLUR, LiquidRenderBackend.TRANSLUCENT),
            LiquidCapabilityPolicy.candidateOrder(31, hardwareAccelerated = true)
        )
    }

    fun `api 30 hardware uses translucent`() {
        assertEquals(
            listOf(LiquidRenderBackend.TRANSLUCENT),
            LiquidCapabilityPolicy.candidateOrder(30, hardwareAccelerated = true)
        )
    }

    fun `software canvas never selects gpu backends`() {
        listOf(27, 31, 33, 37).forEach { sdk ->
            assertEquals(
                listOf(LiquidRenderBackend.TRANSLUCENT),
                LiquidCapabilityPolicy.candidateOrder(sdk, hardwareAccelerated = false)
            )
        }
    }

    fun `fallback plan advances one backend at a time`() {
        val plan = LiquidBackendFallbackPlan(
            listOf(
                LiquidRenderBackend.REFRACTION,
                LiquidRenderBackend.BLUR,
                LiquidRenderBackend.TRANSLUCENT
            )
        )

        assertEquals(LiquidRenderBackend.REFRACTION, plan.current)
        assertEquals(
            LiquidRenderBackend.BLUR,
            plan.advanceAfterFailure(LiquidRenderBackend.REFRACTION)
        )
        assertEquals(
            LiquidRenderBackend.TRANSLUCENT,
            plan.advanceAfterFailure(LiquidRenderBackend.BLUR)
        )
        assertNull(plan.advanceAfterFailure(LiquidRenderBackend.TRANSLUCENT))
    }

    fun `stale failure cannot skip current fallback`() {
        val plan = LiquidBackendFallbackPlan(
            listOf(
                LiquidRenderBackend.REFRACTION,
                LiquidRenderBackend.BLUR,
                LiquidRenderBackend.TRANSLUCENT
            )
        )
        plan.advanceAfterFailure(LiquidRenderBackend.REFRACTION)

        assertEquals(
            LiquidRenderBackend.BLUR,
            plan.advanceAfterFailure(LiquidRenderBackend.REFRACTION)
        )
        assertEquals(LiquidRenderBackend.BLUR, plan.current)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidCapabilityPolicyTest) -> Unit) {
            val s = LiquidCapabilityPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`api 33 hardware tries refraction blur then translucent`() }
        runIsolated { s -> s.`api 31 hardware tries blur then translucent`() }
        runIsolated { s -> s.`api 30 hardware uses translucent`() }
        runIsolated { s -> s.`software canvas never selects gpu backends`() }
        runIsolated { s -> s.`fallback plan advances one backend at a time`() }
        runIsolated { s -> s.`stale failure cannot skip current fallback`() }
    
        LiquidCaptureThroughputCases().verifyAllCases()
        LiquidMemoryPolicyCases().verifyAllCases()
        LiquidOpticalSamplingPolicyCases().verifyAllCases()
        LiquidPerformancePolicyCases().verifyAllCases()
        LiquidRealtimeCapturePolicyCases().verifyAllCases()
    }
}

private class LiquidCaptureThroughputCases {

    private val supported = listOf(30f, 40f, 60f, 90f, 120f)

    fun `warmup samples never trigger a step down`() {
        val tracker = LiquidCaptureThroughputTracker()
        var now = 0L
        // 远低于 120Hz 的间隔，但样本数还没过预热门槛。
        repeat(LiquidCaptureThroughputPolicy.WARMUP_SAMPLES) {
            now += 40_000_000L
            assertFalse(tracker.onCaptureCompleted(now, currentTargetFps = 120f))
        }
        assertEquals(0f, tracker.measuredFramesPerSecond, 0f)
    }

    fun `sustained shortfall steps down only after the full vote count`() {
        val tracker = LiquidCaptureThroughputTracker()
        var now = 0L
        var triggered = false
        var completions = 0
        // 25ms 间隔 ≈ 40fps，远低于 120 × 0.72 的门槛。
        repeat(64) {
            now += 25_000_000L
            completions += 1
            if (tracker.onCaptureCompleted(now, currentTargetFps = 120f)) {
                triggered = true
                return@repeat
            }
        }
        assertTrue(triggered)
        // 预热 + 投票次数决定最早触发点，单次慢帧不可能立即降档。
        assertTrue(
            completions >= LiquidCaptureThroughputPolicy.WARMUP_SAMPLES +
                LiquidCaptureThroughputPolicy.VOTES_TO_STEP_DOWN
        )
    }

    fun `keeping up resets the vote streak`() {
        val tracker = LiquidCaptureThroughputTracker()
        var now = 0L
        repeat(40) {
            // 稳定 8.3ms ≈ 120fps，完全跟得上。
            now += 8_333_333L
            assertFalse(tracker.onCaptureCompleted(now, currentTargetFps = 120f))
        }
        assertTrue(tracker.measuredFramesPerSecond > 110f)
    }

    fun `outlier intervals are discarded instead of poisoning the average`() {
        val tracker = LiquidCaptureThroughputTracker()
        var now = 0L
        repeat(20) {
            now += 8_333_333L
            tracker.onCaptureCompleted(now, currentTargetFps = 120f)
        }
        val before = tracker.measuredFramesPerSecond
        // 页面切换后的第一帧：超过上限的间隔必须被整条丢弃。
        now += LiquidCaptureThroughputPolicy.MAX_VALID_INTERVAL_NANOS + 1L
        assertFalse(tracker.onCaptureCompleted(now, currentTargetFps = 120f))
        assertEquals(before, tracker.measuredFramesPerSecond, 0.001f)
    }

    fun `reset clears the streak and the measurement`() {
        val tracker = LiquidCaptureThroughputTracker()
        var now = 0L
        repeat(20) {
            now += 25_000_000L
            tracker.onCaptureCompleted(now, currentTargetFps = 120f)
        }
        tracker.reset()
        assertEquals(0f, tracker.measuredFramesPerSecond, 0f)
        now += 25_000_000L
        assertFalse(tracker.onCaptureCompleted(now, currentTargetFps = 120f))
    }

    fun `step down picks the highest supported mode at or below the measured rate`() {
        assertEquals(
            60f,
            LiquidCaptureThroughputPolicy.stepDownTarget(120f, measuredFps = 61f, supported),
            0f
        )
        assertEquals(
            90f,
            LiquidCaptureThroughputPolicy.stepDownTarget(120f, measuredFps = 95f, supported),
            0f
        )
    }

    fun `step down never goes below the adaptive floor`() {
        // 实测只有 35fps，但吞吐自适应不得把窗口压到 60Hz 以下；更低档位留给热降载。
        assertEquals(
            LiquidCaptureThroughputPolicy.MIN_ADAPTIVE_FPS,
            LiquidCaptureThroughputPolicy.stepDownTarget(120f, measuredFps = 35f, supported),
            0f
        )
        // 已经在下限上时不再继续降。
        assertEquals(
            60f,
            LiquidCaptureThroughputPolicy.stepDownTarget(60f, measuredFps = 20f, supported),
            0f
        )
    }

    fun `step down is monotone and tolerates degenerate inputs`() {
        assertEquals(
            120f,
            LiquidCaptureThroughputPolicy.stepDownTarget(120f, measuredFps = 0f, supported),
            0f
        )
        assertEquals(
            120f,
            LiquidCaptureThroughputPolicy.stepDownTarget(120f, Float.NaN, supported),
            0f
        )
        assertEquals(
            120f,
            LiquidCaptureThroughputPolicy.stepDownTarget(120f, 30f, emptyList()),
            0f
        )
        assertTrue(
            LiquidCaptureThroughputPolicy.stepDownTarget(120f, 61f, supported) <= 120f
        )
    }

    fun `keeping up threshold matches the documented ratio`() {
        assertTrue(LiquidCaptureThroughputPolicy.isKeepingUp(120f, 120f * 0.75f))
        assertFalse(LiquidCaptureThroughputPolicy.isKeepingUp(120f, 120f * 0.70f))
        // 非法输入按"跟得上"处理，绝不因读数异常而降档。
        assertTrue(LiquidCaptureThroughputPolicy.isKeepingUp(120f, Float.NaN))
        assertTrue(LiquidCaptureThroughputPolicy.isKeepingUp(0f, 10f))
    }

    fun `interval smoothing converges without overshooting`() {
        var interval = LiquidCaptureThroughputPolicy.intervalNanos(120f)
        repeat(60) {
            interval = LiquidCaptureThroughputPolicy.smoothInterval(
                interval,
                LiquidCaptureThroughputPolicy.intervalNanos(60f)
            )
        }
        val converged = LiquidCaptureThroughputPolicy.framesPerSecond(interval)
        assertTrue(converged in 59f..61f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidCaptureThroughputCases) -> Unit) {
            val s = LiquidCaptureThroughputCases()
            block(s)
        }
        runIsolated { s -> s.`warmup samples never trigger a step down`() }
        runIsolated { s -> s.`sustained shortfall steps down only after the full vote count`() }
        runIsolated { s -> s.`keeping up resets the vote streak`() }
        runIsolated { s -> s.`outlier intervals are discarded instead of poisoning the average`() }
        runIsolated { s -> s.`reset clears the streak and the measurement`() }
        runIsolated { s -> s.`step down picks the highest supported mode at or below the measured rate`() }
        runIsolated { s -> s.`step down never goes below the adaptive floor`() }
        runIsolated { s -> s.`step down is monotone and tolerates degenerate inputs`() }
        runIsolated { s -> s.`keeping up threshold matches the documented ratio`() }
        runIsolated { s -> s.`interval smoothing converges without overshooting`() }
    }
}

private class LiquidMemoryPolicyCases {

    fun `running critical releases graphics`() {
        assertTrue(LiquidMemoryPolicy.shouldReleaseGraphics(15))
    }

    fun `ui hidden does not permanently downgrade renderer`() {
        listOf(5, 10, 14, 16, 20, 40, 60, 79).forEach { level ->
            assertFalse(LiquidMemoryPolicy.shouldReleaseGraphics(level))
        }
    }

    fun `complete pressure releases graphics`() {
        assertTrue(LiquidMemoryPolicy.shouldReleaseGraphics(80))
        assertTrue(LiquidMemoryPolicy.shouldReleaseGraphics(81))
        assertTrue(LiquidMemoryPolicy.shouldReleaseGraphics(100))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidMemoryPolicyCases) -> Unit) {
            val s = LiquidMemoryPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`running critical releases graphics`() }
        runIsolated { s -> s.`ui hidden does not permanently downgrade renderer`() }
        runIsolated { s -> s.`complete pressure releases graphics`() }
    }
}

private class LiquidOpticalSamplingPolicyCases {
    fun opticalCopyAndPreparationMemoryRemainBoundedForAllSupportedSourceSizes() {
        for ((width, height) in listOf(1 to 1, 1080 to 2400, 1440 to 3200, 3840 to 2160,
            1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val sample = LiquidBackdropSizingPolicy.resolve(width, height)
            val bytes = LiquidOpticalSamplingPolicy.opticalBytes(sample.width, sample.height)
            assertTrue(bytes <= 2L * 1024L * 1024L)
            assertTrue(bytes * 4L <= LiquidOpticalSamplingPolicy.MAX_PREPARATION_BYTES)
        }
        assertTrue(runCatching { LiquidOpticalSamplingPolicy.opticalBytes(0, 10) }.isFailure)
        assertTrue(runCatching { LiquidOpticalSamplingPolicy.opticalBytes(Int.MAX_VALUE, Int.MAX_VALUE) }.isFailure)
    }

    fun sampledRadiusRepresentsPhysicalDiffusionRatherThanOneFixedPixelRadius() {
        assertEquals(15, LiquidOpticalSamplingPolicy.sampledRadius(270, 1080, 3f))
        assertEquals(18, LiquidOpticalSamplingPolicy.sampledRadius(360, 1440, 3.5f))
        assertEquals(5, LiquidOpticalSamplingPolicy.sampledRadius(360, 1440, 1f))
        assertEquals(10, LiquidOpticalSamplingPolicy.sampledRadius(540, 1080, 1f))
        assertEquals(24, LiquidOpticalSamplingPolicy.sampledRadius(2048, 2048, 4f))
        assertTrue(runCatching { LiquidOpticalSamplingPolicy.sampledRadius(1, 1, Float.NaN) }.isFailure)
        assertTrue(runCatching { LiquidOpticalSamplingPolicy.sampledRadius(1, 0, 1f) }.isFailure)
    }

    fun opticalFilteringRemovesDetailWithoutChangingTheRootImagePixels() {
        val width = 65
        val height = 33
        val original = IntArray(width * height) { pixel ->
            if ((pixel % width + pixel / width) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val before = original.copyOf()
        val optical = LiquidOpticalSamplingPolicy.soften(original, width, height, width * 4, 2f)
        assertArrayEquals(before, original)
        assertNotSame(original, optical)
        assertEquals(original.size, optical.size)
        assertTrue(optical.all { it ushr 24 == 255 })
        val center = (10..22).flatMap { y -> (20..44).map { x -> optical[y * width + x] and 255 } }
        assertTrue(center.max() - center.min() < 8)
        assertTrue(center.all { it in 100..150 })
    }

    fun filteringPreservesFlatColorsAndHandlesNarrowBitmaps() {
        for ((width, height) in listOf(1 to 1, 1 to 31, 31 to 1, 19 to 11)) {
            val source = IntArray(width * height) { 0xFF406080.toInt() }
            assertArrayEquals(source, LiquidOpticalSamplingPolicy.soften(source, width, height,
                width * 4, 2f))
        }
    }

    fun cancelledBackgroundPreparationStopsWithoutMutatingItsInput() {
        val source = IntArray(64) { 0xFF304050.toInt() }
        val original = source.copyOf()
        Thread.currentThread().interrupt()
        try {
            val failure = runCatching { LiquidOpticalSamplingPolicy.soften(source, 8, 8, 32, 2f) }.exceptionOrNull()
            assertTrue(failure is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
            assertArrayEquals(original, source)
        } finally {
            Thread.interrupted()
        }
    }

    fun topChromeHasNoHardFrameAndSelectedLensHasALighterEdgeThanItsTray() {
        assertEquals(0f, LiquidSurfaceEdgePolicy.alphaMultiplier(SurfaceRole.TOP_BAR), 0f)
        assertEquals(0f, LiquidSurfaceEdgePolicy.alphaMultiplier(SurfaceRole.WINDOW), 0f)
        assertTrue(LiquidSurfaceEdgePolicy.alphaMultiplier(SurfaceRole.SELECTED_ITEM) <
            LiquidSurfaceEdgePolicy.alphaMultiplier(SurfaceRole.FLOATING))
        assertTrue(SurfaceRole.entries.all { LiquidSurfaceEdgePolicy.alphaMultiplier(it) in 0f..0.55f })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidOpticalSamplingPolicyCases) -> Unit) {
            val s = LiquidOpticalSamplingPolicyCases()
            block(s)
        }
        runIsolated { s -> s.opticalCopyAndPreparationMemoryRemainBoundedForAllSupportedSourceSizes() }
        runIsolated { s -> s.sampledRadiusRepresentsPhysicalDiffusionRatherThanOneFixedPixelRadius() }
        runIsolated { s -> s.opticalFilteringRemovesDetailWithoutChangingTheRootImagePixels() }
        runIsolated { s -> s.filteringPreservesFlatColorsAndHandlesNarrowBitmaps() }
        runIsolated { s -> s.cancelledBackgroundPreparationStopsWithoutMutatingItsInput() }
        runIsolated { s -> s.topChromeHasNoHardFrameAndSelectedLensHasALighterEdgeThanItsTray() }
    }
}

private class LiquidPerformancePolicyCases {
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

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidPerformancePolicyCases) -> Unit) {
            val s = LiquidPerformancePolicyCases()
            block(s)
        }
        runIsolated { s -> s.`none and light thermal pressure preserve requested refresh rate`() }
        runIsolated { s -> s.`thermal pressure progressively caps realtime capture`() }
        runIsolated { s -> s.`thermal policy never raises a lower requested refresh rate`() }
        runIsolated { s -> s.`thermal pressure also shrinks the sampling budget`() }
        runIsolated { s -> s.`sampling budget never rises above the caller base and clamps invalid status`() }
        runIsolated { s -> s.`invalid inputs fail open to stable defaults`() }
    }
}

private class LiquidRealtimeCapturePolicyCases {
    fun `sampling follows a pixel budget instead of a fixed scale`() {
        assertEquals(3, LiquidRealtimeCapturePolicy.BUFFER_COUNT)
        val budget = LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS
        assertEquals(1_000_000L, budget)

        // 1080p 面板按 100 万像素预算收敛到约 0.62 倍，为高刷新率滑动留出余量。
        val common = LiquidRealtimeCapturePolicy.resolveSize(1080, 2400)
        assertTrue(common.pixels <= budget)
        assertTrue(common.width / 1080f > 0.60f && common.width / 1080f < 0.64f)

        // 1440p 面板不再随分辨率平方增长：缓冲尺寸与 1080p 基本一致，倍率约 0.47。
        val dense = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200)
        assertTrue(dense.pixels <= budget)
        assertTrue(dense.width / 1440f < 0.5f)
        assertTrue(abs(dense.pixels - common.pixels) < budget / 20)

        // 低分屏不会被反向放大到超过原有清晰度。
        val small = LiquidRealtimeCapturePolicy.resolveSize(720, 1280)
        assertEquals(0.72f, small.width / 720f, 0.01f)

        val large = LiquidRealtimeCapturePolicy.resolveSize(4000, 3000)
        assertTrue(large.pixels <= budget)
        assertTrue(abs(large.width.toFloat() / large.height - 4f / 3f) < 0.01f)
    }

    fun `tighter budgets shrink the buffer and never fall below the floor`() {
        val tight = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200, pixelBudget = 600_000L)
        assertTrue(tight.pixels <= 600_000L)

        val floored = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200, pixelBudget = 1L)
        assertTrue(floored.pixels <= LiquidRealtimeCapturePolicy.MIN_SAMPLE_PIXELS)
        assertTrue(floored.width > 0 && floored.height > 0)
    }

    fun `only large surfaces drop to reduced scatter taps`() {
        assertFalse(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1080, 620))
        assertFalse(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1300, 900))
        assertTrue(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1440, 3200))
    }

    fun `support and failure boundaries stay explicit`() {
        assertFalse(LiquidRealtimeCapturePolicy.isSupported(30, hardwareAccelerated = true))
        assertFalse(LiquidRealtimeCapturePolicy.isSupported(33, hardwareAccelerated = false))
        assertTrue(LiquidRealtimeCapturePolicy.isSupported(33, hardwareAccelerated = true))
        assertFalse(LiquidRealtimeCapturePolicy.shouldSuspend(3))
        assertTrue(LiquidRealtimeCapturePolicy.shouldSuspend(4))
    }

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
        assertEquals(255, LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA)
    }

    fun `stretch optics rise smoothly and stay bounded`() {
        val idle = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0f)
        val middle = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0.09f)
        val maximum = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0.18f)

        assertEquals(1f, idle, 0f)
        assertTrue(middle > idle)
        assertTrue(maximum > middle)
        assertEquals(maximum, LiquidRealtimeCapturePolicy.stretchOpticalIntensity(1f), 0f)
    }

    /**
     * 回弹强度必须量化（2026-09-22 真机实证）。
     *
     * 强度每变一次，渲染层就要把整组可见玻璃表面重录一遍；而系统 stretch 距离是连续
     * 衰减的，不量化时回弹期几乎每帧都越过发布门——102 秒滑动采样里坏帧的 draw 录制
     * 中位 3.43ms，是全局中位 1.19ms 的近 3 倍，现场就是"滑动偶发不跟手"。
     */
    fun `stretch optics are quantised so a rebound cannot re-record every frame`() {
        val step = LiquidRealtimeCapturePolicy.STRETCH_INTENSITY_STEP
        assertTrue(step > 0f)
        val values = (0..900).map {
            LiquidRealtimeCapturePolicy.stretchOpticalIntensity(it / 5000f)
        }
        // 每个取值都必须落在整数级上。
        values.forEach {
            val steps = ((it - 1f) / step)
            assertEquals(Math.round(steps).toFloat(), steps, 1e-3f)
        }
        // 级数必须有限且远小于采样点数，否则等于没量化。
        val distinct = values.distinct().size
        assertTrue("量化后级数应远少于采样数: $distinct", distinct < 40)
        // 终态必须精确归 1，不能停在半亮。
        assertEquals(1f, LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0f), 0f)
        // 单调不回头：回弹衰减过程中强度只降不升。
        var previous = Float.MAX_VALUE
        for (i in 900 downTo 0) {
            val v = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(i / 5000f)
            assertTrue(v <= previous + 1e-6f)
            previous = v
        }
    }

    /**
     * 静止门控（2026-09-23 真机：高级材质静止 5 秒渲染 154 帧、每帧 GPU 10ms，柔光同条件 0 帧；
     * 加门控后 0 帧，静止画面与旧实现逐像素一致）。
     */
    fun `identical captures are dropped only when compared against the bound realtime source`() {
        assertTrue(LiquidRealtimeCapturePolicy.isUnchanged(true, false) { true })
        // 内容变了：必须绑定新截图。
        assertFalse(LiquidRealtimeCapturePolicy.isUnchanged(true, false) { false })
        // 抑制期后端绑的是稳定底图：内容恰好相同也必须绑回实时截图，否则玻璃停在磨砂观感。
        assertFalse(LiquidRealtimeCapturePolicy.isUnchanged(false, false) { true })
        // 抑制期完成的截图同样不参与静止判定。
        assertFalse(LiquidRealtimeCapturePolicy.isUnchanged(true, true) { true })
        // 基准不成立时不做逐像素比较（省一次 4MB memcmp）。
        var compared = false
        LiquidRealtimeCapturePolicy.isUnchanged(false, false) { compared = true; true }
        assertFalse(compared)
    }

    fun `idle needs consecutive confirmations and bounded probe latency`() {
        // 刚画完的那一帧可能尚未合成：单张"相同"不足以判定静止。
        assertFalse(LiquidRealtimeCapturePolicy.shouldEnterIdle(1))
        assertTrue(LiquidRealtimeCapturePolicy.shouldEnterIdle(LiquidRealtimeCapturePolicy.IDLE_CONFIRMATIONS))
        assertTrue(LiquidRealtimeCapturePolicy.IDLE_CONFIRMATIONS >= 2)
        assertTrue(LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES >= 1)
        // 纯 RenderThread 动画不经 UI 线程绘制，兜底探测把滞后上限钉在 1 秒以内。
        assertTrue(LiquidRealtimeCapturePolicy.IDLE_PROBE_MS in 250L..1000L)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidRealtimeCapturePolicyCases) -> Unit) {
            val s = LiquidRealtimeCapturePolicyCases()
            block(s)
        }
        runIsolated { s -> s.`sampling follows a pixel budget instead of a fixed scale`() }
        runIsolated { s -> s.`tighter budgets shrink the buffer and never fall below the floor`() }
        runIsolated { s -> s.`only large surfaces drop to reduced scatter taps`() }
        runIsolated { s -> s.`support and failure boundaries stay explicit`() }
        runIsolated { s -> s.`frame pacing follows the fastest supported mode up to one hundred twenty hertz`() }
        runIsolated { s -> s.`stretch optics rise smoothly and stay bounded`() }
        runIsolated { s -> s.`stretch optics are quantised so a rebound cannot re-record every frame`() }
        runIsolated { s -> s.`identical captures are dropped only when compared against the bound realtime source`() }
        runIsolated { s -> s.`idle needs consecutive confirmations and bounded probe latency`() }
    }
}
