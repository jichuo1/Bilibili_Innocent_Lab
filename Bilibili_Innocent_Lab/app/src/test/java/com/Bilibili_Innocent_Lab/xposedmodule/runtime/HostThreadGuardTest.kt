package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 防波堤的契约：**任何** Throwable 都不能穿过它回到宿主栈上。
 *
 * 这些用例锁住 2026-08-29 暂缓项 B1 的实现语义——Hook 回调由框架的 PROTECTIVE 兜底，
 * 而模块投递到宿主 Handler/IdleHandler/监听器/线程上的代码只有这一层保护。
 */
class HostThreadGuardTest {

    @Before
    fun setUp() = HostThreadGuard.resetForTests()

    @After
    fun tearDown() = HostThreadGuard.resetForTests()

    @Test
    fun `run swallows any throwable and keeps counting`() {
        HostThreadGuard.run("k") { throw IllegalStateException("boom") }
        HostThreadGuard.run("k") { throw NoSuchMethodError("linkage") }
        // Error 也必须拦住：宿主进程死于 StackOverflowError 和死于 NPE 没有区别。
        HostThreadGuard.run("k") { throw StackOverflowError() }
        assertEquals(3L, HostThreadGuard.failureCounts()["k"])
    }

    @Test
    fun `run does not intercept the success path`() {
        var ran = false
        HostThreadGuard.run("k") { ran = true }
        assertTrue(ran)
        assertTrue(HostThreadGuard.failureCounts().isEmpty())
    }

    @Test
    fun `call returns the fallback on failure and the value on success`() {
        assertEquals(7, HostThreadGuard.call("k", 7) { error("boom") })
        assertEquals(3, HostThreadGuard.call("k", 7) { 3 })
        assertNull(HostThreadGuard.call<String?>("n", null) { error("boom") })
        assertEquals(1L, HostThreadGuard.failureCounts()["k"])
    }

    @Test
    fun `runnable is reusable so removeCallbacks still matches the same instance`() {
        var calls = 0
        val runnable = HostThreadGuard.runnable("k") {
            calls++
            throw IllegalArgumentException("boom")
        }
        runnable.run()
        runnable.run()
        assertEquals(2, calls)
        assertEquals(2L, HostThreadGuard.failureCounts()["k"])
    }

    @Test
    fun `failures are counted per key and never deduplicated away`() {
        repeat(10) { HostThreadGuard.run("a") { error("boom") } }
        HostThreadGuard.run("b") { error("boom") }
        // 超过日志上限后仍然继续计数——重复崩溃不能变成静默。
        assertTrue(10L > HostThreadGuard.LOG_LIMIT_PER_KEY)
        assertEquals(10L, HostThreadGuard.failureCounts()["a"])
        assertEquals(1L, HostThreadGuard.failureCounts()["b"])
    }

    @Test
    fun `guarded thread body does not terminate on an escaping throwable`() {
        val finished = CountDownLatch(1)
        var uncaught: Throwable? = null
        val thread = Thread({
            HostThreadGuard.run("worker") { throw IllegalStateException("boom") }
            finished.countDown()
        }, "guard-test")
        thread.setUncaughtExceptionHandler { _, throwable -> uncaught = throwable }
        thread.start()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        thread.join(5_000)
        assertNull(uncaught)
        assertFalse(thread.isAlive)
    }

    @Test
    fun `counts accumulate across threads`() {
        val threads = (1..4).map {
            Thread { repeat(25) { HostThreadGuard.run("shared") { error("boom") } } }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(5_000) }
        assertEquals(100L, HostThreadGuard.failureCounts()["shared"])
    }
}
