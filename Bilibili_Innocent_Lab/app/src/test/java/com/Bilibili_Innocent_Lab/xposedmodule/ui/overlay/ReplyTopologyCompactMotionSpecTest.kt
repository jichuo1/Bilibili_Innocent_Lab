package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定脉络悬浮窗折叠/展开的动效规格：端点、错相位 alpha 窗口、单调性与非法输入。 */
class ReplyTopologyCompactMotionSpecTest {

    @Test
    fun `compact height equals header plus status rows`() {
        assertEquals(86, ReplyTopologyCompactMotionSpec.COMPACT_HEIGHT_DP)
        assertEquals(
            ReplyTopologyCompactMotionSpec.HEADER_HEIGHT_DP +
                ReplyTopologyCompactMotionSpec.STATUS_HEIGHT_DP,
            ReplyTopologyCompactMotionSpec.COMPACT_HEIGHT_DP
        )
    }

    @Test
    fun `height interpolation hits exact endpoints`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertEquals(480, spec.heightAt(0f, 480, 86))
        assertEquals(86, spec.heightAt(1f, 480, 86))
        assertEquals(86, spec.heightAt(0f, 86, 480))
        assertEquals(480, spec.heightAt(1f, 86, 480))
    }

    @Test
    fun `height interpolation is monotonic and clamped`() {
        val spec = ReplyTopologyCompactMotionSpec
        var previous = spec.heightAt(-0.5f, 86, 480)
        assertEquals(86, previous)
        val steps = 0..100
        for (step in steps) {
            val value = spec.heightAt(step / 100f, 86, 480)
            assertTrue("height regressed at $step", value >= previous)
            previous = value
        }
        assertEquals(480, previous)
    }

    @Test
    fun `collapse content alpha fades out before trim window`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertEquals(1f, spec.contentAlphaAt(0f, collapsing = true), 1e-6f)
        assertEquals(0f, spec.contentAlphaAt(spec.CONTENT_FADE_OUT_END, collapsing = true), 1e-6f)
        assertEquals(0f, spec.contentAlphaAt(0.5f, collapsing = true), 1e-6f)
        assertEquals(0f, spec.contentAlphaAt(1f, collapsing = true), 1e-6f)
        // 相位窗口内线性递减
        val mid = spec.CONTENT_FADE_OUT_END / 2f
        assertEquals(0.5f, spec.contentAlphaAt(mid, collapsing = true), 1e-6f)
    }

    @Test
    fun `expand content alpha fades in after container settles`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertEquals(0f, spec.contentAlphaAt(0f, collapsing = false), 1e-6f)
        assertEquals(0f, spec.contentAlphaAt(spec.CONTENT_FADE_IN_START, collapsing = false), 1e-6f)
        assertEquals(1f, spec.contentAlphaAt(1f, collapsing = false), 1e-6f)
        // 相位窗口内线性递增
        val mid = spec.CONTENT_FADE_IN_START + (1f - spec.CONTENT_FADE_IN_START) / 2f
        assertEquals(0.5f, spec.contentAlphaAt(mid, collapsing = false), 1e-6f)
    }

    @Test
    fun `content alpha stays within unit range across full progress`() {
        val spec = ReplyTopologyCompactMotionSpec
        for (step in 0..100) {
            val progress = step / 100f
            for (collapsing in booleanArrayOf(true, false)) {
                val alpha = spec.contentAlphaAt(progress, collapsing)
                assertTrue("alpha $alpha out of range", alpha in 0f..1f)
            }
        }
    }

    @Test
    fun `invalid height relations are rejected`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(0, 86) }
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(-10, 86) }
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(480, 0) }
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(86, 86) }
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(80, 86) }
    }

    @Test
    fun `valid height relation returns pair and expanded passthrough`() {
        val spec = ReplyTopologyCompactMotionSpec
        val heights = spec.requireHeights(480, 86)
        assertEquals(480, heights.first)
        assertEquals(86, heights.second)
        assertEquals(480, spec.requireExpandedHeight(480, 86))
    }

    @Test
    fun `durations and phase windows stay coherent`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertTrue(spec.COLLAPSE_DURATION_MS > 0L)
        assertTrue(spec.EXPAND_DURATION_MS > 0L)
        // 收起淡出必须在内容开始被裁剪（高度低于固定行总和）之前完成足够幅度
        assertTrue(spec.CONTENT_FADE_OUT_END in 0.1f..0.5f)
        // 展开渐显必须晚于容器主要位移阶段
        assertTrue(spec.CONTENT_FADE_IN_START in 0.4f..0.9f)
    }
}
