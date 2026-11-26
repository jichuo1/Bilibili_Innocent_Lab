package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadedModuleArtifactTest {
    val folder = TemporaryFolder()

    fun recordsTheActualLoadedArtifactContent() {
        val file = folder.newFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LoadedModuleArtifact.capture(file.path)?.digest())
    }

    fun replacementAndRemovalInvalidateTheCapturedArtifact() {
        val file = folder.newFile().apply { writeText("abc") }
        val initial = requireNotNull(LoadedModuleArtifact.capture(file.path))
        file.writeText("abd")
        assertTrue(file.setLastModified(initial.modified + 2_000))
        assertNull(initial.digest())
        val replaced = requireNotNull(LoadedModuleArtifact.capture(file.path))
        file.writeText("longer")
        assertNull(replaced.digest())
        assertTrue(file.delete()); assertNull(replaced.digest())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LoadedModuleArtifactTest) -> Unit) {
            val s = LoadedModuleArtifactTest()
            s.folder.create()
            try {
                block(s)
            } finally {
                s.folder.delete()
            }
        }
        runIsolated { s -> s.recordsTheActualLoadedArtifactContent() }
        runIsolated { s -> s.replacementAndRemovalInvalidateTheCapturedArtifact() }
    
        LspatchRuntimeProfileCases().modernManagerAndEmbeddedProfilesAreDistinctFromLegacyAndMalformedMarkers()
        NoRootConfigSnapshotCodecCases().verifyAllCases()
        NoRootRestartFlushGuardCases().verifyAllCases()
        NoRootRuntimeDetectorCases().verifyAllCases()
        NoRootSnapshotCapacityCases().verifyAllCases()
    }
}

private class LspatchRuntimeProfileCases {
    fun modernManagerAndEmbeddedProfilesAreDistinctFromLegacyAndMalformedMarkers() {
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

private class NoRootConfigSnapshotCodecCases {

    fun `round trip preserves every supported value type`() {
        val source = enabledSnapshot(
            values = linkedMapOf(
                "boolean.value" to true,
                "integer.value" to 37,
                "long.value" to 9_090_300L,
                "string.value" to "emoji 🐶 与中文"
            )
        )

        val encoded = NoRootConfigSnapshotCodec.encode(source)
        val decoded = NoRootConfigSnapshotCodec.decode(
            encoded,
            expectedModulePackage = MODULE_PACKAGE,
            expectedModuleVersionCode = MODULE_VERSION
        )

        assertEquals(source, decoded)
        try {
            @Suppress("UNCHECKED_CAST")
            (decoded!!.values as MutableMap<String, Any>)["new.value"] = false
            fail("Decoded settings must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: target Hook code only receives an immutable startup snapshot.
        }
    }

    fun `disabled tombstone round trips only with an empty value map`() {
        val tombstone = enabledSnapshot(values = emptyMap()).copy(enabled = false)

        assertEquals(
            tombstone,
            NoRootConfigSnapshotCodec.decode(NoRootConfigSnapshotCodec.encode(tombstone))
        )

        val invalidEnabledEmpty = JSONObject(NoRootConfigSnapshotCodec.encode(tombstone))
            .put("enabled", true)
        assertNull(NoRootConfigSnapshotCodec.decode(invalidEnabledEmpty.toString()))
    }

    fun `decode rejects payload over the byte limit`() {
        val oversized = "x".repeat(NoRootConfigSnapshotCodec.MAX_PAYLOAD_BYTES + 1)

        assertNull(NoRootConfigSnapshotCodec.decode(oversized))
    }

    fun `decode rejects unknown setting type`() {
        val root = JSONObject(NoRootConfigSnapshotCodec.encode(enabledSnapshot()))
        root.getJSONObject("settings")
            .getJSONObject("feature.enabled")
            .put("type", "double")

        assertNull(NoRootConfigSnapshotCodec.decode(root.toString()))
    }

    fun `decode never coerces strings into typed values`() {
        val booleanRoot = JSONObject(NoRootConfigSnapshotCodec.encode(enabledSnapshot()))
        booleanRoot.getJSONObject("settings")
            .getJSONObject("feature.enabled")
            .put("value", "true")
        assertNull(NoRootConfigSnapshotCodec.decode(booleanRoot.toString()))

        val integerRoot = JSONObject(
            NoRootConfigSnapshotCodec.encode(
                enabledSnapshot(values = mapOf("feature.level" to 6))
            )
        )
        integerRoot.getJSONObject("settings")
            .getJSONObject("feature.level")
            .put("value", "6")
        assertNull(NoRootConfigSnapshotCodec.decode(integerRoot.toString()))
    }

    fun `decode rejects unsupported schema`() {
        val root = JSONObject(NoRootConfigSnapshotCodec.encode(enabledSnapshot()))
            .put("schemaVersion", NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION + 1)

        assertNull(NoRootConfigSnapshotCodec.decode(root.toString()))
    }

    fun `decode rejects a different module identity`() {
        val payload = NoRootConfigSnapshotCodec.encode(enabledSnapshot())

        assertNull(
            NoRootConfigSnapshotCodec.decode(
                payload,
                expectedModulePackage = "another.module",
                expectedModuleVersionCode = MODULE_VERSION
            )
        )
        assertNull(
            NoRootConfigSnapshotCodec.decode(
                payload,
                expectedModulePackage = MODULE_PACKAGE,
                expectedModuleVersionCode = MODULE_VERSION + 1L
            )
        )
    }

    private fun enabledSnapshot(
        values: Map<String, Any> = mapOf("feature.enabled" to true)
    ) = NoRootConfigSnapshot(
        schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
        catalogVersion = 3,
        modulePackage = MODULE_PACKAGE,
        moduleVersionCode = MODULE_VERSION,
        revision = 1_700_000_000_001L,
        adapterResetRevision = 2L,
        enabled = true,
        values = values
    )

    private companion object {
        const val MODULE_PACKAGE = "com.Bilibili_Innocent_Lab.xposedmodule"
        const val MODULE_VERSION = 8L
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootConfigSnapshotCodecCases) -> Unit) {
            val s = NoRootConfigSnapshotCodecCases()
            block(s)
        }
        runIsolated { s -> s.`round trip preserves every supported value type`() }
        runIsolated { s -> s.`disabled tombstone round trips only with an empty value map`() }
        runIsolated { s -> s.`decode rejects payload over the byte limit`() }
        runIsolated { s -> s.`decode rejects unknown setting type`() }
        runIsolated { s -> s.`decode never coerces strings into typed values`() }
        runIsolated { s -> s.`decode rejects unsupported schema`() }
        runIsolated { s -> s.`decode rejects a different module identity`() }
    }
}

private class NoRootRestartFlushGuardCases {
    fun directPublicationDoesNotNeedAManagerMirrorButStillRejectsStaleState() {
        assertTrue(NoRootRestartFlushGuard.accepts(true, true, true, true, 42, 42, false, true))
        assertTrue(NoRootRestartFlushGuard.accepts(true, false, false, false, 42, 42, false, true))
        assertFalse(NoRootRestartFlushGuard.accepts(false, true, true, true, 42, 42, false, true))
        assertFalse(NoRootRestartFlushGuard.accepts(true, false, true, false, 42, 42, false, true))
        assertFalse(NoRootRestartFlushGuard.accepts(true, true, true, true, 43, 42, false, true))
        assertFalse(NoRootRestartFlushGuard.accepts(true, true, true, true, 42, 42, false, false))
    }

    fun `accepts confirmed enabled revision that is still current`() {
        assertTrue(
            accepts(
                desiredEnabled = true,
                expectedEnabled = true,
                snapshotEnabled = true
            )
        )
    }

    fun `accepts confirmed tombstone revision that is still current`() {
        assertTrue(
            accepts(
                desiredEnabled = false,
                expectedEnabled = false,
                snapshotEnabled = false
            )
        )
    }

    fun `rejects enabled completion after user disables support`() {
        assertFalse(
            accepts(
                desiredEnabled = false,
                expectedEnabled = true,
                snapshotEnabled = false
            )
        )
    }

    fun `rejects tombstone completion after user enables support`() {
        assertFalse(
            accepts(
                desiredEnabled = true,
                expectedEnabled = false,
                snapshotEnabled = true
            )
        )
    }

    fun `rejects stale generation revision and unconfirmed writes`() {
        assertFalse(accepts(generationMatches = false))
        assertFalse(accepts(snapshotRevision = 43L))
        assertFalse(accepts(remoteWriteConfirmed = false))
    }

    private fun accepts(
        generationMatches: Boolean = true,
        desiredEnabled: Boolean = true,
        expectedEnabled: Boolean = true,
        snapshotEnabled: Boolean? = true,
        snapshotRevision: Long = 42L,
        expectedRevision: Long = 42L,
        remoteWriteConfirmed: Boolean = true
    ) = NoRootRestartFlushGuard.accepts(
        generationMatches = generationMatches,
        desiredEnabled = desiredEnabled,
        expectedEnabled = expectedEnabled,
        snapshotEnabled = snapshotEnabled,
        snapshotRevision = snapshotRevision,
        expectedRevision = expectedRevision,
        remoteWriteConfirmed = remoteWriteConfirmed
    )

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootRestartFlushGuardCases) -> Unit) {
            val s = NoRootRestartFlushGuardCases()
            block(s)
        }
        runIsolated { s -> s.directPublicationDoesNotNeedAManagerMirrorButStillRejectsStaleState() }
        runIsolated { s -> s.`accepts confirmed enabled revision that is still current`() }
        runIsolated { s -> s.`accepts confirmed tombstone revision that is still current`() }
        runIsolated { s -> s.`rejects enabled completion after user disables support`() }
        runIsolated { s -> s.`rejects tombstone completion after user enables support`() }
        runIsolated { s -> s.`rejects stale generation revision and unconfirmed writes`() }
    }
}

private class NoRootRuntimeDetectorCases {
    fun lspatchMarkerSelectsItsOwnModernTransportAndRootLoaderStillWins() {
        assertEquals(HookRuntimeMode.LSPATCH, NoRootRuntimeDetector.classify(
            false, "PathClassLoader", "org.matrix.vector.util.ModuleClassLoader", hasLspatchMarker = true))
        assertEquals(HookRuntimeMode.LSPATCH, NoRootRuntimeDetector.classify(
            false, "org.lsposed.lspatch.loader", "DexClassLoader"))
        assertEquals(HookRuntimeMode.STANDARD_XPOSED, NoRootRuntimeDetector.classify(
            false, "BootClassLoader", "org.lsposed.lspd.util.LspModuleClassLoader", hasLspatchMarker = true))
    }

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

    fun `known loaders skip package manager and profile ipc`() {
        val strictContext = object : android.content.ContextWrapper(null) {
            override fun getPackageName(): String = error("Unexpected packageName lookup")
            override fun getPackageManager(): android.content.pm.PackageManager =
                error("Unexpected PackageManager IPC")
        }
        val lsposedLoader = object : ClassLoader(javaClass.classLoader) {
            override fun toString(): String = "org.lsposed.lspd.util.LspModuleClassLoader[test]"
        }
        val lspatchLoader = object : ClassLoader(javaClass.classLoader) {
            override fun toString(): String = "org.lsposed.lspatch.loader.LspLoader[test]"
        }
        val npatchLoader = object : ClassLoader(javaClass.classLoader) {
            override fun toString(): String = "top.nkbe.npatch.loader.NLoader[test]"
        }
        assertEquals(HookRuntimeMode.STANDARD_XPOSED, NoRootRuntimeDetector.detect(strictContext, null, lsposedLoader))
        assertEquals(HookRuntimeMode.LSPATCH, NoRootRuntimeDetector.detect(strictContext, lspatchLoader, null))
        assertEquals(HookRuntimeMode.NPATCH_LEGACY, NoRootRuntimeDetector.detect(strictContext, npatchLoader, null))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootRuntimeDetectorCases) -> Unit) {
            val s = NoRootRuntimeDetectorCases()
            block(s)
        }
        runIsolated { s -> s.lspatchMarkerSelectsItsOwnModernTransportAndRootLoaderStillWins() }
        runIsolated { s -> s.`actual NPatch framework loader wins over patched manifest`() }
        runIsolated { s -> s.`LSPosed module loader wins when patched target is also in root scope`() }
        runIsolated { s -> s.`manifest marker is only the unknown-loader fallback`() }
        runIsolated { s -> s.`known loaders skip package manager and profile ipc`() }
    }
}

private class NoRootSnapshotCapacityCases {
    fun validChineseRuleListsFitBothTheNoRootFileAndExistingAdmissionDocument() {
        val rules = "中文规则\n".repeat(10_000)
        val remoteValues = RemoteHookConfigContract.resolveSourceValues(mapOf(
            FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS to rules,
            FeaturePreferences.COMMENT_FILTER_KEYWORDS to rules))
        val values = SettingsCatalog.specs.associate { it.storageKey to remoteValues.getValue(it.storageKey) }
        val snapshot = snapshot(values)
        val encoded = NoRootConfigSnapshotCodec.encode(snapshot)
        assertTrue("Fixture must exceed the old UTF-8 limit", encoded.toByteArray(Charsets.UTF_8).size > 256 * 1024)
        assertEquals(snapshot, NoRootConfigSnapshotCodec.decode(encoded))
        val document = RemoteHookConfigContract.encode(1, BuildConfig.VERSION_CODE.toLong(), true,
            snapshot.revision, UserTermsDecision.ACCEPTED, remoteValues)
        val direct = HostAdmissionContract.encode(document)
        assertTrue(direct.length <= HostAdmissionContract.MAX_DOCUMENT_CHARS)
        assertEquals(document, HostAdmissionContract.decode(direct))
        assertTrue(RemoteHookConfigContract.decode(document) is RemoteHookConfigDecodeResult.Ready)
    }

    fun asciiAndChineseHaveTheSameTotalCharacterBoundary() {
        for (unit in listOf("a", "中")) {
            val base = snapshot((0..3).associate { "rules$it" to unit.repeat(60_000) })
            // Fill a fifth field instead of exceeding the existing per-setting cap.
            val withFiller = base.copy(values = base.values + ("filler" to ""))
            val fillerLength = NoRootConfigSnapshotCodec.MAX_PAYLOAD_CHARS - NoRootConfigSnapshotCodec.encode(withFiller).length
            val boundary = withFiller.copy(values = withFiller.values + ("filler" to unit.repeat(fillerLength)))
            val exact = NoRootConfigSnapshotCodec.encode(boundary)
            assertEquals(NoRootConfigSnapshotCodec.MAX_PAYLOAD_CHARS, exact.length)
            assertEquals(boundary, NoRootConfigSnapshotCodec.decode(exact))
            assertNull(NoRootConfigSnapshotCodec.decode(exact + " "))
            assertThrows(IllegalArgumentException::class.java) {
                NoRootConfigSnapshotCodec.encode(boundary.copy(values = boundary.values + ("filler" to unit.repeat(fillerLength + 1))))
            }
        }
    }

    fun singleSettingAndIdentityValidationRemainStrict() {
        assertThrows(IllegalArgumentException::class.java) {
            NoRootConfigSnapshotCodec.encode(snapshot(mapOf("rules" to "中".repeat(NoRootConfigSnapshotCodec.MAX_STRING_LENGTH + 1))))
        }
        val encoded = NoRootConfigSnapshotCodec.encode(snapshot(mapOf("rules" to "中".repeat(50_000))))
        assertNull(NoRootConfigSnapshotCodec.decode(encoded, expectedModulePackage = "other.module"))
        assertNull(NoRootConfigSnapshotCodec.decode(encoded, expectedModuleVersionCode = BuildConfig.VERSION_CODE.toLong() + 1))
        assertNull(NoRootConfigSnapshotCodec.decode("中".repeat(NoRootConfigSnapshotCodec.MAX_PAYLOAD_CHARS + 1)))
    }

    private fun snapshot(values: Map<String, Any>) = NoRootConfigSnapshot(
        NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION, SettingsCatalog.CATALOG_VERSION,
        BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong(), 42, 0, true, values)

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootSnapshotCapacityCases) -> Unit) {
            val s = NoRootSnapshotCapacityCases()
            block(s)
        }
        runIsolated { s -> s.validChineseRuleListsFitBothTheNoRootFileAndExistingAdmissionDocument() }
        runIsolated { s -> s.asciiAndChineseHaveTheSameTotalCharacterBoundary() }
        runIsolated { s -> s.singleSettingAndIdentityValidationRemainStrict() }
    }
}
