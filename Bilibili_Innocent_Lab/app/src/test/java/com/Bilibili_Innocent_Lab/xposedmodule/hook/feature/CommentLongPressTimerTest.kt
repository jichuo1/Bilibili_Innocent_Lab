package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentLongPressTimerTest {
    private class FakeHandler(var accepts: Boolean = true) : CommentLongPressTimer.Poster {
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

    private val runnable = Runnable {}
    private fun timer() = CommentLongPressTimer(runnable)

    @Test fun oneDownThroughManyDispatchLevelsPostsOnce() {
        val timer = timer(); val handler = FakeHandler()
        val levels = 12 // 评论根、容器、子 View……一次 DOWN 逐层经过
        val posted = (1..levels).count { timer.schedule(sessionId = 7, delayMillis = 400, handler) }
        assertEquals(1, posted)
        assertEquals("one pending message, not one per level", 1, handler.queue.size)
        assertEquals(1, handler.posts)
        assertEquals("only the first level touches the message queue", 1, handler.removes)
        assertEquals(400L, handler.queue.single().second)
        assertTrue(timer.isScheduled)
    }

    @Test fun legacyBehaviourWasOneRemovePlusOnePostPerLevel() {
        // 优化前：每层 removeCallbacks + postDelayed。这里复现旧调用模式，给出相同工作量下的对照数。
        val handler = FakeHandler()
        repeat(12) { handler.remove(runnable); handler.post(runnable, 400) }
        assertEquals(12, handler.removes); assertEquals(12, handler.posts)
        val timer = timer(); val optimized = FakeHandler()
        repeat(12) { timer.schedule(7, 400, optimized) }
        assertEquals(1, optimized.removes); assertEquals(1, optimized.posts)
    }

    @Test fun aNewGestureSessionSchedulesAgain() {
        val timer = timer(); val handler = FakeHandler()
        assertTrue(timer.schedule(1, 400, handler))
        timer.cancel(handler) // 新会话开始时旧会话被清理
        assertFalse(timer.isScheduled)
        assertTrue(handler.queue.isEmpty())
        assertTrue(timer.schedule(2, 400, handler))
        assertEquals(1, handler.queue.size)
    }

    @Test fun cancelRemovesThePendingMessageAndAllowsReschedulingTheSameSession() {
        val timer = timer(); val handler = FakeHandler()
        timer.schedule(3, 400, handler)
        timer.cancel(handler)
        assertTrue(handler.queue.isEmpty())
        assertTrue("same id after cancel posts again (it was cleared)", timer.schedule(3, 400, handler))
    }

    @Test fun postFailureDoesNotMarkTheSessionSoALaterLevelCanRetry() {
        val timer = timer(); val handler = FakeHandler(accepts = false)
        assertFalse(timer.schedule(5, 400, handler))
        assertFalse(timer.isScheduled)
        handler.accepts = true
        assertTrue("Handler became available: next dispatch level posts", timer.schedule(5, 400, handler))
        assertEquals(1, handler.queue.size)
    }

    @Test fun afterFiringTheSameSessionMayBeRescheduledByALaterLevel() {
        val timer = timer(); val handler = FakeHandler()
        timer.schedule(9, 400, handler)
        handler.queue.clear() // 消息出队并开始执行
        timer.onFired()
        assertFalse(timer.isScheduled)
        assertTrue(timer.schedule(9, 100, handler))
    }

    @Test fun sessionZeroIsNeverScheduledAndNegativeDelayIsClamped() {
        val timer = timer(); val handler = FakeHandler()
        assertFalse(timer.schedule(0, 400, handler))
        assertEquals(0, handler.posts)
        assertTrue(timer.schedule(1, -50, handler))
        assertEquals(0L, handler.queue.single().second)
    }

    @Test fun cancelWithoutAHandlerStillResetsTheState() {
        val timer = timer()
        timer.schedule(4, 400, FakeHandler())
        timer.cancel(null)
        assertFalse(timer.isScheduled)
    }
}
