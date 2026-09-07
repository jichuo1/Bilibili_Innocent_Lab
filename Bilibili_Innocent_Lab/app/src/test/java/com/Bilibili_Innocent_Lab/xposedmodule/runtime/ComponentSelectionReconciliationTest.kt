package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSelectionCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ComponentSelectionReconciliationTest {
    private val kinds = mapOf(
        "mine" to "item", "bottom_bar" to "bottom_tab",
        "home_tabs" to "home_tab", "home_components" to "home_component"
    )

    @Test
    fun `downgrade from three components to two releases only the missing selection on every surface`() {
        kinds.forEach { (surface, kind) ->
            val prefs = MemoryPreferences()
            val first = entry(kind, "first")
            val second = entry(kind, "second")
            val removed = entry(kind, "removed")
            assertTrue(write(prefs, surface, 910L, listOf(first, second, removed)))
            val key = ComponentSelectionReconciler.selectorsKey(surface)
            prefs.values[key] = MineComponentSelectionCodec.encode(setOf(first.key, removed.key))
            val manual = mapOf(
                FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES to "manual mine",
                FeaturePreferences.MINE_COMPONENT_HIDDEN_IDS to "legacy-id",
                FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES to "manual bottom",
                FeaturePreferences.HOME_TAB_HIDDEN_RULES to "manual tabs",
                FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES to "manual home"
            )
            prefs.values.putAll(manual)
            // 同 id 的标题变化、已隐藏状态、不可编辑状态都不能误删。
            assertTrue(write(prefs, surface, 909L, listOf(
                first.copy(title = "renamed", showing = false, selectable = false), second
            )))
            assertEquals(setOf(first.key), selected(prefs, key))
            manual.forEach { (manualKey, value) -> assertEquals(value, prefs.values[manualKey]) }
        }
    }

    @Test
    fun `same version rescan and module or install timestamp changes keep selections`() {
        val prefs = MemoryPreferences()
        val entry = entry("home_tab", "present")
        assertTrue(write(prefs, "home_tabs", 910L, listOf(entry)))
        val key = ComponentSelectionReconciler.selectorsKey("home_tabs")
        prefs.values[key] = MineComponentSelectionCodec.encode(setOf(entry.key, "old"))
        val payload = payload("home_tabs", listOf(entry))
        assertTrue(MineComponentSnapshotStore.write(prefs.instance, payload,
            MineComponentSnapshotSource(910L, 99L, 99L)))
        assertEquals(setOf(entry.key, "old"), selected(prefs, key))
    }

    @Test
    fun `legacy global source remains available for all four surfaces scanned in any order`() {
        val prefs = MemoryPreferences()
        prefs.values.putAll(mapOf(
            "mine_component_scan_source_present" to true,
            "mine_component_scan_target_version" to 909L,
            "mine_component_scan_target_update_time" to 1L,
            "mine_component_scan_module_version" to 14L
        ))
        kinds.forEach { (surface, _) ->
            prefs.values[ComponentSelectionReconciler.selectorsKey(surface)] = "[\"missing\"]"
        }
        kinds.entries.reversed().forEach { (surface, kind) ->
            assertTrue(write(prefs, surface, 910L, listOf(entry(kind, "present"))))
            assertEquals(emptySet<String>(), selected(prefs, ComponentSelectionReconciler.selectorsKey(surface)))
        }
        assertEquals(909L, prefs.values["mine_component_scan_target_version"])
        // 后续一个面的版本再次改变，不影响其他面的来源。
        val key = ComponentSelectionReconciler.selectorsKey("mine")
        prefs.values[key] = "[\"missing-again\"]"
        assertTrue(write(prefs, "home_tabs", 911L, listOf(entry("home_tab", "present"))))
        assertTrue(write(prefs, "mine", 911L, listOf(entry("item", "present"))))
        assertEquals(emptySet<String>(), selected(prefs, key))
    }

    @Test
    fun `invalid empty or incomplete scan cannot clear selection or advance version`() {
        val prefs = MemoryPreferences()
        val entry = entry("home_component", "present")
        assertTrue(write(prefs, "home_components", 910L, listOf(entry)))
        prefs.values[ComponentSelectionReconciler.selectorsKey("home_components")] = "[\"missing\"]"
        val before = prefs.values.toMap()
        assertFalse(write(prefs, "home_components", 909L, emptyList()))
        assertFalse(MineComponentSnapshotStore.write(prefs.instance, "invalid", MineComponentSnapshotSource(909L, 1L, 14L)))
        assertFalse(MineComponentSnapshotStore.write(prefs.instance, payload("home_components", listOf(entry)), MineComponentSnapshotSource(909L, 0L, 14L)))
        assertEquals(before, prefs.values)
        assertTrue(write(prefs, "home_components", 909L, listOf(entry)))
        assertEquals(emptySet<String>(), selected(prefs, ComponentSelectionReconciler.selectorsKey("home_components")))
    }

    @Test
    fun `no known source establishes baseline without guessing a version change`() {
        val prefs = MemoryPreferences()
        val key = ComponentSelectionReconciler.selectorsKey("mine")
        prefs.values[key] = "[\"unknown\"]"
        assertTrue(write(prefs, "mine", 909L, listOf(entry("item", "present"))))
        assertEquals(setOf("unknown"), selected(prefs, key))
    }

    private fun entry(kind: String, id: String) = requireNotNull(
        MineComponentScanEntry.create(kind, id, id, null, true)
    )

    private fun payload(surface: String, entries: List<MineComponentScanEntry>) =
        MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), entries, surface, 100L)

    private fun write(prefs: MemoryPreferences, surface: String, version: Long, entries: List<MineComponentScanEntry>) =
        MineComponentSnapshotStore.write(prefs.instance, payload(surface, entries), MineComponentSnapshotSource(version, version, 14L))

    private fun selected(prefs: MemoryPreferences, key: String) =
        MineComponentSelectionCodec.decode(prefs.values[key] as String)

    /** 用真实 Store 路径验证事务写入键，避免只测试集合交集而漏掉跨面/手填误写。 */
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any>()
        val instance = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString", "getLong", "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, args ->
                when (method.name) {
                    "putString", "putLong", "putBoolean" -> {
                        pending[args!![0] as String] = args[1]
                        proxy
                    }
                    "commit" -> { values.putAll(pending); true }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
