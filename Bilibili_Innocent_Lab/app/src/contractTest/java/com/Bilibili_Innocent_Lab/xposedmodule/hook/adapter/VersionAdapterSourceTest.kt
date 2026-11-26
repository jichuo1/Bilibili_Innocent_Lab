package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

/**
 * [VersionAdapterSource] 自身的自检门禁，防扫描范围缩水或拆出目录逃逸。
 */
class VersionAdapterSourceTest {

    fun `overloaded functions select the intended declaration precisely without neighbor coupling`() {
        val quick = VersionAdapterSource.function("quickLocate") { it.contains("plan: AdaptPlan") }
        val whole = VersionAdapterSource.versionAdapter()
        assertTrue(quick.contains("quickLocatePlanned("))
        assertTrue("slice ${quick.length} should be much smaller than ${whole.length}", quick.length < whole.length / 8)
        assertFalse(quick.contains("EMPTY_PAUSE_POINTS"))

        val ensure = VersionAdapterSource.function("ensureAdapted") { it.contains("startupCache: StartupCacheSnapshot") }
        assertTrue(ensure.contains("auditDexSourceAsync("))
        assertFalse(ensure.contains("fun quickLocate("))
    }

    fun `missing or ambiguous functions fail loudly`() {
        val missing = assertThrows(IllegalStateException::class.java) {
            VersionAdapterSource.function("nonExistentAdapterHelper")
        }
        assertTrue(missing.message.orEmpty().contains("not found"))
        val ambiguous = assertThrows(IllegalStateException::class.java) {
            VersionAdapterSource.function("quickLocate")
        }
        assertTrue(ambiguous.message.orEmpty().contains("matched 2 times"))
    }

    fun `scanned set covers VersionAdapter and adapter package without pulling in dex backend`() {
        val paths = VersionAdapterSource.scannedPaths
        assertTrue("hook/VersionAdapter.kt" in paths)
        assertTrue("hook/adapter/AdaptPlan.kt" in paths)
        assertTrue("hook/adapter/AdaptAssistGate.kt" in paths)
        for (splitPath in listOf(
            "hook/adapter/AdaptCacheStore.kt",
            "hook/adapter/AdaptResultCodec.kt",
            "hook/adapter/AdaptResultMerger.kt",
            "hook/adapter/AdaptResultValidator.kt",
            "hook/adapter/DexFeatureAdapter.kt",
            "hook/adapter/ProtocolFingerprintBuilder.kt",
            "hook/adapter/locators/CommentLocator.kt",
            "hook/adapter/locators/CommentTopologyLocator.kt",
            "hook/adapter/locators/HomeRecommendFeedLocator.kt",
            "hook/adapter/locators/LocatorReflectUtils.kt",
            "hook/adapter/locators/MediaQualityAndPlayerLocator.kt",
            "hook/adapter/locators/MineAndHomeUiLocator.kt",
            "hook/adapter/locators/VideoRelateLocator.kt"
        )) {
            assertTrue("missing split file in VersionAdapterSource: $splitPath", splitPath in paths)
        }
        assertFalse(paths.any { it.startsWith("hook/adapter/dex/") })
    }

    fun `stripping comments preserves non-nested function counts across every scanned file`() {
        var total = 0
        VersionAdapterSource.adapterFiles().forEach { (path, raw) ->
            val stripped = VersionAdapterSource.code(raw)
            val onRaw = VersionAdapterSource.nonNestedFunctions(raw)
            val onStripped = VersionAdapterSource.nonNestedFunctions(stripped)
            assertEquals(
                "$path: stripping comments must not change declared function list",
                onRaw.map { it.first },
                onStripped.map { it.first }
            )
            total += onRaw.size
        }
        assertTrue("scanned adapter surface should contain plenty of functions: $total", total > 50)
    }

    fun `no extracted adapter or locator files escape VersionAdapterSource`() {
        assertEquals(
            "extracted adapter/locator files must stay under hook/adapter/*.kt or hook/adapter/locators/",
            emptyList<String>(),
            VersionAdapterSource.unscannedAdapterFiles()
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (VersionAdapterSourceTest) -> Unit) {
            val s = VersionAdapterSourceTest()
            block(s)
        }
        runIsolated { s -> s.`overloaded functions select the intended declaration precisely without neighbor coupling`() }
        runIsolated { s -> s.`missing or ambiguous functions fail loudly`() }
        runIsolated { s -> s.`scanned set covers VersionAdapter and adapter package without pulling in dex backend`() }
        runIsolated { s -> s.`stripping comments preserves non-nested function counts across every scanned file`() }
        runIsolated { s -> s.`no extracted adapter or locator files escape VersionAdapterSource`() }
    }
}
