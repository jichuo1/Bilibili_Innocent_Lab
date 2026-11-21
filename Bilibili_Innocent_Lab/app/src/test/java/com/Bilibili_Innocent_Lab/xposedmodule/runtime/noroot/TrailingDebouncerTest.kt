package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
        val debouncer = TrailingDebouncer(scheduler, delayMs = 20L)
        val runs = AtomicInteger()
        val last = AtomicReference<Int>()
        val done = CountDownLatch(1)
        repeat(6) { index ->
            debouncer.request {
                runs.incrementAndGet()
                last.set(index)
                done.countDown()
            }
        }
        assertTrue("debounced task should run", done.await(3, TimeUnit.SECONDS))
        Thread.sleep(60L) // 被合并掉的任务若没取消，会在这段时间里冒出来
        assertEquals(1, runs.get())
        assertEquals(5, last.get())
    }

    fun `requests keep pushing the run back until the burst stops`() {
        val debouncer = TrailingDebouncer(scheduler, delayMs = 35L)
        val runs = AtomicInteger()
        val started = System.nanoTime()
        val done = CountDownLatch(1)
        repeat(4) {
            debouncer.request { runs.incrementAndGet(); done.countDown() }
            Thread.sleep(15L) // 每次都比去抖窗口短，整串请求持续约 60ms
        }
        assertEquals("nothing may run while requests keep arriving", 0, runs.get())
        assertTrue(done.await(3, TimeUnit.SECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("ran after the burst, not 35ms after the first request ($elapsedMs ms)", elapsedMs >= 60L)
        assertEquals(1, runs.get())
    }

    fun `a request after the run is a new run and a running task is not interrupted`() {
        val debouncer = TrailingDebouncer(scheduler, delayMs = 15L)
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

    @org.junit.Test
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
}
