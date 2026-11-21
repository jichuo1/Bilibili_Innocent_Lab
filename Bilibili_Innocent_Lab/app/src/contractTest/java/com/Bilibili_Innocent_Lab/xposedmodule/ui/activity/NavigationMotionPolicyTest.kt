package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMotionPolicyTest {
    private fun source(name: String): String =
        LceMotionSource.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt")

    @Test
    fun motionHostsWireLumenControllerAndBusyGuardsCleanly() {
        for (page in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val code = source(page)
            assertTrue(code.contains("motionController = ContainerMorphController("))
            assertTrue(code.contains("motionController.start(savedInstanceState == null)"))
            assertTrue(code.contains("motionController.onDestroy()"))
            assertFalse(code.contains("private fun animateMotionTo("))
        }
        val diagnostics = source("DiagnosticsActivity")
        assertTrue(diagnostics.contains("pendingScreenState = state"))
        assertTrue(diagnostics.contains("exportRunning || pickerOpen || activeDialog != null"))
        assertTrue(source("SettingsBackupActivity").contains("busy || pickerOpen || page == Page.WORKING"))
        assertTrue(source("SettingsBackupActivity").contains("motionHost.registerNavigationBack(this)"))
        assertTrue(diagnostics.contains("motionHost::registerNavigationBack"))
    }
}
