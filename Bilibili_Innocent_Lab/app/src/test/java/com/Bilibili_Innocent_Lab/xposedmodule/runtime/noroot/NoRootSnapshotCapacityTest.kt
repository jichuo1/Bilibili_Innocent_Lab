package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test

class NoRootSnapshotCapacityTest {
    @Test fun validChineseRuleListsFitBothTheNoRootFileAndExistingAdmissionDocument() {
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

    @Test fun asciiAndChineseHaveTheSameTotalCharacterBoundary() {
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

    @Test fun singleSettingAndIdentityValidationRemainStrict() {
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
}
