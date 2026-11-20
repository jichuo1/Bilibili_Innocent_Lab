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
 * 终止事件（UP/CANCEL）的裁决统一走 finishDescLikeTouch / resolveTerminal，不再有 UP 余量与标题额外延时。
 */
class DescriptionCopyTouchContractTest {
    private fun source() = SourceContract.read("hook/HookEntry.kt")

    @Test fun cancellationStopsModuleTimersWithoutTouchingHostPressStateOnTerminalEvents() {
        // 终止/清理路径不得调用 View.cancelLongPress()：它连带 removeTapCallback() 清掉 PFLAG_PREPRESSED，
        // 快点按（<tapTimeout）的宿主点击会被静默丢弃。
        val clear = source().after("private fun clearDescTouchSession(").before("* 让出给滑动时取消模块长按")
        assertTrue(clear.contains("if (resetHandled)"))
        assertTrue(clear.contains("freeCopyGesture.cancelSchedule("))
        assertFalse("清理路径不得取消宿主/框架的按压状态", clear.contains("cancelLongPress"))
        // 让出给滑动：只有简介（宿主 DescTagSpan 自实现长按）才取消宿主长按，标题一律不碰。
        val cancel = source().after("private fun cancelDescLongPressCandidate()").before("private fun observeFreeCopyTouch(")
        assertTrue(cancel.contains("freeCopyGesture.cancelSchedule("))
        assertTrue(cancel.contains("if (!isTitleView(view)) runCatching { view.cancelLongPress() }"))
        assertEquals("取消候选里只有这一处 cancelLongPress", 1, Regex("""cancelLongPress\(\)""").findAll(cancel).count())
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
        assertTrue(description.contains("freeCopyHoldSlop(v)"))
        assertTrue(description.contains("freeCopyLongPressThreshold()"))
        assertTrue(description.contains("finishDescLikeTouch(ev, v)"))
        assertFalse(description.contains("60f"))
        assertFalse(description.contains("requestDisallowInterceptTouchEvent"))
        // 设备坡度只在这一处读取，再经策略放宽。
        val slop = source().after("private fun freeCopyHoldSlop(").before("private fun freeCopyDueDelayMs()")
        assertTrue(slop.contains("scaledTouchSlop.toFloat()"))
        assertTrue(slop.contains("FreeCopyPressPolicy.holdSlop("))
    }

    @Test fun externalClaimIsAcceptedByListenerAndCommentTerminalIsNotTruncatedByDescCleanup() {
        // P0-1 接线钉子：到期 Runnable/UP 先以事件时钟 claim（置 HANDLED）再进监听器；
        // 监听器守卫必须对已认领（HANDLED）的同一目标放行，不能被 PENDING-only 守卫二次拒绝。
        val listener = source().after("private val sharedFreeCopyListener =").before("private fun hapticFeedback(")
        assertTrue("守卫 A 对 HANDLED 放行",
            listener.contains("descTouchedView !== view || (!freeCopyGesture.isPending && !freeCopyGesture.isHandled)"))
        assertTrue("守卫 B 已认领跳过二次 claim",
            listener.contains("!freeCopyGesture.isHandled &&"))
        // P0-2 接线钉子：三表面共用 matches，desc 清理必须按 descTouchedView/handled 判会话归属，
        // 评论会话的终止事件不得被 desc 清理 reset 截断。
        val dispatch = source().after("// 简介触摸长按检测：").before("// 拦截官方「复制简介全文」实现")
        val descTerminal = dispatch.after("if ((descTouchedView != null || descLongPressHandled) &&").before("if (!runtimeCommentFreeCopyEnabled) return@before")
        assertTrue("desc 终止清理按会话归属门控", descTerminal.contains("freeCopyGesture.matches(ev.downTime)"))
        assertTrue("仅终止事件走 desc 裁决", descTerminal.contains("ACTION_UP") && descTerminal.contains("ACTION_CANCEL"))
        assertTrue("祖先/容器收到终止事件走同一裁决", descTerminal.contains("finishDescLikeTouch(ev, null)"))
        assertTrue("已认领的 UP 在祖先分支同样被消费", descTerminal.contains("this.result = true"))
        assertTrue(descTerminal.contains("return@before"))
        // 短按安全网：UP 事件时长不足阈值时撤销已误弹气泡并交还宿主。
        assertTrue("短按安全网函数存在", source().contains("private fun revokePrematurePopupIfShortTap("))
        val finish = source().after("private fun finishDescLikeTouch(").before("private var suppressOfficialUntilMs = 0L")
        assertEquals("简介/标题（目标与祖先）经同一个裁决函数接入短按安全网", 1,
            Regex("""revokePrematurePopupIfShortTap\(ev\)""").findAll(finish).count())
        assertEquals("评论 UP 仍单独接入短按安全网", 1,
            Regex("""revokePrematurePopupIfShortTap\(ev\)""").findAll(dispatch).count())
        val revoke = source().after("private fun revokePrematurePopupIfShortTap(").before("private fun finishDescLikeTouch(")
        assertTrue("事件时长不足阈值才撤销", revoke.contains("ev.eventTime - ev.downTime >= freeCopyGesture.thresholdMs"))
        assertTrue("仅撤销仍在显示的自家气泡", revoke.contains("isOurBubbleShowing()"))
    }

    @Test fun bubbleDismissReleasesOnlyHandledGestureAfterIdentityCheck() {
        val finish = source().after("private fun finishBubbleSession(").before("private val sharedFreeCopyListener =")
        assertTrue(finish.indexOf("activeBubbleSessionId != sessionId") < finish.indexOf("freeCopyGesture.finishHandled()"))
        assertFalse(finish.contains("freeCopyGesture.reset()"))
    }

    @Test fun longPressAuthorityIsEventClockAndShortTapUpReturnsToHost() {
        // 事件时钟为唯一长按判据：UP 经 resolveTerminal 用 ev.eventTime - ev.downTime 复核，短按不认领。
        val dispatch = source().after("// 简介触摸长按检测：").before("// 拦截官方「复制简介全文」实现")
        assertTrue("评论 UP 事件时钟复核", dispatch.contains("freeCopyGesture.resolveTerminal("))
        assertTrue(dispatch.contains("ev.downTime, ev.eventTime"))
        assertTrue(dispatch.contains("FreeCopyGestureDetector.Terminal.CLAIM_AND_CONSUME"))
        val finish = source().after("private fun finishDescLikeTouch(").before("private var suppressOfficialUntilMs = 0L")
        assertTrue("简介/标题 UP 事件时钟复核", finish.contains("freeCopyGesture.resolveTerminal(ev.downTime, ev.eventTime, isUp, handled)"))
        // 三表面共用同一个后端实例与单一到期 Runnable。
        assertEquals(1, Regex("""private val freeCopyGesture[:\s]""").findAll(source()).count())
        assertEquals(1, Regex("""private val freeCopyLongPressRunnable""").findAll(source()).count())
        // 后端 claim 以事件时钟判定（eventTime - downTime >= thresholdMs），不再有 UP 余量。
        val detector = SourceContract.read("hook/feature/FreeCopyGestureDetector.kt")
        val claim = detector.after("fun claim(").before("/** 终止事件")
        assertTrue(claim.contains("eventTime - downTime < thresholdMs"))
        assertFalse(claim.contains("MARGIN"))
    }
}
