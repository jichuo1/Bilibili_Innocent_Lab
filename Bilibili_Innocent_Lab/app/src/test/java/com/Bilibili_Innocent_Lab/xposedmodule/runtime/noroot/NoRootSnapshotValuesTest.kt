package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class NoRootSnapshotValuesTest {
    @Test fun wrongPreferenceTypesFallBackWithoutLosingTheRestOfTheDocument() {
        val raw = SettingsCatalog.specs.associate { spec ->
            spec.storageKey to when (spec.defaultValue) {
                is SettingValue.Bool -> "old boolean"
                is SettingValue.IntValue -> false
                is SettingValue.Text -> 42
            }
        }
        val actual = snapshot(raw)
        assertEquals(SettingsCatalog.specs.map { it.storageKey }.toSet(), actual.keys)
        assertEquals(snapshot(emptyMap()), actual)
    }

    @Test fun normalizationRetainsLegacyMaterialAndClampsContinuousValues() {
        val raw = mapOf(
            FeaturePreferences.HOST_CARD_ACRYLIC to true,
            FeaturePreferences.HOST_BACKGROUND_BLUR to 100,
            FeaturePreferences.HOST_BACKGROUND_SATURATION to -10,
            FeaturePreferences.HOST_BACKGROUND_PRESET to "unsupported"
        )
        val actual = snapshot(raw)
        assertEquals("acrylic", actual[FeaturePreferences.HOST_CARD_MATERIAL])
        assertEquals(60, actual[FeaturePreferences.HOST_BACKGROUND_BLUR])
        assertEquals(0, actual[FeaturePreferences.HOST_BACKGROUND_SATURATION])
        assertEquals(snapshot(emptyMap())[FeaturePreferences.HOST_BACKGROUND_PRESET],
            actual[FeaturePreferences.HOST_BACKGROUND_PRESET])
        assertEquals("original", snapshot(raw + (FeaturePreferences.HOST_CARD_MATERIAL to 42))[
            FeaturePreferences.HOST_CARD_MATERIAL])
    }

    @Test fun credentialsRuntimeMetadataAndUnknownKeysStayOutOfTheSnapshotFile() {
        val raw = mapOf(
            RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY to "test-only-key",
            RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to 123L,
            RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to 456L,
            "unrelated" to true
        )
        val actual = snapshot(raw)
        assertEquals(snapshot(emptyMap()), actual)
        assertTrue(raw.keys.none(actual::containsKey))
    }

    @Test fun publicationCapturesOnePreferenceMapWithoutTypedReadsOrWrites() {
        var reads = 0
        val preferences = preferences {
            check(++reads == 1) { "Snapshot mixed different settings batches" }
            mapOf(FeaturePreferences.HOST_BACKGROUND_BLUR to 20)
        }
        assertEquals(20, NoRootSupportStore.snapshotValues(preferences)[FeaturePreferences.HOST_BACKGROUND_BLUR])
        assertEquals(1, reads)
    }

    private fun snapshot(raw: Map<String, Any>) = NoRootSupportStore.snapshotValues(preferences { raw })

    private fun preferences(read: () -> Map<String, Any>): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> read()
                else -> error("Snapshot must not use ${method.name}")
            }
        } as SharedPreferences
}
