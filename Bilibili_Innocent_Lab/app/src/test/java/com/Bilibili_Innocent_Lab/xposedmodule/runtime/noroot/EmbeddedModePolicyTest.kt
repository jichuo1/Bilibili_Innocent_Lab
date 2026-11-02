package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedModePolicyTest {
    @Test fun disabledSupportBlocksBothEmbeddedLoadersEvenWithTheOtherManagerPresent() {
        for (nPatch in NPatchMode.entries) for (lspatch in LspatchMode.entries) {
            assertEquals("$nPatch / $lspatch",
                nPatch == NPatchMode.EMBEDDED || lspatch == LspatchMode.EMBEDDED,
                EmbeddedModePolicy.isBlocked(nPatch, lspatch, supported = false))
        }
    }

    @Test fun enablingSupportRestoresEveryExistingMode() {
        for (nPatch in NPatchMode.entries) for (lspatch in LspatchMode.entries) {
            assertFalse(EmbeddedModePolicy.isBlocked(nPatch, lspatch, supported = true))
        }
    }

    @Test fun buildFlagDrivesSupportPolicy() {
        assertEquals(BuildConfig.EMBEDDED_MODE_SUPPORTED, EmbeddedModePolicy.SUPPORTED)
    }

    @Test fun currentBuildDisablesEmbeddedSupportWithoutBlockingManagerOrUnpatchedHosts() {
        assertTrue(EmbeddedModePolicy.isBlocked(NPatchMode.EMBEDDED, LspatchMode.UNKNOWN))
        assertTrue(EmbeddedModePolicy.isBlocked(NPatchMode.UNKNOWN, LspatchMode.EMBEDDED))
        assertFalse(EmbeddedModePolicy.isBlocked(NPatchMode.MANAGER, LspatchMode.UNKNOWN))
        assertFalse(EmbeddedModePolicy.isBlocked(NPatchMode.UNKNOWN, LspatchMode.MANAGER))
        assertFalse(EmbeddedModePolicy.isBlocked(NPatchMode.UNKNOWN, LspatchMode.UNKNOWN))
    }
}
