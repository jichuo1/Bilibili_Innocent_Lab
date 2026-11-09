package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 性能报告 P2-04：评论手势的空根快路与单次长按调度。不得恢复被撤回的全局 hook 自解除或版本硬门控方案。 */
class CommentLongPressDispatchContractTest {
    private val source = SourceContract.read("hook/HookEntry.kt")

    @Test fun downWithoutAnyRegisteredCommentRootSkipsTheAncestorWalkAfterCleaningTheOldSession() {
        val down = source.after("// 新手势落在非简介 View 时终止旧简介会话。").before("root?.let { beginOrRetargetCommentTouch(v, it, ev) }")
        val cleanup = down.indexOf("clearCommentTouchSession(resetHandled = true)")
        val empty = down.indexOf("commentRootRefs.isEmpty()")
        val walk = down.indexOf("while (cur != null)")
        assertTrue(cleanup in 0 until empty)
        assertTrue("the emptiness check is made under the root lock", down.contains("synchronized(commentRootLock) { commentRootRefs.isEmpty() }"))
        assertTrue(empty in 0 until walk)
        assertTrue(down.substring(empty, walk).contains("return@before"))
    }

    @Test fun movePathStaysConstantTimeAndMoreButtonPassthroughIsKept() {
        assertTrue(source.contains("祖先链查找仅在 DOWN 执行；MOVE 热路径不加锁、不遍历。"))
        assertTrue(source.contains("commentMoreButtonPassthroughDownTime = ev.downTime"))
        assertTrue(source.contains("if (root != null &&\n                                                (isCommentMoreButton(v) || isCommentActionsContainer(v)))")
            || source.contains("isCommentMoreButton(v) || isCommentActionsContainer(v)"))
    }

    @Test fun retargetingNeverReschedulesTheSameSessionDirectly() {
        val retarget = source.after("private fun beginOrRetargetCommentTouch(").before("private fun activeCommentTouchTarget(")
        assertTrue(retarget.contains("commentLongPressTimer.schedule(activeCommentTouchSessionId, delay, commentLongPressPoster(handler))"))
        assertFalse("no per-level removeCallbacks/postDelayed of the comment runnable", retarget.contains("removeCallbacks("))
        assertFalse(retarget.contains("postDelayed("))
        // 目标与根引用仍逐层更新：最深层触摸 View 才是气泡锚点。
        assertTrue(retarget.contains("commentTouchTargetRef = java.lang.ref.WeakReference(v)"))
        assertTrue(retarget.contains("commentTouchRootRef = java.lang.ref.WeakReference(root)"))
        // 到期时刻仍由会话起点固定。
        assertTrue(retarget.contains("val dueAt = commentTouchObservedAtMs + 400L"))
    }

    @Test fun everyEndOfTheSessionCancelsThroughTheTimerAndFiringResetsIt() {
        val clear = source.after("private fun clearCommentTouchSession(").before("/** DOWN 在 dispatch 链上会依次经过祖先与子 View")
        assertTrue(clear.contains("commentLongPressTimer.cancel("))
        assertFalse(clear.contains("removeCallbacks(commentLongPressRunnable)"))
        val runnable = source.after("private val commentLongPressRunnable: Runnable =").before("private val commentLongPressTimer")
        assertTrue(runnable.indexOf("commentLongPressTimer.onFired()") in 0 until runnable.indexOf("val sessionId = activeCommentTouchSessionId"))
        assertEquals("the comment runnable is only ever posted by the timer", 0,
            Regex("""postDelayed\(commentLongPressRunnable""").findAll(source).count())
    }

    @Test fun timerMarksTheSessionOnlyAfterTheMessageIsReallyQueued() {
        val timer = SourceContract.read("hook/feature/CommentLongPressTimer.kt")
        val schedule = timer.after("fun schedule(").before("/** 取消")
        assertTrue(schedule.indexOf("if (!poster.post(") in 0 until schedule.indexOf("scheduledSessionId = sessionId"))
    }
}
