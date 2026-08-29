package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定脉络悬浮窗折叠/展开的动效规格：端点、透明度行同相收缩、单调性与非法输入。 */
class ReplyTopologyCompactMotionSpecTest {

    @Test
    fun `compact height equals header plus status rows`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertEquals(86, spec.COMPACT_HEIGHT_DP)
        assertEquals(
            spec.HEADER_HEIGHT_DP + spec.STATUS_HEIGHT_DP,
            spec.COMPACT_HEIGHT_DP
        )
        // 展开态固定行总高 = 标题 + 透明度行 + 状态行
        assertEquals(
            spec.HEADER_HEIGHT_DP + spec.OPACITY_ROW_HEIGHT_DP + spec.STATUS_HEIGHT_DP,
            120
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
        for (step in 0..100) {
            val value = spec.heightAt(step / 100f, 86, 480)
            assertTrue("height regressed at $step", value >= previous)
            previous = value
        }
        assertEquals(480, previous)
    }

    @Test
    fun `opacity row height shrinks with panel and keeps status row continuous`() {
        val spec = ReplyTopologyCompactMotionSpec
        // 折叠：透明度行 34 -> 1（插值下限 1px，终态残余由 GONE 消除）；展开：1 -> 34
        assertEquals(34, spec.heightAt(0f, spec.OPACITY_ROW_HEIGHT_DP, 0))
        assertEquals(1, spec.heightAt(1f, spec.OPACITY_ROW_HEIGHT_DP, 0))
        assertEquals(1, spec.heightAt(0f, 0, spec.OPACITY_ROW_HEIGHT_DP))
        assertEquals(spec.OPACITY_ROW_HEIGHT_DP, spec.heightAt(1f, 0, spec.OPACITY_ROW_HEIGHT_DP))
        // 同一进度下，状态行 y = header + opacityRow 高度：折叠端回到标题正下方（1px 残余），
        // 展开端为标题 + 满高，全程无跳变
        val headerPx = 100
        val yCollapsed = headerPx + spec.heightAt(1f, spec.OPACITY_ROW_HEIGHT_DP, 0)
        val yExpanded = headerPx + spec.heightAt(0f, spec.OPACITY_ROW_HEIGHT_DP, 0)
        assertEquals(headerPx + 1, yCollapsed)
        assertEquals(headerPx + spec.OPACITY_ROW_HEIGHT_DP, yExpanded)
    }

    @Test
    fun `opacity row alpha is in phase with its height`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertEquals(1f, spec.opacityRowAlphaAt(0f, collapsing = true), 1e-6f)
        assertEquals(0f, spec.opacityRowAlphaAt(1f, collapsing = true), 1e-6f)
        assertEquals(0.5f, spec.opacityRowAlphaAt(0.5f, collapsing = true), 1e-6f)
        assertEquals(0f, spec.opacityRowAlphaAt(0f, collapsing = false), 1e-6f)
        assertEquals(1f, spec.opacityRowAlphaAt(1f, collapsing = false), 1e-6f)
        assertEquals(0.5f, spec.opacityRowAlphaAt(0.5f, collapsing = false), 1e-6f)
    }

    @Test
    fun `opacity row alpha and height stay monotonic across full progress`() {
        val spec = ReplyTopologyCompactMotionSpec
        for (collapsing in booleanArrayOf(true, false)) {
            var prevAlpha = spec.opacityRowAlphaAt(0f, collapsing)
            var prevHeight = spec.heightAt(0f, if (collapsing) 34 else 0, if (collapsing) 0 else 34)
            for (step in 0..100) {
                val progress = step / 100f
                val alpha = spec.opacityRowAlphaAt(progress, collapsing)
                val height = spec.heightAt(progress, if (collapsing) 34 else 0, if (collapsing) 0 else 34)
                assertTrue("alpha out of range", alpha in 0f..1f)
                if (collapsing) {
                    assertTrue("alpha regressed at $step", alpha <= prevAlpha)
                    assertTrue("height regressed at $step", height <= prevHeight)
                } else {
                    assertTrue("alpha regressed at $step", alpha >= prevAlpha)
                    assertTrue("height regressed at $step", height >= prevHeight)
                }
                prevAlpha = alpha
                prevHeight = height
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
    fun `durations stay positive`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertTrue(spec.COLLAPSE_DURATION_MS > 0L)
        assertTrue(spec.EXPAND_DURATION_MS > 0L)
    }
}
