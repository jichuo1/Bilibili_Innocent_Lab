package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Test

class NPatchRuntimeProfileTest {
    @Test fun readsManagerAndEmbeddedWithoutDependingOnManagerInstallation() {
        val manager = NPatchRuntimeProfiles.parseJson("""{"useManager":true,"sigBypassLevel":2,"debuggable":false,"lspConfig":{"API_CODE":102,"VERSION_CODE":833}}""")
        assertEquals(NPatchMode.MANAGER, manager.mode)
        assertEquals(2, manager.signatureLevel)
        assertEquals(false, manager.debuggable)
        assertEquals(102, manager.loaderApi)
        assertEquals(833L, manager.loaderVersionCode)
        assertTrue(manager.metadataValid)
        assertEquals(NPatchMode.EMBEDDED, NPatchRuntimeProfiles.parseJson("""{"useManager":false}""").mode)
    }

    @Test fun absentMalformedAndWrongTypedModesStayUnknown() {
        for (raw in listOf("", "{", "[]", "{}", """{"useManager":"false"}""", """{"useManager":0}""")) {
            val profile = NPatchRuntimeProfiles.parseJson(raw)
            assertEquals(raw, NPatchMode.UNKNOWN, profile.mode)
            assertFalse(raw, profile.metadataValid)
        }
    }

    @Test fun corruptOptionalFieldsDoNotBecomeCapabilities() {
        val profile = NPatchRuntimeProfiles.parseJson("""{"useManager":true,"sigBypassLevel":"2","debuggable":1,"lspConfig":{"API_CODE":102.5,"VERSION_CODE":-1}}""")
        assertEquals(NPatchMode.MANAGER, profile.mode)
        assertNull(profile.signatureLevel)
        assertNull(profile.debuggable)
        assertNull(profile.loaderApi)
        assertNull(profile.loaderVersionCode)
        assertNull(NPatchRuntimeProfiles.parseJson("""{"useManager":false,"sigBypassLevel":6}""").signatureLevel)
        assertFalse(NPatchRuntimeProfiles.parseJson(" ".repeat(65537)).metadataValid)
    }

    @Test fun versionMismatchRequiresManagerModeAndTwoKnownVersions() {
        val patch = NPatchRuntimeProfile(mode = NPatchMode.MANAGER, loaderVersionCode = 833)
        assertTrue(NPatchEnvironment(patch, 846, "test").versionMismatch)
        assertFalse(NPatchEnvironment(patch, 833, "test").versionMismatch)
        assertFalse(NPatchEnvironment(patch, null, null).versionMismatch)
        assertFalse(NPatchEnvironment(patch.copy(mode = NPatchMode.EMBEDDED), 846, "test").versionMismatch)
        assertFalse(NPatchEnvironment(patch.copy(loaderVersionCode = null), 846, "test").versionMismatch)
    }
}
