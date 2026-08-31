package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MineScanEntryTest {

    @Test
    fun `json round trip preserves fields`() {
        val entry = MineComponentFilterFeatureInstaller.MineScanEntry(
            kind = "item",
            title = "历史记录",
            id = "123",
            uri = "bilibili://user_center/history",
            showing = false
        )
        val json = JSONObject().apply {
            put("kind", entry.kind)
            entry.title?.let { put("title", it) }
            entry.id?.let { put("id", it) }
            entry.uri?.let { put("uri", it) }
            put("showing", entry.showing)
        }
        val restored = MineComponentFilterFeatureInstaller.MineScanEntry.fromJson(json)
        assertEquals("item", restored.kind)
        assertEquals("历史记录", restored.title)
        assertEquals("123", restored.id)
        assertEquals("bilibili://user_center/history", restored.uri)
        assertFalse(restored.showing)
    }

    @Test
    fun `nullable fields remain null when absent`() {
        val json = JSONObject().apply {
            put("kind", "group")
            put("showing", true)
        }
        val restored = MineComponentFilterFeatureInstaller.MineScanEntry.fromJson(json)
        assertEquals("group", restored.kind)
        assertNull(restored.title)
        assertNull(restored.id)
        assertNull(restored.uri)
        assertTrue(restored.showing)
    }

    @Test
    fun `snapshot array serialization and parse`() {
        val entries = listOf(
            MineComponentFilterFeatureInstaller.MineScanEntry("item", "A", "1", "u1", true),
            MineComponentFilterFeatureInstaller.MineScanEntry("group", "B", null, null, false)
        )
        val json = JSONObject().apply {
            put("v", 1)
            put("items", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        }.toString()
        val arr = JSONObject(json).optJSONArray("items")!!
        assertEquals(2, arr.length())
        assertEquals("A", arr.getJSONObject(0).getString("title"))
        assertFalse(arr.getJSONObject(1).getBoolean("showing"))
    }
}
