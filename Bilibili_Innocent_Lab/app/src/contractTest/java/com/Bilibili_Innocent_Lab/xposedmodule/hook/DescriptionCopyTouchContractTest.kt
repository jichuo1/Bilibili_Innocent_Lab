package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三表面（标题/简介/评论）共用统一手势后端 freeCopyGesture 后的触摸接线契约。
 * 长按以事件时钟（ev.eventTime - ev.downTime）为权威：短按 UP 无论主线程多拥塞都交还宿主。
 */
class DescriptionCopyTouchContractTest {
    private fun source() = SourceContract.read("hook/HookEntry.kt")

    @Test fun cancellationStopsHostAndModuleTimersWithoutTakingScrollOwnership() {
        val clear = source().after("private fun clearDescTouchSession(").before("private fun cancelDescLongPressCandidate()")
        assertTrue(clear.contains("if (resetHandled)"))
        assertTrue(clear.contains("view.cancelLongPress()"))
        assertTrue(clear.contains("freeCopyGesture.cancelSchedule("))
        val cancel = source().after("private fun cancelDescLongPressCandidate()").before("private fun observeFreeCopyTouch(")
        assertTrue(cancel.contains("freeCopyGesture.cancelSchedule("))
        assertTrue(cancel.contains("view.cancelLongPress()"))
        assertFalse(cancel.contains("freeCopyGesture.reset()"))
        val observer = source().after("private fun observeFreeCopyTouch(").before("private fun cancelFreeCopyCandidate(")
        assertTrue(observer.contains("ACTION_POINTER_DOWN"))
        assertTrue(observer.contains("ACTION_POINTER_UP"))
        assertTrue(observer.contains("ACTION_CANCEL"))
        assertTrue(observer.contains("getHistoricalX(index, history)"))
        assertTrue(observer.contains("getHistoricalY(index, history)"))
        assertTrue(observer.contains("findPointerIndex(freeCopyGesture.pointerId)"))
        assertFalse(observer.contains("requestDisallowInterceptTouchEvent"))
        assertFalse(observer.contains("dispatchTouchEvent"))
    }

    @Test fun allEntriesRespectSameCandidateAndDeviceSlop() {
        val listener = source().after("private val sharedFreeCopyListener =").before("private fun hapticFeedback(")
        assertTrue(listener.contains("!view.isAttachedToWindow || !view.isShown"))
        assertTrue(listener.contains("view.windowVisibility != View.VISIBLE"))
        assertTrue(listener.contains("freeCopyGesture.claim("))
        val timer = source().after("private val freeCopyLongPressRunnable: Runnable =").before("private fun freeCopyPoster(")
        assertTrue(timer.contains("!freeCopyGesture.isPending"))
        assertTrue(timer.contains("freeCopyGesture.claim("))
        assertTrue(timer.contains("sharedFreeCopyListener.onLongClick(descTarget)"))
        val dispatch = source().after("// 简介触摸长按检测：").before("// 拦截官方「复制简介全文」实现")
        assertTrue(dispatch.indexOf("observeFreeCopyTouch(ev)") < dispatch.indexOf("if (!isDescLikeTarget) {"))
        val description = dispatch.after("freeCopyGesture.begin(")
        assertTrue(description.contains("scaledTouchSlop.toFloat()"))
        assertTrue(description.contains("freeCopyGesture.claimAtUp(ev.downTime, ev.eventTime)"))
        assertFalse(description.contains("60f"))
        assertFalse(description.contains("requestDisallowInterceptTouchEvent"))
    }

    @Test fun externalClaimIsAcceptedByListenerAndCommentTerminalIsNotTruncatedByDescCleanup() {
        // P0-1 接线钉子：到期 Runnable/UP 先以事件时钟 claim（置 HANDLED）再进监听器；
        // 监听器守卫必须对已认领（HANDLED）的同一目标放行，不能被 PENDING-only 守卫二次拒绝。
        val listener = source().after("private val sharedFreeCopyListener =").before("private fun hapticFeedback(")
        assertTrue("守卫 A 对 HANDLED 放行",
            listener.contains("descTouchedView !== view || (!freeCopyGesture.isPending && !freeCopyGesture.isHandled)"))
        assertTrue("守卫 B 已认领跳过二次 claim",
            listener.contains("!freeCopyGesture.isHandled &&"))
        // P0-2 接线钉子：三表面共用 matches，desc 清理必须按 descTouchedView 判会话归属，
        // 评论会话的终止事件不得被 desc 清理 reset 截断。
        val dispatch = source().after("// 简介触摸长按检测：").before("// 拦截官方「复制简介全文」实现")
        val descTerminal = dispatch.after("if (descTouchedView != null &&").before("clearDescTouchSession(resetHandled = !descLongPressHandled)")
        assertTrue("desc 终止清理按会话归属门控", descTerminal.contains("freeCopyGesture.matches(ev.downTime)"))
        assertTrue("仅终止事件走 desc 清理", descTerminal.contains("ACTION_UP") && descTerminal.contains("ACTION_CANCEL"))
        // 短按安全网：UP 事件时长不足阈值时撤销已误弹气泡并交还宿主（两表面均接入）。
        assertTrue("短按安全网函数存在", source().contains("private fun revokePrematurePopupIfShortTap("))
        assertEquals("简介/标题、评论与祖先 UP 均接入短按安全网", 3,
            Regex("""revokePrematurePopupIfShortTap\(ev\)""").findAll(dispatch).count())
        val revoke = source().after("private fun revokePrematurePopupIfShortTap(").before("private fun isOfficialSuppressionActive(")
        assertTrue("事件时长不足阈值才撤销", revoke.contains("ev.eventTime - ev.downTime >= freeCopyGesture.thresholdMs"))
        assertTrue("仅撤销仍在显示的自家气泡", revoke.contains("isOurBubbleShowing()"))
    }

    @Test fun bubbleDismissReleasesOnlyHandledGestureAfterIdentityCheck() {
        val finish = source().after("private fun finishBubbleSession(").before("private val sharedFreeCopyListener =")
        assertTrue(finish.indexOf("activeBubbleSessionId != sessionId") < finish.indexOf("freeCopyGesture.finishHandled()"))
        assertFalse(finish.contains("freeCopyGesture.reset()"))
    }

    @Test fun longPressAuthorityIsEventClockAndShortTapUpReturnsToHost() {
        // 事件时钟为唯一长按判据：UP 用 ev.eventTime - ev.downTime 复核，短按不认领。
        val dispatch = source().after("// 简介触摸长按检测：").before("// 拦截官方「复制简介全文」实现")
        assertTrue("简介/标题 UP 事件时钟复核（带临界余量）", dispatch.contains("freeCopyGesture.claimAtUp(ev.downTime, ev.eventTime)"))
        assertTrue("评论 UP 事件时钟复核（带临界余量）", dispatch.contains("freeCopyGesture.claimAtUp(ev.downTime, ev.eventTime)"))
        assertTrue("标题阈值在系统线上加余量", dispatch.contains("FreeCopyGestureDetector.TITLE_THRESHOLD_EXTRA_MS"))
        // 三表面共用同一个后端实例与单一到期 Runnable。
        assertEquals(1, Regex("""private val freeCopyGesture[:\s]""").findAll(source()).count())
        assertEquals(1, Regex("""private val freeCopyLongPressRunnable""").findAll(source()).count())
        // 后端 claim 以事件时钟判定（eventTime - downTime >= requiredMs）；UP 认领另加临界余量。
        val detector = SourceContract.read("hook/feature/FreeCopyGestureDetector.kt")
        val claim = detector.after("fun claim(").before("fun reset()")
        assertTrue(claim.contains("eventTime - downTime < requiredMs"))
        assertTrue(claim.contains("thresholdMs + UP_CLAIM_MARGIN_MS"))
    }
}
