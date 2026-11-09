package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HostLayerNeutralPlanTest {
    private data class Layer(
        var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0,
        var width: Int = -1, var height: Int = -1, var kind: HostLayerKind = HostLayerKind.OTHER
    )

    /** 可变的 LayerDrawable 替身；`copy()` 对应 newDrawable().mutate()，并计数。 */
    private class FakeLayers(val layers: List<Layer>) : HostLayerSnapshot {
        var copies = 0
        override val count get() = layers.size
        override fun insetLeft(index: Int) = layers[index].left
        override fun insetTop(index: Int) = layers[index].top
        override fun insetRight(index: Int) = layers[index].right
        override fun insetBottom(index: Int) = layers[index].bottom
        override fun width(index: Int) = layers[index].width
        override fun height(index: Int) = layers[index].height
        override fun kind(index: Int) = layers[index].kind
        fun copy(): FakeLayers { copies++; return FakeLayers(layers.map { it.copy() }) }
    }

    /** 与 HostBackgroundPages.applyNeutralPlan 相同的修改。 */
    private fun apply(copy: FakeLayers, plan: HostLayerNeutralPlan) {
        if (plan.isBorderedInput) {
            copy.layers[0].height = plan.borderLine
            copy.layers[1].kind = HostLayerKind.OTHER // 透明
            return
        }
        for (index in plan.cleared) copy.layers[index].kind = HostLayerKind.OTHER
    }

    /** 优化前的逻辑：先复制，再在复制品上判定；只有 changed 才「赋回」。返回 (是否赋回, 赋回的结构)。 */
    private fun legacy(original: FakeLayers, viewWidth: Int, viewHeight: Int, maxLine: Int): Pair<Boolean, FakeLayers> {
        val copy = original.copy()
        var changed = false
        val line = if (copy.count == 2) copy.insetTop(1) else 0
        val bordered = line in 1..maxLine && (0 until 2).all { i ->
            copy.height(i) <= 0 && copy.insetLeft(i) == 0 && copy.insetRight(i) == 0 && copy.insetBottom(i) == 0 &&
                copy.kind(i) == HostLayerKind.NEUTRAL_PLAIN_GRADIENT
        } && copy.insetTop(0) == 0
        if (bordered) {
            copy.layers[0].height = line
            copy.layers[1].kind = HostLayerKind.OTHER
            changed = true
        }
        for (i in 0 until copy.count) {
            if (bordered) break
            val h = copy.height(i)
            val w = copy.width(i)
            if (h > 0 && h < viewHeight * .9f || w > 0 && w < viewWidth * .9f) continue
            if (copy.kind(i) != HostLayerKind.OTHER) { copy.layers[i].kind = HostLayerKind.OTHER; changed = true }
        }
        return changed to copy
    }

    private fun gradient(top: Int = 0, height: Int = -1) =
        Layer(top = top, height = height, kind = HostLayerKind.NEUTRAL_PLAIN_GRADIENT)

    @Test fun transparentOrUnrelatedLayersProduceNoPlanAndNoCopy() {
        val layers = FakeLayers(listOf(Layer(), Layer(kind = HostLayerKind.OTHER)))
        assertNull(HostLayerNeutralPlan.of(layers, 1080, 200, 12))
        assertEquals("扫描原件不复制", 0, layers.copies)
        assertNull(HostLayerNeutralPlan.of(FakeLayers(emptyList()), 1080, 200, 12))
    }

    @Test fun fullWidthNeutralLayerNeedsOneCopyAndOnlyThatLayerChanges() {
        val layers = FakeLayers(listOf(
            Layer(kind = HostLayerKind.NEUTRAL_COLOR),
            Layer(kind = HostLayerKind.OTHER),
            Layer(kind = HostLayerKind.NEUTRAL_PLAIN_GRADIENT)
        ))
        val plan = HostLayerNeutralPlan.of(layers, 1080, 200, 12)!!
        assertFalse(plan.isBorderedInput)
        assertArrayEquals(intArrayOf(0, 2), plan.cleared)
    }

    @Test fun partialSizeLayersAreKeptEvenWhenNeutral() {
        val layers = FakeLayers(listOf(
            Layer(height = 20, kind = HostLayerKind.NEUTRAL_COLOR),      // 分隔线
            Layer(width = 300, kind = HostLayerKind.NEUTRAL_COLOR),      // 窄于 90% 宽
            Layer(height = 190, kind = HostLayerKind.NEUTRAL_COLOR)      // 不低于 90% 高，仍是整块底色
        ))
        val plan = HostLayerNeutralPlan.of(layers, 1080, 200, 12)!!
        assertArrayEquals(intArrayOf(2), plan.cleared)
    }

    @Test fun borderedInputKeepsTheLineAndRestrictsTheBaseLayer() {
        val layers = FakeLayers(listOf(gradient(), gradient(top = 3)))
        val plan = HostLayerNeutralPlan.of(layers, 1080, 200, 12)!!
        assertTrue(plan.isBorderedInput)
        assertEquals(3, plan.borderLine)
        assertEquals(0, plan.cleared.size)
    }

    @Test fun lineTallerThanTheLimitFallsBackToClearingFullSizeLayers() {
        val layers = FakeLayers(listOf(gradient(), gradient(top = 13)))
        val plan = HostLayerNeutralPlan.of(layers, 1080, 200, 12)!!
        assertFalse(plan.isBorderedInput)
        assertArrayEquals(intArrayOf(0, 1), plan.cleared)
    }

    @Test fun alreadyProcessedBorderedInputIsIdempotent() {
        val original = FakeLayers(listOf(gradient(), gradient(top = 3)))
        val first = HostLayerNeutralPlan.of(original, 1080, 200, 12)!!
        val processed = original.copy().also { apply(it, first) }
        // 处理后的底层高 3px（<90% 视图高），顶层已透明：不再需要复制。
        assertNull(HostLayerNeutralPlan.of(processed, 1080, 200, 12))
    }

    @Test fun planMatchesTheCopyFirstLogicOnRandomLayerSets() {
        val random = Random(20261029L)
        val kinds = HostLayerKind.values()
        var sawPlan = 0
        var sawNoPlan = 0
        repeat(20_000) {
            val count = random.nextInt(4)
            val list = List(count) {
                Layer(
                    left = if (random.nextInt(4) == 0) 1 else 0,
                    top = if (random.nextInt(3) == 0) random.nextInt(16) else 0,
                    right = if (random.nextInt(5) == 0) 2 else 0,
                    bottom = if (random.nextInt(5) == 0) 2 else 0,
                    width = if (random.nextInt(4) == 0) random.nextInt(1200) else -1,
                    height = if (random.nextInt(3) == 0) random.nextInt(260) - 5 else -1,
                    kind = kinds[random.nextInt(kinds.size)]
                )
            }
            val original = FakeLayers(list)
            val viewWidth = 400 + random.nextInt(800)
            val viewHeight = 40 + random.nextInt(300)
            val before = original.layers.map { it.copy() }
            val plan = HostLayerNeutralPlan.of(original, viewWidth, viewHeight, 12)
            assertEquals("规划不得修改原件", before, original.layers)
            val (changed, expected) = legacy(original, viewWidth, viewHeight, 12)
            assertEquals("changed 与是否有计划一致: $list", changed, plan != null)
            if (plan == null) {
                sawNoPlan++
            } else {
                sawPlan++
                val actual = original.copy().also { apply(it, plan) }
                assertEquals("复制品最终结构一致: $list", expected.layers, actual.layers)
            }
        }
        // 随机集合应同时覆盖两种结果，避免恒真比较。
        assertTrue("有计划的样本: $sawPlan", sawPlan > 500)
        assertTrue("无计划的样本: $sawNoPlan", sawNoPlan > 500)
    }
}
