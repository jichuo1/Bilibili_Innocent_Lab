package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ComponentPickerSelection
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSelectionCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
     *
     * `cloud_keys`（云控键扫描面）是第三种观测面：用户的选择是带取值的固定值，存在
     * `cloud.key_freeze.picked`，不是隐藏用的 selector，不能被"未观察到就清理"的对账碰，
     * 面板直接用查询结果里的快照，所以同样点名豁免。
     */
    fun `every scan surface either has a selectors key or is a draft only surface`() {
        val draftOnly = setOf(
            MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
            MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS,
            MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS
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

    fun `no known source establishes baseline without guessing a version change`() {
        val prefs = MemoryPreferences()
        val key = ComponentSelectionReconciler.selectorsKey("mine")
        prefs.values[key] = "[\"unknown\"]"
        assertTrue(write(prefs, "mine", 909L, listOf(entry("item", "present"))))
        assertEquals(setOf("unknown"), selected(prefs, key))
    }

    fun `pushed snapshot never reads or rewrites selectors or manual rules`() {
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

    fun `cross version scans preserve aliases until explicit selection confirmation or cancellation`() {
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

    fun `partial scan never reads or rewrites selector values or other surfaces`() {
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ComponentSelectionReconciliationTest) -> Unit) {
            val s = ComponentSelectionReconciliationTest()
            block(s)
        }
        runIsolated { s -> s.`every scan surface either has a selectors key or is a draft only surface`() }
        runIsolated { s -> s.`a component pool snapshot is stored instead of reported as a failed write`() }
        runIsolated { s -> s.`cross version partial scans keep unseen selections until their components are observed`() }
        runIsolated { s -> s.`same version rescan and module or install timestamp changes keep selections`() }
        runIsolated { s -> s.`legacy global source remains available for every picker surface scanned in any order`() }
        runIsolated { s -> s.`invalid empty or incomplete scan cannot clear selection or advance version`() }
        runIsolated { s -> s.`no known source establishes baseline without guessing a version change`() }
        runIsolated { s -> s.`pushed snapshot never reads or rewrites selectors or manual rules`() }
        runIsolated { s -> s.`cross version scans preserve aliases until explicit selection confirmation or cancellation`() }
        runIsolated { s -> s.`partial scan never reads or rewrites selector values or other surfaces`() }
    
        CrossAppBroadcastPolicyCases().verifyAllCases()
        FairRunningMemoryPolicyCases().verifyAllCases()
        GitHubReleaseCheckerCases().verifyAllCases()
        HostAdmissionCollectCases().verifyAllCases()
        HostAdmissionHandshakeCases().verifyAllCases()
    }
}

private class CrossAppBroadcastPolicyCases {

    fun `sender identity is shared starting from Android 14`() {
        assertFalse(CrossAppBroadcastPolicy.shouldShareSenderIdentity(33))
        assertTrue(CrossAppBroadcastPolicy.shouldShareSenderIdentity(34))
        assertTrue(CrossAppBroadcastPolicy.shouldShareSenderIdentity(35))
    }

    fun `ephemeral callback avoids synthetic permission before Android 13`() {
        assertEquals(
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.PLATFORM_WITH_EPHEMERAL_PROOF,
            CrossAppBroadcastPolicy.callbackReceiverRegistration(28)
        )
        assertEquals(
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.PLATFORM_WITH_EPHEMERAL_PROOF,
            CrossAppBroadcastPolicy.callbackReceiverRegistration(32)
        )
    }

    fun `ephemeral callback is not exported starting from Android 13`() {
        assertEquals(
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.NOT_EXPORTED,
            CrossAppBroadcastPolicy.callbackReceiverRegistration(33)
        )
        assertEquals(
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.NOT_EXPORTED,
            CrossAppBroadcastPolicy.callbackReceiverRegistration(35)
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CrossAppBroadcastPolicyCases) -> Unit) {
            val s = CrossAppBroadcastPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`sender identity is shared starting from Android 14`() }
        runIsolated { s -> s.`ephemeral callback avoids synthetic permission before Android 13`() }
        runIsolated { s -> s.`ephemeral callback is not exported starting from Android 13`() }
    }
}

private class FairRunningMemoryPolicyCases {

    fun `intent action beats a conflicting bundle action`() {
        assertEquals(
            FairRunningMemoryKind.TRIM,
            FairRunningMemoryPolicy.kindFor(
                FairRunningMemoryContract.ACTION_TRIM,
                FairRunningMemoryContract.ACTION_VALUE_KILL
            )
        )
        assertEquals(
            FairRunningMemoryKind.KILL,
            FairRunningMemoryPolicy.kindFor(
                FairRunningMemoryContract.ACTION_KILL,
                FairRunningMemoryContract.ACTION_VALUE_TRIM
            )
        )
    }

    fun `bundle action is only a fallback when the intent is unknown`() {
        assertEquals(
            FairRunningMemoryKind.TRIM,
            FairRunningMemoryPolicy.kindFor(null, FairRunningMemoryContract.ACTION_VALUE_TRIM)
        )
        assertEquals(
            FairRunningMemoryKind.KILL,
            FairRunningMemoryPolicy.kindFor("other.action", FairRunningMemoryContract.ACTION_VALUE_KILL)
        )
        assertNull(FairRunningMemoryPolicy.kindFor(null, null))
        assertNull(FairRunningMemoryPolicy.kindFor("android.intent.action.BOOT_COMPLETED", "save"))
    }

    fun `trim and kill both release graphics but only kill asks to persist`() {
        assertTrue(FairRunningMemoryPolicy.shouldReleaseGraphics(FairRunningMemoryKind.TRIM))
        assertTrue(FairRunningMemoryPolicy.shouldReleaseGraphics(FairRunningMemoryKind.KILL))
        assertFalse(FairRunningMemoryPolicy.shouldPersist(FairRunningMemoryKind.TRIM))
        assertTrue(FairRunningMemoryPolicy.shouldPersist(FairRunningMemoryKind.KILL))
    }

    fun `result codes match the published table`() {
        assertEquals(0, FairRunningMemoryPolicy.resultCode(true))
        assertEquals(1, FairRunningMemoryPolicy.resultCode(false))
        assertEquals(android.os.IBinder.FIRST_CALL_TRANSACTION, FairRunningMemoryContract.TRANSACTION_EXCEPTION_REPLY)
        assertEquals(3_000L, FairRunningMemoryContract.SYSTEM_DEADLINE_MS)
        assertTrue(FairRunningMemoryContract.WORK_BUDGET_MS < FairRunningMemoryContract.SYSTEM_DEADLINE_MS)
        assertTrue(FairRunningMemoryPolicy.receiverMustBeExported())
    }

    fun `trim extras parse heap aliases and pss fields`() {
        val notice = FairRunningMemoryContract.fromFields(
            FairRunningMemoryContract.ACTION_TRIM,
            FairRunningMemoryFields(
                notifyType = FairRunningMemoryContract.NOTIFY_TYPE_HEAP,
                notifyId = 42,
                reason = "Excessive Java Heap Usage",
                action = FairRunningMemoryContract.ACTION_VALUE_TRIM,
                heapAllocKb = 12_000,
                heapCapacityKb = 24_000
            )
        )
        assertNotNull(notice)
        assertEquals(FairRunningMemoryKind.TRIM, notice!!.kind)
        assertEquals(FairRunningMemoryContract.NOTIFY_TYPE_HEAP, notice.notifyType)
        assertEquals(42, notice.notifyId)
        assertEquals(12_000, notice.heapSizeKb)
        assertEquals(24_000, notice.heapCapacityKb)
        assertNull(notice.callback)
    }

    fun `heapSize wins over the sample-code heapAlloc alias`() {
        val notice = FairRunningMemoryContract.fromFields(
            FairRunningMemoryContract.ACTION_KILL,
            FairRunningMemoryFields(
                notifyType = FairRunningMemoryContract.NOTIFY_TYPE_PSS,
                notifyId = 7,
                action = FairRunningMemoryContract.ACTION_VALUE_KILL,
                heapSizeKb = 8,
                heapAllocKb = 99
            )
        )
        assertEquals(FairRunningMemoryKind.KILL, notice!!.kind)
        assertEquals(8, notice.heapSizeKb)
    }

    fun `missing extra bundle is still a valid notice`() {
        val notice = FairRunningMemoryContract.fromFields(
            FairRunningMemoryContract.ACTION_TRIM,
            FairRunningMemoryFields(notifyId = 1)
        )
        assertNotNull(notice)
        assertNull(notice!!.pssKb)
        assertNull(notice.heapSizeKb)
    }

    fun `root extras without common are ignored`() {
        assertNull(FairRunningMemoryContract.parse(FairRunningMemoryContract.ACTION_TRIM, extras = null))
        assertNull(
            FairRunningMemoryContract.fromFields(
                intentAction = "android.intent.action.BOOT_COMPLETED",
                fields = FairRunningMemoryFields(notifyId = 1)
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FairRunningMemoryPolicyCases) -> Unit) {
            val s = FairRunningMemoryPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`intent action beats a conflicting bundle action`() }
        runIsolated { s -> s.`bundle action is only a fallback when the intent is unknown`() }
        runIsolated { s -> s.`trim and kill both release graphics but only kill asks to persist`() }
        runIsolated { s -> s.`result codes match the published table`() }
        runIsolated { s -> s.`trim extras parse heap aliases and pss fields`() }
        runIsolated { s -> s.`heapSize wins over the sample-code heapAlloc alias`() }
        runIsolated { s -> s.`missing extra bundle is still a valid notice`() }
        runIsolated { s -> s.`root extras without common are ignored`() }
    }
}

private class GitHubReleaseCheckerCases {

    // ===== 版本解析与比较 =====

    fun `detects newer stable semantic versions`() {
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.5", "1.0.4"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.1.0", "1.0.99"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("2.0.0", "1.99.99"))
    }

    fun `alpha ordering follows the published scheme`() {
        // 1.0.8-alpha.1 < 1.0.8-alpha.2 < 1.0.8 < 1.0.9-alpha.1
        val alpha1 = releaseVersion("v1.0.8-alpha.1")
        val alpha2 = releaseVersion("v1.0.8-alpha.2")
        val stable = releaseVersion("v1.0.8")
        val nextAlpha = releaseVersion("v1.0.9-alpha.1")

        assertTrue(alpha1 < alpha2)
        assertTrue(alpha2 < stable)
        assertTrue(stable < nextAlpha)
    }

    fun `isNewerVersion covers stable and alpha mixtures`() {
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.8-alpha.1", "1.0.7"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.8-alpha.2", "1.0.8-alpha.1"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.8", "1.0.8-alpha.2"))
        assertTrue(GitHubReleaseChecker.isNewerVersion("v1.0.9-alpha.1", "1.0.8"))

        // 正式版发布后，更低的 Alpha 不再提示。
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.8-alpha.3", "1.0.8"))
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.8", "1.0.8"))
        // Alpha 用户切回稳定版时不得被提示降级。
        assertFalse(GitHubReleaseChecker.isNewerVersion("v1.0.7", "1.0.8-alpha.2"))
    }

    fun `compareVersions reports tri-state relation`() {
        assertEquals(
            GitHubReleaseChecker.VersionRelation.REMOTE_NEWER,
            GitHubReleaseChecker.compareVersions("v1.0.8", "1.0.7")
        )
        assertEquals(
            GitHubReleaseChecker.VersionRelation.EQUAL,
            GitHubReleaseChecker.compareVersions("v1.0.8", "v1.0.8")
        )
        assertEquals(
            GitHubReleaseChecker.VersionRelation.LOCAL_NEWER,
            GitHubReleaseChecker.compareVersions("v1.0.7", "1.0.8-alpha.2")
        )
        assertNull(GitHubReleaseChecker.compareVersions("not-a-version", "1.0.7"))
    }

    fun `unsupported tag formats are rejected`() {
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8-beta.1"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8-rc.1"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8-alpha"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8-alpha.x"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8.1"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0"))
        assertNull(GitHubReleaseChecker.ReleaseVersion.parse("not-a-version"))
        assertNotNull(GitHubReleaseChecker.ReleaseVersion.parse("v1.0.8-alpha.1"))
        assertNotNull(GitHubReleaseChecker.ReleaseVersion.parse("1.0.8"))
    }

    // ===== 渠道持久化值解析 =====

    fun `update channel falls back to stable for unknown values`() {
        assertEquals(
            GitHubReleaseChecker.UpdateChannel.STABLE,
            GitHubReleaseChecker.UpdateChannel.fromStorageValue(null)
        )
        assertEquals(
            GitHubReleaseChecker.UpdateChannel.STABLE,
            GitHubReleaseChecker.UpdateChannel.fromStorageValue("stable")
        )
        assertEquals(
            GitHubReleaseChecker.UpdateChannel.PREVIEW,
            GitHubReleaseChecker.UpdateChannel.fromStorageValue("preview")
        )
        assertEquals(
            GitHubReleaseChecker.UpdateChannel.STABLE,
            GitHubReleaseChecker.UpdateChannel.fromStorageValue("garbage")
        )
    }

    // ===== 稳定版端点解析 =====

    fun `stable endpoint parses a valid stable release`() {
        val info = GitHubReleaseChecker.parseLatestStableRelease(
            releaseJson(tag = "v1.0.8", prerelease = false)
        )

        assertEquals("v1.0.8", info.tagName)
        assertFalse(info.prerelease)
        assertEquals("v1.0.8", info.displayName)
        assertEquals(
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/v1.0.8/app.apk",
            info.apkDownloadUrl
        )
    }

    fun `stable endpoint preserves markdown structure and reports bounded excerpts`() {
        val markdown = "## Changes\r\n\r\n- **Fixed** update rendering\u0000"
        val regular = GitHubReleaseChecker.parseLatestStableRelease(
            releaseJson(tag = "v1.0.8", prerelease = false, body = markdown)
        )
        assertEquals("## Changes\n\n- **Fixed** update rendering", regular.releaseNotes)
        assertFalse(regular.releaseNotesTruncated)

        val oversized = GitHubReleaseChecker.parseLatestStableRelease(
            releaseJson(
                tag = "v1.0.8",
                prerelease = false,
                body = "## Changes\n\n" + "x".repeat(ReleaseNotesSourcePolicy.MAX_MARKDOWN_LENGTH + 64)
            )
        )
        assertTrue(oversized.releaseNotesTruncated)
        assertTrue(oversized.releaseNotes.length <= ReleaseNotesSourcePolicy.MAX_MARKDOWN_LENGTH)
    }

    fun `stable endpoint rejects prerelease draft and mismatched tags`() {
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parseLatestStableRelease(
                releaseJson(tag = "v1.0.8-alpha.1", prerelease = true)
            )
        }
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parseLatestStableRelease(
                releaseJson(tag = "v1.0.8", prerelease = false, draft = true)
            )
        }
        // Alpha 标签即使被错误标记为 prerelease=false 也不得进入稳定渠道。
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parseLatestStableRelease(
                releaseJson(tag = "v1.0.8-alpha.1", prerelease = false)
            )
        }
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parseLatestStableRelease(
                releaseJson(tag = "1.0.8-beta.1", prerelease = false)
            )
        }
    }

    // ===== 预览渠道列表解析 =====

    fun `preview channel picks the highest semantic version not the first item`() {
        val payload = listPayload(
            releaseJson(tag = "v1.0.8-alpha.1", prerelease = true),
            releaseJson(tag = "v1.0.7", prerelease = false),
            releaseJson(tag = "v1.0.8", prerelease = false),
            releaseJson(tag = "v1.0.8-alpha.2", prerelease = true)
        )

        val info = GitHubReleaseChecker.parsePreviewReleases(payload)

        // 1.0.8 高于 1.0.8-alpha.2：预览渠道用户在正式版发布后收到正式版。
        assertEquals("v1.0.8", info.tagName)
        assertFalse(info.prerelease)
    }

    fun `preview channel accepts the newest alpha when it outranks stable`() {
        val payload = listPayload(
            releaseJson(tag = "v1.0.7", prerelease = false),
            releaseJson(tag = "v1.0.8-alpha.3", prerelease = true)
        )

        val info = GitHubReleaseChecker.parsePreviewReleases(payload)

        assertEquals("v1.0.8-alpha.3", info.tagName)
        assertTrue(info.prerelease)
    }

    fun `preview channel ignores draft mismatched and unsupported releases`() {
        val payload = listPayload(
            releaseJson(tag = "v1.0.9", prerelease = false, draft = true),
            releaseJson(tag = "v1.0.9-alpha.1", prerelease = false),
            releaseJson(tag = "v1.0.9", prerelease = true),
            releaseJson(tag = "1.0.9-beta.1", prerelease = true),
            releaseJson(tag = "garbage-tag", prerelease = false),
            releaseJson(tag = "v1.0.9-alpha.2", prerelease = true)
        )

        val info = GitHubReleaseChecker.parsePreviewReleases(payload)

        assertEquals("v1.0.9-alpha.2", info.tagName)
        assertTrue(info.prerelease)
    }

    fun `preview channel throws when no valid release remains`() {
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parsePreviewReleases("[]")
        }
        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.parsePreviewReleases(
                listPayload(releaseJson(tag = "v1.0.9-alpha.1", prerelease = false))
            )
        }
    }

    // ===== 镜像回退 =====

    fun `uses mirror after official endpoint times out`() {
        val official = endpoint("GitHub", "https://api.github.test/latest")
        val mirror = endpoint("Mirror", "https://mirror.test/latest")
        val visited = mutableListOf<String>()
        val expected = stableRelease()

        val actual = GitHubReleaseChecker.fetchWithFallback(listOf(official, mirror)) { current ->
            visited += current.name
            if (current === official) throw SocketTimeoutException("simulated timeout")
            expected
        }

        assertSame(expected, actual)
        assertEquals(listOf("GitHub", "Mirror"), visited)
    }

    fun `does not contact mirror when GitHub succeeds`() {
        val visited = mutableListOf<String>()
        val expected = stableRelease()

        val actual = GitHubReleaseChecker.fetchWithFallback(
            listOf(endpoint("GitHub", "https://api.github.test/latest"), endpoint("Mirror", "https://mirror.test/latest"))
        ) { current ->
            visited += current.name
            expected
        }

        assertSame(expected, actual)
        assertEquals(listOf("GitHub"), visited)
    }

    fun `reports failure only after every endpoint fails`() {
        val endpoints = listOf(
            endpoint("GitHub", "https://api.github.test/latest"),
            endpoint("Mirror", "https://mirror.test/latest")
        )

        val error = assertThrows(IOException::class.java) {
            GitHubReleaseChecker.fetchWithFallback(endpoints) { current ->
                throw IOException("${current.name} unavailable")
            }
        }

        assertTrue(error.message.orEmpty().contains("all update-check mirrors"))
        assertEquals(1, error.suppressed.size)
    }

    // ===== URL 白名单 =====

    fun `accepts only release links from this repository`() {
        val expected = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/v1.0.5/app.apk"
        assertEquals(expected, GitHubReleaseChecker.validateGitHubUrl(expected, "APK asset"))

        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.validateGitHubUrl(
                "https://github.com/another-owner/another-repo/releases/download/v9.9.9/app.apk",
                "APK asset"
            )
        }
    }

    fun `release details use GitHub when official page is reachable`() {
        val official = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.6"
        val probed = mutableListOf<String>()

        val destination = GitHubReleaseChecker.resolveReleaseDetailsDestination(official) { url ->
            probed += url
        }

        assertEquals(official, destination.url)
        assertFalse(destination.usesMirror)
        assertEquals(listOf(official), probed)
    }

    fun `release details switch to full page mirror after GitHub timeout`() {
        val official = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.6"

        val destination = GitHubReleaseChecker.resolveReleaseDetailsDestination(official) {
            throw SocketTimeoutException("simulated GitHub timeout")
        }

        assertEquals(
            "https://kkgithub.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.6",
            destination.url
        )
        assertTrue(destination.usesMirror)
    }

    fun `release details reject URLs outside this repository before probing`() {
        var probed = false

        assertThrows(IOException::class.java) {
            GitHubReleaseChecker.resolveReleaseDetailsDestination(
                "https://github.com/another-owner/another-repo/releases/tag/v9.9.9"
            ) {
                probed = true
            }
        }

        assertFalse(probed)
    }

    // ===== 测试夹具 =====

    private fun releaseVersion(tag: String): GitHubReleaseChecker.ReleaseVersion =
        requireNotNull(GitHubReleaseChecker.ReleaseVersion.parse(tag))

    private fun releaseJson(
        tag: String,
        prerelease: Boolean,
        draft: Boolean = false,
        body: String = "release notes for $tag"
    ): String {
        val obj = JSONObject()
        obj.put("tag_name", tag)
        obj.put("name", tag)
        obj.put("draft", draft)
        obj.put("prerelease", prerelease)
        obj.put(
            "html_url",
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/$tag"
        )
        obj.put("body", body)
        val assets = JSONArray()
        val asset = JSONObject()
        asset.put("name", "Bilibili_Innocent_Lab-$tag.apk")
        asset.put(
            "browser_download_url",
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/$tag/app.apk"
        )
        assets.put(asset)
        obj.put("assets", assets)
        return obj.toString()
    }

    private fun listPayload(vararg items: String): String =
        JSONArray().apply { items.forEach { put(JSONObject(it)) } }.toString()

    private fun endpoint(name: String, url: String) =
        GitHubReleaseChecker.ReleaseEndpoint(name, url, connectTimeoutMs = 1, readTimeoutMs = 1)

    private fun stableRelease() = GitHubReleaseChecker.ReleaseInfo(
        tagName = "v1.0.5",
        displayName = "v1.0.5",
        releaseNotes = "Stable",
        htmlUrl = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.0.5",
        apkDownloadUrl = "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/download/v1.0.5/app.apk",
        prerelease = false
    )

    fun verifyAllCases() {
        fun runIsolated(block: (GitHubReleaseCheckerCases) -> Unit) {
            val s = GitHubReleaseCheckerCases()
            block(s)
        }
        runIsolated { s -> s.`detects newer stable semantic versions`() }
        runIsolated { s -> s.`alpha ordering follows the published scheme`() }
        runIsolated { s -> s.`isNewerVersion covers stable and alpha mixtures`() }
        runIsolated { s -> s.`compareVersions reports tri-state relation`() }
        runIsolated { s -> s.`unsupported tag formats are rejected`() }
        runIsolated { s -> s.`update channel falls back to stable for unknown values`() }
        runIsolated { s -> s.`stable endpoint parses a valid stable release`() }
        runIsolated { s -> s.`stable endpoint preserves markdown structure and reports bounded excerpts`() }
        runIsolated { s -> s.`stable endpoint rejects prerelease draft and mismatched tags`() }
        runIsolated { s -> s.`preview channel picks the highest semantic version not the first item`() }
        runIsolated { s -> s.`preview channel accepts the newest alpha when it outranks stable`() }
        runIsolated { s -> s.`preview channel ignores draft mismatched and unsupported releases`() }
        runIsolated { s -> s.`preview channel throws when no valid release remains`() }
        runIsolated { s -> s.`uses mirror after official endpoint times out`() }
        runIsolated { s -> s.`does not contact mirror when GitHub succeeds`() }
        runIsolated { s -> s.`reports failure only after every endpoint fails`() }
        runIsolated { s -> s.`accepts only release links from this repository`() }
        runIsolated { s -> s.`release details use GitHub when official page is reachable`() }
        runIsolated { s -> s.`release details switch to full page mirror after GitHub timeout`() }
        runIsolated { s -> s.`release details reject URLs outside this repository before probing`() }
    }
}

private class HostAdmissionCollectCases {
    fun explicitClosePreemptsAGrantWithoutTurningOrdinarySyncFailuresIntoPermanentDenials() {
        val closed = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, disabledRevision = 42L)
        val results = listOf(
            HostAdmissionCollect().accept(closed, true).accept(granted(), true),
            HostAdmissionCollect().accept(granted(), true).accept(closed, false)
        )
        for (result in results) {
            assertTrue(result.sawDenied)
            assertTrue(result.fallbackBlocked)
            assertNull(result.grant)
            assertEquals(42L, result.failure.disabledRevision)
        }
    }
    fun aGenericDenialCannotEraseTheCurrentCloseRevision() {
        val closed = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_DENIED, disabledRevision = 42L)
        val first = HostAdmissionCollect().accept(closed, acceptGrant = true).accept(denied(), acceptGrant = true)
        val second = HostAdmissionCollect().accept(denied(), acceptGrant = true).accept(closed, acceptGrant = true)
        assertEquals(42L, first.failure.disabledRevision)
        assertEquals(42L, second.failure.disabledRevision)
        assertNull(first.grant)
        assertNull(second.grant)
    }
    private fun unavailable() = HostAdmissionClient.Result()
    private fun denied() = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_DENIED)
    private fun timeout() = HostAdmissionClient.Result(reason = "admission_timeout")
    private fun granted(): HostAdmissionClient.Result {
        val snapshot = RemoteHookConfigSnapshot(
            generation = 1L,
            moduleVersionCode = 1L,
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = UserTermsDecision.ACCEPTED,
            values = emptyMap(),
        )
        return HostAdmissionClient.Result(
            HostAdmissionClient.Grant(snapshot, "remote_config_transport_fallback"),
            reason = "",
        )
    }

    fun firstGrantWinsInsideTheWindow() {
        val collect = HostAdmissionCollect()
            .accept(unavailable(), acceptGrant = true)
            .accept(granted(), acceptGrant = true)
        assertNotNull(collect.grant)
        assertFalse(collect.sawDenied)
    }

    fun authorityDenialDiscardsALaterGrant() {
        val collect = HostAdmissionCollect()
            .accept(denied(), acceptGrant = true)
            .accept(granted(), acceptGrant = true)
        assertTrue(collect.sawDenied)
        assertNull(collect.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, collect.failure.reason)
    }

    fun timeoutDrainLatchesADenyThatAlreadyArrived() {
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>()
        queue.offer(denied())
        queue.offer(granted())
        val collect = HostAdmissionCollect()
            .accept(timeout(), acceptGrant = true)
            .drain(queue, acceptGrant = false)
        assertTrue(collect.sawDenied)
        assertNull(collect.grant)
        assertTrue(queue.isEmpty())
    }

    fun anAlreadyQueuedDenialPreemptsTheFastGrantPath() {
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>().apply { offer(denied()) }
        val result = HostAdmissionCollect().accept(granted(), true).drain(queue, acceptGrant = false)
        assertTrue(result.sawDenied)
        assertTrue(result.fallbackBlocked)
        assertNull(result.grant)
    }

    fun timeoutDrainIgnoresLateGrantsWhenNobodyDenied() {
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>()
        queue.offer(granted())
        val collect = HostAdmissionCollect()
            .accept(unavailable(), acceptGrant = true)
            .drain(queue, acceptGrant = false)
        assertFalse(collect.sawDenied)
        assertNull(collect.grant)
        assertNull(collect.failure.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_UNAVAILABLE, collect.failure.reason)
    }

    fun syncFailureSurvivesTransportFailureWithoutBecomingTermsDenial() {
        val sync = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED)
        val collect = HostAdmissionCollect().accept(sync, true).accept(unavailable(), true).accept(timeout(), true)
        assertFalse(collect.sawDenied)
        assertTrue(collect.fallbackBlocked)
        assertEquals(sync.reason, collect.failure.reason)
    }

    fun freshGrantCanRecoverAnotherRoutesSyncFailureButNotRealDenial() {
        val sync = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED)
        val recovered = HostAdmissionCollect().accept(sync, true).accept(granted(), true)
        assertNotNull(recovered.grant)
        assertFalse(recovered.sawDenied)
        val refused = recovered.accept(denied(), true).accept(granted(), true)
        assertTrue(refused.sawDenied)
        assertNull(refused.grant)
    }

    fun lateSyncResponseBlocksFallbackAndDiagnosticCountIsBounded() {
        val sync = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            diagnostics = listOf(HostAdmissionDiagnostic("provider", "prepare", "manager_sync_required", true)))
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>().apply { offer(sync); offer(granted()) }
        var collect = HostAdmissionCollect().drain(queue, acceptGrant = false)
        assertTrue(collect.fallbackBlocked)
        assertNull(collect.grant)
        repeat(8) { collect = collect.accept(sync, false) }
        assertEquals(4, collect.diagnostics.size)
    }

    fun transportOnlyRetryCannotEraseAnEarlierAuthorityBlock() {
        val state = HostAdmissionFallbackState()
        for (reason in listOf(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, HostAdmissionRouteClassifier.REASON_DENIED)) {
            state.observe(HostAdmissionClient.Result(reason = reason))
            state.observe(unavailable())
            state.observe(granted())
            val next = state.newAttempt().accept(timeout(), true)
            assertTrue(next.fallbackBlocked)
            assertEquals(reason, next.failure.reason)
        }
        state.observe(HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED))
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, state.newAttempt().failure.reason)
        val identity = com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity("publication-instance", 1, 1, 1, "a".repeat(64))
        val fresh = granted().let { it.copy(grant = it.grant!!.copy(source = HostAdmissionContract.NORMAL, identity = identity)) }
        state.observe(fresh)
        assertFalse(state.newAttempt().fallbackBlocked)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionCollectCases) -> Unit) {
            val s = HostAdmissionCollectCases()
            block(s)
        }
        runIsolated { s -> s.explicitClosePreemptsAGrantWithoutTurningOrdinarySyncFailuresIntoPermanentDenials() }
        runIsolated { s -> s.aGenericDenialCannotEraseTheCurrentCloseRevision() }
        runIsolated { s -> s.firstGrantWinsInsideTheWindow() }
        runIsolated { s -> s.authorityDenialDiscardsALaterGrant() }
        runIsolated { s -> s.timeoutDrainLatchesADenyThatAlreadyArrived() }
        runIsolated { s -> s.anAlreadyQueuedDenialPreemptsTheFastGrantPath() }
        runIsolated { s -> s.timeoutDrainIgnoresLateGrantsWhenNobodyDenied() }
        runIsolated { s -> s.syncFailureSurvivesTransportFailureWithoutBecomingTermsDenial() }
        runIsolated { s -> s.freshGrantCanRecoverAnotherRoutesSyncFailureButNotRealDenial() }
        runIsolated { s -> s.lateSyncResponseBlocksFallbackAndDiagnosticCountIsBounded() }
        runIsolated { s -> s.transportOnlyRetryCannotEraseAnEarlierAuthorityBlock() }
    }
}

private class HostAdmissionHandshakeCases {
    private val nonce = "00000000-0000-0000-0000-000000000001"
    private val challenge = "00000000-0000-0000-0000-000000000002"
    private fun snapshot(revision: Long = 0L, decision: UserTermsDecision = UserTermsDecision.ACCEPTED) =
        RemoteHookConfigSnapshot(1L, BuildConfig.VERSION_CODE.toLong(), true, 0L, decision,
            RemoteHookConfigContract.resolveSourceValues(mapOf(RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to revision)))
    private val normal = snapshot()
    private fun identity(config: RemoteHookConfigSnapshot = normal) = PublicationIdentity(nonce, 1L, 1L, 1L,
        RemoteHookConfigContract.contentFingerprint(config))
    private fun reply(status: String, config: RemoteHookConfigSnapshot = normal) = HostAdmissionHandshake.Reply(
        HostAdmissionContract.VERSION, BuildConfig.VERSION_CODE.toLong(), nonce, status,
        identity(config), HostAdmissionContract.NORMAL, challenge)

    private fun exchange(prepared: HostAdmissionHandshake.Reply, granted: HostAdmissionHandshake.Reply = reply("granted"),
        config: RemoteHookConfigSnapshot? = normal, direct: Boolean = false, npatch: Boolean = false) = HostAdmissionHandshake.exchange(
        "provider", nonce, config, direct, 100L, { 0L }, { prepared }, { value, _ ->
            assertEquals(challenge, value)
            granted
        }, nPatchRuntime = npatch)

    fun currentNpatchCloseRevisionSurvivesBothHandshakePhasesOnlyWithAValidEnvelope() {
        val closed = reply("manager_sync_required").copy(disabledRevision = 42L)
        for (result in listOf(exchange(closed), exchange(reply("prepared"), closed))) {
            assertNull(result.grant)
            assertEquals(42L, result.disabledRevision)
            assertEquals(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, result.reason)
        }
        for (unbound in listOf(closed.copy(nonce = "wrong-nonce-value"), closed.copy(moduleVersion = -1))) {
            assertEquals(0L, exchange(unbound).disabledRevision)
            assertEquals(0L, exchange(reply("prepared"), unbound).disabledRevision)
        }
    }

    fun npatchRequiresANoRootRevisionWhileStandardAdmissionKeepsItsOriginalContract() {
        assertNotNull(exchange(reply("prepared")).grant)
        val stale = exchange(reply("prepared"), npatch = true)
        assertNull(stale.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, stale.reason)
        val current = normal.copy(noRootRevision = 42L)
        assertEquals(current, exchange(reply("prepared", current), reply("granted", current), current, npatch = true).grant?.snapshot)
    }

    fun completeCurrentSnapshotRequiresBoundPrepareAndConfirm() {
        val result = exchange(reply("prepared"))
        assertEquals(normal, result.grant?.snapshot)
        assertEquals("confirm", result.diagnostics.single().phase)
        for (wrong in listOf(reply("granted").copy(nonce = "another-nonce-value"),
            reply("granted").copy(moduleVersion = -1), reply("granted").copy(source = HostAdmissionContract.DIRECT),
            reply("granted").copy(identity = identity().copy(consentRevision = 2L)))) {
            assertNull(exchange(reply("prepared"), wrong).grant)
        }
    }

    fun denialRequiresCurrentVersionAndNonceInBothPhases() {
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, exchange(reply("denied")).reason)
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, exchange(reply("prepared"), reply("denied")).reason)
        for (bad in listOf(reply("denied").copy(version = -1), reply("denied").copy(moduleVersion = -1),
            reply("denied").copy(nonce = "wrong-nonce-value"))) {
            assertEquals(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, exchange(bad).reason)
            assertEquals(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, exchange(reply("prepared"), bad).reason)
        }
    }

    fun missingChallengeOrIdentityIsAResponseFailureWithoutTermsDenial() {
        for (bad in listOf(reply("prepared").copy(identity = null), reply("prepared").copy(challenge = null),
            reply("prepared").copy(challenge = "short"), reply("prepared").copy(source = "unknown"))) {
            val result = exchange(bad)
            assertNull(result.grant)
            assertEquals(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, result.reason)
            assertFalse(HostAdmissionRouteClassifier.latchesDenial(result.reason))
        }
    }

    fun newerConfigRequiresResyncAndRecoversWithANewCompleteSnapshot() {
        val newer = snapshot(5L)
        val old = exchange(reply("prepared", newer))
        assertEquals(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, old.reason)
        assertFalse(old.diagnostics.single().fingerprintMatches!!)
        assertTrue(HostAdmissionRouteClassifier.blocksFallback(old.reason))
        val recovered = exchange(reply("prepared", newer), reply("granted", newer), newer)
        assertEquals(newer, recovered.grant?.snapshot)
        assertEquals(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, exchange(reply("manager_sync_required")).reason)
        assertEquals(HostAdmissionRouteClassifier.REASON_CONSENT_PENDING, exchange(reply("consent_commit_failed")).reason)
    }

    fun directPathRetainsCompleteDocumentAndConsentVerification() {
        fun document(config: RemoteHookConfigSnapshot) = RemoteHookConfigContract.encode(config.generation,
            config.moduleVersionCode, config.deliveryEnabled, config.noRootRevision, config.decision, config.values)
        fun prepared(config: RemoteHookConfigSnapshot) = reply("prepared", config).copy(source = HostAdmissionContract.DIRECT,
            document = HostAdmissionContract.encode(document(config)))
        val granted = reply("granted").copy(source = HostAdmissionContract.DIRECT)
        assertNotNull(exchange(prepared(normal), granted, direct = true).grant)
        assertNull(exchange(prepared(normal), granted, direct = false).grant)
        val corrupt = document(normal).toMutableMap().apply { put(RemoteHookConfigContract.KEY_DIGEST, "0".repeat(64)) }
        for (bad in listOf(prepared(normal).copy(document = "invalid"),
            prepared(normal).copy(document = HostAdmissionContract.encode(corrupt)), prepared(snapshot(decision = UserTermsDecision.DECLINED)))) {
            val result = exchange(bad, granted, direct = true)
            assertNull(result.grant)
            assertEquals(HostAdmissionRouteClassifier.REASON_CONFIG_INVALID, result.reason)
            assertTrue(HostAdmissionRouteClassifier.blocksFallback(result.reason))
        }
    }

    fun lateConfirmCannotInstallAndDiagnosticsCannotEchoUntrustedText() {
        var now = 0L
        val result = HostAdmissionHandshake.exchange("provider", nonce, normal, false, 100L, { now },
            { reply("prepared") }, { _, _ -> now = 100L; reply("granted") })
        assertNull(result.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_TIMEOUT, result.reason)
        val secret = "private-value-that-must-not-be-logged"
        val summary = HostAdmissionDiagnostic(secret, secret, secret).summary()
        assertFalse(summary.contains(secret))
        assertTrue(summary.contains("status=unknown"))
    }

    fun verifiedNegativePrepareAtTheDeadlineStillBlocksFallback() {
        for ((status, reason) in mapOf("denied" to HostAdmissionRouteClassifier.REASON_DENIED,
            "manager_sync_required" to HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            "consent_commit_failed" to HostAdmissionRouteClassifier.REASON_CONSENT_PENDING)) {
            var now = 0L
            val result = HostAdmissionHandshake.exchange("provider", nonce, normal, false, 100L, { now },
                { now = 100L; reply(status) }, { _, _ -> fail("negative prepare must not confirm"); null })
            assertNull(result.grant)
            assertEquals(reason, result.reason)
            assertTrue(HostAdmissionRouteClassifier.blocksFallback(result.reason))
        }
    }

    fun verifiedNegativeConfirmAtTheDeadlineStillBlocksFallback() {
        for ((status, reason) in mapOf("denied" to HostAdmissionRouteClassifier.REASON_DENIED,
            "manager_sync_required" to HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            "consent_commit_failed" to HostAdmissionRouteClassifier.REASON_CONSENT_PENDING)) {
            var now = 0L
            val result = HostAdmissionHandshake.exchange("provider", nonce, normal, false, 100L, { now },
                { reply("prepared") }, { _, _ -> now = 100L; reply(status) })
            assertNull(result.grant)
            assertEquals(reason, result.reason)
        }
    }

    fun latePreparedResponseCannotStartAConfirmation() {
        var now = 0L
        val result = HostAdmissionHandshake.exchange("provider", nonce, normal, false, 100L, { now },
            { now = 100L; reply("prepared") }, { _, _ -> fail("late prepare must not confirm"); null })
        assertNull(result.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_TIMEOUT, result.reason)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionHandshakeCases) -> Unit) {
            val s = HostAdmissionHandshakeCases()
            block(s)
        }
        runIsolated { s -> s.currentNpatchCloseRevisionSurvivesBothHandshakePhasesOnlyWithAValidEnvelope() }
        runIsolated { s -> s.npatchRequiresANoRootRevisionWhileStandardAdmissionKeepsItsOriginalContract() }
        runIsolated { s -> s.completeCurrentSnapshotRequiresBoundPrepareAndConfirm() }
        runIsolated { s -> s.denialRequiresCurrentVersionAndNonceInBothPhases() }
        runIsolated { s -> s.missingChallengeOrIdentityIsAResponseFailureWithoutTermsDenial() }
        runIsolated { s -> s.newerConfigRequiresResyncAndRecoversWithANewCompleteSnapshot() }
        runIsolated { s -> s.directPathRetainsCompleteDocumentAndConsentVerification() }
        runIsolated { s -> s.lateConfirmCannotInstallAndDiagnosticsCannotEchoUntrustedText() }
        runIsolated { s -> s.verifiedNegativePrepareAtTheDeadlineStillBlocksFallback() }
        runIsolated { s -> s.verifiedNegativeConfirmAtTheDeadlineStillBlocksFallback() }
        runIsolated { s -> s.latePreparedResponseCannotStartAConfirmation() }
    }
}
