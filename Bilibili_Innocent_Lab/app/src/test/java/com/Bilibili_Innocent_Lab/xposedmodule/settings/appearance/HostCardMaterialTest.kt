package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.junit.Assert.*
import org.junit.Test

class HostCardMaterialTest {
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
        listOf(emptyMap(), mapOf(FeaturePreferences.HOST_CARD_ACRYLIC to true),
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostCardMaterialTest) -> Unit) {
            val s = HostCardMaterialTest()
            block(s)
        }
        runIsolated { s -> s.`new installations and malformed choices use original`() }
        runIsolated { s -> s.`legacy acrylic remains enabled until a new choice is made`() }
        runIsolated { s -> s.`settings screen and host publication agree on legacy and explicit values`() }
    }
}
