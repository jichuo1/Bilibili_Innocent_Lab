package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判定窗口策略：所有表面同一条线、到期定时器的起点选择、按住漂移容忍。 */
class FreeCopyPressPolicyTest {
    @Test fun thresholdFollowsTheSystemLongPressLineAndFallsBackOnlyWhenUnreadable() {
        assertEquals(400L, FreeCopyPressPolicy.thresholdMs(400L))
        // 用户在无障碍里调整过长按时间：按用户的线，不夹紧。
        assertEquals(500L, FreeCopyPressPolicy.thresholdMs(500L))
        assertEquals(1_500L, FreeCopyPressPolicy.thresholdMs(1_500L))
        assertEquals(FreeCopyGestureDetector.DEFAULT_THRESHOLD_MS, FreeCopyPressPolicy.thresholdMs(0L))
        assertEquals(FreeCopyGestureDetector.DEFAULT_THRESHOLD_MS, FreeCopyPressPolicy.thresholdMs(-5L))
    }

    @Test fun thereIsNoPerSurfaceDelayLeftInThePolicy() {
        // 前两轮的标题 +300ms 与 UP +150ms 余量已移除；策略只暴露三个函数/常量，不含表面维度。
        val names = FreeCopyPressPolicy::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(names.none { it.contains("title", ignoreCase = true) })
        assertTrue(names.none { it.contains("margin", ignoreCase = true) })
        // companion 的 const val 编译成外层类的静态字段。
        val gestureConsts = FreeCopyGestureDetector::class.java.declaredFields.map { it.name }
        assertTrue(gestureConsts.contains("DEFAULT_THRESHOLD_MS"))
        assertTrue(gestureConsts.none { it.contains("TITLE") || it.contains("MARGIN") })
    }

    @Test fun holdSlopIsSlightlyWiderThanTheTapSlopButStillBounded() {
        assertEquals(12f, FreeCopyPressPolicy.holdSlop(8f), 0.0001f)
        assertTrue(FreeCopyPressPolicy.LONG_PRESS_SLOP_FACTOR >= 1f)
        assertTrue(FreeCopyPressPolicy.LONG_PRESS_SLOP_FACTOR <= 2f)
        // 非法坡度放大后仍非法，交给后端失败封闭（begin 会把会话直接置为已取消）。
        assertTrue(FreeCopyPressPolicy.holdSlop(Float.NaN).isNaN())
        val g = FreeCopyGestureDetector({}, 400L)
        g.begin(1_000L, 1_000L, 7, 0f, 0f, FreeCopyPressPolicy.holdSlop(Float.NaN))
        assertFalse(g.isPending)
    }

    @Test fun dueIsAnchoredAtTheDownEventWhileInputLagIsSmall() {
        // DOWN 事件时间 1000，模块 1012 才处理（输入派发延迟 12ms），此刻 now=1012：
        // 到期时刻 = 1000 + 400，还要等 388ms，而不是 400ms。
        assertEquals(388L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_012L, 1_012L, 400L))
        // 没有延迟：正好 400。
        assertEquals(400L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_000L, 1_000L, 400L))
        // 延迟恰好等于上限：仍按事件时间起算。
        val cap = FreeCopyPressPolicy.MAX_INPUT_LAG_CREDIT_MS
        assertEquals(400L - cap, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_000L + cap, 1_000L + cap, 400L))
    }

    @Test fun congestedInputFallsBackToTheObservedClockSoAQueuedUpIsNotRacedByAnEarlyBubble() {
        val cap = FreeCopyPressPolicy.MAX_INPUT_LAG_CREDIT_MS
        // 输入积压 200ms 才被处理：从观测时间起算，到期时刻 = 1200 + 400。
        assertEquals(400L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_200L, 1_200L, 400L))
        // 刚超过上限的一毫秒也退回观测时间。
        assertEquals(400L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_000L + cap + 1L, 1_000L + cap + 1L, 400L))
    }

    @Test fun dueDelayIsNeverNegativeAndIgnoresClockSkew() {
        assertEquals(0L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_000L, 5_000L, 400L))
        // 观测时间早于 DOWN 事件时间（时钟错位）：不给提前量，从观测时间起算。
        assertEquals(400L, FreeCopyPressPolicy.dueDelayMs(1_000L, 990L, 990L, 400L))
    }
}
