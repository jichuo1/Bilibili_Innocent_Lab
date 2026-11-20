package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按自由复制的端到端手势流水线仿真：生产对象（[FreeCopyGestureDetector] + [FreeCopyPressPolicy]）
 * 配虚拟时钟与假 Handler，覆盖短按不误触发、标题展开不被吞、长按触发延迟、移动超坡度取消、
 * ACTION_CANCEL、交还宿主的条件。
 *
 * 编排层（[Rig]）镜像 HookEntry 的 observeFreeCopyTouch / 到期 Runnable / finishDescLikeTouch；
 * 接线是否仍与此一致由 contractTest 的 FreeCopyGestureStrategyContractTest 与
 * DescriptionCopyTouchContractTest 钉住，宿主侧的真实效果仍须真机验证。
 *
 * “宿主点击”的模型：UP 到达宿主（模块没有消费）、本次手势没有被取消、也没有弹过气泡，宿主就会
 * 执行点击（标题展开/收起、评论三点、简介 span）。
 */
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
    @Test fun quickTapsOfAnyDurationBelowTheLineNeverShowABubbleAndAlwaysReachTheHost() {
        for (held in listOf(25L, 60L, 99L, 100L, 180L, 300L, 399L)) {
            val r = Rig()
            r.down(1_000L)
            r.up(1_000L + held)
            assertEquals("held=$held", 0, r.bubbleCount)
            assertFalse("held=$held 不得消费 UP", r.upConsumed)
            assertTrue("held=$held 宿主点击（标题展开）必须发生", r.hostClicked)
        }
    }

    @Test fun titleTapJustBelowTheSystemLineExpandsAndOneMillisecondLaterIsALongPress() {
        val tap = Rig(); tap.down(1_000L); tap.up(1_399L)
        assertTrue(tap.hostClicked); assertEquals(0, tap.bubbleCount)
        val hold = Rig(); hold.down(1_000L); hold.up(1_401L)
        assertFalse("长按之后宿主不得再展开标题", hold.hostClicked)
        assertEquals(1, hold.bubbleCount)
        assertTrue("长按的 UP 必须被消费", hold.upConsumed)
    }

    @Test fun tapAfterALongPressSessionStartsCleanAndStillExpands() {
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
    @Test fun bubbleAppearsExactlyAtTheSystemLineWithNoExtraDelayForAnySurface() {
        val r = Rig()
        r.down(1_000L)
        r.advanceTo(1_399L); assertFalse(r.bubbleShowing)
        r.advanceTo(1_400L)
        assertTrue("按住满 400ms 即弹泡（不是 700ms）", r.bubbleShowing)
        assertEquals(1_400L, r.bubbleShownAt)
    }

    @Test fun smallInputLagDoesNotDelayTheBubbleButCongestionDoesNotRaceAheadOfIt() {
        val fast = Rig(); fast.down(1_000L, lagMs = 12L) // 模块 1012 才看到 DOWN
        fast.advanceTo(1_399L); assertFalse(fast.bubbleShowing)
        fast.advanceTo(1_400L); assertTrue("手指落下后 400ms，不是处理后 400ms", fast.bubbleShowing)
        val congested = Rig(); congested.down(1_000L, lagMs = 300L) // 主线程积压 300ms
        congested.advanceTo(1_699L); assertFalse(congested.bubbleShowing)
        congested.advanceTo(1_700L); assertTrue(congested.bubbleShowing)
    }

    @Test fun userAdjustedSystemLongPressTimeoutIsHonouredByEverySurface() {
        val r = Rig(systemLongPressMs = 500L)
        r.down(1_000L)
        r.advanceTo(1_499L); assertFalse(r.bubbleShowing)
        r.advanceTo(1_500L); assertTrue(r.bubbleShowing)
        val tap = Rig(systemLongPressMs = 500L); tap.down(1_000L); tap.up(1_450L)
        assertTrue("500ms 线下 450ms 仍是点击", tap.hostClicked)
    }

    @Test fun heldLongPressAfterTheBubbleConsumesUpAndBlocksTheHostClick() {
        val r = Rig()
        r.down(1_000L)
        r.advanceTo(1_400L)
        r.up(2_000L)
        assertTrue(r.upConsumed)
        assertFalse(r.hostClicked)
        assertEquals("同一次按住只弹一次", 1, r.bubbleCount)
    }

    // ===== 移动超坡度取消 =====
    @Test fun driftInsideTheHoldSlopKeepsTheLongPressAlive() {
        val r = Rig(touchSlop = 8f) // 容忍半径 12px
        r.down(1_000L)
        r.move(1_100L, 100f, 209f)  // 9px：旧的 8px 坡度会取消，现在仍有效
        r.move(1_200L, 100f, 211f)  // 11px
        r.advanceTo(1_400L)
        assertTrue(r.bubbleShowing)
    }

    @Test fun movingBeyondTheHoldSlopCancelsEverySourceAndHandsTheTapBackToTheHost() {
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

    @Test fun returningToTheDownPointDoesNotReviveACancelledGesture() {
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

    @Test fun secondFingerCancelsTheCandidate() {
        val r = Rig()
        r.down(1_000L)
        r.move(1_150L, 100f, 200f, pointerCount = 2)
        r.advanceTo(1_400L)
        assertEquals(0, r.bubbleCount)
    }

    // ===== ACTION_CANCEL / 父容器拦截 =====
    @Test fun parentInterceptionBeforeTheLineNeverShowsABubbleAndConsumesNothing() {
        val r = Rig()
        r.down(1_000L)
        r.cancel(1_200L)            // RecyclerView 接管滚动
        r.advanceTo(1_400L)
        assertEquals(0, r.bubbleCount)
        assertFalse(r.upConsumed)
        assertFalse("被取消的手势不是点击", r.hostClicked)
    }

    @Test fun cancelAfterTheBubbleIsShownKeepsTheBubbleAndNeverConsumesTheCancel() {
        val r = Rig()
        r.down(1_000L); r.advanceTo(1_400L)
        r.cancel(1_800L)
        assertEquals(1, r.bubbleCount)
        assertFalse("CANCEL 本身不消费，交还宿主做自己的收尾", r.upConsumed)
    }

    // ===== 拥塞 / 交还宿主的条件 =====
    @Test fun congestedShortTapIsHandedToTheHostWhenTheUpIsProcessedBeforeTheLateRunnable() {
        val r = Rig()
        r.down(1_000L, lagMs = 200L)               // DOWN 积压 200ms 才被模块看到（观测时间 1200）
        r.up(1_090L, congested = true, processedAt = 1_700L) // UP 事件时间只按了 90ms
        assertEquals("迟到的 Runnable 不得把短按误判成长按", 0, r.bubbleCount)
        assertTrue(r.hostClicked)
        assertFalse(r.upConsumed)
    }

    @Test fun safetyNetRevokesABubbleThatTheRunnableShowedBeforeAShortUpWasProcessed() {
        val r = Rig()
        r.down(1_000L, lagMs = 0L)
        r.runnableBeatsUp(upEventTime = 1_120L, runnableAt = 1_400L)
        assertTrue("事件时钟：真实按住 120ms，是点击", r.hostClicked)
        assertFalse(r.bubbleShowing)
        assertFalse(r.upConsumed)
    }

    @Test fun genuineHoldWhoseRunnableWasLateIsStillClaimedAtUpByTheEventClock() {
        val r = Rig()
        r.down(1_000L)
        // 主线程在 1000..1700 之间卡住，Runnable 没有机会执行；UP 事件时间 1520（真实按住 520ms）。
        r.up(1_520L, congested = true, processedAt = 1_700L)
        assertEquals(1, r.bubbleCount)
        assertTrue(r.upConsumed)
        assertFalse(r.hostClicked)
    }

    @Test fun upExactlyOnTheLineIsALongPressAndOneMillisecondShortIsATap() {
        val onLine = Rig(); onLine.down(1_000L); onLine.up(1_400L, congested = true, processedAt = 1_400L)
        assertEquals(1, onLine.bubbleCount)
        val short = Rig(); short.down(1_000L); short.up(1_399L, congested = true, processedAt = 1_399L)
        assertEquals(0, short.bubbleCount)
        assertTrue(short.hostClicked)
    }

    @Test fun upBelongingToAnotherStreamIsNotClaimedByTheCurrentSession() {
        val g = FreeCopyGestureDetector({}, 400L)
        g.begin(1_000L, 1_000L, 7, 0f, 0f, 12f)
        assertEquals(FreeCopyGestureDetector.Terminal.HAND_TO_HOST,
            g.resolveTerminal(900L, 5_000L, isUp = true, alreadyHandled = false))
        assertTrue(g.isPending)
    }
}
