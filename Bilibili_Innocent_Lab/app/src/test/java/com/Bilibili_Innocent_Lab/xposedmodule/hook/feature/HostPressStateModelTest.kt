package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说明为什么模块的触摸清理路径**不能**调用 View.cancelLongPress()（标题展开“时灵时不灵”的一条已验证根因）。
 *
 * 这不是对真实 View 的测试，而是 AOSP View.java（API 34，onTouchEvent 的 ACTION_DOWN/ACTION_UP 分支、
 * removeTapCallback、cancelLongPress）里那段按压状态机的最小模型，用来固定推理：
 * - 滚动容器内的控件按下后先置 PFLAG_PREPRESSED，并 postDelayed(CheckForTap, getTapTimeout()=100ms)；
 * - CheckForTap 运行后才变 PFLAG_PRESSED；
 * - UP 时只有 PRESSED 或 PREPRESSED 才会 performClick；
 * - cancelLongPress() = removeLongPressCallback() + removeTapCallback()，后者清掉 PREPRESSED。
 * 于是 100ms 内抬手（快点按）又恰好被模块在 UP 的 before 钩子里 cancelLongPress，点击被静默丢弃；
 * 慢一点（≥100ms，已 PRESSED）的点按不受影响——表现为同一个控件点击时灵时不灵。
 * 模块侧的约束由 DescriptionCopyTouchContractTest 钉住（清理路径不含 cancelLongPress）。
 */
class HostPressStateModelTest {
    private class ViewPressModel(private val tapTimeoutMs: Long = 100L) {
        private var prepressed = false
        private var pressed = false
        private var tapCheckAt = Long.MAX_VALUE
        var clicks = 0

        /** ACTION_DOWN，控件位于滚动容器内（ViewGroup.shouldDelayChildPressedState 默认 true）。 */
        fun down(t: Long) {
            prepressed = true
            pressed = false
            tapCheckAt = t + tapTimeoutMs
        }

        private fun advance(t: Long) {
            if (tapCheckAt <= t) { // CheckForTap 运行
                if (prepressed) { prepressed = false; pressed = true }
                tapCheckAt = Long.MAX_VALUE
            }
        }

        /**
         * cancelLongPress() = removeLongPressCallback() + removeTapCallback()：先让时间走到 [t]
         * （此前到期的 CheckForTap 已经把控件置为 PRESSED），再清掉仍存在的 PREPRESSED 并撤掉 CheckForTap。
         */
        fun cancelLongPress(t: Long) {
            advance(t)
            prepressed = false
            tapCheckAt = Long.MAX_VALUE
        }

        /** ACTION_UP。 */
        fun up(t: Long) {
            advance(t)
            if (pressed || prepressed) clicks += 1
            pressed = false; prepressed = false
        }
    }

    @Test fun aQuickTapClicksWhenNobodyTouchesThePressState() {
        for (held in listOf(30L, 60L, 99L, 100L, 250L)) {
            val v = ViewPressModel(); v.down(0L); v.up(held)
            assertEquals("held=$held", 1, v.clicks)
        }
    }

    @Test fun cancelLongPressBeforeUpSilentlyDropsClicksShorterThanTheTapTimeout() {
        // 模块在 UP 的 before 钩子里清理：与 UP 同一时刻调用 cancelLongPress。
        val quick = ViewPressModel(); quick.down(0L); quick.cancelLongPress(60L); quick.up(60L)
        assertEquals("<100ms 的快点按被 cancelLongPress 吞掉", 0, quick.clicks)
        val edge = ViewPressModel(); edge.down(0L); edge.cancelLongPress(99L); edge.up(99L)
        assertEquals(0, edge.clicks)
    }

    @Test fun slowerTapsSurviveCancelLongPressWhichIsWhyTheFailureLooksRandom() {
        // 同一个控件、同一段代码：按压时长跨过 tapTimeout 就“灵”，没跨过就“不灵”。
        val results = listOf(40L, 80L, 100L, 120L, 200L).map { held ->
            val v = ViewPressModel(); v.down(0L); v.cancelLongPress(held); v.up(held); v.clicks
        }
        assertEquals(listOf(0, 0, 1, 1, 1), results)
    }

    @Test fun theFixedCleanupPathLeavesTheHostPressStateAlone() {
        // 修复后终止事件路径只撤销模块自己的 Runnable：任何时长的点按都点击一次，且 UP 之后不留残余。
        for (held in listOf(20L, 60L, 90L, 110L, 300L)) {
            val v = ViewPressModel(); v.down(0L); v.up(held)
            assertEquals("held=$held", 1, v.clicks)
        }
        val v = ViewPressModel(); v.down(0L); v.up(60L)
        assertFalse("按压结束后没有额外点击", v.clicks > 1)
        assertTrue(v.clicks == 1)
    }
}
