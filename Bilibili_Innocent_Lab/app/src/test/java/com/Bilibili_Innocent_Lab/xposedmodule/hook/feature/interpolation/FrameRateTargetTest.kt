package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FrameRateTargetTest {
    private val ltpo = listOf(1f, 10f, 24f, 30f, 60f, 90f, 120f, 144f)
    private val rooted = ltpo + listOf(165f, 185f)
    private fun choose(source: Float?, ceiling: Int, modes: List<Float> = ltpo, measured: Float? = null, ms: Double = 5.0) =
        FrameRateTarget.choose(source, ceiling, modes, measured, ms)

    @Test fun midpointRouteChoosesOneIntermediateWithoutAResearchModelTiming() {
        assertEquals(60, FrameRateTarget.midpoint(29.97f, 120, ltpo, null))
        assertEquals(50, FrameRateTarget.midpoint(25f, 60, listOf(60f), null))
        assertNull(FrameRateTarget.midpoint(30f, 120, ltpo, 45f))
        assertNull(FrameRateTarget.midpoint(60f, 60, ltpo, null))
    }

    @Test fun `default ceiling keeps the validated 60 and 50 fps behaviour`() {
        assertEquals(60, choose(30f, 60))
        assertEquals(60, choose(29.97f, 60))
        assertEquals(50, choose(25f, 60))
        assertEquals(60, choose(null, 60))
    }

    @Test fun `the system limit caps a higher ceiling`() {
        // 用户设 185，但系统实际只给 60（LTPO 对视频应用的策略）或 120：按实际上限补。
        assertEquals(60, choose(30f, 185, measured = 60f))
        assertEquals(120, choose(30f, 185, measured = 120f))
        assertEquals(144, choose(24f, 185, measured = 144f))
        // root 后实际给到 185：30 fps 源取 180（185 不是 30 的倍数，且需 180 或 360 Hz 模式）。
        assertEquals(180, choose(30f, 185, rooted + listOf(180f), measured = 180f))
        assertEquals(150, choose(30f, 185, rooted + listOf(150f), measured = 185f))
    }

    @Test fun `targets must be presentable by a display mode`() {
        // 30→90 只有在存在 90 Hz（或 180 Hz）模式时才选；否则退到 60。
        assertEquals(90, choose(30f, 90))
        assertEquals(60, choose(30f, 90, listOf(60f, 120f)))
        assertEquals(120, choose(24f, 120))
        assertEquals(72, choose(24f, 90, listOf(60f, 72f, 144f)))
    }

    @Test fun `inference capacity bounds the number of synthetic frames`() {
        // 每秒可持续补帧 0.8 × 1000 / ms：15 ms → 53 次/秒，30 fps 源最多补到 60（30 次/秒）而非 90（60 次/秒）。
        assertEquals(60, choose(30f, 185, measured = 144f, ms = 15.0))
        assertEquals(120, choose(30f, 185, measured = 144f, ms = 8.0))
        assertNull(choose(30f, 60, ms = 30.0))
    }

    @Test fun `any tier the engine accepts keeps 30 to 60 interpolating`() {
        // 引擎档位上限：1080p 1/4 档 26 ms、其余 24 ms；被接受的档位不能再被帧率选择拒绝。
        for (ms in listOf(FrameInterpolationEngineHolder.FINE_TIER_LIMIT_MS, FrameInterpolationEngineHolder.CAPABILITY_LIMIT_MS))
            assertEquals("ms=$ms", 60, choose(30f, 60, ms = ms))
        assertEquals(50, choose(25f, 60, ms = FrameInterpolationEngineHolder.FINE_TIER_LIMIT_MS))
    }

    @Test fun `sources already at or near the limit and unsupported rates are not interpolated`() {
        assertNull(choose(60f, 60))
        assertNull(choose(30f, 60, measured = 45f))
        assertNull(choose(15f, 60))
        assertNull(choose(50f, 90))
    }

    @Test fun `measured vsync snaps to the nearest panel mode`() {
        assertEquals(120f, FrameRateTarget.snap(118.7f, ltpo))
        assertEquals(60f, FrameRateTarget.snap(60.4f, ltpo))
        assertEquals(75f, FrameRateTarget.snap(75f, ltpo))
    }
}
