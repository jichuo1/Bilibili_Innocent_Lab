package com.Bilibili_Innocent_Lab.xposedmodule.settings

import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class ModuleUiSettingsTest {

    @Test
    fun catalogDefaultsStoredValuesAndWrongTypesResolveSafely() {
        val empty = ModuleUiSettings.EMPTY
        SettingsCatalog.specs.forEach { spec ->
            when (val default = spec.defaultValue) {
                is SettingValue.Bool ->
                    assertEquals(spec.storageKey, default.value, empty.bool(spec.storageKey))
                is SettingValue.Text ->
                    assertEquals(spec.storageKey, default.value, empty.string(spec.storageKey))
                is SettingValue.IntValue ->
                    assertEquals(spec.storageKey, default.value, empty.int(spec.storageKey))
            }
        }

        val defaultOn = SettingsCatalog.specs.first { it.defaultValue == SettingValue.Bool(true) }
        val defaultOff = SettingsCatalog.specs.first { it.defaultValue == SettingValue.Bool(false) }
        val text = SettingsCatalog.specs.first { it.defaultValue is SettingValue.Text }
        val number = SettingsCatalog.specs.first { it.defaultValue is SettingValue.IntValue }
        val untouched = SettingsCatalog.specs.last { it.defaultValue == SettingValue.Bool(true) }
        val settings = readOf(
            defaultOn.storageKey to false,
            defaultOff.storageKey to true,
            text.storageKey to "kept",
            number.storageKey to 275,
            // 目录里没有的键出现在偏好里（旧版本残留）不能影响任何读取。
            "legacy.leftover.key" to true
        )
        assertEquals(false, settings.bool(defaultOn.storageKey))
        assertEquals(true, settings.bool(defaultOff.storageKey))
        assertEquals("kept", settings.string(text.storageKey))
        assertEquals(275, settings.int(number.storageKey))
        assertEquals(true, settings.bool(untouched.storageKey))

        // 类型不符必须落回默认值，而不是抛异常。
        val boolKey = defaultOn.storageKey
        val textKey = text.storageKey
        val intSpec = number
        val mistyped = readOf(
            boolKey to "not a boolean",
            textKey to 42,
            intSpec.storageKey to "not an int"
        )
        assertEquals(true, mistyped.bool(boolKey))
        assertEquals("", mistyped.string(textKey))
        assertEquals((intSpec.defaultValue as SettingValue.IntValue).value, mistyped.int(intSpec.storageKey))
    }

    @Test
    fun unavailableStoreUnknownKeysAndWithDeriveBehaveSafely() {
        assertSame(ModuleUiSettings.EMPTY, ModuleUiSettings.of { null })
        assertSame(ModuleUiSettings.EMPTY, ModuleUiSettings.of { emptyMap() })
        assertSame(ModuleUiSettings.EMPTY, ModuleUiSettings.of { error("preferences unavailable") })
        val defaultTrueKey = SettingsCatalog.specs.first { it.defaultValue == SettingValue.Bool(true) }.storageKey
        assertEquals(true, ModuleUiSettings.of { error("boom") }.bool(defaultTrueKey))

        // 未登记的键返回零值而不是抛异常：设置页不能因为漏登记一个键就白屏。
        val empty = ModuleUiSettings.EMPTY
        assertEquals(false, empty.bool("never.registered"))
        assertEquals("", empty.string("never.registered"))
        assertEquals(0, empty.int("never.registered"))

        val key = SettingsCatalog.specs.first { it.defaultValue == SettingValue.Bool(false) }.storageKey
        val other = SettingsCatalog.specs.last { it.defaultValue == SettingValue.Bool(false) }.storageKey
        val base = readOf(other to true)
        val derived = base.with(key, true)
        assertEquals(true, derived.bool(key))
        assertEquals(true, derived.bool(other))
        // 源快照不可变。
        assertEquals(false, base.bool(key))
    }

    private fun readOf(vararg pairs: Pair<String, Any?>): ModuleUiSettings =
        ModuleUiSettings.of { pairs.toMap() }.also { assertNotNull(it) }
}
