package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeCopyPressFlowTest {
    private class Rig(private val systemLongPressMs: Long = 400L, private val touchSlop: Float = 8f) {
        private val queue = ArrayList<Pair<Runnable, Long>>() // 到期的绝对时刻（虚拟 uptime）
        private val poster = object : FreeCopyGestureDetector.Poster {
            override fun remove(runnable: Runnable) { queue.removeAll { it.first === runnable } }
            override fun post(runnable: Runnable, delayMillis: Long): Boolean {
                queue += runnable to (now + delayMillis); return true
            }
        }

        var now = 0L
        var bubbleShownAt = -1L
        var bubbleCount = 0
        var handledFlag = false
        var upConsumed = false
        var hostClicked = false
        var cancelled = false
        private var upDelivered = false
        private var candidate = false // 对应 descTouchedView != null

        val bubbleShowing get() = bubbleShownAt >= 0L

        /** 到期 Runnable 的真实逻辑：与 HookEntry.freeCopyLongPressRunnable 一致。 */
        private val fire: Runnable = Runnable {
            detector.onFired()
            if (detector.sessionId == 0L || !detector.isPending) return@Runnable
            if (!candidate) return@Runnable
            if (detector.claim(detector.downTime, now)) showBubble()
        }

        val detector = FreeCopyGestureDetector(fire, FreeCopyGestureDetector.DEFAULT_THRESHOLD_MS)

        private fun showBubble() {
            bubbleCount += 1
            bubbleShownAt = now
            handledFlag = true
            candidate = false // showFreeCopyPopup 内 clearDescTouchSession(resetHandled = false)
        }

        /** [lagMs] = 模块观测到 DOWN 比事件时间晚多少（输入派发延迟/主线程拥塞）。 */
        fun down(downTime: Long, x: Float = 100f, y: Float = 200f, lagMs: Long = 0L) {
            now = downTime + lagMs
            queue.clear()
            handledFlag = false; bubbleShownAt = -1L; upConsumed = false; hostClicked = false
            cancelled = false; upDelivered = false; candidate = true
            detector.begin(downTime, now, 7, x, y, FreeCopyPressPolicy.holdSlop(touchSlop),
                FreeCopyPressPolicy.thresholdMs(systemLongPressMs))
            val delay = FreeCopyPressPolicy.dueDelayMs(detector.downTime, detector.observedAtMs, now, detector.thresholdMs)
            detector.schedule(detector.sessionId, delay, poster)
        }

        /** 让虚拟时间走到 [t]，期间到期的 Runnable 依次执行。 */
        fun advanceTo(t: Long) {
            while (true) {
                val next = queue.filter { it.second <= t }.minByOrNull { it.second } ?: break
                queue.remove(next)
                now = maxOf(now, next.second)
                next.first.run()
            }
            now = maxOf(now, t)
        }

        fun move(t: Long, x: Float, y: Float, pointerCount: Int = 1) {
            advanceTo(t)
            if (!detector.matches(detector.downTime) || !detector.isPending) return
            if (detector.sample(detector.downTime, 7, pointerCount, x, y)) {
                detector.cancelSchedule(poster); candidate = false; cancelled = true
            }
        }

        /** 父容器拦截 / 系统取消（ACTION_CANCEL）。 */
        fun cancel(t: Long) {
            advanceTo(t)
            if (detector.cancel(detector.downTime)) {
                detector.cancelSchedule(poster); candidate = false
            }
            cancelled = true
            finish(eventTime = t, isUp = false)
        }

        /**
         * UP 到达。[congested]=true 表示主线程拥塞：UP（事件时间 [t]）先于到期 Runnable 被处理，
         * 随后虚拟时间才走到 [processedAt]，迟到的 Runnable 再尝试执行。
         */
        fun up(t: Long, congested: Boolean = false, processedAt: Long = t) {
            if (congested) now = maxOf(now, processedAt) else advanceTo(t)
            upDelivered = true
            upConsumed = finish(eventTime = t, isUp = true)
            hostClicked = upDelivered && !upConsumed && !cancelled && !bubbleShowing
            if (congested) advanceTo(processedAt)
        }

        /** 到期 Runnable 抢在 UP 之前执行（墙钟已超线，但手指真实只按了很短）。 */
        fun runnableBeatsUp(upEventTime: Long, runnableAt: Long) {
            advanceTo(runnableAt)
            up(upEventTime, congested = true, processedAt = runnableAt)
        }

        /** 与 HookEntry.finishDescLikeTouch 一致。返回 true = 消费 UP。 */
        private fun finish(eventTime: Long, isUp: Boolean): Boolean {
            detector.cancelSchedule(poster)
            if (isUp) revokePrematurePopupIfShortTap(eventTime)
            var handled = handledFlag
            if (!handled && !detector.matches(detector.downTime)) { clear(true); return false }
            if (detector.resolveTerminal(detector.downTime, eventTime, isUp, handled) ==
                FreeCopyGestureDetector.Terminal.CLAIM_AND_CONSUME && candidate
            ) {
                showBubble(); handled = true
            }
            clear(!handled)
            return handled && isUp
        }

        private fun revokePrematurePopupIfShortTap(eventTime: Long) {
            if (eventTime - detector.downTime >= detector.thresholdMs) return
            if (!bubbleShowing) return
            bubbleShownAt = -1L
            handledFlag = false
            detector.finishHandled()
        }

        private fun clear(resetHandled: Boolean) {
            detector.cancelSchedule(poster)
            candidate = false
            if (resetHandled) { handledFlag = false; detector.reset() }
        }
    }

    // ===== 短按不误触发 / 标题展开不被吞 =====
    fun quickTapsOfAnyDurationBelowTheLineNeverShowABubbleAndAlwaysReachTheHost() {
        for (held in listOf(25L, 60L, 99L, 100L, 180L, 300L, 399L)) {
            val r = Rig()
            r.down(1_000L)
            r.up(1_000L + held)
            assertEquals("held=$held", 0, r.bubbleCount)
            assertFalse("held=$held 不得消费 UP", r.upConsumed)
            assertTrue("held=$held 宿主点击（标题展开）必须发生", r.hostClicked)
        }
    }

    fun titleTapJustBelowTheSystemLineExpandsAndOneMillisecondLaterIsALongPress() {
        val tap = Rig(); tap.down(1_000L); tap.up(1_399L)
        assertTrue(tap.hostClicked); assertEquals(0, tap.bubbleCount)
        val hold = Rig(); hold.down(1_000L); hold.up(1_401L)
        assertFalse("长按之后宿主不得再展开标题", hold.hostClicked)
        assertEquals(1, hold.bubbleCount)
        assertTrue("长按的 UP 必须被消费", hold.upConsumed)
    }

    fun tapAfterALongPressSessionStartsCleanAndStillExpands() {
        val r = Rig()
        r.down(1_000L); r.up(1_600L)
        assertEquals(1, r.bubbleCount)
        r.detector.finishHandled() // 气泡关闭
        r.down(5_000L); r.up(5_060L)
        assertTrue("上一次长按不影响下一次点击", r.hostClicked)
        assertFalse(r.upConsumed)
        assertEquals("没有新增气泡", 1, r.bubbleCount)
    }

    // ===== 长按触发延迟 =====
    fun bubbleAppearsExactlyAtTheSystemLineWithNoExtraDelayForAnySurface() {
        val r = Rig()
        r.down(1_000L)
        r.advanceTo(1_399L); assertFalse(r.bubbleShowing)
        r.advanceTo(1_400L)
        assertTrue("按住满 400ms 即弹泡（不是 700ms）", r.bubbleShowing)
        assertEquals(1_400L, r.bubbleShownAt)
    }

    fun smallInputLagDoesNotDelayTheBubbleButCongestionDoesNotRaceAheadOfIt() {
        val fast = Rig(); fast.down(1_000L, lagMs = 12L) // 模块 1012 才看到 DOWN
        fast.advanceTo(1_399L); assertFalse(fast.bubbleShowing)
        fast.advanceTo(1_400L); assertTrue("手指落下后 400ms，不是处理后 400ms", fast.bubbleShowing)
        val congested = Rig(); congested.down(1_000L, lagMs = 300L) // 主线程积压 300ms
        congested.advanceTo(1_699L); assertFalse(congested.bubbleShowing)
        congested.advanceTo(1_700L); assertTrue(congested.bubbleShowing)
    }

    fun userAdjustedSystemLongPressTimeoutIsHonouredByEverySurface() {
        val r = Rig(systemLongPressMs = 500L)
        r.down(1_000L)
        r.advanceTo(1_499L); assertFalse(r.bubbleShowing)
        r.advanceTo(1_500L); assertTrue(r.bubbleShowing)
        val tap = Rig(systemLongPressMs = 500L); tap.down(1_000L); tap.up(1_450L)
        assertTrue("500ms 线下 450ms 仍是点击", tap.hostClicked)
    }

    fun heldLongPressAfterTheBubbleConsumesUpAndBlocksTheHostClick() {
        val r = Rig()
        r.down(1_000L)
        r.advanceTo(1_400L)
        r.up(2_000L)
        assertTrue(r.upConsumed)
        assertFalse(r.hostClicked)
        assertEquals("同一次按住只弹一次", 1, r.bubbleCount)
    }

    // ===== 移动超坡度取消 =====
    fun driftInsideTheHoldSlopKeepsTheLongPressAlive() {
        val r = Rig(touchSlop = 8f) // 容忍半径 12px
        r.down(1_000L)
        r.move(1_100L, 100f, 209f)  // 9px：旧的 8px 坡度会取消，现在仍有效
        r.move(1_200L, 100f, 211f)  // 11px
        r.advanceTo(1_400L)
        assertTrue(r.bubbleShowing)
    }

    fun movingBeyondTheHoldSlopCancelsEverySourceAndHandsTheTapBackToTheHost() {
        val r = Rig(touchSlop = 8f)
        r.down(1_000L)
        r.move(1_100L, 100f, 213f)  // 13px > 12px
        assertTrue(r.cancelled)
        r.advanceTo(1_400L)
        assertFalse("取消后到期 Runnable 不得弹泡", r.bubbleShowing)
        r.up(1_900L) // 即使按住很久：已让出给滑动，UP 不认领、不消费
        assertFalse(r.upConsumed)
        assertEquals(0, r.bubbleCount)
    }

    fun returningToTheDownPointDoesNotReviveACancelledGesture() {
        val r = Rig()
        r.down(1_000L)
        r.move(1_100L, 100f, 230f)
        r.move(1_200L, 100f, 200f)
        r.advanceTo(1_500L)
        assertEquals(0, r.bubbleCount)
        r.up(1_600L)
        assertEquals(0, r.bubbleCount)
        assertFalse(r.upConsumed)
    }

    fun secondFingerCancelsTheCandidate() {
        val r = Rig()
        r.down(1_000L)
        r.move(1_150L, 100f, 200f, pointerCount = 2)
        r.advanceTo(1_400L)
        assertEquals(0, r.bubbleCount)
    }

    // ===== ACTION_CANCEL / 父容器拦截 =====
    fun parentInterceptionBeforeTheLineNeverShowsABubbleAndConsumesNothing() {
        val r = Rig()
        r.down(1_000L)
        r.cancel(1_200L)            // RecyclerView 接管滚动
        r.advanceTo(1_400L)
        assertEquals(0, r.bubbleCount)
        assertFalse(r.upConsumed)
        assertFalse("被取消的手势不是点击", r.hostClicked)
    }

    fun cancelAfterTheBubbleIsShownKeepsTheBubbleAndNeverConsumesTheCancel() {
        val r = Rig()
        r.down(1_000L); r.advanceTo(1_400L)
        r.cancel(1_800L)
        assertEquals(1, r.bubbleCount)
        assertFalse("CANCEL 本身不消费，交还宿主做自己的收尾", r.upConsumed)
    }

    // ===== 拥塞 / 交还宿主的条件 =====
    fun congestedShortTapIsHandedToTheHostWhenTheUpIsProcessedBeforeTheLateRunnable() {
        val r = Rig()
        r.down(1_000L, lagMs = 200L)               // DOWN 积压 200ms 才被模块看到（观测时间 1200）
        r.up(1_090L, congested = true, processedAt = 1_700L) // UP 事件时间只按了 90ms
        assertEquals("迟到的 Runnable 不得把短按误判成长按", 0, r.bubbleCount)
        assertTrue(r.hostClicked)
        assertFalse(r.upConsumed)
    }

    fun safetyNetRevokesABubbleThatTheRunnableShowedBeforeAShortUpWasProcessed() {
        val r = Rig()
        r.down(1_000L, lagMs = 0L)
        r.runnableBeatsUp(upEventTime = 1_120L, runnableAt = 1_400L)
        assertTrue("事件时钟：真实按住 120ms，是点击", r.hostClicked)
        assertFalse(r.bubbleShowing)
        assertFalse(r.upConsumed)
    }

    fun genuineHoldWhoseRunnableWasLateIsStillClaimedAtUpByTheEventClock() {
        val r = Rig()
        r.down(1_000L)
        // 主线程在 1000..1700 之间卡住，Runnable 没有机会执行；UP 事件时间 1520（真实按住 520ms）。
        r.up(1_520L, congested = true, processedAt = 1_700L)
        assertEquals(1, r.bubbleCount)
        assertTrue(r.upConsumed)
        assertFalse(r.hostClicked)
    }

    fun upExactlyOnTheLineIsALongPressAndOneMillisecondShortIsATap() {
        val onLine = Rig(); onLine.down(1_000L); onLine.up(1_400L, congested = true, processedAt = 1_400L)
        assertEquals(1, onLine.bubbleCount)
        val short = Rig(); short.down(1_000L); short.up(1_399L, congested = true, processedAt = 1_399L)
        assertEquals(0, short.bubbleCount)
        assertTrue(short.hostClicked)
    }

    fun upBelongingToAnotherStreamIsNotClaimedByTheCurrentSession() {
        val g = FreeCopyGestureDetector({}, 400L)
        g.begin(1_000L, 1_000L, 7, 0f, 0f, 12f)
        assertEquals(FreeCopyGestureDetector.Terminal.HAND_TO_HOST,
            g.resolveTerminal(900L, 5_000L, isUp = true, alreadyHandled = false))
        assertTrue(g.isPending)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FreeCopyPressFlowTest) -> Unit) {
            val s = FreeCopyPressFlowTest()
            block(s)
        }
        runIsolated { s -> s.quickTapsOfAnyDurationBelowTheLineNeverShowABubbleAndAlwaysReachTheHost() }
        runIsolated { s -> s.titleTapJustBelowTheSystemLineExpandsAndOneMillisecondLaterIsALongPress() }
        runIsolated { s -> s.tapAfterALongPressSessionStartsCleanAndStillExpands() }
        runIsolated { s -> s.bubbleAppearsExactlyAtTheSystemLineWithNoExtraDelayForAnySurface() }
        runIsolated { s -> s.smallInputLagDoesNotDelayTheBubbleButCongestionDoesNotRaceAheadOfIt() }
        runIsolated { s -> s.userAdjustedSystemLongPressTimeoutIsHonouredByEverySurface() }
        runIsolated { s -> s.heldLongPressAfterTheBubbleConsumesUpAndBlocksTheHostClick() }
        runIsolated { s -> s.driftInsideTheHoldSlopKeepsTheLongPressAlive() }
        runIsolated { s -> s.movingBeyondTheHoldSlopCancelsEverySourceAndHandsTheTapBackToTheHost() }
        runIsolated { s -> s.returningToTheDownPointDoesNotReviveACancelledGesture() }
        runIsolated { s -> s.secondFingerCancelsTheCandidate() }
        runIsolated { s -> s.parentInterceptionBeforeTheLineNeverShowsABubbleAndConsumesNothing() }
        runIsolated { s -> s.cancelAfterTheBubbleIsShownKeepsTheBubbleAndNeverConsumesTheCancel() }
        runIsolated { s -> s.congestedShortTapIsHandedToTheHostWhenTheUpIsProcessedBeforeTheLateRunnable() }
        runIsolated { s -> s.safetyNetRevokesABubbleThatTheRunnableShowedBeforeAShortUpWasProcessed() }
        runIsolated { s -> s.genuineHoldWhoseRunnableWasLateIsStillClaimedAtUpByTheEventClock() }
        runIsolated { s -> s.upExactlyOnTheLineIsALongPressAndOneMillisecondShortIsATap() }
        runIsolated { s -> s.upBelongingToAnotherStreamIsNotClaimedByTheCurrentSession() }
    
        FreeCopyPressPolicyCases().verifyAllCases()
        FullNumberFeatureInstallerCases().verifyAllCases()
        HomeBannerFeatureInstallerCases().verifyAllCases()
        HomeComponentFilterFeatureInstallerCases().verifyAllCases()
        HomeExtraCardPolicyCases().verifyAllCases()
        HomeFrameLockFeatureInstallerCases().verifyAllCases()
        HostBackgroundConfigCases().verifyAllCases()
    }
}

private class FreeCopyPressPolicyCases {
    fun thresholdFollowsTheSystemLongPressLineAndFallsBackOnlyWhenUnreadable() {
        assertEquals(400L, FreeCopyPressPolicy.thresholdMs(400L))
        // 用户在无障碍里调整过长按时间：按用户的线，不夹紧。
        assertEquals(500L, FreeCopyPressPolicy.thresholdMs(500L))
        assertEquals(1_500L, FreeCopyPressPolicy.thresholdMs(1_500L))
        assertEquals(FreeCopyGestureDetector.DEFAULT_THRESHOLD_MS, FreeCopyPressPolicy.thresholdMs(0L))
        assertEquals(FreeCopyGestureDetector.DEFAULT_THRESHOLD_MS, FreeCopyPressPolicy.thresholdMs(-5L))
    }

    fun thereIsNoPerSurfaceDelayLeftInThePolicy() {
        // 前两轮的标题 +300ms 与 UP +150ms 余量已移除；策略只暴露三个函数/常量，不含表面维度。
        val names = FreeCopyPressPolicy::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(names.none { it.contains("title", ignoreCase = true) })
        assertTrue(names.none { it.contains("margin", ignoreCase = true) })
        // companion 的 const val 编译成外层类的静态字段。
        val gestureConsts = FreeCopyGestureDetector::class.java.declaredFields.map { it.name }
        assertTrue(gestureConsts.contains("DEFAULT_THRESHOLD_MS"))
        assertTrue(gestureConsts.none { it.contains("TITLE") || it.contains("MARGIN") })
    }

    fun holdSlopIsSlightlyWiderThanTheTapSlopButStillBounded() {
        assertEquals(12f, FreeCopyPressPolicy.holdSlop(8f), 0.0001f)
        assertTrue(FreeCopyPressPolicy.LONG_PRESS_SLOP_FACTOR >= 1f)
        assertTrue(FreeCopyPressPolicy.LONG_PRESS_SLOP_FACTOR <= 2f)
        // 非法坡度放大后仍非法，交给后端失败封闭（begin 会把会话直接置为已取消）。
        assertTrue(FreeCopyPressPolicy.holdSlop(Float.NaN).isNaN())
        val g = FreeCopyGestureDetector({}, 400L)
        g.begin(1_000L, 1_000L, 7, 0f, 0f, FreeCopyPressPolicy.holdSlop(Float.NaN))
        assertFalse(g.isPending)
    }

    fun dueIsAnchoredAtTheDownEventWhileInputLagIsSmall() {
        // DOWN 事件时间 1000，模块 1012 才处理（输入派发延迟 12ms），此刻 now=1012：
        // 到期时刻 = 1000 + 400，还要等 388ms，而不是 400ms。
        assertEquals(388L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_012L, 1_012L, 400L))
        // 没有延迟：正好 400。
        assertEquals(400L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_000L, 1_000L, 400L))
        // 延迟恰好等于上限：仍按事件时间起算。
        val cap = FreeCopyPressPolicy.MAX_INPUT_LAG_CREDIT_MS
        assertEquals(400L - cap, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_000L + cap, 1_000L + cap, 400L))
    }

    fun congestedInputFallsBackToTheObservedClockSoAQueuedUpIsNotRacedByAnEarlyBubble() {
        val cap = FreeCopyPressPolicy.MAX_INPUT_LAG_CREDIT_MS
        // 输入积压 200ms 才被处理：从观测时间起算，到期时刻 = 1200 + 400。
        assertEquals(400L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_200L, 1_200L, 400L))
        // 刚超过上限的一毫秒也退回观测时间。
        assertEquals(400L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_000L + cap + 1L, 1_000L + cap + 1L, 400L))
    }

    fun dueDelayIsNeverNegativeAndIgnoresClockSkew() {
        assertEquals(0L, FreeCopyPressPolicy.dueDelayMs(1_000L, 1_000L, 5_000L, 400L))
        // 观测时间早于 DOWN 事件时间（时钟错位）：不给提前量，从观测时间起算。
        assertEquals(400L, FreeCopyPressPolicy.dueDelayMs(1_000L, 990L, 990L, 400L))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FreeCopyPressPolicyCases) -> Unit) {
            val s = FreeCopyPressPolicyCases()
            block(s)
        }
        runIsolated { s -> s.thresholdFollowsTheSystemLongPressLineAndFallsBackOnlyWhenUnreadable() }
        runIsolated { s -> s.thereIsNoPerSurfaceDelayLeftInThePolicy() }
        runIsolated { s -> s.holdSlopIsSlightlyWiderThanTheTapSlopButStillBounded() }
        runIsolated { s -> s.dueIsAnchoredAtTheDownEventWhileInputLagIsSmall() }
        runIsolated { s -> s.congestedInputFallsBackToTheObservedClockSoAQueuedUpIsNotRacedByAnEarlyBubble() }
        runIsolated { s -> s.dueDelayIsNeverNegativeAndIgnoresClockSkew() }
    }
}

private class FullNumberFeatureInstallerCases {

    fun `converts supported non-negative integers without allocation helpers`() {
        assertEquals("0", FullNumberFeatureInstaller.rawNumberText(0))
        assertEquals("123456789", FullNumberFeatureInstaller.rawNumberText(123456789L))
        assertEquals("12", FullNumberFeatureInstaller.rawNumberText(12.toShort()))
        assertEquals("7", FullNumberFeatureInstaller.rawNumberText(7.toByte()))
        assertEquals("00123", FullNumberFeatureInstaller.rawNumberText("00123"))
    }

    fun `fails open for unsupported or ambiguous values`() {
        assertNull(FullNumberFeatureInstaller.rawNumberText(-1))
        assertNull(FullNumberFeatureInstaller.rawNumberText(-1L))
        assertNull(FullNumberFeatureInstaller.rawNumberText(""))
        assertNull(FullNumberFeatureInstaller.rawNumberText("12.3"))
        assertNull(FullNumberFeatureInstaller.rawNumberText(" 123 "))
        assertNull(FullNumberFeatureInstaller.rawNumberText(null))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FullNumberFeatureInstallerCases) -> Unit) {
            val s = FullNumberFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`converts supported non-negative integers without allocation helpers`() }
        runIsolated { s -> s.`fails open for unsupported or ambiguous values`() }
    }
}

private class HomeBannerFeatureInstallerCases {

    fun `accepts exact dedicated container even with additional decorations`() {
        assertTrue(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = DEDICATED_CONTAINER,
                childClassNames = listOf(BANNER, INDICATOR, "android.view.View"),
                bannerClassName = BANNER
            )
        )
    }

    fun `accepts generic container only for banner and indicator children`() {
        assertTrue(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = "com.bilibili.magicasakura.widgets.TintConstraintLayout",
                childClassNames = listOf(BANNER, INDICATOR),
                bannerClassName = BANNER
            )
        )
        assertTrue(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = "android.widget.FrameLayout",
                childClassNames = listOf(BANNER),
                bannerClassName = BANNER
            )
        )
    }

    fun `rejects generic container with business or unknown children`() {
        assertFalse(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = "androidx.constraintlayout.widget.ConstraintLayout",
                childClassNames = listOf(BANNER, INDICATOR, "android.widget.TextView"),
                bannerClassName = BANNER
            )
        )
        assertFalse(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = "android.widget.FrameLayout",
                childClassNames = listOf(INDICATOR),
                bannerClassName = BANNER
            )
        )
    }

    private companion object {
        const val DEDICATED_CONTAINER =
            "com.bilibili.pegasus.holders.bannerv8.BannerV8Container"
        const val BANNER = "com.bilibili.pegasus.holders.bannerv8.V8Banner"
        const val INDICATOR = "com.bilibili.app.comm.list.widget.swiper.CircleIndicator"
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HomeBannerFeatureInstallerCases) -> Unit) {
            val s = HomeBannerFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`accepts exact dedicated container even with additional decorations`() }
        runIsolated { s -> s.`accepts generic container only for banner and indicator children`() }
        runIsolated { s -> s.`rejects generic container with business or unknown children`() }
    }
}

private class HomeComponentFilterFeatureInstallerCases {

    /** 新首页框架形状：标记落在父类 `BaseHomeFragment`，运行时类是子类 `HomeFragment`。 */
    private open class BaseHomeFragment
    private class HomeFragment : BaseHomeFragment()

    /** 新框架外壳（底栏页容器）：名字里是 `homeframe`，不能被当成首页容器。 */
    private open class BaseHomeFrameFragment
    private class HomeFrameFragment : BaseHomeFrameFragment()

    fun `new home frame page counts as home container through its superclass`() {
        assertTrue(HomeComponentFilterFeatureInstaller.isHomeContainer(HomeFragment::class.java))
        assertFalse(HomeComponentFilterFeatureInstaller.isHomeContainer(HomeFrameFragment::class.java))
        assertFalse(HomeComponentFilterFeatureInstaller.isHomeContainer(Any::class.java))
    }

    fun `matches only bilibili home component class candidates`() {
        assertTrue(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "popularfragment",
                "com.bilibili.app.home.PopularFragment"
            )
        )
        assertFalse(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "detail",
                "com.bilibili.ship.theseus.detail.UnitedBizDetailsFragment"
            )
        )
        assertFalse(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "popularfragment",
                "third.party.PopularFragment"
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HomeComponentFilterFeatureInstallerCases) -> Unit) {
            val s = HomeComponentFilterFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`new home frame page counts as home container through its superclass`() }
        runIsolated { s -> s.`matches only bilibili home component class candidates`() }
    }
}

private class HomeExtraCardPolicyCases {
    fun comicBusinessTypeIsExactInEachExistingCarrier() {
        for (token in listOf("comic", "CoMiC", " CARD_TYPE_COMIC ")) {
            assertTrue(HomeExtraCardPolicy.isComic(token, null, null, null))
            assertTrue(HomeExtraCardPolicy.isComic(null, token, null, null))
            assertTrue(HomeExtraCardPolicy.isComic(null, null, token, null))
        }
        for (token in listOf(null, "av", "manga", "comic_video", "comic_future", "not_comic", "漫画解说")) {
            assertFalse(token, HomeExtraCardPolicy.isComic(token, token, token, null))
        }
    }

    fun comicRoutesRequireTheirOwnKnownAuthorityAndPath() {
        for (uri in listOf("bilibili://comic/detail?comic_id=123", "BILIBILI://COMIC/reader?ep_id=456",
            "bilibili://comic/home", "https://manga.bilibili.com/m/detail/mc123",
            "HTTPS://MANGA.BILIBILI.COM/detail/mc123?from=home",
            "http://manga.bilibili.com/m/mc123/456")) {
            assertTrue(uri, HomeExtraCardPolicy.isComic(null, null, null, uri))
        }
        for (uri in listOf(null, "", "not a uri", "fake://comic/detail", "bilibili://comic.evil/detail",
            "bilibili://comic/detail_more", "bilibili://comic/reader_fake", "bilibili://comic/unknown",
            "bilibili://comic/%64etail", "bilibili://user@comic/detail", "bilibili://comic:123/detail",
            "bilibili://video/123?target=bilibili://comic/detail", "bilibili://comic-video/detail",
            "https://manga.bilibili.com.evil.test/m/detail/mc123",
            "https://notmanga.bilibili.com/m/detail/mc123", "https://evil.test/manga.bilibili.com/m/detail/mc123",
            "https://evil.test@manga.bilibili.com/m/detail/mc123", "https://manga.bilibili.com:123/m/detail/mc123",
            "https://manga.bilibili.com/m/detail/mc0", "https://manga.bilibili.com/m/detail/mc123evil",
            "https://manga.bilibili.com/m/mc123/0", "https://manga.bilibili.com/app-download?from=home",
            "https://manga.bilibili.com/mc123/456",
            "https://manga.bilibili.com/m/detail/mc" + "1".repeat(4096))) {
            assertFalse(uri, HomeExtraCardPolicy.isComic(null, null, null, uri))
        }
    }

    fun comicDetectionLeavesExistingPgcAndSpecialClassificationUnchanged() {
        val uri = "bilibili://comic/detail?comic_id=123"
        assertTrue(HomeExtraCardPolicy.isComic("special", null, null, uri))
        assertEquals(HomeExtraCardPolicy.Kind.SPECIAL, HomeExtraCardPolicy.classify("special", null, null, uri))
        assertEquals(HomeExtraCardPolicy.Kind.UNKNOWN, HomeExtraCardPolicy.classify("comic", null, "comic", uri))
        assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify("pgc", null, null, null))
    }

    fun explicitTypesWorkInEachExistingCarrier() {
        for (token in listOf("bangumi", "bangumi_av", "bangumi_p", "bangumi_rcmd", "pgc", "CARD_TYPE_PGC")) {
            assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify(token, null, null, null))
            assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify(null, token, null, null))
            assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify(null, null, token, null))
        }
        for (token in listOf("special", "special_s", "special_s_p", "CARD_TYPE_SPECIAL")) {
            assertEquals(HomeExtraCardPolicy.Kind.SPECIAL, HomeExtraCardPolicy.classify(token, null, null, null))
        }
    }

    fun exactPlaybackRoutesWinOverSpecialAppearance() {
        for (uri in listOf(
            "bilibili://bangumi/play/ep123?from=home", "bilibili://bangumi/season/123",
            "bilibili://pgc/play/ss123", "https://www.bilibili.com/bangumi/play/ep123",
            "https://m.bilibili.com/bangumi/play/ss123/", "https://bilibili.com/bangumi/play/ep1#x"
        )) {
            assertEquals(uri, HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify("special", null, null, uri))
        }
        assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify("special", "pgc", null, null))
    }

    fun ugcAndUnknownTypesNeverMatchBySubstring() {
        for (token in listOf(null, "", "av", "bangumi_ugc", "special_attention", "special_future",
            "bangumi_future", "not_pgc", "movie_review", "电视剧解说", "small_cover_v2")) {
            assertEquals(token, HomeExtraCardPolicy.Kind.UNKNOWN, HomeExtraCardPolicy.classify(token, null, null, null))
        }
        assertEquals(HomeExtraCardPolicy.Kind.UNKNOWN, HomeExtraCardPolicy.classify("bangumi_ugc", "bangumi", "av", null))
    }

    fun untrustedOrUnrecognizedRoutesFailOpen() {
        for (uri in listOf(null, "", "not a uri", "https://evil.test/bangumi/play/ep123",
            "https://www.bilibili.com.evil.test/bangumi/play/ep123",
            "https://evil.test@www.bilibili.com/bangumi/play/ep123",
            "https://www.bilibili.com:123/bangumi/play/ep123",
            "https://www.bilibili.com/video/BV123?next=/bangumi/play/ep123",
            "bilibili://video/123?from=bangumi", "bilibili://bangumi/play/ep0",
            "bilibili://bangumi/play/ep123evil", "bilibili://bangumi/unknown/123",
            "bilibili://bangumi/play/%65p123", "bilibili://bangumi/play/ep123/" + "x".repeat(4096))) {
            assertEquals(uri, HomeExtraCardPolicy.Kind.UNKNOWN, HomeExtraCardPolicy.classify(null, null, null, uri))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HomeExtraCardPolicyCases) -> Unit) {
            val s = HomeExtraCardPolicyCases()
            block(s)
        }
        runIsolated { s -> s.comicBusinessTypeIsExactInEachExistingCarrier() }
        runIsolated { s -> s.comicRoutesRequireTheirOwnKnownAuthorityAndPath() }
        runIsolated { s -> s.comicDetectionLeavesExistingPgcAndSpecialClassificationUnchanged() }
        runIsolated { s -> s.explicitTypesWorkInEachExistingCarrier() }
        runIsolated { s -> s.exactPlaybackRoutesWinOverSpecialAppearance() }
        runIsolated { s -> s.ugcAndUnknownTypesNeverMatchBySubstring() }
        runIsolated { s -> s.untrustedOrUnrecognizedRoutesFailOpen() }
    }
}

private class HomeFrameLockFeatureInstallerCases {
    /** Mirrors `kntr.base.config.KConfig#ff(String, boolean)`: the host answers with whatever the cloud sent. */
    class FakeConfig {
        var cloud = true
        fun ff(key: String, default: Boolean): Boolean = if (key == KEY) cloud else default
    }

    class OtherShape { fun ff(key: String, default: Boolean, extra: Int): Boolean = default }
    class FakeViewModel

    private fun loader(config: Class<*>? = FakeConfig::class.java, frame: Boolean = true) =
        object : ClassLoader(HomeFrameLockFeatureInstallerCases::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "kntr.base.config.KConfig" -> config ?: throw ClassNotFoundException(name)
                HomeFrameTabLocator.VIEW_MODEL_CLASS -> if (frame) FakeViewModel::class.java else throw ClassNotFoundException(name)
                else -> super.loadClass(name, resolve)
            }
        }

    private fun environment(
        registrar: HookRegistrar = TestHookRegistrar,
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = loader(),
        status: (String, String) -> Unit = { _, _ -> },
        evidence: (String, FeatureRuntimeStage, Int) -> Unit = { _, _, _ -> }
    ) = HookEnvironment(
        processName = process,
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = status,
        runtimeEvidence = evidence
    )

    private fun call(registrar: PlayerPortTestRegistrar, key: Any?, original: Boolean): Any? =
        registrar.invoke("home_frame_lock.flag", args = arrayOf(key, false)) { original }

    fun `cloud request for the new frame is turned back into false`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val statuses = mutableListOf<String>()
        val result = HomeFrameLockFeatureInstaller(true)
            .install(environment(registrar, status = { _, value -> statuses += value }) { id, stage, _ ->
                assertEquals(HomeFrameLockFeatureInstaller.ID, id)
                stages += stage
            })
        assertEquals(FeatureInstallResult.Installed(1), result)
        assertEquals(1, registrar.hooks.size)
        assertEquals(false, call(registrar, KEY, true))
        assertEquals(false, call(registrar, KEY, true))
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages)
        assertTrue("applied:blocked-cloud-switch" in statuses)
    }

    fun `a flag the cloud already left off is observed but not reported as applied`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        HomeFrameLockFeatureInstaller(true).install(environment(registrar) { _, stage, _ -> stages += stage })
        assertEquals(false, call(registrar, KEY, false))
        assertTrue(FeatureRuntimeStage.OBSERVED in stages)
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
    }

    fun `every other cloud flag keeps the value the host computed`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        HomeFrameLockFeatureInstaller(true).install(environment(registrar) { _, stage, _ -> stages += stage })
        for (key in listOf(null, "use_new_home_frame", "dd.use_new_home_frame_v2", "dd.home_content_compat_enable",
            "DD.USE_NEW_HOME_FRAME", "dd.use_new_home_frame ")) {
            assertEquals(true, call(registrar, key, true))
        }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)
    }

    fun `disabled secondary process and hosts without the new frame register nothing`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("disabled"), HomeFrameLockFeatureInstaller(false).install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            HomeFrameLockFeatureInstaller(true).install(environment(registrar, process = "tv.danmaku.bili:download")))
        val statuses = mutableListOf<String>()
        assertEquals(FeatureInstallResult.Skipped("not-applicable-host"),
            HomeFrameLockFeatureInstaller(true).install(
                environment(registrar, loader = loader(frame = false), status = { _, value -> statuses += value })))
        assertEquals(listOf("not-applicable-host"), statuses)
        assertTrue(registrar.hooks.isEmpty())
    }

    fun `a missing or reshaped config boundary fails closed instead of hooking something else`() {
        assertEquals(FeatureInstallResult.Skipped("missing-class-loader"),
            HomeFrameLockFeatureInstaller(true).install(environment(loader = null)))
        assertEquals(FeatureInstallResult.Skipped("missing-config-boundary"),
            HomeFrameLockFeatureInstaller(true).install(environment(loader = loader(config = null))))
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("missing-config-boundary"),
            HomeFrameLockFeatureInstaller(true).install(environment(registrar, loader = loader(config = OtherShape::class.java))))
        assertTrue(registrar.hooks.isEmpty())
    }

    fun `a registration failure is reported and not counted as installed`() {
        assertEquals(FeatureInstallResult.Skipped("registration-failed"),
            HomeFrameLockFeatureInstaller(true).install(environment(PlayerPortTestRegistrar("home_frame_lock.flag"))))
    }

    private companion object {
        const val KEY = "dd.use_new_home_frame"
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HomeFrameLockFeatureInstallerCases) -> Unit) {
            val s = HomeFrameLockFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`cloud request for the new frame is turned back into false`() }
        runIsolated { s -> s.`a flag the cloud already left off is observed but not reported as applied`() }
        runIsolated { s -> s.`every other cloud flag keeps the value the host computed`() }
        runIsolated { s -> s.`disabled secondary process and hosts without the new frame register nothing`() }
        runIsolated { s -> s.`a missing or reshaped config boundary fails closed instead of hooking something else`() }
        runIsolated { s -> s.`a registration failure is reported and not counted as installed`() }
    }
}

private class HostBackgroundConfigCases {
    fun backgroundIsPublishedAfterTheClassicCommentCatalogVersions() {
        val background = SettingsCatalog.specs.filter { it.id.startsWith("host.background.") }
        assertEquals(setOf("host.background.preset", "host.background.blur", "host.background.saturation",
            "host.background.veil", "host.background.asset"), background.map { it.id }.toSet())
        assertTrue(background.all { it.introducedCatalogVersion == 49 })
        val capabilities = DiagnosticCapabilityCatalog.definitions.filter { it.id == "host_background" }
        assertEquals(listOf("host_background"), capabilities.map { it.id })
        assertTrue(capabilities.all { it.introducedCatalogVersion == 21 })
    }

    fun malformedModeAndAssetNeverEnableAnArbitraryFile() {
        assertEquals(HostBackgroundPreset.OFF, HostBackgroundPreset.read("unknown"))
        listOf("../../secret", "content://other/image", "A".repeat(36), "").forEach {
            assertFalse(HostBackgroundConfig.validAsset(it))
            assertEquals("", HostBackgroundConfig(asset = it).normalized().asset)
        }
        assertTrue(HostBackgroundConfig.validAsset("4e9c1f80-2fb0-4d98-a899-cba11d7b7344"))
    }

    fun hookAndBackupAgreeOnParameterBoundsAndDefaults() {
        val raw = HostBackgroundConfig(HostBackgroundPreset.CUSTOM, blur = 1000, saturation = -50, veil = 1000).normalized()
        assertEquals(60, raw.blur); assertEquals(0, raw.saturation); assertEquals(90, raw.veil)
        val default = HostBackgroundConfig()
        listOf(FeaturePreferences.HOST_BACKGROUND_BLUR to default.blur,
            FeaturePreferences.HOST_BACKGROUND_SATURATION to default.saturation,
            FeaturePreferences.HOST_BACKGROUND_VEIL to default.veil).forEach { (key, value) ->
            assertEquals(SettingValue.IntValue(value), SettingsCatalog.byStorageKey.getValue(key).defaultValue)
        }
        assertEquals(RestorePolicy.MANUAL, SettingsCatalog.byStorageKey.getValue(FeaturePreferences.HOST_BACKGROUND_ASSET).restorePolicy)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostBackgroundConfigCases) -> Unit) {
            val s = HostBackgroundConfigCases()
            block(s)
        }
        runIsolated { s -> s.backgroundIsPublishedAfterTheClassicCommentCatalogVersions() }
        runIsolated { s -> s.malformedModeAndAssetNeverEnableAnArbitraryFile() }
        runIsolated { s -> s.hookAndBackupAgreeOnParameterBoundsAndDefaults() }
    }
}
