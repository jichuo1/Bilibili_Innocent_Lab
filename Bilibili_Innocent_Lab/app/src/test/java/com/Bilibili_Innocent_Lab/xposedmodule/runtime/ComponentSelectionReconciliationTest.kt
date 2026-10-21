package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ComponentPickerSelection
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSelectionCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ComponentSelectionReconciliationTest {
    private val kinds = mapOf(
        "mine" to "item", "bottom_bar" to "bottom_tab",
        "home_tabs" to "home_tab", "home_components" to "home_component",
        "component_pools" to "component_pool"
    )

    /**
     * 每一个勾选面都必须在 [ComponentSelectionReconciler.SELECTOR_KEYS] 里登记。
     *
     * 2026-09-15 真机故障的直接判据：组件库资源池面进了 `ALLOWED_SURFACES`，
     * 却没进这张表，`selectorsKey` 抛出的异常被 `MineComponentSnapshotStore.write`
     * 的 `runCatching` 吞成 `false` → 面板每次都提示"模块缓存写入失败"。
     * 异常被吞掉意味着日志里也看不出所以然，只能靠这条把漏登记钉在单测里。
     *
     * 反馈面板的两个草稿面（`section_picks` / `author_picks`）落到
     * RecommendationBlocklistDraft 等用户确认，不走勾选存储，所以点名豁免；
     * 以后再加面时这条会强制做出选择，不会再静默漏过去。
     *
     * 注意豁免判据是"草稿面"而不是 `ACCUMULATING_SURFACES`：`component_pools`
     * 也是累积面，但它有自己的勾选存储键，两个集合并不是同一回事。
     */
    @Test
    fun `every scan surface either has a selectors key or is a draft only surface`() {
        val draftOnly = setOf(
            MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
            MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS
        )
        assertEquals(
            MineComponentSnapshotCodec.ALLOWED_SURFACES,
            ComponentSelectionReconciler.SELECTOR_KEYS.keys + draftOnly
        )
        assertEquals(kinds.keys, ComponentSelectionReconciler.SELECTOR_KEYS.keys)
        assertEquals(
            ComponentSelectionReconciler.SELECTOR_KEYS.size,
            ComponentSelectionReconciler.SELECTOR_KEYS.values.distinct().size
        )
        assertEquals(
            FeaturePreferences.COMPONENT_POOL_BLOCKED_SELECTORS,
            ComponentSelectionReconciler.selectorsKey(MineComponentSnapshotCodec.SURFACE_COMPONENT_POOLS)
        )
        draftOnly.forEach { surface ->
            assertThrows(IllegalStateException::class.java) {
                ComponentSelectionReconciler.selectorsKey(surface)
            }
        }
    }

    /** 走完整条落盘路：资源池快照必须真的写进模块偏好，而不是被吞成 STORE_FAILED。 */
    @Test
    fun `a component pool snapshot is stored instead of reported as a failed write`() {
        val prefs = MemoryPreferences()
        val pool = entry("component_pool", "mod-fitness")
        assertTrue(write(prefs, "component_pools", 910L, listOf(pool)))
        assertEquals(
            payload("component_pools", listOf(pool)),
            prefs.values["mine_component_scan_snapshot_component_pools"]
        )
        // 勾选在版本没变时原样保留，和其余四个面一致。
        val key = ComponentSelectionReconciler.selectorsKey("component_pools")
        prefs.values[key] = MineComponentSelectionCodec.encode(setOf(pool.key))
        assertTrue(write(prefs, "component_pools", 910L, listOf(pool)))
        assertEquals(setOf(pool.key), selected(prefs, key))
    }

    @Test
    fun `cross version partial scans keep unseen selections until their components are observed`() {
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
            assertEquals(setOf(first.key, removed.key), selected(prefs, key))
            // The next group arrives separately. It must still be selected when it becomes visible.
            assertTrue(write(prefs, surface, 909L, listOf(removed)))
            assertTrue(ComponentPickerSelection.isSelected(removed, selected(prefs, key)))
            assertEquals(setOf(first.key, removed.key), selected(prefs, key))
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
    fun `legacy global source remains available for every picker surface scanned in any order`() {
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
            assertEquals(setOf("missing"), selected(prefs, ComponentSelectionReconciler.selectorsKey(surface)))
        }
        assertEquals(909L, prefs.values["mine_component_scan_target_version"])
        // 后续一个面的版本再次改变，不影响其他面的来源。
        val key = ComponentSelectionReconciler.selectorsKey("mine")
        prefs.values[key] = "[\"missing-again\"]"
        assertTrue(write(prefs, "home_tabs", 911L, listOf(entry("home_tab", "present"))))
        assertTrue(write(prefs, "mine", 911L, listOf(entry("item", "present"))))
        assertEquals(setOf("missing-again"), selected(prefs, key))
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
        assertEquals(setOf("missing"), selected(prefs, ComponentSelectionReconciler.selectorsKey("home_components")))
    }

    @Test
    fun `no known source establishes baseline without guessing a version change`() {
        val prefs = MemoryPreferences()
        val key = ComponentSelectionReconciler.selectorsKey("mine")
        prefs.values[key] = "[\"unknown\"]"
        assertTrue(write(prefs, "mine", 909L, listOf(entry("item", "present"))))
        assertEquals(setOf("unknown"), selected(prefs, key))
    }

    @Test fun `pushed snapshot never reads or rewrites selectors or manual rules`() {
        val cache = MemoryPreferences()
        val key = ComponentSelectionReconciler.selectorsKey("mine")
        cache.values[key] = "[\"keep\"]"
        cache.values["mine_component_hidden_rules"] = "manual"
        assertTrue(MineComponentSnapshotStore.cache(cache.instance, payload("mine", listOf(entry("item", "new"))),
            MineComponentSnapshotSource(911, 100, 17)))
        assertEquals("[\"keep\"]", cache.values[key])
        assertEquals("manual", cache.values["mine_component_hidden_rules"])
        val before = cache.values.toMap()
        assertFalse(MineComponentSnapshotStore.cache(cache.instance, "broken", MineComponentSnapshotSource(911, 100, 17)))
        assertEquals(before, cache.values)
    }

    @Test fun `cross version scans preserve aliases until explicit selection confirmation or cancellation`() {
        val prefs = MemoryPreferences()
        val legacy = entry("bottom_tab", "legacy-icon")
        assertTrue(write(prefs, "bottom_bar", 915L, listOf(legacy)))
        val key = ComponentSelectionReconciler.selectorsKey("bottom_bar")
        prefs.values[key] = MineComponentSelectionCodec.encode(setOf(legacy.key))
        val current = requireNotNull(MineComponentScanEntry.create(
            "bottom_tab", "route", "route", null, true, aliases = setOf(legacy.key)
        ))
        assertTrue(write(prefs, "bottom_bar", 916L, listOf(current)))
        assertEquals(setOf(legacy.key), selected(prefs, key))
        assertTrue(ComponentPickerSelection.isSelected(current, selected(prefs, key)))
        // Existing picker confirmation migrates exact aliases, rather than a scan guessing identity.
        prefs.values[key] = MineComponentSelectionCodec.encode(ComponentPickerSelection.merge(
            selected(prefs, key), listOf(current to true)
        ))
        assertEquals(setOf(current.key), selected(prefs, key))
        assertTrue(write(prefs, "bottom_bar", 917L, listOf(current)))
        assertEquals(setOf(current.key), selected(prefs, key))
        // Unchecking clears both the primary key and aliases. Later scans cannot resurrect either.
        prefs.values[key] = MineComponentSelectionCodec.encode(setOf(current.key, legacy.key))
        prefs.values[key] = MineComponentSelectionCodec.encode(ComponentPickerSelection.merge(
            selected(prefs, key), listOf(current to false)
        ))
        assertTrue(write(prefs, "bottom_bar", 915L, listOf(current)))
        assertEquals(emptySet<String>(), selected(prefs, key))
        assertFalse(ComponentPickerSelection.isSelected(current, selected(prefs, key)))
    }

    @Test fun `partial scan never reads or rewrites selector values or other surfaces`() {
        val prefs = MemoryPreferences()
        val first = entry("item", "A")
        val second = entry("item", "B")
        assertTrue(write(prefs, "mine", 915L, listOf(first, second)))
        val mineKey = ComponentSelectionReconciler.selectorsKey("mine")
        val bottomKey = ComponentSelectionReconciler.selectorsKey("bottom_bar")
        prefs.values[mineKey] = MineComponentSelectionCodec.encode(setOf(second.key))
        prefs.values[bottomKey] = "[\"untouched-bottom\"]"
        prefs.readKeys.clear()
        prefs.writtenKeys.clear()
        assertTrue(write(prefs, "mine", 916L, listOf(first)))
        assertFalse(mineKey in prefs.readKeys || mineKey in prefs.writtenKeys)
        assertFalse(bottomKey in prefs.readKeys || bottomKey in prefs.writtenKeys)
        assertEquals(setOf(second.key), selected(prefs, mineKey))
        assertEquals("[\"untouched-bottom\"]", prefs.values[bottomKey])
        // Reject draft-only surfaces as before; preserving selections must not broaden write scope.
        val before = prefs.values.toMap()
        assertFalse(write(prefs, "section_picks", 916L, listOf(entry("section", "1"))))
        assertEquals(before, prefs.values)
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

    /** 用真实 Store 路径验证事务及访问键，防止扫描重写勾选、其他面或手填规则。 */
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any>()
        val readKeys = mutableSetOf<String>()
        val writtenKeys = mutableSetOf<String>()
        val instance = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString", "getLong", "getBoolean" -> {
                    readKeys += args!![0] as String
                    values[args[0]] ?: args[1]
                }
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
                        writtenKeys += args!![0] as String
                        pending[args[0] as String] = args[1]
                        proxy
                    }
                    "commit" -> { values.putAll(pending); true }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
