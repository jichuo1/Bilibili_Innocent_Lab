package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云控键扫描面的快照协议：独立的条目与载荷上限、取值标记、累积合并，以及其它面的上限不被放宽。 */
class CloudKeyScanSnapshotTest {
    private val surface = MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS

    private fun entry(key: String, value: String = CloudKeyScan.VALUE_TRUE) = checkNotNull(CloudKeyScan.entryOf(key, value))

    private fun entries(count: Int, keyLength: Int = 12) =
        (1..count).map { entry(("ff_" + it.toString().padStart(keyLength - 3, '0'))) }

    @Test fun `the cloud key surface is a registered accumulating surface with larger limits`() {
        assertTrue(surface in MineComponentSnapshotCodec.ALLOWED_SURFACES)
        assertTrue(surface in MineComponentSnapshotCodec.ACCUMULATING_SURFACES)
        assertTrue("cloud_key" in MineComponentSnapshotCodec.ALLOWED_KINDS)
        assertEquals(1024, MineComponentSnapshotCodec.maxEntries(surface))
        assertEquals(256 * 1024, MineComponentSnapshotCodec.maxPayloadBytes(surface))
        assertEquals(256, MineComponentSnapshotCodec.maxEntries(MineComponentSnapshotCodec.SURFACE_HOME_TABS))
        assertEquals(64 * 1024, MineComponentSnapshotCodec.maxPayloadBytes(MineComponentSnapshotCodec.SURFACE_MINE))
    }

    @Test fun `a full scan round trips with the boolean markers intact`() {
        val sent = entries(1024) + emptyList()
        val payload = MineComponentSnapshotCodec.encode("tv.danmaku.bili", setOf(CloudKeyScan.CAPABILITY), sent, surface)
        val decoded = checkNotNull(MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false))
        assertEquals(surface, decoded.surface)
        assertEquals(1024, decoded.entries.size)
        assertEquals(sent, decoded.entries)
        assertTrue(payload.toByteArray().size <= MineComponentSnapshotCodec.maxPayloadBytes(surface))
    }

    @Test fun `a thousand very long keys are shrunk to fit instead of the whole scan being refused`() {
        val longKeys = (1..1024).map { entry("ff_" + it.toString().padStart(6, '0') + "_" + "k".repeat(80)) }
        val payload = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), longKeys, surface)
        assertTrue("size=${payload.toByteArray().size}", payload.toByteArray().size <= MineComponentSnapshotCodec.maxPayloadBytes(surface))
        val decoded = checkNotNull(MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false))
        assertTrue(decoded.entries.size in 400..1023)
        // 收缩从尾部砍，前面的键原样保留。
        assertEquals(longKeys.take(decoded.entries.size), decoded.entries)
    }

    @Test fun `encoding truncates at the surface limit and decoding refuses more than it`() {
        val payload = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), entries(1100), surface)
        assertEquals(1024, checkNotNull(MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)).entries.size)
        val forged = JSONObject(payload).apply {
            put("items", JSONArray().also { array -> entries(1025).forEach { array.put(it.toJson()) } })
        }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(forged, allowLegacy = false))
    }

    @Test fun `the larger limits belong to the cloud key surface only`() {
        val tabs = MineComponentSnapshotCodec.SURFACE_HOME_TABS
        val many = (1..300).map { checkNotNull(MineComponentScanEntry.create("home_tab", "t$it", "id$it", null, true)) }
        val payload = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), many, tabs)
        assertEquals(256, checkNotNull(MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)).entries.size)
        val forged = JSONObject(payload).apply {
            put("items", JSONArray().also { array -> many.forEach { array.put(it.toJson()) } })
        }.toString()
        assertNull("257+ entries on another surface stay refused", MineComponentSnapshotCodec.decodeOrNull(forged, allowLegacy = false))
        // 合法 JSON 但超过 64KiB 的非云控面载荷仍被拒；同样大小放在云控面则可以。
        val padded = JSONObject(payload).apply { put("padding", "x".repeat(70_000)) }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(padded, allowLegacy = false))
        val cloud = JSONObject(MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), entries(5), surface))
            .apply { put("padding", "x".repeat(70_000)) }.toString()
        assertNotNull(MineComponentSnapshotCodec.decodeOrNull(cloud, allowLegacy = false))
        val oversizedCloud = JSONObject(MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), entries(5), surface))
            .apply { put("padding", "x".repeat(300_000)) }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(oversizedCloud, allowLegacy = false))
    }

    @Test fun `accumulating unions old and new scans and the new value of a key wins`() {
        val previous = listOf(entry("ff_a", "true"), entry("ff_b", "false"))
        val current = listOf(entry("ff_b", "true"), entry("ff_c", "mixed"))
        val merged = MineComponentSnapshotCodec.accumulate(surface, previous, current)
        assertEquals(listOf("ff_a", "ff_b", "ff_c"), merged.map { it.id })
        assertEquals("true", merged.single { it.id == "ff_b" }.value)
        assertEquals("mixed", merged.single { it.id == "ff_c" }.value)
    }

    @Test fun `the merged scan keeps the current keys and fills the rest from history up to its own cap`() {
        val previous = entries(1024)
        val current = listOf(entry("ff_zzzzzz_new"))
        val merged = MineComponentSnapshotCodec.accumulate(surface, previous, current)
        assertEquals(1024, merged.size)
        assertTrue(merged.any { it.id == "ff_zzzzzz_new" })
    }

    @Test fun `the value marker round trips and a forged or over long one is refused`() {
        val entry = entry("ff_a", "mixed")
        assertEquals(entry, MineComponentScanEntry.fromJsonOrNull(entry.toJson()))
        assertEquals("mixed", MineComponentScanEntry.fromJsonOrNull(entry.toJson())!!.value)
        assertNull(MineComponentScanEntry.fromJsonOrNull(entry.toJson().put("value", "x".repeat(40))))
        assertNull(MineComponentScanEntry.create("cloud_key", null, "ff_a", null, true, value = "x".repeat(40)))
        // 键必须由键名推出，伪造的别名/键不被接受。
        assertNull(MineComponentScanEntry.fromJsonOrNull(entry.toJson().put("key", "cloud_key:other")))
        // 旧条目没有 value 字段，行为不变。
        val tab = checkNotNull(MineComponentScanEntry.create("home_tab", "热门", "hot", null, true))
        assertNull(tab.value)
        assertTrue(!tab.toJson().has("value"))
    }

    @Test fun `a cloud key entry needs a key name and has no title based fallback`() {
        assertNull(MineComponentScanEntry.create("cloud_key", "only a title", null, null, true))
        assertNull(MineComponentScanEntry.create("cloud_key", null, "   ", null, true))
        assertEquals("cloud_key:ff_x", MineComponentScanEntry.create("cloud_key", null, "ff_x", null, true)!!.key)
    }
}
