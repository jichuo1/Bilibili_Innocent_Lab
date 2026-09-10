package com.Bilibili_Innocent_Lab.xposedmodule.settings

import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleUiSettingsTest {

    @Test fun `every catalog default is reachable through the typed accessors`() {
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
    }

    @Test fun `stored values win over catalog defaults and untouched keys keep theirs`() {
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
    }

    /** 与被替换的 115 段样板逐条对齐：类型不符必须落回默认值，而不是抛异常。 */
    @Test fun `a wrong stored type falls back to the catalog default instead of throwing`() {
        val boolKey = SettingsCatalog.specs.first { it.defaultValue == SettingValue.Bool(true) }.storageKey
        val textKey = SettingsCatalog.specs.first { it.defaultValue is SettingValue.Text }.storageKey
        val intSpec = SettingsCatalog.specs.first { it.defaultValue is SettingValue.IntValue }
        val settings = readOf(
            boolKey to "not a boolean",
            textKey to 42,
            intSpec.storageKey to "not an int"
        )
        assertEquals(true, settings.bool(boolKey))
        assertEquals("", settings.string(textKey))
        assertEquals((intSpec.defaultValue as SettingValue.IntValue).value, settings.int(intSpec.storageKey))
    }

    @Test fun `an unavailable or unreadable preference store degrades to all defaults`() {
        assertSame(ModuleUiSettings.EMPTY, ModuleUiSettings.of { null })
        assertSame(ModuleUiSettings.EMPTY, ModuleUiSettings.of { emptyMap() })
        assertSame(ModuleUiSettings.EMPTY, ModuleUiSettings.of { error("preferences unavailable") })
        val key = SettingsCatalog.specs.first { it.defaultValue == SettingValue.Bool(true) }.storageKey
        assertEquals(true, ModuleUiSettings.of { error("boom") }.bool(key))
    }

    /** 未登记的键返回零值而不是抛异常：设置页不能因为漏登记一个键就白屏。 */
    @Test fun `an unknown key yields the zero value of its accessor`() {
        val settings = ModuleUiSettings.EMPTY
        assertEquals(false, settings.bool("never.registered"))
        assertEquals("", settings.string("never.registered"))
        assertEquals(0, settings.int("never.registered"))
    }

    @Test fun `with derives a single key without rereading or mutating the source`() {
        val key = SettingsCatalog.specs.first { it.defaultValue == SettingValue.Bool(false) }.storageKey
        val other = SettingsCatalog.specs.last { it.defaultValue == SettingValue.Bool(false) }.storageKey
        val base = readOf(other to true)
        val derived = base.with(key, true)
        assertEquals(true, derived.bool(key))
        assertEquals(true, derived.bool(other))
        // 源快照不可变。
        assertEquals(false, base.bool(key))
    }

    /**
     * 迁移护栏：设置页读的每一个键都必须能在 [SettingsCatalog] 里查到。
     *
     * 这条是为了那次一口气替换 115 个偏好读取点而写的。键都是 `const val` 引用，
     * 拼错是编译错误；但**引用错常量**编译期发现不了，而未登记的键会静默变成零值。
     * 这里直接扫源码把 `uiSettings.bool(X)` 里的 X 解析成字符串再查目录，
     * 所以新增开关忘了登记目录会在门禁里红，而不是到真机上才发现开关"点了没反应"。
     */
    @Test fun `every preference key the settings page reads is registered in the catalog`() {
        val constants = constantValues()
        assertTrue("constant table looks empty: ${constants.size}", constants.size > 100)
        val main = sourceOf("ui/activity/MainActivity.kt")
        val used = Regex("""\buiSettings\.(bool|string|int)\(\s*([A-Za-z_][\w.]*)\s*\)""")
            .findAll(main)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        // 迁移完成后这里应该有上百处；数量掉下来说明有人把快照读法改回了逐键 getBoolean。
        assertTrue("uiSettings.* call sites: ${used.size}", used.size >= 100)
        val unresolved = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        val typeMismatch = mutableListOf<String>()
        used.forEach { (accessor, symbol) ->
            // 局部变量（循环里的动态 key）不在常量表里，按设计跳过。
            val key = constants[symbol] ?: run {
                if (symbol.contains('.')) unresolved += symbol
                return@forEach
            }
            val spec = SettingsCatalog.byStorageKey[key]
            if (spec == null) {
                unregistered += "$symbol ($key)"
                return@forEach
            }
            val expected = when (spec.defaultValue) {
                is SettingValue.Bool -> "bool"
                is SettingValue.Text -> "string"
                is SettingValue.IntValue -> "int"
            }
            if (expected != accessor) typeMismatch += "$symbol: read as $accessor, catalog is $expected"
        }
        assertEquals("unresolved constants", emptyList<String>(), unresolved)
        assertEquals("keys missing from SettingsCatalog", emptyList<String>(), unregistered)
        assertEquals("accessor/catalog type mismatch", emptyList<String>(), typeMismatch)
    }

    /** `FeaturePreferences.X` 这类限定名 → 实际存储键，从源码里解析，避免在测试里抄一份。 */
    private fun constantValues(): Map<String, String> {
        val files = mapOf(
            "FeaturePreferences" to "hook/feature/FeaturePreferences.kt",
            "HookEntry" to "hook/HookEntry.kt",
            "MaterialColorSpecStore" to "settings/appearance/MaterialColorSpecStore.kt",
            "ModalBackdropBlurStore" to "settings/appearance/ModalBackdropBlurStore.kt"
        )
        val out = mutableMapOf<String, String>()
        files.forEach { (owner, path) ->
            Regex("""const val (\w+)\s*=\s*"([^"]*)"""").findAll(sourceOf(path)).forEach {
                out["$owner.${it.groupValues[1]}"] = it.groupValues[2]
            }
        }
        return out
    }

    private fun sourceOf(relative: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative"
        return requireNotNull(
            sequenceOf(File(path), File("app/$path")).firstOrNull(File::isFile)
        ) { "missing source: $relative" }.readText()
    }

    private fun readOf(vararg pairs: Pair<String, Any?>): ModuleUiSettings =
        ModuleUiSettings.of { pairs.toMap() }.also { assertNotNull(it) }
}
