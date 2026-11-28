package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.ContentRefreshCoalescer.Companion.ARMED
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.ContentRefreshCoalescer.Companion.NOW
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

class HostWorkCoalescingTest {
    @Test
    fun verifyRefreshCoalescingAndLatestWorkQueue() {
        // ContentRefreshCoalescer: first change and any change after quiet interval flush immediately
        run {
            val interval = 28L
            val coalescer = ContentRefreshCoalescer(interval)
            assertEquals(NOW, coalescer.onChange(1_000, coalesce = true))
            assertFalse(coalescer.armed)
            assertEquals(NOW, coalescer.onChange(1_000 + interval, coalesce = true))
            assertFalse(coalescer.armed)
        }

        // ContentRefreshCoalescer: burst arms exactly one trailing timer
        run {
            val interval = 28L
            val coalescer = ContentRefreshCoalescer(interval)
            assertEquals(NOW, coalescer.onChange(1_000, coalesce = true))
            assertEquals(interval, coalescer.onChange(1_011, coalesce = true))
            assertTrue(coalescer.armed)
            assertEquals(ARMED, coalescer.onChange(1_022, coalesce = true))
            assertEquals(ARMED, coalescer.onChange(1_033, coalesce = true))
        }

        // ContentRefreshCoalescer: timer waits for burst to go quiet and then flushes once
        run {
            val coalescer = ContentRefreshCoalescer(28L)
            coalescer.onChange(1_000, coalesce = true)
            coalescer.onChange(1_011, coalesce = true)
            coalescer.onChange(1_033, coalesce = true)
            assertEquals(22L, coalescer.onTimer(1_039))
            assertTrue(coalescer.armed)
            assertEquals(NOW, coalescer.onTimer(1_061))
            assertFalse(coalescer.armed)
        }

        // ContentRefreshCoalescer: without coalescing every change flushes and stale timer is disarmed
        run {
            val coalescer = ContentRefreshCoalescer(28L)
            coalescer.onChange(1_000, coalesce = true)
            coalescer.onChange(1_011, coalesce = true)
            assertTrue(coalescer.armed)
            assertEquals(NOW, coalescer.onChange(1_012, coalesce = false))
            assertFalse(coalescer.armed)
            assertEquals(NOW, coalescer.onChange(1_013, coalesce = false))
        }

        // ContentRefreshCoalescer: late timer does not double flush after isolated change already did
        run {
            val interval = 28L
            val coalescer = ContentRefreshCoalescer(interval)
            coalescer.onChange(1_000, coalesce = true)
            coalescer.onChange(1_011, coalesce = true)
            assertEquals(NOW, coalescer.onChange(1_011 + interval, coalesce = true))
            assertFalse(coalescer.armed)
        }

        // ContentRefreshCoalescer: cancel drops pending trailing flush
        run {
            val interval = 28L
            val coalescer = ContentRefreshCoalescer(interval)
            coalescer.onChange(1_000, coalesce = true)
            coalescer.onChange(1_011, coalesce = true)
            coalescer.cancel()
            assertFalse(coalescer.armed)
            assertEquals(interval, coalescer.onChange(1_012, coalesce = true))
        }

        // ContentRefreshCoalescer: interval must be positive
        try {
            ContentRefreshCoalescer(0L)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }

        // HostLatestWorkQueue: rapid changes prepare only active and latest inputs
        run {
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

        // HostLatestWorkQueue: queued changes never multiply executor entries and clear drops obsolete work
        run {
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

        // HostLatestWorkQueue: independent owners sharing one executor remain fair
        run {
            val executor = ArrayDeque<Runnable>()
            val first = HostLatestWorkQueue { executor.addLast(it) }
            val second = HostLatestWorkQueue { executor.addLast(it) }
            val order = mutableListOf<String>()
            first.submit { order += "first-active"; first.submit { order += "first-latest" } }
            second.submit { order += "second" }
            while (executor.isNotEmpty()) executor.removeFirst().run()
            assertEquals(listOf("first-active", "second", "first-latest"), order)
        }

        // HostLatestWorkQueue: failed work cannot strand a newer preparation
        run {
            val executor = ArrayDeque<Runnable>()
            val queue = HostLatestWorkQueue { executor.addLast(it) }
            val failure = IllegalStateException("preparation failed")
            var latest = false
            queue.submit { queue.submit { latest = true }; throw failure }
            try {
                executor.removeFirst().run()
                fail("failure was swallowed")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            executor.removeFirst().run()
            assertTrue(latest)
            assertTrue(executor.isEmpty())
        }

        // HostLatestWorkQueue: executor rejection releases ownership so the next request can proceed
        run {
            val executor = ArrayDeque<Runnable>()
            var rejecting = true
            val queue = HostLatestWorkQueue {
                if (rejecting) throw RejectedExecutionException()
                executor.addLast(it)
            }
            try {
                queue.submit { fail("rejected input executed") }
                fail("rejection was swallowed")
            } catch (_: RejectedExecutionException) {
            }
            rejecting = false
            var prepared = false
            queue.submit { prepared = true }
            executor.removeFirst().run()
            assertTrue(prepared)
        }
    }
}
