package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class InjectedSystemLocaleCacheTest {
    @Test
    fun `successful lookup is reused until system or module selection changes`() {
        val cache = InjectedSystemLocaleCache()
        var reads = 0
        assertEquals("zh-CN", cache.getOrLoad { reads++; "zh-CN" })
        assertEquals("zh-CN", cache.getOrLoad { reads++; "en" })
        assertEquals(1, reads)
        cache.invalidate()
        assertNull(cache.current)
        assertEquals("en", cache.getOrLoad { reads++; "en" })
        assertEquals(2, reads)
    }

    @Test
    fun `failed platform read is not cached and can recover without a language event`() {
        val cache = InjectedSystemLocaleCache()
        assertNull(cache.getOrLoad { null })
        assertNull(cache.current)
        assertEquals("zh-Hant", cache.getOrLoad { "zh-Hant" })
    }

    @Test
    fun `parallel readers share one successful platform read`() {
        val cache = InjectedSystemLocaleCache()
        val reads = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val readers = List(4) {
                pool.submit<String?> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    cache.getOrLoad { reads.incrementAndGet(); "en" }
                }
            }
            start.countDown()
            readers.forEach { assertEquals("en", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, reads.get())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `invalidation cannot be overwritten by an older in flight read`() {
        val cache = InjectedSystemLocaleCache()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidating = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val old = pool.submit<String?> {
                cache.getOrLoad {
                    reading.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    "en"
                }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val event = pool.submit {
                invalidating.countDown()
                cache.invalidate()
            }
            assertTrue(invalidating.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("en", old.get(5, TimeUnit.SECONDS))
            event.get(5, TimeUnit.SECONDS)
            assertNull(cache.current)
            assertEquals("zh-CN", cache.getOrLoad { "zh-CN" })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
