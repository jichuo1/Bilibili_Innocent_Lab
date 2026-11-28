package com.Bilibili_Innocent_Lab.xposedmodule.contract

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntrySource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.VersionAdapterSource
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KotlinSourceScanner] 与三套源码扫描器（[HookEntrySource] / [VersionAdapterSource] / [SettingsUiSource]）
 * 自身的自检门禁，防止切片退化、静默放宽或拆出目录逃逸。
 */
class SourceScannerContractTest {

    @Test
    fun `scanner slices braces templates expressions properties and rejects missing or ambiguous targets`() {
        val braceSource = """
            class Fixture {
                private fun target(count: Int = 1) {
                    val text = "not a } close brace {"
                    val templated = "nested ${'$'}{if (count > 0) "a } b" else "c { d"}"
                    // } neither is this {
                    /* } /* nested */ nor this { */
                    val raw = ${'"'}${'"'}${'"'} } still not { ${'"'}${'"'}${'"'}
                    val marker = count
                }
                private fun after() {
                    val other = 0
                }
            }
        """.trimIndent()
        val braceBody = KotlinSourceScanner.functions(braceSource, "target").single()
        assertTrue(braceBody.contains("val marker = count"))
        assertTrue(braceBody.contains("not a } close brace {"))
        assertFalse("must not run into the following declaration", braceBody.contains("val other = 0"))
        assertEquals(braceBody.count { it == '{' }, braceBody.count { it == '}' })

        val propSource = """
            private val targetRunnable: Runnable =
                HostThreadGuard.runnable("k") {
                    val inner = 1
                }
            private val afterProp = 2
        """.trimIndent()
        val prop = KotlinSourceScanner.properties(propSource, "targetRunnable").single()
        assertTrue(prop.contains("val inner = 1"))
        assertFalse(prop.contains("afterProp"))

        val lambdaDefaultSource = """
            private fun target(
                onDone: () -> Unit = {},
                style: Int = 0
            ) {
                val inside = onDone
            }
        """.trimIndent()
        val lambdaDefaultBody = KotlinSourceScanner.functions(lambdaDefaultSource, "target").single()
        assertTrue(lambdaDefaultBody.contains("val inside = onDone"))
        assertTrue(lambdaDefaultBody.trimEnd().endsWith("}"))

        val nestedExprSource = """
            private fun target(enabled: Boolean): String = getString(
                when {
                    enabled -> R.string.a
                    else -> R.string.b
                }
            )

            private fun after() = 0
        """.trimIndent()
        val nestedExprBody = KotlinSourceScanner.functions(nestedExprSource, "target").single()
        assertTrue(nestedExprBody.trimEnd().endsWith(")"))
        assertEquals(nestedExprBody.count { it == '(' }, nestedExprBody.count { it == ')' })
        assertFalse(nestedExprBody.contains("fun after"))

        val continuedExprSource = """
            private fun target(spec: Surface): Snapshot? =
                Store.read(this, spec.surface)

            private fun after() = 0
        """.trimIndent()
        val continuedExprBody = KotlinSourceScanner.functions(continuedExprSource, "target").single()
        assertTrue(continuedExprBody, continuedExprBody.contains("Store.read(this, spec.surface)"))
        assertFalse(continuedExprBody.contains("fun after"))

        val singleLineExprSource = "private fun target() = helper(1, 2)\nprivate fun after() = 0\n"
        assertEquals("fun target() = helper(1, 2)", KotlinSourceScanner.functions(singleLineExprSource, "target").single())

        val extension = "internal fun MainActivity.target() {\n    val moved = 1\n}\n"
        assertTrue(KotlinSourceScanner.functions(extension, "target").single().contains("val moved = 1"))
        val member = "    private fun target() {\n        val kept = 1\n    }\n"
        assertTrue(KotlinSourceScanner.functions(member, "target").single().contains("val kept = 1"))

        val localFunSource = """
            internal fun MainActivity.showSomething() {
                val dialog = Dialog(this)
                fun decide(enabled: Boolean) {
                    val nested = enabled
                }
                presentModalDialog(dialog, container)
            }
        """.trimIndent()
        val declared = KotlinSourceScanner.declaredFunctions(localFunSource, indent = 0)
        assertEquals(listOf("showSomething"), declared.map { it.first })
        assertTrue(declared.single().second.contains("presentModalDialog(dialog, container)"))

        val urlCommentSource = """
            private fun target() {
                val url = "https://example.com/a/b" // 真注释要抹掉
                val kept = 1
            }
        """.trimIndent()
        val strippedUrlCode = KotlinSourceScanner.code(urlCommentSource)
        assertEquals("length must stay aligned", urlCommentSource.length, strippedUrlCode.length)
        assertTrue(strippedUrlCode, strippedUrlCode.contains("\"https://example.com/a/b\""))
        assertFalse("the real comment must be gone", strippedUrlCode.contains("真注释"))
        assertTrue(KotlinSourceScanner.functions(strippedUrlCode, "target").single().contains("val kept = 1"))

        val dup = "fun dup() {\n}\nfun dup() {\n}\n"
        assertEquals(2, KotlinSourceScanner.functions(dup, "dup").size)
        val dupError = assertThrows(IllegalStateException::class.java) {
            KotlinSourceScanner.function(listOf("Dup.kt" to dup), "dup", "synthetic dup")
        }
        assertTrue(dupError.message.orEmpty().contains("matched 2 times"))

        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                HookEntrySource.function("thisHookFunctionDoesNotExistAnywhere")
            }.message.orEmpty().contains("not found")
        )
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                VersionAdapterSource.function("nonExistentAdapterHelper")
            }.message.orEmpty().contains("not found")
        )
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                VersionAdapterSource.function("quickLocate")
            }.message.orEmpty().contains("matched 2 times")
        )
        val settingsMissing = assertThrows(IllegalStateException::class.java) {
            SettingsUiSource.function("thisFunctionDoesNotExistAnywhere")
        }.message.orEmpty()
        assertTrue(settingsMissing, settingsMissing.contains("not found"))
    }

    @Test
    fun `real production slices stay bounded and comment stripping preserves declarations across all scanners`() {
        val finished = HookEntrySource.function("onAdaptFinished")
        val wholeHook = HookEntrySource.hookEntry()
        assertTrue(finished.contains("applyAdaptOutcome"))
        assertTrue("slice ${finished.length} should be much smaller than ${wholeHook.length}", finished.length < wholeHook.length / 10)
        assertTrue(finished.trimEnd().endsWith("}"))
        assertFalse(finished.contains("fun onSilentCacheReady"))

        val quick = VersionAdapterSource.function("quickLocate") { it.contains("plan: AdaptPlan") }
        val wholeAdapter = VersionAdapterSource.versionAdapter()
        assertTrue(quick.contains("quickLocatePlanned("))
        assertTrue("slice ${quick.length} should be much smaller than ${wholeAdapter.length}", quick.length < wholeAdapter.length / 8)
        assertFalse(quick.contains("EMPTY_PAUSE_POINTS"))

        val ensure = VersionAdapterSource.function("ensureAdapted") { it.contains("startupCache: StartupCacheSnapshot") }
        assertTrue(ensure.contains("auditDexSourceAsync("))
        assertFalse(ensure.contains("fun quickLocate("))

        val present = SettingsUiSource.function("presentSizedModalDialog")
        val wholeMain = SettingsUiSource.mainActivity()
        assertTrue(present.contains("dialog.show()"))
        assertTrue(present.contains("registerBackCallback()"))
        assertTrue("extracted ${present.length} of ${wholeMain.length} chars", present.length < wholeMain.length / 4)
        assertTrue(present.trimEnd().endsWith("}"))
        assertFalse(present.contains("fun createModalContainer"))

        var hookTotal = 0
        HookEntrySource.hookEntryFiles().forEach { (path, raw) ->
            val stripped = HookEntrySource.code(raw)
            val onRaw = HookEntrySource.nonNestedFunctions(raw)
            val onStripped = HookEntrySource.nonNestedFunctions(stripped)
            assertEquals(
                "$path: stripping comments must not change declared function list",
                onRaw.map { it.first },
                onStripped.map { it.first }
            )
            hookTotal += onRaw.size
        }
        assertTrue("scanned hook surface should contain plenty of functions: $hookTotal", hookTotal > 30)

        var adapterTotal = 0
        VersionAdapterSource.adapterFiles().forEach { (path, raw) ->
            val stripped = VersionAdapterSource.code(raw)
            val onRaw = VersionAdapterSource.nonNestedFunctions(raw)
            val onStripped = VersionAdapterSource.nonNestedFunctions(stripped)
            assertEquals(
                "$path: stripping comments must not change declared function list",
                onRaw.map { it.first },
                onStripped.map { it.first }
            )
            adapterTotal += onRaw.size
        }
        assertTrue("scanned adapter surface should contain plenty of functions: $adapterTotal", adapterTotal > 50)

        var settingsTotal = 0
        SettingsUiSource.settingsUiFiles().forEach { (name, raw) ->
            val stripped = SettingsUiSource.code(raw)
            listOf(0, 4).forEach { indent ->
                val onRaw = SettingsUiSource.declaredFunctions(raw, indent)
                val onStripped = SettingsUiSource.declaredFunctions(stripped, indent)
                assertEquals(
                    "$name (indent=$indent): stripping comments must not change the member set",
                    onRaw.map { it.first },
                    onStripped.map { it.first }
                )
                settingsTotal += onRaw.size
            }
        }
        assertTrue("the scanned surface should declare plenty of members: $settingsTotal", settingsTotal > 100)
    }

    @Test
    fun `scanned sets cover expected sources and reject unscanned feature adapter or activity extractions`() {
        val hookFiles = HookEntrySource.hookEntryFiles().map { it.first }
        assertTrue("hook/HookEntry.kt" in hookFiles)
        assertTrue("hook/feature/FeatureInstaller.kt" in hookFiles)
        assertTrue("hook/feature/CommentTopologyFeatureInstaller.kt" in hookFiles)
        assertEquals(
            "extracted hook files must live inside HookEntrySource's scanned roots",
            emptyList<String>(),
            HookEntrySource.unscannedFeatureExtractions()
        )

        val adapterPaths = VersionAdapterSource.scannedPaths
        assertTrue("hook/VersionAdapter.kt" in adapterPaths)
        assertTrue("hook/adapter/AdaptPlan.kt" in adapterPaths)
        assertTrue("hook/adapter/AdaptAssistGate.kt" in adapterPaths)
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
            assertTrue("missing split file in VersionAdapterSource: $splitPath", splitPath in adapterPaths)
        }
        assertFalse(adapterPaths.any { it.startsWith("hook/adapter/dex/") })
        assertEquals(
            "extracted adapter/locator files must stay under hook/adapter/*.kt or hook/adapter/locators/",
            emptyList<String>(),
            VersionAdapterSource.unscannedAdapterFiles()
        )

        assertTrue(SettingsUiSource.settingsUiFiles().map { it.first }.contains("MainActivity.kt"))
        assertTrue(SettingsUiSource.all().contains("class MainActivity"))
        assertEquals(
            "these files declare fun MainActivity.<name> but escape the extraction gates; " +
                "rename them to a SettingsUiSource.VOLUME_SUFFIXES suffix (or extend that list)",
            emptyList<String>(),
            SettingsUiSource.unscannedMainActivityExtensions()
        )
        val volumes = SettingsUiSource.volumeFileNames
        val dialogs = SettingsUiSource.dialogFileNames
        assertTrue("dialogs must stay inside the scanned volumes", volumes.containsAll(dialogs))
        assertTrue("dialogFileNames must keep its narrow meaning", dialogs.all { it.endsWith("Dialogs.kt") })
        assertEquals("the scanned set must not contain duplicates", volumes.distinct(), volumes)
        assertTrue("MainActivity is added separately, never as a volume", "MainActivity.kt" !in volumes)
    }
}
