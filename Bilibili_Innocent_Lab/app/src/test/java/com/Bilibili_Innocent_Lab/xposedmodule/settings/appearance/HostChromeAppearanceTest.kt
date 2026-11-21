package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import org.junit.Assert.assertEquals
import org.junit.Test

class HostChromeAppearanceTest {
    fun prismKeepsItsIdentityOnOlderDevicesAndPreservesTheSoftBackend() {
        val prism = HostChromeAppearance.read("prism", "software", bottom = true, enabled = true)
        assertEquals(HostGlassMaterial.PRISM, prism.material)
        assertEquals(HostGlassRenderer.AUTO, prism.effectiveRenderer)
        assertEquals(prism, prism.compatible(27))
        assertEquals(prism, prism.compatible(32))
        assertEquals(HostGlassRenderer.SOFTWARE, prism.copy(material = HostGlassMaterial.SOFT).effectiveRenderer)
        for (id in listOf("host.top_bar.glass.material", "host.bottom_bar.glass.material")) {
            val spec = com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog.byId.getValue(id)
            org.junit.Assert.assertTrue(spec.accepts(com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue.Text("prism")))
        }
    }
    fun existingUsersAndOldBackupsKeepTheirSeparateBackends() {
        assertEquals(HostChromeAppearance(), HostChromeAppearance.read(null, null, bottom = false, enabled = true))
        assertEquals(HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE),
            HostChromeAppearance.read("soft", "legacy", bottom = true, enabled = true))
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeAppearanceTest) -> Unit) {
            val s = HostChromeAppearanceTest()
            block(s)
        }
        runIsolated { s -> s.prismKeepsItsIdentityOnOlderDevicesAndPreservesTheSoftBackend() }
        runIsolated { s -> s.existingUsersAndOldBackupsKeepTheirSeparateBackends() }
        runIsolated { s -> s.firstEnableUsesAutomaticButExplicitChoicesSurviveDisabling() }
        runIsolated { s -> s.liquidTemporarilyUsesGpuWithoutErasingThePreviousSoftwareChoice() }
        runIsolated { s -> s.barsShareOriginalBlurStrengthWithASeparateSoftwareFallbackRadius() }
    }
}
