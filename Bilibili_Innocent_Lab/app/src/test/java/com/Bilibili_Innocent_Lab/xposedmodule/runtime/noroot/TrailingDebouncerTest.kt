package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import java.util.PriorityQueue
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailingDebouncerTest {
    private lateinit var scheduler: ScheduledThreadPoolExecutor

    fun setUp() {
        scheduler = ScheduledThreadPoolExecutor(1)
    }

    fun tearDown() {
        scheduler.shutdownNow()
    }

    fun `a burst collapses into one run of the last task`() {
        val virtual = VirtualScheduledExecutor()
        val debouncer = TrailingDebouncer(virtual, delayMs = 20L)
        val runs = AtomicInteger()
        val last = AtomicReference<Int>()
        repeat(6) { index ->
            assertTrue(debouncer.request {
                runs.incrementAndGet()
                last.set(index)
            })
        }
        assertEquals(0, runs.get())
        virtual.advanceBy(19L)
        assertEquals(0, runs.get())
        virtual.advanceBy(1L)
        assertEquals(1, runs.get())
        assertEquals(5, last.get())
        virtual.advanceBy(100L) // 被合并掉的任务若没取消，会在这段时间里冒出来
        assertEquals(1, runs.get())
    }

    fun `requests keep pushing the run back until the burst stops`() {
        val virtual = VirtualScheduledExecutor()
        val debouncer = TrailingDebouncer(virtual, delayMs = 35L)
        val runs = AtomicInteger()
        repeat(4) {
            assertTrue(debouncer.request { runs.incrementAndGet() })
            virtual.advanceBy(15L) // 每次都比去抖窗口短，整串请求持续 60ms
        }
        assertEquals("nothing may run while requests keep arriving", 0, runs.get())
        virtual.advanceBy(19L)
        assertEquals(0, runs.get())
        virtual.advanceBy(1L)
        assertTrue("ran after the burst, not 35ms after the first request (${virtual.nowMs} ms)", virtual.nowMs >= 60L)
        assertEquals(1, runs.get())
    }

    fun `a request after the run is a new run and a running task is not interrupted`() {
        val debouncer = TrailingDebouncer(scheduler, delayMs = 1L)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFinished = AtomicInteger()
        val secondRan = CountDownLatch(1)
        debouncer.request {
            firstStarted.countDown()
            releaseFirst.await(3, TimeUnit.SECONDS)
            firstFinished.incrementAndGet()
        }
        assertTrue(firstStarted.await(3, TimeUnit.SECONDS))
        // 首个任务正在执行时又来了新请求：不能打断它，且新请求最终要单独再跑一次。
        assertTrue(debouncer.request { secondRan.countDown() })
        releaseFirst.countDown()
        assertTrue("second request must still run", secondRan.await(3, TimeUnit.SECONDS))
        assertEquals("the running task finished, was not cancelled", 1, firstFinished.get())
    }

    fun `a shut down scheduler reports false instead of throwing`() {
        val debouncer = TrailingDebouncer(scheduler, delayMs = 10L)
        scheduler.shutdownNow()
        assertFalse(debouncer.request { error("must not run") })
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (TrailingDebouncerTest) -> Unit) {
            val s = TrailingDebouncerTest()
            s.setUp()
            try {
                block(s)
            } finally {
                s.tearDown()
            }
        }
        runIsolated { s -> s.`a burst collapses into one run of the last task`() }
        runIsolated { s -> s.`requests keep pushing the run back until the burst stops`() }
        runIsolated { s -> s.`a request after the run is a new run and a running task is not interrupted`() }
        runIsolated { s -> s.`a shut down scheduler reports false instead of throwing`() }
    }

    private class VirtualScheduledExecutor : AbstractExecutorService(), ScheduledExecutorService {
        var nowMs: Long = 0L
            private set
        private var nextSeq: Long = 0L
        private var shutdownFlag = false
        private val queue = PriorityQueue<ScheduledEntry<*>>()

        private inner class ScheduledEntry<V>(
            val triggerAtMs: Long,
            val seq: Long,
            callable: Callable<V>
        ) : FutureTask<V>(callable), ScheduledFuture<V> {
            override fun getDelay(unit: TimeUnit): Long =
                unit.convert((triggerAtMs - nowMs).coerceAtLeast(0L), TimeUnit.MILLISECONDS)

            override fun compareTo(other: Delayed): Int {
                val o = other as? ScheduledEntry<*> ?: return getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
                val cmp = triggerAtMs.compareTo(o.triggerAtMs)
                return if (cmp != 0) cmp else seq.compareTo(o.seq)
            }

            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                val cancelled = super.cancel(mayInterruptIfRunning)
                if (cancelled) queue.remove(this)
                return cancelled
            }
        }

        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
            while (true) {
                val head = queue.peek() ?: break
                if (head.isCancelled) {
                    queue.poll()
                    continue
                }
                if (head.triggerAtMs > nowMs) break
                queue.poll()
                head.run()
            }
        }

        override fun shutdown() { shutdownFlag = true }
        override fun shutdownNow(): MutableList<Runnable> {
            shutdownFlag = true
            val remaining = queue.filterNot { it.isCancelled }.toMutableList<Runnable>()
            queue.clear()
            return remaining
        }
        override fun isShutdown(): Boolean = shutdownFlag
        override fun isTerminated(): Boolean = shutdownFlag && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
        override fun execute(command: Runnable) {
            if (shutdownFlag) throw RejectedExecutionException("shutdown")
            command.run()
        }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (shutdownFlag) throw RejectedExecutionException("shutdown")
            val triggerAt = nowMs + unit.toMillis(delay.coerceAtLeast(0L))
            val entry = ScheduledEntry(triggerAt, nextSeq++, Callable<Any?> { command.run(); null })
            queue.add(entry)
            return entry
        }
        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
            if (shutdownFlag) throw RejectedExecutionException("shutdown")
            val triggerAt = nowMs + unit.toMillis(delay.coerceAtLeast(0L))
            val entry = ScheduledEntry(triggerAt, nextSeq++, callable)
            queue.add(entry)
            return entry
        }
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
    }
}
