package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 诊断页迟到回调不得杀进程（2026-09-23 真机 logcat）。
 *
 * `DiagnosticsViewModel.onCleared()` 关闭执行器后，异步送达的宿主诊断查询回调仍调用 `submit`，
 * 主线程抛 `RejectedExecutionException`：
 * `DiagnosticsViewModel.collectAsync(DiagnosticsViewModel.kt:74)` ←
 * `HostRuntimeDiagnosticsQueryClient.query$deliver`。快速进出诊断页即可触发。
 */
class DiagnosticsLateCallbackTest {

    fun submitAfterShutdownIsDroppedInsteadOfThrowing() {
        val executor = ShutdownSafeExecutor("test-diagnostics")
        val ran = CountDownLatch(1)
        assertTrue(executor.submit { ran.countDown() })
        assertTrue(ran.await(2, TimeUnit.SECONDS))

        executor.shutdownNow()
        assertTrue(executor.isShutdown)
        var lateRan = false
        // 旧实现在这里抛 RejectedExecutionException。
        assertFalse(executor.submit { lateRan = true })
        Thread.sleep(50)
        assertFalse(lateRan)
    }

    fun shutdownIsIdempotent() {
        val executor = ShutdownSafeExecutor("test-diagnostics")
        executor.shutdownNow()
        executor.shutdownNow()
        assertFalse(executor.submit { })
    }

    fun viewModelRoutesEverySubmitThroughTheShutdownSafeExecutor() {
        val relative = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/DiagnosticsViewModel.kt"
        val source = SourceContract.read(relative)
        val viewModel = source.after("internal class DiagnosticsViewModel")
        assertTrue("执行器必须是关闭后丢弃的那一种",
            viewModel.contains("private val worker = ShutdownSafeExecutor("))
        assertFalse("ViewModel 不得再直接持有裸 ExecutorService",
            viewModel.contains("Executors.newSingleThreadExecutor"))
        val refresh = viewModel.after("fun refresh(").before("private fun collectAsync(")
        assertTrue("迟到的查询回调必须先检查是否已关闭",
            refresh.indexOf("if (worker.isShutdown) return@query") in 0 until refresh.indexOf("collectAsync("))
        assertEquals("两个提交点（采集、导出）都走安全执行器", 2,
            Regex("""worker\.submit \{""").findAll(viewModel).count())
        val cleared = viewModel.after("override fun onCleared()")
        assertTrue(cleared.contains("worker.shutdownNow()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (DiagnosticsLateCallbackTest) -> Unit) {
            val s = DiagnosticsLateCallbackTest()
            block(s)
        }
        runIsolated { s -> s.submitAfterShutdownIsDroppedInsteadOfThrowing() }
        runIsolated { s -> s.shutdownIsIdempotent() }
        runIsolated { s -> s.viewModelRoutesEverySubmitThroughTheShutdownSafeExecutor() }
    }
}
