package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertEquals
import org.junit.Test

class NoRootRuntimeDetectorTest {

    @Test
    fun `actual NPatch framework loader wins over patched manifest`() {
        assertEquals(
            HookRuntimeMode.NPATCH_LEGACY,
            NoRootRuntimeDetector.classify(
                hasNpatchMarker = true,
                frameworkFingerprint = "org.lsposed.npatch.loader",
                moduleFingerprint = "PathClassLoader"
            )
        )
    }

    @Test
    fun `LSPosed module loader wins when patched target is also in root scope`() {
        assertEquals(
            HookRuntimeMode.STANDARD_XPOSED,
            NoRootRuntimeDetector.classify(
                hasNpatchMarker = true,
                frameworkFingerprint = "BootClassLoader",
                moduleFingerprint = "org.lsposed.lspd.util.LspModuleClassLoader"
            )
        )
    }

    @Test
    fun `manifest marker is only the unknown-loader fallback`() {
        assertEquals(
            HookRuntimeMode.NPATCH_LEGACY,
            NoRootRuntimeDetector.classify(true, "PathClassLoader", "DexClassLoader")
        )
        assertEquals(
            HookRuntimeMode.STANDARD_XPOSED,
            NoRootRuntimeDetector.classify(false, "PathClassLoader", "DexClassLoader")
        )
    }
}
