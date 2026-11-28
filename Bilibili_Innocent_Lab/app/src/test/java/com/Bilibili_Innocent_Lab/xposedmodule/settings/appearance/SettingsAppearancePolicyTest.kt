package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAppearancePolicyTest {

    @Test
    fun hostCardAndChromeAppearancePolicies() {
        HostCardMaterialCases().verifyAllCases()
        HostChromeAppearanceCases().verifyAllCases()
    }

    @Test
    fun materialColorSpecParsing() {
        MaterialColorSpecCases().verifyAllCases()
    }
}

private class HostCardMaterialCases {
    fun `new installations and malformed choices use original`() {
        assertEquals(HostCardMaterial.ORIGINAL, HostCardMaterial.resolve(emptyMap<String, Any?>()))
        listOf("unknown", "", true, 2).forEach { value ->
            assertEquals(HostCardMaterial.ORIGINAL, HostCardMaterial.read(value, true))
        }
    }

    fun `legacy acrylic remains enabled until a new choice is made`() {
        val legacy = mapOf(FeaturePreferences.HOST_CARD_ACRYLIC to true)
        assertEquals(HostCardMaterial.ACRYLIC, HostCardMaterial.resolve(legacy))
        HostCardMaterial.entries.forEach { material ->
            assertEquals(material, HostCardMaterial.resolve(legacy + (FeaturePreferences.HOST_CARD_MATERIAL to material.value)))
        }
    }

    fun `settings screen and host publication agree on legacy and explicit values`() {
        listOf(
            emptyMap(),
            mapOf(FeaturePreferences.HOST_CARD_ACRYLIC to true),
            mapOf(FeaturePreferences.HOST_CARD_ACRYLIC to false),
            mapOf(FeaturePreferences.HOST_CARD_MATERIAL to "mica"),
            mapOf(FeaturePreferences.HOST_CARD_ACRYLIC to true, FeaturePreferences.HOST_CARD_MATERIAL to "original"),
            mapOf(FeaturePreferences.HOST_CARD_ACRYLIC to true, FeaturePreferences.HOST_CARD_MATERIAL to "bad")
        ).forEach { values ->
            val expected = HostCardMaterial.resolve(values).value
            assertEquals(expected, ModuleUiSettings.of { values }.string(FeaturePreferences.HOST_CARD_MATERIAL))
            val host = RemoteHookConfigContract.resolveSourceValues(values)
            assertEquals(expected, host[FeaturePreferences.HOST_CARD_MATERIAL])
            assertFalse(host.containsKey(FeaturePreferences.HOST_CARD_ACRYLIC))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostCardMaterialCases) -> Unit) {
            val s = HostCardMaterialCases()
            block(s)
        }
        runIsolated { s -> s.`new installations and malformed choices use original`() }
        runIsolated { s -> s.`legacy acrylic remains enabled until a new choice is made`() }
        runIsolated { s -> s.`settings screen and host publication agree on legacy and explicit values`() }
    }
}

private class HostChromeAppearanceCases {
    fun prismKeepsItsIdentityOnOlderDevicesAndPreservesTheSoftBackend() {
        val prism = HostChromeAppearance.read("prism", "software", bottom = true, enabled = true)
        assertEquals(HostGlassMaterial.PRISM, prism.material)
        assertEquals(HostGlassRenderer.AUTO, prism.effectiveRenderer)
        assertEquals(prism, prism.compatible(27))
        assertEquals(prism, prism.compatible(32))
        assertEquals(HostGlassRenderer.SOFTWARE, prism.copy(material = HostGlassMaterial.SOFT).effectiveRenderer)
        for (id in listOf("host.top_bar.glass.material", "host.bottom_bar.glass.material")) {
            val spec = SettingsCatalog.byId.getValue(id)
            assertTrue(spec.accepts(SettingValue.Text("prism")))
        }
    }

    fun existingUsersAndOldBackupsKeepTheirSeparateBackends() {
        assertEquals(HostChromeAppearance(), HostChromeAppearance.read(null, null, bottom = false, enabled = true))
        assertEquals(
            HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE),
            HostChromeAppearance.read("soft", "legacy", bottom = true, enabled = true)
        )
    }

    fun firstEnableUsesAutomaticButExplicitChoicesSurviveDisabling() {
        assertEquals(HostGlassRenderer.AUTO, HostChromeAppearance.read(null, null, bottom = true, enabled = false).renderer)
        assertEquals(HostGlassRenderer.SOFTWARE, HostChromeAppearance.read("soft", "software", bottom = false, enabled = false).renderer)
        assertEquals(HostGlassRenderer.AUTO, HostChromeAppearance.read("soft", "auto", bottom = true, enabled = true).renderer)
    }

    fun liquidTemporarilyUsesGpuWithoutErasingThePreviousSoftwareChoice() {
        val original = HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE)
        val liquid = original.copy(material = HostGlassMaterial.LIQUID)
        assertEquals(HostGlassRenderer.AUTO, liquid.effectiveRenderer)
        assertEquals(original, liquid.copy(material = HostGlassMaterial.SOFT))
        assertEquals(original, liquid.compatible(32))
        assertEquals(liquid, liquid.compatible(33))
    }

    fun barsShareOriginalBlurStrengthWithASeparateSoftwareFallbackRadius() {
        val soft = HostSurfaceStyle.floating(false, 22f)
        assertEquals(LumenSurfaceMaterial.FROSTED, soft.material)
        assertEquals(17f, soft.sampling.blurRadiusDp, 0f)
        assertEquals(10f, soft.sampling.softwareBlurRadiusDp!!, 0f)
        val liquid = HostSurfaceStyle.floating(true, 32f, HostChromeAppearance(HostGlassMaterial.LIQUID, HostGlassRenderer.SOFTWARE))
        assertEquals(LumenSurfaceMaterial.LIQUID, liquid.material)
        assertEquals(LumenSurfaceBackend.AUTO, liquid.sampling.backend)
        assertEquals(soft.sampling.blurRadiusDp, liquid.sampling.blurRadiusDp, 0f)
        val software = HostSurfaceStyle.floating(false, 32f, HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE))
        assertEquals(LumenSurfaceBackend.SOFTWARE, software.sampling.backend)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeAppearanceCases) -> Unit) {
            val s = HostChromeAppearanceCases()
            block(s)
        }
        runIsolated { s -> s.prismKeepsItsIdentityOnOlderDevicesAndPreservesTheSoftBackend() }
        runIsolated { s -> s.existingUsersAndOldBackupsKeepTheirSeparateBackends() }
        runIsolated { s -> s.firstEnableUsesAutomaticButExplicitChoicesSurviveDisabling() }
        runIsolated { s -> s.liquidTemporarilyUsesGpuWithoutErasingThePreviousSoftwareChoice() }
        runIsolated { s -> s.barsShareOriginalBlurStrengthWithASeparateSoftwareFallbackRadius() }
    }
}

private class MaterialColorSpecCases {

    fun `storage values decode only supported specifications`() {
        assertEquals(
            MaterialColorSpec.SPEC_2021,
            MaterialColorSpec.fromStorageValue("2021")
        )
        assertEquals(
            MaterialColorSpec.SPEC_2025,
            MaterialColorSpec.fromStorageValue("2025")
        )
    }

    fun `missing or unsupported values fall back to spec 2021`() {
        assertEquals(MaterialColorSpec.SPEC_2021, MaterialColorSpec.DEFAULT)
        assertEquals(MaterialColorSpec.SPEC_2021, MaterialColorSpec.fromStorageValue(null))
        assertEquals(MaterialColorSpec.SPEC_2021, MaterialColorSpec.fromStorageValue("2026"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (MaterialColorSpecCases) -> Unit) {
            val s = MaterialColorSpecCases()
            block(s)
        }
        runIsolated { s -> s.`storage values decode only supported specifications`() }
        runIsolated { s -> s.`missing or unsupported values fall back to spec 2021`() }
    }
}
