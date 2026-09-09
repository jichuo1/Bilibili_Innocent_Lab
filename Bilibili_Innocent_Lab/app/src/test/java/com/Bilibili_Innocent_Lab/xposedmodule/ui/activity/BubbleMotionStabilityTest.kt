package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleMotionStabilityTest {
    @Test fun entryIsShorterWithoutAddingAReboundPhase() {
        assertEquals(245L, BubbleMotionSpec.ENTER_DURATION_MS)
        assertEquals(280L, BubbleMotionSpec.CLOSE_DURATION_MS)
    }

    @Test fun bothProfilesAreMonotonicAndStayWithinTheirFinalSize() {
        for (entry in listOf(true, false)) {
            var previousX = 0f
            var previousY = 0f
            for (step in 0..10_000) {
                val progress = step / 10_000f
                val x = BubbleMotionSpec.scaleX(progress, entry)
                val y = BubbleMotionSpec.scaleY(progress, entry)
                assertTrue("width at $progress", x in 0f..1f && x >= previousX)
                assertTrue("height at $progress", y in 0f..1f && y >= previousY)
                previousX = x
                previousY = y
            }
        }
    }

    @Test fun independentAxesHaveLessThanTwoPercentAspectDistortion() {
        for (entry in listOf(true, false)) {
            var maximumRatio = 1f
            for (step in 1..10_000) {
                val progress = step / 10_000f
                val x = BubbleMotionSpec.scaleX(progress, entry)
                val y = BubbleMotionSpec.scaleY(progress, entry)
                assertTrue(y > 0f)
                val ratio = x / y
                assertTrue("aspect at $progress: $ratio", ratio in 1f..1.02f)
                maximumRatio = maxOf(maximumRatio, ratio)
            }
            assertTrue("axes should remain slightly independent", maximumRatio > 1.005f)
        }
    }

    @Test fun axesReachTheSameExactEndpointWithoutAnEarlyClamp() {
        assertEquals(0f, BubbleMotionSpec.scaleX(0f, true), 0f)
        assertEquals(0f, BubbleMotionSpec.scaleY(0f, true), 0f)
        for (progress in listOf(0.78f, 0.8f, 0.86f, 0.9f, 0.99f)) {
            assertTrue(BubbleMotionSpec.scaleX(progress, true) < 1f)
            assertTrue(BubbleMotionSpec.scaleY(progress, true) < 1f)
        }
        assertEquals(1f, BubbleMotionSpec.scaleX(1f, true), 0f)
        assertEquals(1f, BubbleMotionSpec.scaleY(1f, true), 0f)
    }

    @Test fun reversingFromEveryEntryStageNeverReplaysAReboundPeak() {
        // 保持同一个 entry profile 倒走，尤其覆盖旧曲线 0.8..1 的错峰回弹区间。
        // continuation 允许自然承接已有动量；本测试约束进度开始下降之后的实际宽高。
        for (startStep in 1..100) {
            val start = startStep / 100f
            var previousX = BubbleMotionSpec.scaleX(start, true)
            var previousY = BubbleMotionSpec.scaleY(start, true)
            for (step in 999 downTo 0) {
                val progress = start * (step / 1000f)
                val x = BubbleMotionSpec.scaleX(progress, true)
                val y = BubbleMotionSpec.scaleY(progress, true)
                assertTrue("width grew while closing from $start at $progress", x <= previousX)
                assertTrue("height grew while closing from $start at $progress", y <= previousY)
                previousX = x
                previousY = y
            }
            assertEquals(0f, previousX, 0f)
            assertEquals(0f, previousY, 0f)
        }
    }
}
