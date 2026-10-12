package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import org.junit.Assert.*
import org.junit.Test

class HostBackgroundConfigTest {
    @Test fun backgroundIsPublishedAfterTheClassicCommentCatalogVersions() {
        val background = SettingsCatalog.specs.filter { it.id.startsWith("host.background.") }
        assertEquals(setOf("host.background.preset", "host.background.blur", "host.background.saturation",
            "host.background.veil", "host.background.asset"), background.map { it.id }.toSet())
        assertTrue(background.all { it.introducedCatalogVersion == 49 })
        val capabilities = DiagnosticCapabilityCatalog.definitions.filter { it.id == "host_background" }
        assertEquals(listOf("host_background"), capabilities.map { it.id })
        assertTrue(capabilities.all { it.introducedCatalogVersion == 21 })
    }

    @Test fun malformedModeAndAssetNeverEnableAnArbitraryFile() {
        assertEquals(HostBackgroundPreset.OFF, HostBackgroundPreset.read("unknown"))
        listOf("../../secret", "content://other/image", "A".repeat(36), "").forEach {
            assertFalse(HostBackgroundConfig.validAsset(it))
            assertEquals("", HostBackgroundConfig(asset = it).normalized().asset)
        }
        assertTrue(HostBackgroundConfig.validAsset("4e9c1f80-2fb0-4d98-a899-cba11d7b7344"))
    }

    @Test fun hookAndBackupAgreeOnParameterBoundsAndDefaults() {
        val raw = HostBackgroundConfig(HostBackgroundPreset.CUSTOM, blur = 1000, saturation = -50, veil = 1000).normalized()
        assertEquals(60, raw.blur); assertEquals(0, raw.saturation); assertEquals(90, raw.veil)
        val default = HostBackgroundConfig()
        listOf(FeaturePreferences.HOST_BACKGROUND_BLUR to default.blur,
            FeaturePreferences.HOST_BACKGROUND_SATURATION to default.saturation,
            FeaturePreferences.HOST_BACKGROUND_VEIL to default.veil).forEach { (key, value) ->
            assertEquals(SettingValue.IntValue(value), SettingsCatalog.byStorageKey.getValue(key).defaultValue)
        }
        assertEquals(RestorePolicy.MANUAL, SettingsCatalog.byStorageKey.getValue(FeaturePreferences.HOST_BACKGROUND_ASSET).restorePolicy)
    }
}
