package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AgentScreenshotWorkerTest {
    private fun awaitFree(worker: AgentScreenshotWorker) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.busy && System.nanoTime() < deadline) Thread.sleep(1)
        assertFalse("worker should release its in-flight slot", worker.busy)
    }

    @Test fun acceptedJobRunsOffTheCallingThread() {
        val worker = AgentScreenshotWorker("BIL-AgentShot-test")
        val ran = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
        assertTrue(worker.trySubmit { thread.set(Thread.currentThread()); ran.countDown() })
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertNotEquals(Thread.currentThread(), thread.get())
        assertEquals("BIL-AgentShot-test", thread.get().name)
        awaitFree(worker)
    }

    @Test fun secondSubmissionWhileBusyIsRejectedAndNeverRunsOnTheCaller() {
        val worker = AgentScreenshotWorker("BIL-AgentShot-test")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(worker.trySubmit { started.countDown(); release.await(5, TimeUnit.SECONDS) })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val ranRejected = AtomicInteger()
        // 满载时拒绝：任务绝不会在提交者线程（主线程）上运行，资源仍归调用方释放。
        repeat(50) { assertFalse(worker.trySubmit { ranRejected.incrementAndGet() }) }
        release.countDown()
        awaitFree(worker)
        assertEquals(0, ranRejected.get())
        val ranAfter = CountDownLatch(1)
        assertTrue(worker.trySubmit { ranAfter.countDown() })
        assertTrue(ranAfter.await(5, TimeUnit.SECONDS))
        awaitFree(worker)
    }

    @Test fun failingJobStillReleasesTheSlot() {
        val worker = AgentScreenshotWorker("BIL-AgentShot-test")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        try {
            assertTrue(worker.trySubmit { throw IllegalStateException("encode failed") })
            awaitFree(worker)
            val ran = CountDownLatch(1)
            assertTrue(worker.trySubmit { ran.countDown() })
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            awaitFree(worker)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun inFlightNeverExceedsOneUnderContention() {
        val worker = AgentScreenshotWorker("BIL-AgentShot-test")
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val accepted = AtomicInteger()
        val completed = AtomicInteger()
        val producers = List(4) {
            Thread {
                repeat(300) {
                    val taken = worker.trySubmit {
                        peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                        Thread.sleep(0, 200_000)
                        running.decrementAndGet()
                        completed.incrementAndGet()
                    }
                    if (taken) accepted.incrementAndGet()
                    Thread.yield()
                }
            }
        }
        producers.forEach(Thread::start)
        producers.forEach { it.join(10_000) }
        awaitFree(worker)
        assertEquals(1, peak.get())
        assertTrue(accepted.get() > 0)
        assertEquals(accepted.get(), completed.get())
    }

    @Test fun fullFrameCropIsTheWholeBitmap() {
        val region = AgentScreenshotGeometry.cropRegion(0, 0, 1080, 2400, 0, 0, 1080, 2400, 1080, 2400)
        assertArrayEquals(intArrayOf(0, 0, 1080, 2400), region)
    }

    @Test fun cropScalesFromWindowCoordinatesToScreenshotPixels() {
        // 窗口 (100,200)-(1180,2600)，截图分辨率是窗口的一半。
        val region = AgentScreenshotGeometry.cropRegion(100, 400, 1180, 2000, 100, 200, 1080, 2400, 540, 1200)
        assertArrayEquals(intArrayOf(0, 100, 540, 800), region)
    }

    @Test fun cropKeepsAtLeastOnePixelAndTruncatesLikeTheLegacyFormula() {
        val empty = AgentScreenshotGeometry.cropRegion(10, 10, 10, 10, 0, 0, 1000, 1000, 100, 100)
        assertEquals(1, empty[2])
        assertEquals(1, empty[3])
        // (1*5/7)=0, (3*5/7)=2 -> 宽 2
        assertArrayEquals(intArrayOf(0, 0, 2, 2), AgentScreenshotGeometry.cropRegion(1, 1, 3, 3, 0, 0, 7, 7, 5, 5))
    }

    @Test fun scalingNeverUpscalesAndCapsTheLongSideAt960() {
        assertArrayEquals(intArrayOf(432, 960), AgentScreenshotGeometry.scaledSize(1080, 2400))
        assertArrayEquals(intArrayOf(960, 432), AgentScreenshotGeometry.scaledSize(2400, 1080))
        assertArrayEquals(intArrayOf(500, 300), AgentScreenshotGeometry.scaledSize(500, 300))
        assertArrayEquals(intArrayOf(960, 960), AgentScreenshotGeometry.scaledSize(960, 960))
        assertArrayEquals(intArrayOf(1, 960), AgentScreenshotGeometry.scaledSize(1, 5000))
    }

    @Test fun limitsMatchTheHistoricalPolicy() {
        assertEquals(8_500_000L, AgentScreenshotGeometry.MAX_SOURCE_PIXELS)
        assertEquals(130_000, AgentScreenshotGeometry.MAX_ENCODED_BYTES)
        assertEquals(960f, AgentScreenshotGeometry.MAX_SIDE, 0f)
    }
}
