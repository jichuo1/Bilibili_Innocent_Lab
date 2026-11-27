package com.bilab.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSettingsSchemaTest {

    @Test
    fun `core setting definition validates and normalizes typed values`() {
        val boolDef = CoreSettingDefinition(
            id = "ads.pause.hidden",
            storageKey = "ads_pause_hidden",
            type = CoreSettingValueType.BOOLEAN,
            defaultValue = CoreSettingValue.Bool(true)
        )
        assertTrue(boolDef.accepts(CoreSettingValue.Bool(false)))
        assertFalse(boolDef.accepts(CoreSettingValue.IntValue(1)))
        assertNull(boolDef.normalizeForBackup(CoreSettingValue.Text("true")))

        val intRangeDef = CoreSettingDefinition(
            id = "comments.min_level",
            storageKey = "comments_min_level",
            type = CoreSettingValueType.INTEGER,
            defaultValue = CoreSettingValue.IntValue(1),
            integerRange = 1..6
        )
        assertTrue(intRangeDef.accepts(CoreSettingValue.IntValue(6)))
        assertFalse(intRangeDef.accepts(CoreSettingValue.IntValue(7)))
        assertEquals(
            CoreSettingValue.IntValue(6),
            intRangeDef.normalizeForBackup(CoreSettingValue.IntValue(99))
        )
        assertEquals(
            CoreSettingValue.IntValue(1),
            intRangeDef.normalizeForBackup(CoreSettingValue.IntValue(-3))
        )

        val enumTextDef = CoreSettingDefinition(
            id = "log.level",
            storageKey = "log_level",
            type = CoreSettingValueType.STRING,
            defaultValue = CoreSettingValue.Text("complete"),
            allowedStrings = setOf("minimal", "complete"),
            maxStringLength = 16
        )
        assertTrue(enumTextDef.accepts(CoreSettingValue.Text("minimal")))
        assertFalse(enumTextDef.accepts(CoreSettingValue.Text("verbose")))
        assertEquals(
            CoreSettingValue.Text("complete"),
            enumTextDef.normalizeForBackup(CoreSettingValue.Text("verbose"))
        )
        assertNull(enumTextDef.normalizeForBackup(CoreSettingValue.Text("x".repeat(17))))
        assertEquals(CoreSettingValueType.INTEGER, CoreSettingValueType.fromSerializedName("integer"))
        assertNull(CoreSettingValueType.fromSerializedName("unknown"))
    }

    @Test
    fun `plugin setting key space enforces strict namespace isolation`() {
        assertEquals("plg.", PluginSettingKeySpace.PREFIX)
        val key = PluginSettingKeySpace.keyFor("com.bilab.filter", "enabled")
        assertEquals("plg.com.bilab.filter.enabled", key)
        assertTrue(PluginSettingKeySpace.isPluginKey(key))
        assertTrue(PluginSettingKeySpace.belongsToPlugin(key, "com.bilab.filter"))
        assertEquals(key, PluginSettingKeySpace.requireIsolatedKey("com.bilab.filter", key))

        assertFalse(PluginSettingKeySpace.isPluginKey("ads.pause.hidden"))
        assertFalse(PluginSettingKeySpace.isPluginKey("plg."))
        assertFalse(PluginSettingKeySpace.isPluginKey("plg.com.bilab..escape"))

        assertFalse(PluginSettingKeySpace.belongsToPlugin("plg.com.bilab.filter2.enabled", "com.bilab.filter"))
        assertFalse(PluginSettingKeySpace.belongsToPlugin("plg.com.bilab.filter.", "com.bilab.filter"))
        assertFalse(PluginSettingKeySpace.belongsToPlugin("ads.pause.hidden", "com.bilab.filter"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requireIsolatedKey rejects foreign plugin key`() {
        PluginSettingKeySpace.requireIsolatedKey("com.bilab.alpha", "plg.com.bilab.beta.flag")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requireIsolatedKey rejects core key`() {
        PluginSettingKeySpace.requireIsolatedKey("com.bilab.alpha", "ads.pause.hidden")
    }
}
