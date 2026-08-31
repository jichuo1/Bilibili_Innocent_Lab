package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MineScanEntryTest {

    @Test
    fun `current snapshot round trip preserves stable selector and metadata`() {
        val entry = requireNotNull(
            MineComponentScanEntry.create(
                kind = "item",
                title = "历史记录",
                id = "123",
                uri = "bilibili://user_center/history",
                showing = false
            )
        )
        val payload = MineComponentSnapshotCodec.encode(
            processName = MineComponentSnapshotCodec.TARGET_PACKAGE,
            capabilities = setOf("item_filter"),
            entries = listOf(entry),
            generatedAt = 1234L
        )

        val restored = MineComponentSnapshotCodec.decodeOrNull(payload)
        assertNotNull(restored)
        assertEquals(1234L, restored?.generatedAt)
        assertEquals(setOf("item_filter"), restored?.capabilities)
        assertEquals("item:id:123", restored?.entries?.single()?.key)
        assertFalse(requireNotNull(restored).entries.single().showing)
    }

    @Test
    fun `legacy v1 snapshot is readable and derives a stable key`() {
        val payload = JSONObject().apply {
            put("v", 1)
            put("items", JSONArray().put(JSONObject().apply {
                put("kind", "group")
                put("title", "更多服务")
                put("showing", true)
            }))
        }.toString()

        val restored = MineComponentSnapshotCodec.decodeOrNull(payload)
        assertEquals("group:title:更多服务", restored?.entries?.single()?.key)
        assertTrue(requireNotNull(restored).entries.single().showing)
        assertEquals(0L, restored.generatedAt)
    }

    @Test
    fun `provider mode rejects legacy mismatched keys and oversized payloads`() {
        val legacy = JSONObject().apply {
            put("v", 1)
            put("items", JSONArray())
        }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(legacy, allowLegacy = false))

        val mismatchedKey = JSONObject().apply {
            put("schema", MineComponentSnapshotCodec.CURRENT_SCHEMA_VERSION)
            put("targetPackage", MineComponentSnapshotCodec.TARGET_PACKAGE)
            put("process", MineComponentSnapshotCodec.TARGET_PACKAGE)
            put("generatedAt", 1L)
            put("items", JSONArray().put(JSONObject().apply {
                put("key", "item:id:wrong")
                put("kind", "item")
                put("id", "right")
                put("showing", true)
            }))
        }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(mismatchedKey, allowLegacy = false))

        val oversized = "x".repeat(MineComponentSnapshotCodec.MAX_PAYLOAD_BYTES + 1)
        assertNull(MineComponentSnapshotCodec.decodeOrNull(oversized, allowLegacy = false))
    }

    @Test
    fun `selection codec preserves commas and removes duplicates`() {
        val values = setOf(
            "item:uri:bilibili://mine?a=1,b=2",
            "group:title:更多服务"
        )
        val encoded = MineComponentSelectionCodec.encode(values)
        assertEquals(values, MineComponentSelectionCodec.decode(encoded))
        assertEquals(setOf("item:id:1"), MineComponentSelectionCodec.decode("item:id:1\nitem:id:1"))
    }

    @Test
    fun `entry factory rejects entries without identity or overlong fields`() {
        assertNull(MineComponentScanEntry.create("group", null, null, null, true))
        assertNull(MineComponentScanEntry.create("unknown", "title", null, null, true))
        val overlong = MineComponentScanEntry.create(
            "item",
            "t".repeat(300),
            null,
            null,
            true
        )
        assertNull(overlong)
    }
}
