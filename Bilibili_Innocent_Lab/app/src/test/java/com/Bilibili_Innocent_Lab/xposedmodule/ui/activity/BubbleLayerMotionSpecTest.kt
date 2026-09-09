package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleLayerMotionSpecTest {
    @Test fun layerEndpointsAreExact() {
        assertEquals(0f, BubbleLayerMotionSpec.surfaceOpacity(0f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.surfaceOpacity(1f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.sourceIconWeight(0f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.sourceIconWeight(1f), 0f)
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
        assertEquals(1f, BubbleLayerMotionSpec.iconOpacity(0.12f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.iconOpacity(0.30f), 0f)
    }

    @Test fun surfaceArrivesBeforeTextAndContourEndsBeforeIconDisappears() {
        assertEquals(0f, BubbleLayerMotionSpec.surfaceOpacity(0.035f), 0f)
        assertEquals(1f, BubbleLayerMotionSpec.surfaceOpacity(0.15f), 0f)
        assertEquals(0f, BubbleLayerMotionSpec.contentFraction(0.32f, 0, 8), 0f)
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
        val decreasing = listOf<(Float) -> Float>(
            BubbleLayerMotionSpec::sourceIconWeight,
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
        }
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
        for (boundary in listOf(0f, 0.03f, 0.035f, 0.12f, 0.14f, 0.15f, 0.28f,
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
