package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断页 ViewModel 安全执行器与二级页面动效宿主接线契约。 */
class SecondaryActivityWiringContractTest {

    @Test
    fun diagnosticsViewModelRoutesAllSubmissionsThroughShutdownSafeExecutor() {
        val source = SourceContract.read("ui/activity/DiagnosticsViewModel.kt")
        val viewModel = source.after("internal class DiagnosticsViewModel")
        assertTrue("执行器必须是关闭后丢弃的那一种", viewModel.contains("private val worker = ShutdownSafeExecutor("))
        assertFalse("ViewModel 不得再直接持有裸 ExecutorService", viewModel.contains("Executors.newSingleThreadExecutor"))
        val refresh = viewModel.after("fun refresh(").before("private fun collectAsync(")
        assertTrue(
            "迟到的查询回调必须先检查是否已关闭",
            refresh.indexOf("if (worker.isShutdown) return@query") in 0 until refresh.indexOf("collectAsync(")
        )
        assertEquals("两个提交点（采集、导出）都走安全执行器", 2, Regex("""worker\.submit \{""").findAll(viewModel).count())
        val cleared = viewModel.after("override fun onCleared()")
        assertTrue(cleared.contains("worker.shutdownNow()"))
    }

    @Test
    fun secondaryActivityMotionHostsWireControllerSkinNotifyGuardsAndCleanup() {
        for (page in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val code = SourceContract.read("ui/activity/$page.kt")
            assertTrue(page, code.contains("motionController = ContainerMorphController("))
            assertTrue(page, code.contains("motionController.start(savedInstanceState == null)"))
            assertTrue(page, code.contains("motionController.onDestroy()"))
            assertTrue(page, code.contains("notifyPositionChanged = ::notifyPreparedSkinPositionChanged"))
            assertFalse(page, code.contains("private fun animateMotionTo("))
        }
        val diagnostics = SourceContract.read("ui/activity/DiagnosticsActivity.kt")
        assertTrue(diagnostics.contains("pendingScreenState = state"))
        assertTrue(diagnostics.contains("exportRunning || pickerOpen || activeDialog != null"))
        assertTrue(diagnostics.contains("motionHost::registerNavigationBack"))

        val backup = SourceContract.read("ui/activity/SettingsBackupActivity.kt")
        assertTrue(backup.contains("busy || pickerOpen || page == Page.WORKING"))
        assertTrue(backup.contains("motionHost.registerNavigationBack(this)"))
    }
}
