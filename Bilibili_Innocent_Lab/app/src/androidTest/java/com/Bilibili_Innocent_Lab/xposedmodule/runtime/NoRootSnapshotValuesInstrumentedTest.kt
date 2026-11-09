package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 使用真实但独立的 Android 偏好文件验证生产快照构造，不修改测试机设置及条款。 */
@RunWith(AndroidJUnit4::class)
class NoRootSnapshotValuesInstrumentedTest {
    @Test fun invalidTypedPreferencesProduceACompleteSnapshotWithoutMutatingUserValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "snapshot_types_${UUID.randomUUID()}"
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            SettingsCatalog.specs.forEach { spec ->
                when (spec.defaultValue) {
                    is SettingValue.Bool -> editor.putString(spec.storageKey, "old boolean")
                    is SettingValue.IntValue -> editor.putBoolean(spec.storageKey, false)
                    is SettingValue.Text -> editor.putInt(spec.storageKey, 42)
                }
            }
            editor.putString(RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY, "test-only-key")
            assertTrue(editor.commit())
            val original = prefs.all
            val actual = NoRootSupportStore.snapshotValues(prefs)
            val defaults = RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>())
            assertEquals(SettingsCatalog.specs.map { it.storageKey }.toSet(), actual.keys)
            actual.forEach { (key, value) -> assertEquals("key=$key", defaults.getValue(key), value) }
            assertEquals(original, prefs.all)
            assertFalse(actual.containsKey(RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY))

            assertTrue(prefs.edit().remove(FeaturePreferences.HOST_CARD_MATERIAL)
                .putBoolean(FeaturePreferences.HOST_CARD_ACRYLIC, true)
                .putInt(FeaturePreferences.HOST_BACKGROUND_BLUR, 100).commit())
            val migrated = NoRootSupportStore.snapshotValues(prefs)
            assertEquals("acrylic", migrated[FeaturePreferences.HOST_CARD_MATERIAL])
            assertEquals(60, migrated[FeaturePreferences.HOST_BACKGROUND_BLUR])
            assertFalse(prefs.contains(FeaturePreferences.HOST_CARD_MATERIAL))
            assertEquals(100, prefs.getInt(FeaturePreferences.HOST_BACKGROUND_BLUR, 0))
        } finally {
            assertTrue(context.deleteSharedPreferences(name))
        }
    }
}
