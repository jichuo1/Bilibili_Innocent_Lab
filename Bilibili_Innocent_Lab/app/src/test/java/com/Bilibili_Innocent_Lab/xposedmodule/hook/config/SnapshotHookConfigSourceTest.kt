package com.Bilibili_Innocent_Lab.xposedmodule.hook.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotHookConfigSourceTest {

    @Test
    fun `reads supported values and uses defaults for absent keys`() {
        val source = SnapshotHookConfigSource(
            mapOf(
                "boolean" to true,
                "integer" to 37,
                "long" to 9_090_300L,
                "string" to "value"
            )
        )

        assertTrue(source.getBoolean("boolean", false))
        assertEquals(37, source.getInt("integer", -1))
        assertEquals(9_090_300L, source.getLong("long", -1L))
        assertEquals("value", source.getString("string", "default"))

        assertFalse(source.getBoolean("missing.boolean", false))
        assertEquals(-1, source.getInt("missing.integer", -1))
        assertEquals(-1L, source.getLong("missing.long", -1L))
        assertEquals("default", source.getString("missing.string", "default"))
    }

    @Test
    fun `wrong value type never coerces across configuration types`() {
        val source = SnapshotHookConfigSource(
            mapOf(
                "boolean" to "true",
                "integer" to 37L,
                "long" to 37,
                "string" to true
            )
        )

        assertFalse(source.getBoolean("boolean", false))
        assertEquals(-1, source.getInt("integer", -1))
        assertEquals(-1L, source.getLong("long", -1L))
        assertEquals("default", source.getString("string", "default"))
    }

    @Test
    fun `constructor snapshots the input map`() {
        val mutable = mutableMapOf<String, Any>("feature" to true)
        val source = SnapshotHookConfigSource(mutable)

        mutable["feature"] = false

        assertTrue(source.getBoolean("feature", false))
    }
}
