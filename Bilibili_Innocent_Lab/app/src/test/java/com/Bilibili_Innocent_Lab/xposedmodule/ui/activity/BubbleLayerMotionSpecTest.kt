package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleLayerMotionSpecTest {
    @Test fun layerEndpointsAreExact() {
        assertEquals(0f, BubbleLayerMotionSpec.surfaceOpacity(0f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.surfaceOpacity(1f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.sourceIconWeight(0f), 0f)
        // 展开端必须把真实图标完整还给工具栏：图标层此时已经淡尽，只剩它撑着那个位置。
        assertEquals(1f, BubbleLayerMotionSpec.sourceIconWeight(1f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.iconOpacity(0f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.iconOpacity(1f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.contourMix(0f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.contourMix(1f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.iconTravelFraction(0f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.iconTravelFraction(1f), 0f)
    }

    @Test fun rowsAlwaysRevealFromTopToBottom() {
        for (count in listOf(1, 2, 3, 4, 8, 32)) {
            for (step in 0..1000) {
                val progress = step / 1000f
                var previousRow = 1f
                for (index in 0 until count) {
                    val row = BubbleLayerMotionSpec.contentFraction(progress, index, count)
                    assertTrue("row $index of $count at $progress", row in 0f..previousRow)
                    previousRow = row
                }
            }
        }
        assertTrue(BubbleLayerMotionSpec.contentFraction(0.5f, 0, 8) > 0f)
        assertEquals(0f, BubbleLayerMotionSpec.contentFraction(0.5f, 7, 8), 0f)
    }

    /**
     * 链式加入：相邻行的间隔恒定、重叠恒为 50%，末行结束正好落在 `CONTENT_END`。
     *
     * 回归：原来是"固定总跨度 0.28 摊到行数上 + 每行固定淡入 0.30"，GitHub 面板 8 行时
     * 间隔 0.04、重叠 87%，八行几乎同时浮现，读起来是一整块淡入而不是一行接一行。
     */
    @Test fun rowsJoinAsAChainWithAConstantGapWhateverTheRowCount() {
        val start = BubbleLayerMotionSpec.CONTENT_START
        val end = BubbleLayerMotionSpec.CONTENT_END
        for (count in listOf(1, 2, 3, 5, 8, 32)) {
            // 每行的起点 = 首次离开 0 的进度；用细扫描反推，不依赖内部公式。
            val starts = (0 until count).map { index ->
                (0..100_000).first { step ->
                    BubbleLayerMotionSpec.contentFraction(step / 100_000f, index, count) > 0f
                } / 100_000f
            }
            assertEquals("row 0 must start at CONTENT_START", start, starts.first(), 0.0005f)
            if (count > 2) {
                val gaps = starts.zipWithNext { a, b -> b - a }
                val first = gaps.first()
                gaps.forEach {
                    assertEquals("gap must be constant for $count rows", first, it, 0.0015f)
                }
                assertTrue("gap must be positive for $count rows", first > 0f)
            }
            // 末行必须正好用完预算：再早就浪费，再晚就会在 settle 处跳一下。
            val lastStart = starts.last()
            val window = (end - start) / (count.toFloat() + BubbleLayerMotionSpec.CONTENT_WINDOW_STEPS - 1f) *
                BubbleLayerMotionSpec.CONTENT_WINDOW_STEPS
            assertEquals("last row must land on CONTENT_END", end, lastStart + window, 0.002f)
        }
        // 8 行（GitHub 面板）时重叠必须落在"链式"区间，而不是旧的 87%。
        val step8 = (end - start) / (8f + BubbleLayerMotionSpec.CONTENT_WINDOW_STEPS - 1f)
        val overlap = (step8 * BubbleLayerMotionSpec.CONTENT_WINDOW_STEPS - step8) /
            (step8 * BubbleLayerMotionSpec.CONTENT_WINDOW_STEPS)
        assertEquals(0.5f, overlap, 0.001f)
    }

    @Test fun everyRowRestoresExactlyByNinetyPercent() {
        for (count in listOf(1, 2, 4, 8, Int.MAX_VALUE)) {
            for (index in listOf(0, count / 2, count - 1, Int.MAX_VALUE)) {
                assertEquals(0f, BubbleLayerMotionSpec.contentFraction(0f, index, count), 0f)
                for (progress in listOf(0.90f, 0.95f, 1f, 2f)) {
                    assertEquals(1f, BubbleLayerMotionSpec.contentFraction(progress, index, count), 0f)
                }
            }
        }
    }

    @Test fun reversingUsesTheSameFrameAndRemovesLowerRowsFirst() {
        for (start in listOf(0.1f, 0.4f, 0.65f, 0.85f, 1f)) {
            for (index in 0 until 8) {
                var previous = BubbleLayerMotionSpec.contentFraction(start, index, 8)
                for (step in 1000 downTo 0) {
                    val progress = start * (step / 1000f)
                    val current = BubbleLayerMotionSpec.contentFraction(progress, index, 8)
                    assertTrue("row grew on return from $start at $progress", current <= previous)
                    previous = current
                }
                assertEquals(0f, previous, 0f)
            }
        }
        // 相同进度无方向状态：关闭途中反向展开也不会切换到另一条内容曲线。
        val forward = (0..1000).map { BubbleLayerMotionSpec.contentFraction(it / 1000f, 3, 8) }
        val reverse = (1000 downTo 0).map { BubbleLayerMotionSpec.contentFraction(it / 1000f, 3, 8) }
        assertEquals(forward, reverse.reversed())
    }

    @Test fun sourceAndMovingIconExchangeBeforeTravelBegins() {
        for (step in 0..1000) {
            val progress = 0.035f * (step / 1000f)
            assertEquals(1f, BubbleLayerMotionSpec.sourceIconWeight(progress) +
                BubbleLayerMotionSpec.iconOpacity(progress), 0f)
            assertEquals(0f, BubbleLayerMotionSpec.iconTravelFraction(progress), 0f)
        }
        assertEquals(0f, BubbleLayerMotionSpec.sourceIconWeight(0.035f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.iconOpacity(0.035f), 0f)
        // 融合窗口 0.05 → 0.26：交接一结束就开始，真实图标同步接回。
        assertEquals(1f, BubbleLayerMotionSpec.iconOpacity(0.05f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.iconOpacity(0.26f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.iconOpacity(0.30f), 0f)
    }

    @Test fun surfaceArrivesBeforeTextAndContourEndsBeforeIconDisappears() {
        assertEquals(0f, BubbleLayerMotionSpec.surfaceOpacity(0.035f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.surfaceOpacity(0.15f), 0f)
        // 表面完全不透明（0.15）之后正文才起步，起点精确落在 CONTENT_START。
        assertEquals(0f, BubbleLayerMotionSpec.contentFraction(0.15f, 0, 8), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.contentFraction(
            BubbleLayerMotionSpec.CONTENT_START, 0, 8), 0f)
        assertTrue(BubbleLayerMotionSpec.contentFraction(
            BubbleLayerMotionSpec.CONTENT_START + 0.01f, 0, 8) > 0f)
        assertEquals(0f, BubbleLayerMotionSpec.contourMix(0.14f), 0f)
        assertTrue(BubbleLayerMotionSpec.iconOpacity(0.14f) > 0f)
        assertEquals(1f, BubbleLayerMotionSpec.iconTravelFraction(0.28f), 0f)
    }

    @Test fun scalarTransitionsAreMonotonicAndBounded() {
        val increasing = listOf<(Float) -> Float>(
            BubbleLayerMotionSpec::surfaceOpacity,
            BubbleLayerMotionSpec::iconTravelFraction,
            { BubbleLayerMotionSpec.contentFraction(it, 7, 8) }
        )
        // sourceIconWeight 故意不在这里：它是先交出、再接回的凹形曲线，见
        // sourceSlotKeepsExactlyOneIconAtEveryProgress。
        val decreasing = listOf<(Float) -> Float>(
            BubbleLayerMotionSpec::contourMix
        )
        for (function in increasing) {
            var previous = 0f
            for (step in 0..10_000) {
                val value = function(step / 10_000f)
                assertTrue(value in previous..1f)
                previous = value
            }
        }
        for (function in decreasing) {
            var previous = 1f
            for (step in 0..10_000) {
                val value = function(step / 10_000f)
                assertTrue(value in 0f..previous)
                previous = value
            }
        }
        for (step in 0..10_000) {
            assertTrue(BubbleLayerMotionSpec.iconOpacity(step / 10_000f) in 0f..1f)
            assertTrue(BubbleLayerMotionSpec.sourceIconWeight(step / 10_000f) in 0f..1f)
        }
    }

    /**
     * 来源位置的图案总量守恒。
     *
     * 回归：图标层原来在 30% 处就淡尽，真实图标却要等 [BubblePanelLayer.settleExpanded] 才回来，
     * 于是工具栏上那个位置空掉约 70% 的展开时长（气泡挂在图标下方，并不遮住它），
     * 现场表现为"展开时图标突然消失一下"。
     */
    @Test fun sourceSlotKeepsExactlyOneIconAtEveryProgress() {
        for (step in 0..10_000) {
            val progress = step / 10_000f
            assertEquals(
                "source slot lost ink at $progress",
                1f,
                BubbleLayerMotionSpec.sourceIconWeight(progress) +
                    BubbleLayerMotionSpec.iconOpacity(progress),
                0f
            )
        }
        // 交接：图标层还压在原位时才允许真实图标让位。
        assertEquals(0f, BubbleLayerMotionSpec.sourceIconWeight(0.035f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.iconTravelFraction(0.035f), 0f)
        // 接回：幽灵淡尽的那一刻真实图标必须已经完整在位，之后不再变化。
        for (progress in listOf(0.30f, 0.5f, 0.9f, 1f, 2f)) {
            assertEquals(1f, BubbleLayerMotionSpec.sourceIconWeight(progress), 0f)
        }
        // 中段是一次真正的凹陷，而不是被压平成"全程可见"（那会看到两个图标）。
        assertEquals(0f, BubbleLayerMotionSpec.sourceIconWeight(0.05f), 0f)
        assertTrue(BubbleLayerMotionSpec.sourceIconWeight(0.20f) > 0.2f)
        // 真实图标为 0 的那一段必须极短，且期间幽灵还压在原位（行程 < 2%）——
        // 这两条一起才等于"槽位任何时刻都有图案"。
        var zeroWidth = 0
        for (step in 0..10_000) {
            val progress = step / 10_000f
            if (BubbleLayerMotionSpec.sourceIconWeight(progress) > 0f) continue
            zeroWidth++
            assertTrue(
                "ghost already left the slot at $progress",
                BubbleLayerMotionSpec.iconTravelFraction(progress) < 0.02f
            )
        }
        assertTrue("zero-weight window too wide: $zeroWidth/10000", zeroWidth < 200)
    }

    @Test fun everyPhaseBoundaryIsContinuous() {
        val functions = listOf<(Float) -> Float>(
            BubbleLayerMotionSpec::surfaceOpacity,
            BubbleLayerMotionSpec::sourceIconWeight,
            BubbleLayerMotionSpec::iconOpacity,
            BubbleLayerMotionSpec::contourMix,
            BubbleLayerMotionSpec::iconTravelFraction,
            { BubbleLayerMotionSpec.contentFraction(it, 0, 8) },
            { BubbleLayerMotionSpec.contentFraction(it, 7, 8) }
        )
        for (boundary in listOf(0f, 0.03f, 0.035f, 0.05f, 0.12f, 0.14f, 0.15f, 0.26f, 0.28f,
            0.30f, 0.32f, 0.60f, 0.62f, 0.90f, 1f)) {
            for (function in functions) {
                val before = function(boundary - 0.00001f)
                val after = function(boundary + 0.00001f)
                assertTrue("jump at $boundary", kotlin.math.abs(after - before) < 0.001f)
            }
        }
    }

    @Test fun invalidCountsAndIndicesNeverEscapeTheirFirstOrLastRow() {
        for (progress in listOf(0f, 0.3f, 0.5f, 0.8f, 1f)) {
            for (count in listOf(Int.MIN_VALUE, -5, 0, 1)) {
                for (index in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
                    assertEquals(BubbleLayerMotionSpec.contentFraction(progress, 0, 1),
                        BubbleLayerMotionSpec.contentFraction(progress, index, count), 0f)
                }
            }
            assertEquals(BubbleLayerMotionSpec.contentFraction(progress, 0, 8),
                BubbleLayerMotionSpec.contentFraction(progress, Int.MIN_VALUE, 8), 0f)
            assertEquals(BubbleLayerMotionSpec.contentFraction(progress, 7, 8),
                BubbleLayerMotionSpec.contentFraction(progress, Int.MAX_VALUE, 8), 0f)
        }
    }

    @Test fun invalidProgressIsClampedAndNanFallsBackToCollapsed() {
        val functions = listOf<(Float) -> Float>(
            BubbleLayerMotionSpec::surfaceOpacity,
            BubbleLayerMotionSpec::sourceIconWeight,
            BubbleLayerMotionSpec::iconOpacity,
            BubbleLayerMotionSpec::contourMix,
            BubbleLayerMotionSpec::iconTravelFraction,
            { BubbleLayerMotionSpec.contentFraction(it, 3, 8) }
        )
        for (function in functions) {
            for (input in listOf(Float.NEGATIVE_INFINITY, -100f, Float.NaN)) {
                assertEquals(function(0f), function(input), 0f)
            }
            for (input in listOf(Float.POSITIVE_INFINITY, 100f)) {
                assertEquals(function(1f), function(input), 0f)
            }
        }
    }
}
