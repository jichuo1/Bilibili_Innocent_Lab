package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 策略面板的安全边界：草稿不提前发布，五项共用一个导航入口和原有模态生命周期。 */
class PlayerBufferSettingsContractTest {
    fun allBufferSettingsShareTheAnchoredEntryAndUseTheTypedUiSnapshot() {
        // 入口调用点在增强分区里，已随分区外移到 *Sections.kt。
        val main = SettingsUiSource.settingsPage()
        val entry = SettingsUiSource.function("playerBufferSettingsRows")
        val keys = listOf("MODE", "SECONDS", "MEMORY_MB", "DISK_MB", "WIFI_ONLY")
        keys.forEach { key -> assertTrue(main.contains("FeaturePreferences.PLAYER_BUFFER_$key")) }
        assertTrue(entry.contains("setOnClickListener { showPlayerBufferDialog(anchor = it) }"))
        assertTrue(entry.contains(".forEach { key -> bindSettingDestination(this, key) }"))
        assertTrue(main.contains("playerBufferSettingsRows(this)"))
        keys.filter { it != "WIFI_ONLY" }.forEach { key ->
            assertTrue(main.contains("uiSettings.int(FeaturePreferences.PLAYER_BUFFER_$key)"))
        }
        assertTrue(main.contains("uiSettings.bool(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY)"))
    }

    fun failedReadbackKeepsTheDraftPanelOpenAndDoesNotUpdateTheActivityMirror() {
        val panel = SettingsUiSource.function("showPlayerBufferDialog")
        val failure = panel.indexOf("if (!saved)")
        val update = panel.indexOf("playerBufferMode = draftMode.value")
        assertTrue(failure >= 0 && update > failure)
        assertTrue(panel.substring(failure, update).contains("return"))
        assertFalse(panel.substring(failure, update).contains("dismissWithAnimation"))
        val readback = panel.indexOf("check(preferences.getBoolean(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY")
        assertTrue(readback >= 0 && readback < failure)
        assertTrue(panel.contains("presentModalDialog(dialog, container, anchor)"))
        assertTrue(panel.contains("dismissWithAnimation(dialog, container)"))
    }

    fun inactiveFieldsAreRetainedAndOnlyWholePrefetchEnablesNetworkAndDiskControls() {
        val panel = SettingsUiSource.function("showPlayerBufferDialog")
        assertTrue(panel.contains("if (!editor.isEnabled) return current"))
        assertTrue(panel.contains("seconds.isEnabled = draftMode == PlayerBufferMode.CUSTOM"))
        assertTrue(panel.contains("disk.isEnabled = draftMode == PlayerBufferMode.WHOLE"))
        assertTrue(panel.contains("wifiOnly.isEnabled = draftMode == PlayerBufferMode.WHOLE"))
        assertFalse(panel.contains("setWindowAnimations("))
        assertFalse(panel.contains("registerReceiver("))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerBufferSettingsContractTest) -> Unit) {
            val s = PlayerBufferSettingsContractTest()
            block(s)
        }
        runIsolated { s -> s.allBufferSettingsShareTheAnchoredEntryAndUseTheTypedUiSnapshot() }
        runIsolated { s -> s.failedReadbackKeepsTheDraftPanelOpenAndDoesNotUpdateTheActivityMirror() }
        runIsolated { s -> s.inactiveFieldsAreRetainedAndOnlyWholePrefetchEnablesNetworkAndDiskControls() }
    }
}
