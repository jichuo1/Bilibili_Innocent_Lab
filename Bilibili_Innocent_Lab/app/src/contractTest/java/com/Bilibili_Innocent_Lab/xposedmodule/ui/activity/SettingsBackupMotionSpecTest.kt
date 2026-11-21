package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupMotionSpecTest {
    @Test
    fun motionHostsNotifyPreparedSkinPositionChangedAndCleanUp() {
        for (activity in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val source = LceMotionSource.read(
                "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$activity.kt"
            )
            assertTrue(activity, source.contains("notifyPositionChanged = ::notifyPreparedSkinPositionChanged"))
            assertTrue(activity, source.contains("motionController.onDestroy()"))
        }
    }
}
