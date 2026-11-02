package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Test

class LspatchRuntimeProfileTest {
    @Test fun modernManagerAndEmbeddedProfilesAreDistinctFromLegacyAndMalformedMarkers() {
        for (manager in listOf(false, true)) {
            val profile = LspatchRuntimeProfiles.parseJson(
                """{"useManager":$manager,"lspConfig":{"API_CODE":102,"VERSION_CODE":487}}""")
            assertTrue(profile.modern)
            assertEquals(if (manager) LspatchMode.MANAGER else LspatchMode.EMBEDDED, profile.mode)
            assertEquals(487L, profile.loaderVersion)
        }
        for (raw in listOf("", "{}", "[]", "x".repeat(65537),
            """{"useManager":false,"lspConfig":{"API_CODE":93,"VERSION_CODE":360}}""",
            """{"useManager":"false","lspConfig":{"API_CODE":102,"VERSION_CODE":487}}""",
            """{"useManager":false,"lspConfig":{"API_CODE":"102","VERSION_CODE":487}}""",
            """{"useManager":false,"lspConfig":{"API_CODE":102,"VERSION_CODE":-1}}""")) {
            assertFalse(raw.take(100), LspatchRuntimeProfiles.parseJson(raw).modern)
        }
    }
}
