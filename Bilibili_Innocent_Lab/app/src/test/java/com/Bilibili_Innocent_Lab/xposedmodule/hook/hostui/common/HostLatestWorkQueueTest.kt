package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class HostLatestWorkQueueTest {
    fun `rapid changes prepare only active and latest inputs`() {
        val executor = ArrayDeque<Runnable>()
        val queue = HostLatestWorkQueue { executor.addLast(it) }
        val prepared = mutableListOf<Int>()
        queue.submit {
            prepared += 0
            repeat(5_000) { next -> queue.submit { prepared += next + 1 } }
            assertTrue(executor.isEmpty())
        }
        assertEquals(1, executor.size)
        executor.removeFirst().run()
        assertEquals(listOf(0), prepared)
        assertEquals(1, executor.size)
        executor.removeFirst().run()
        assertEquals(listOf(0, 5_000), prepared)
        assertTrue(executor.isEmpty())
    }

    fun `queued changes never multiply executor entries and clear drops obsolete work`() {
        val executor = ArrayDeque<Runnable>()
        val queue = HostLatestWorkQueue { executor.addLast(it) }
        repeat(100) { queue.submit { fail("obsolete input executed") } }
        assertEquals(1, executor.size)
        queue.clear()
        var prepared = false
        queue.submit { prepared = true }
        assertEquals(1, executor.size)
        executor.removeFirst().run()
        assertTrue(prepared)
        assertTrue(executor.isEmpty())
        queue.submit { fail("closed owner input executed") }
        queue.clear()
        executor.removeFirst().run()
        assertTrue(executor.isEmpty())
    }

    fun `independent owners sharing one executor remain fair`() {
        val executor = ArrayDeque<Runnable>()
        val first = HostLatestWorkQueue { executor.addLast(it) }
        val second = HostLatestWorkQueue { executor.addLast(it) }
        val order = mutableListOf<String>()
        first.submit { order += "first-active"; first.submit { order += "first-latest" } }
        second.submit { order += "second" }
        while (executor.isNotEmpty()) executor.removeFirst().run()
        assertEquals(listOf("first-active", "second", "first-latest"), order)
    }

    fun `failed work cannot strand a newer preparation`() {
        val executor = ArrayDeque<Runnable>()
        val queue = HostLatestWorkQueue { executor.addLast(it) }
        val failure = IllegalStateException("preparation failed")
        var latest = false
        queue.submit { queue.submit { latest = true }; throw failure }
        try { executor.removeFirst().run(); fail("failure was swallowed") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        executor.removeFirst().run()
        assertTrue(latest)
        assertTrue(executor.isEmpty())
    }

    fun `executor rejection releases ownership so the next request can proceed`() {
        val executor = ArrayDeque<Runnable>()
        var rejecting = true
        val queue = HostLatestWorkQueue {
            if (rejecting) throw RejectedExecutionException()
            executor.addLast(it)
        }
        try { queue.submit { fail("rejected input executed") }; fail("rejection was swallowed") }
        catch (_: RejectedExecutionException) { }
        rejecting = false
        var prepared = false
        queue.submit { prepared = true }
        executor.removeFirst().run()
        assertTrue(prepared)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostLatestWorkQueueTest) -> Unit) {
            val s = HostLatestWorkQueueTest()
            block(s)
        }
        runIsolated { s -> s.`rapid changes prepare only active and latest inputs`() }
        runIsolated { s -> s.`queued changes never multiply executor entries and clear drops obsolete work`() }
        runIsolated { s -> s.`independent owners sharing one executor remain fair`() }
        runIsolated { s -> s.`failed work cannot strand a newer preparation`() }
        runIsolated { s -> s.`executor rejection releases ownership so the next request can proceed`() }
    }
}
