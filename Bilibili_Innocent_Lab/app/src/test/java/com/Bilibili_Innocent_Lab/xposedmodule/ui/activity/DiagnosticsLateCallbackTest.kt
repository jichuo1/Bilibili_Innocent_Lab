package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断页迟到回调不得杀进程（2026-09-23 真机 logcat）。
 *
 * `DiagnosticsViewModel.onCleared()` 关闭执行器后，异步送达的宿主诊断查询回调仍调用 `submit`，
 * 旧实现抛 `RejectedExecutionException`。
 */
class DiagnosticsLateCallbackTest {

    @Test
    fun shutdownSafeExecutorDropsLateSubmissionsAndShutdownIsIdempotent() {
        val executor = ShutdownSafeExecutor("test-diagnostics")
        val ran = CountDownLatch(1)
        assertTrue(executor.submit { ran.countDown() })
        assertTrue(ran.await(2, TimeUnit.SECONDS))

        executor.shutdownNow()
        assertTrue(executor.isShutdown)
        var lateRan = false
        assertFalse(executor.submit { lateRan = true })
        assertFalse(lateRan)

        val second = ShutdownSafeExecutor("test-diagnostics-idempotent")
        second.shutdownNow()
        second.shutdownNow()
        assertTrue(second.isShutdown)
        assertFalse(second.submit { })
    }
}
