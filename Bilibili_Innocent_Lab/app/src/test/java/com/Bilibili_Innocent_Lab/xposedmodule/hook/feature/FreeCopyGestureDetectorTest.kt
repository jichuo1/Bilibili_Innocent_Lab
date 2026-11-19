package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一长按手势后端：平移自 DescriptionCopyGestureTest（相位机）与 CommentLongPressTimerTest（单次调度），
 * 并新增「事件时钟权威 + 拥塞回归」钉住本次修复——短按 UP 无论主线程多拥塞都交还宿主。
 */
class FreeCopyGestureDetectorTest {
    private val runnable = Runnable {}
    private fun detector(threshold: Long = 400L) = FreeCopyGestureDetector(runnable, threshold)
    private fun gesture(slop: Float = 8f, threshold: Long = 400L) =
        detector(threshold).apply { begin(1_000L, 1_000L, 7, 100f, 200f, slop) }

    // ===== 相位机：事件时钟认领 =====
    @Test fun stationaryPressClaimsOnceAtThreshold() {
        val g = gesture()
        assertFalse(g.claim(1_000L, 1_399L))
        assertTrue(g.claim(1_000L, 1_400L))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    @Test fun thresholdIsConfigurableAndBoundariesAreExact() {
        val g = gesture(threshold = 500L)
        assertFalse(g.claim(1_000L, 1_499L))
        assertTrue(g.claim(1_000L, 1_500L))
    }

    @Test fun upClaimRequiresMarginBeyondThresholdSoBorderlineTapsStayWithHost() {
        // 刚越线就抬手多半是瞄准小目标的慢点按：UP 认领要求阈值+余量，临界区交还宿主点击。
        val g = gesture()
        assertFalse(g.claimAtUp(1_000L, 1_400L))
        assertFalse(g.claimAtUp(1_000L, 1_549L))
        assertTrue(g.isPending)
        assertTrue(g.claimAtUp(1_000L, 1_550L))
        assertFalse(g.claimAtUp(1_000L, 1_600L))
    }

    @Test fun heldPressStillClaimsAtPlainThresholdWhileFingerIsDown() {
        // 按住中（到期 Runnable/宿主监听器）走 claim，不受 UP 余量影响，即时反馈不延迟。
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
    }

    @Test fun shortTapAndSmallJitterStayWithHost() {
        val g = gesture()
        assertFalse(g.sample(1_000L, 7, 1, 103f, 204f))
        assertFalse(g.claim(1_000L, 1_100L))
        assertTrue(g.isPending)
    }

    @Test fun slowScrollBelowOldSixtyPixelsCancelsAllLongPressSources() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 1, 100f, 209f))
        assertFalse(g.claim(1_000L, 1_400L)) // 定时器
        assertFalse(g.claim(1_000L, 1_500L)) // 系统监听器
        assertFalse(g.claim(1_000L, 2_000L)) // UP 兜底
    }

    @Test fun returningToDownPointDoesNotRearmAfterScroll() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 1, 120f, 200f))
        assertFalse(g.sample(1_000L, 7, 1, 100f, 200f))
        assertFalse(g.claim(1_000L, 1_600L))
    }

    @Test fun historicalExcursionCancelsEvenWhenLatestPointIsInsideSlop() {
        val g = gesture()
        for (y in listOf(202f, 215f, 200f)) g.sample(1_000L, 7, 1, 100f, y)
        assertFalse(g.claim(1_000L, 1_500L))
    }

    @Test fun upCoordinatesAlsoRejectUndeliveredMovement() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 1, 100f, 230f))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    @Test fun diagonalMovementUsesSameRadialBudgetAsAxialMovement() {
        val g = gesture()
        assertFalse(g.sample(1_000L, 7, 1, 104f, 206f))
        assertTrue(g.sample(1_000L, 7, 1, 106f, 206f))
    }

    @Test fun deviceSlopControlsCancellationRatherThanPixelMagicNumber() {
        val small = gesture(8f)
        val large = gesture(24f)
        assertTrue(small.sample(1_000L, 7, 1, 100f, 216f))
        assertFalse(large.sample(1_000L, 7, 1, 100f, 216f))
        assertTrue(large.claim(1_000L, 1_400L))
    }

    @Test fun parentCancellationIsIrreversibleWithinSameStream() {
        val g = gesture()
        assertTrue(g.cancel(1_000L))
        assertFalse(g.cancel(1_000L))
        assertTrue(g.matches(1_000L))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    @Test fun secondPointerCancelsCandidateWithoutChoosingAnotherFinger() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 2, 100f, 200f))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    @Test fun missingOriginalPointerFailsClosed() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 9, 1, 100f, 200f))
        assertFalse(g.claim(1_000L, 1_500L))
    }

    @Test fun eventsFromAnotherStreamDoNotCancelOrClaimCurrentGesture() {
        val g = gesture()
        assertFalse(g.sample(999L, 7, 1, 1_000f, 1_000f))
        assertFalse(g.cancel(999L))
        assertFalse(g.claim(999L, 1_500L))
        assertTrue(g.claim(1_000L, 1_400L))
    }

    @Test fun replacementGestureRejectsOldCallbackAndOldTerminalEvent() {
        val g = gesture()
        g.begin(2_000L, 2_000L, 9, 0f, 0f, 8f)
        assertFalse(g.claim(1_000L, 3_000L))
        assertFalse(g.cancel(1_000L))
        assertFalse(g.claim(2_000L, 2_100L))
        assertTrue(g.claim(2_000L, 2_400L))
    }

    @Test fun completedLongPressCannotBeClaimedAgainByMovementOrAnotherEntry() {
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
        assertFalse(g.sample(1_000L, 7, 1, 100f, 500f))
        assertFalse(g.cancel(1_000L))
        assertFalse(g.claim(1_000L, 1_600L))
    }

    @Test fun terminationRestoresNonTouchAndNextGestureEligibility() {
        val g = gesture()
        g.cancel(1_000L)
        g.reset()
        assertFalse(g.isTracking)
        assertFalse(g.claim(1_000L, 2_000L))
        g.begin(2_000L, 2_000L, 7, 0f, 0f, 8f)
        assertTrue(g.claim(2_000L, 2_400L))
    }

    @Test fun invalidSamplesCannotOpenPopup() {
        val g = gesture()
        assertTrue(g.sample(1_000L, 7, 1, Float.NaN, 200f))
        assertFalse(g.claim(1_000L, 1_500L))
        g.begin(2_000L, 2_000L, 7, 0f, 0f, Float.POSITIVE_INFINITY)
        assertFalse(g.isPending)
        assertFalse(g.claim(2_000L, 2_500L))
    }

    @Test fun bubbleDismissRestoresNonTouchEligibilityAfterHandledGesture() {
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
        g.finishHandled()
        assertFalse(g.isTracking)
    }

    @Test fun oldBubbleDismissCannotDiscardNewGestureDuringExitAnimation() {
        val g = gesture()
        assertTrue(g.claim(1_000L, 1_400L))
        g.begin(2_000L, 2_000L, 9, 0f, 0f, 8f)
        g.finishHandled()
        assertTrue(g.isPending)
        assertTrue(g.claim(2_000L, 2_400L))
    }

    // ===== 拥塞回归（本次修复核心）：事件时钟权威，短按 UP 不被迟到 Runnable 误判 =====
    @Test fun congestedShortTapUpReturnsToHostEvenIfRunnableFiresLate() {
        val g = gesture()
        // DOWN 观测后主线程拥塞：UP 事件时长仅 120ms（真实短按），但 Runnable 迟到至 600ms 才执行。
        // UP 先派发：事件时钟复核失败 → 不认领，会话取消，交还宿主点击（标题展开/评论三点）。
        assertFalse("短按 UP 事件时长 < 阈值，绝不认领", g.claim(1_000L, 1_120L))
        g.cancel(1_000L)
        // 迟到的 Runnable 再尝试认领：会话已 CANCELLED，仍失败 → 不弹泡。
        assertFalse("迟到 Runnable 不得把短按误判成长按", g.claim(1_000L, 1_600L))
        assertFalse(g.isHandled)
    }

    @Test fun genuineHoldClaimsEvenWhenRunnableIsCongested() {
        val g = gesture()
        // 真实按住 600ms（事件时长 >= 阈值），即使 Runnable 迟到，UP 复核仍认领。
        assertTrue(g.claim(1_000L, 1_600L))
        assertTrue(g.isHandled)
    }

    @Test fun armedOnlyAfterArmWindowWhilePending() {
        val g = gesture()
        assertFalse(g.isArmed(1_000L + 299L))
        assertTrue(g.isArmed(1_000L + 300L))
        g.claim(1_000L, 1_400L)
        assertFalse("已认领不再是候选武装", g.isArmed(1_000L + 400L))
        g.reset()
        g.begin(2_000L, 2_000L, 7, 0f, 0f, 8f)
        g.cancel(2_000L)
        assertFalse("已让出滑动不再武装", g.isArmed(2_000L + 400L))
    }

    @Test fun sessionIdentityIsolatesStaleRunnableAcrossGestures() {
        val g = gesture()
        val first = g.sessionId
        g.begin(2_000L, 2_000L, 9, 0f, 0f, 8f)
        val second = g.sessionId
        assertTrue("新手势会话序号递增", second != first)
        // 旧会话的到期回调按旧 sessionId 找不到当前会话 → 不弹。
        assertFalse(g.matches(1_000L))
        assertTrue(g.matches(2_000L))
    }

    // ===== 单次调度（平移自 CommentLongPressTimerTest）=====
    private class FakeHandler(var accepts: Boolean = true) : FreeCopyGestureDetector.Poster {
        val queue = ArrayList<Pair<Runnable, Long>>()
        var removes = 0
        var posts = 0
        override fun remove(runnable: Runnable) { removes++; queue.removeAll { it.first === runnable } }
        override fun post(runnable: Runnable, delayMillis: Long): Boolean {
            posts++
            if (!accepts) return false
            queue += runnable to delayMillis
            return true
        }
    }

    @Test fun oneDownThroughManyDispatchLevelsPostsOnce() {
        val g = detector(); val handler = FakeHandler()
        val levels = 12
        val posted = (1..levels).count { g.schedule(sessionId = 7, delayMillis = 400, poster = handler) }
        assertEquals(1, posted)
        assertEquals(1, handler.queue.size)
        assertEquals(1, handler.posts)
        assertEquals(1, handler.removes)
        assertEquals(400L, handler.queue.single().second)
        assertTrue(g.isScheduled)
    }

    @Test fun aNewGestureSessionSchedulesAgain() {
        val g = detector(); val handler = FakeHandler()
        assertTrue(g.schedule(1, 400, handler))
        g.cancelSchedule(handler)
        assertFalse(g.isScheduled)
        assertTrue(handler.queue.isEmpty())
        assertTrue(g.schedule(2, 400, handler))
        assertEquals(1, handler.queue.size)
    }

    @Test fun cancelRemovesThePendingMessageAndAllowsReschedulingTheSameSession() {
        val g = detector(); val handler = FakeHandler()
        g.schedule(3, 400, handler)
        g.cancelSchedule(handler)
        assertTrue(handler.queue.isEmpty())
        assertTrue(g.schedule(3, 400, handler))
    }

    @Test fun postFailureDoesNotMarkTheSessionSoALaterLevelCanRetry() {
        val g = detector(); val handler = FakeHandler(accepts = false)
        assertFalse(g.schedule(5, 400, handler))
        assertFalse(g.isScheduled)
        handler.accepts = true
        assertTrue(g.schedule(5, 400, handler))
        assertEquals(1, handler.queue.size)
    }

    @Test fun afterFiringTheSameSessionMayBeRescheduledByALaterLevel() {
        val g = detector(); val handler = FakeHandler()
        g.schedule(9, 400, handler)
        handler.queue.clear()
        g.onFired()
        assertFalse(g.isScheduled)
        assertTrue(g.schedule(9, 100, handler))
    }

    @Test fun sessionZeroIsNeverScheduledAndNegativeDelayIsClamped() {
        val g = detector(); val handler = FakeHandler()
        assertFalse(g.schedule(0, 400, handler))
        assertEquals(0, handler.posts)
        assertTrue(g.schedule(1, -50, handler))
        assertEquals(0L, handler.queue.single().second)
    }

    @Test fun cancelWithoutAHandlerStillResetsTheState() {
        val g = detector()
        g.schedule(4, 400, FakeHandler())
        g.cancelSchedule(null)
        assertFalse(g.isScheduled)
    }

    @Test fun resetAlsoClearsScheduleSoSameIdNewSessionIsNotMistakenAsScheduled() {
        val g = detector(); val handler = FakeHandler()
        g.schedule(4, 400, handler)
        assertTrue(g.isScheduled)
        // 只 reset 未 cancelSchedule 的路径：调度标记必须一并复位，同号新会话仍可投递。
        g.reset()
        assertFalse(g.isScheduled)
        assertTrue(g.schedule(4, 400, handler))
    }

    @Test fun beginThresholdOverridesDefaultAndFallsBackOnNonPositive() {
        val g = detector(threshold = 400L)
        g.begin(1_000L, 1_000L, 7, 0f, 0f, 8f, thresholdMs = 500L)
        assertEquals(500L, g.thresholdMs)
        assertFalse(g.claim(1_000L, 1_400L))
        assertTrue(g.claim(1_000L, 1_500L))
        g.begin(2_000L, 2_000L, 7, 0f, 0f, 8f, thresholdMs = 0L)
        assertEquals(FreeCopyGestureDetector.DEFAULT_THRESHOLD_MS, g.thresholdMs)
    }
}
