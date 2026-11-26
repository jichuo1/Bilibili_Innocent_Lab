package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 性能报告 P2-04：评论手势的空根快路与单次长按调度。不得恢复被撤回的全局 hook 自解除或版本硬门控方案。
 * 手势判定统一走 freeCopyGesture（与标题/简介共用）；评论特有的目标/根校验与 more_button 直通保留。 */
class CommentLongPressDispatchContractTest {
    private val gestureFiles = HookEntrySource.freeCopyScopeFiles()

    fun downWithoutAnyRegisteredCommentRootSkipsTheAncestorWalkAfterCleaningTheOldSession() {
        val dispatch = HookEntrySource.function("handleDispatchTouchEvent", gestureFiles)
        val down = dispatch.after("if (!isDescLikeTarget) {").after("if (action == MotionEvent.ACTION_DOWN) {")
            .before("root?.let { beginOrRetargetCommentTouch(v, it, ev) }")
        val cleanup = down.indexOf("clearCommentTouchSession(resetHandled = true)")
        val empty = down.indexOf("commentRootRefs.isEmpty()")
        val walk = down.indexOf("while (cur != null)")
        assertTrue(cleanup in 0 until empty)
        assertTrue(
            "the emptiness check is made under the root lock",
            down.contains("synchronized(FreeCopyBindScheduler.commentRootLock)") && down.contains("FreeCopyBindScheduler.commentRootRefs.isEmpty()")
        )
        assertTrue(empty in 0 until walk)
        assertTrue(down.substring(empty, walk).contains("return false"))
        val installer = HookEntrySource.function("installDescriptionFreeCopyHooks", gestureFiles)
        assertTrue(installer.contains("if (FreeCopyGestureState.handleDispatchTouchEvent(v, ev))"))
    }

    fun movePathStaysConstantTimeAndMoreButtonPassthroughIsKept() {
        val dispatch = HookEntrySource.function("handleDispatchTouchEvent", gestureFiles)
        val nonDownNonDesc = dispatch
            .after("if (!isDescLikeTarget) {")
            .after("root?.let { beginOrRetargetCommentTouch(v, it, ev) }")
            .after("return false")
            .before("MotionEvent.ACTION_DOWN -> {")
        assertFalse("MOVE path must not walk ancestors", nonDownNonDesc.contains("while (cur != null)"))
        assertFalse("MOVE path must not take commentRootLock", nonDownNonDesc.contains("commentRootLock"))
        assertTrue(dispatch.contains("commentMoreButtonPassthroughDownTime = ev.downTime"))
        assertTrue(
            dispatch.contains("FreeCopyTextExtractor.isCommentMoreButton(v) ||") &&
                dispatch.contains("FreeCopyTextExtractor.isCommentActionsContainer(v)")
        )
    }

    fun retargetingNeverReschedulesTheSameSessionDirectly() {
        val retarget = HookEntrySource.function("beginOrRetargetCommentTouch", gestureFiles)
        assertTrue(retarget.contains("freeCopyGesture.schedule(freeCopyGesture.sessionId, freeCopyDueDelayMs(), freeCopyPoster(handler))"))
        assertFalse("no per-level removeCallbacks/postDelayed of the long-press runnable", retarget.contains("removeCallbacks("))
        assertFalse(retarget.contains("postDelayed("))
        // 目标与根引用仍逐层更新：最深层触摸 View 才是气泡锚点。
        assertTrue(retarget.contains("commentTouchTargetRef = WeakReference(v)"))
        assertTrue(retarget.contains("commentTouchRootRef = WeakReference(root)"))
        // 到期时刻仍由会话起点固定：经 FreeCopyPressPolicy.dueDelayMs（事件时间/观测时间二选一）计算一次。
        val due = HookEntrySource.function("freeCopyDueDelayMs", gestureFiles)
        assertTrue(due.contains("FreeCopyPressPolicy.dueDelayMs("))
        assertTrue(due.contains("freeCopyGesture.downTime, freeCopyGesture.observedAtMs"))
    }

    fun everyEndOfTheSessionCancelsThroughTheDetectorAndFiringResetsIt() {
        val clear = HookEntrySource.function("clearCommentTouchSession", gestureFiles)
        assertTrue(clear.contains("freeCopyGesture.cancelSchedule("))
        assertFalse(clear.contains("removeCallbacks(commentLongPressRunnable)"))
        val runnable = HookEntrySource.property("freeCopyLongPressRunnable", gestureFiles)
        assertTrue(runnable.indexOf("freeCopyGesture.onFired()") in 0 until runnable.indexOf("val sessionId = freeCopyGesture.sessionId"))
        val totalDirectPosts = gestureFiles.sumOf { (_, raw) ->
            Regex("""postDelayed\(freeCopyLongPressRunnable""").findAll(raw).count()
        }
        assertEquals("the long-press runnable is only ever posted by the detector scheduler", 0, totalDirectPosts)
    }

    fun detectorMarksTheSessionOnlyAfterTheMessageIsReallyQueued() {
        val detector = SourceContract.read("hook/feature/FreeCopyGestureDetector.kt")
        val schedule = detector.after("fun schedule(").before("/** 取消")
        assertTrue(schedule.indexOf("if (!poster.post(") in 0 until schedule.indexOf("scheduledSessionId = sessionId"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentLongPressDispatchContractTest) -> Unit) {
            val s = CommentLongPressDispatchContractTest()
            block(s)
        }
        runIsolated { s -> s.downWithoutAnyRegisteredCommentRootSkipsTheAncestorWalkAfterCleaningTheOldSession() }
        runIsolated { s -> s.movePathStaysConstantTimeAndMoreButtonPassthroughIsKept() }
        runIsolated { s -> s.retargetingNeverReschedulesTheSameSessionDirectly() }
        runIsolated { s -> s.everyEndOfTheSessionCancelsThroughTheDetectorAndFiringResetsIt() }
        runIsolated { s -> s.detectorMarksTheSessionOnlyAfterTheMessageIsReallyQueued() }
    }
}
