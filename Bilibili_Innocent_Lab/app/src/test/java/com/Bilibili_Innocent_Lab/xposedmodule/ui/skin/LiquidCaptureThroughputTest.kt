package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidCaptureThroughputPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidCaptureThroughputTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidCaptureThroughputTest {

    private val supported = listOf(30f, 40f, 60f, 90f, 120f)

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
    fun `keeping up threshold matches the documented ratio`() {
        assertTrue(LiquidCaptureThroughputPolicy.isKeepingUp(120f, 120f * 0.75f))
        assertFalse(LiquidCaptureThroughputPolicy.isKeepingUp(120f, 120f * 0.70f))
        // 非法输入按"跟得上"处理，绝不因读数异常而降档。
        assertTrue(LiquidCaptureThroughputPolicy.isKeepingUp(120f, Float.NaN))
        assertTrue(LiquidCaptureThroughputPolicy.isKeepingUp(0f, 10f))
    }

    @Test
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
}
