package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.lib.homepage.mine.MenuGroupV2
import org.junit.Assert.*
import org.junit.Test
import tv.danmaku.bili.ui.main2.api.AccountMineV2

class MineComponentModelBoundaryTest {
    private class Harness(rules: String = "", ids: List<String> = emptyList(), keys: List<String> = emptyList(),
                          points: VersionAdapter.MineComponentPoints? = modernPoints(),
                          account: VersionAdapter.MineAccountMinePoints? = null) {
        val registrar = PlayerPortTestRegistrar()
        val snapshots = ArrayList<ScanSnapshotContent>()
        val logs = ArrayList<String>()
        val evidence = ArrayList<FeatureRuntimeStage>()
        val statuses = ArrayList<String>()
        val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), object : HookRegistrar by registrar {
                override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                                     block: ModernMemberHookCreator.() -> Unit) {
                    val owner = Class.forName(point.className)
                    registrar.exact(id, owner, point.methodName,
                        *requireNotNull(point.paramClassNames).map { Class.forName(it) }.toTypedArray(), block = block)
                }
            }, { _, message -> logs += message }, { _, message -> fail(message) }, { channel, status ->
                if (channel == MineComponentFilterFeatureInstaller.CHANNEL_STATUS) statuses += status
            },
            writeScanSnapshot = { _, content -> snapshots += content; true },
            runtimeEvidence = { _, stage, _ -> evidence += stage })
        init {
            assertTrue(MineComponentFilterFeatureInstaller(rules, ids, MineComponentSelectionCodec.encode(keys),
                points, account).install(environment) is FeatureInstallResult.Installed)
        }
        fun construct(value: AccountMineV2) {
            registrar.invoke(registrar.hooks.keys.first { it.startsWith("mine.model.") }, value)
        }
        fun getter(group: MenuGroupV2): List<*> = registrar.invoke("mine.components.list.0", group) {
            group.itemList
        } as List<*>
        fun entries() = snapshots.last().entries
    }

    @Test fun `constructor prunes union of three settings and direct consumers never need getter hook`() {
        val h = Harness(rules = "按标题隐藏", ids = listOf("12"), keys = listOf("item:id:11"))
        val keep = item("保留", 14)
        val settings = item("设置", 10, MineSettingsEntryGuard.SETTINGS_URI)
        val group = group("菜单", item("选择隐藏", 11), item("旧ID隐藏", 12), item("按标题隐藏", 13), keep, settings)
        val unrelated = arrayListOf("unchanged")
        val value = model(group, unrelated = unrelated)
        assertEquals("installed:mode=filter+scan,model=1,account=0,getter=1,legacy=0", h.statuses.single())
        h.construct(value)

        assertEquals(listOf("observed:path=model,count=1", "applied:path=model,count=3"), h.statuses.drop(1))
        assertEquals(listOf(14L, 10L), value.directRenderGroups().single().directRenderItems().map { it.id })
        assertSame(unrelated, value.unrelated())
        assertEquals(setOf("item:id:11", "item:id:12", "item:id:13"), h.entries().filter { !it.showing }.map { it.key }.toSet())
        assertFalse(h.entries().single { it.key == "item:id:10" }.selectable)
        assertTrue(h.evidence.contains(FeatureRuntimeStage.OBSERVED))
        assertTrue(h.evidence.contains(FeatureRuntimeStage.APPLIED))
        assertTrue(h.logs.any { "path=model stage=APPLIED" in it })
        assertTrue(h.logs.none { "选择隐藏" in it || "按标题隐藏" in it })
    }

    @Test fun `getter fallback still handles an unconstructed local group and all configuration sources`() {
        val h = Harness("标题", listOf("22"), listOf("item:id:21", "item:id:10"))
        val settings = item("设置", 10, MineSettingsEntryGuard.SETTINGS_URI)
        val group = group("本地菜单", item("选择", 21), item("旧ID", 22), item("标题", 23), settings)
        val rendered = h.getter(group).filterIsInstance<MenuGroupV2.Item>()
        assertEquals(listOf(settings), rendered)
        assertTrue(h.logs.any { "path=getter stage=OBSERVED" in it })
        assertTrue(h.logs.any { "path=getter stage=APPLIED" in it })
        assertEquals(listOf("observed:path=getter,count=4", "applied:path=getter,count=3"), h.statuses.drop(1))
        assertFalse(h.entries().single { it.key == "item:id:10" }.selectable)
    }

    @Test fun `group button and live tip rules work on renamed models without empty group occupancy`() {
        val keys = listOf("group:title:整组隐藏", "button:uri:bili://button", "live_tip:id:90", "item:id:31")
        val h = Harness(keys = keys)
        val removed = group("整组隐藏", item("分组内", 30))
        val emptied = group("清空后不留白", item("隐藏", 31))
        val buttonGroup = MenuGroupV2("保留组", arrayListOf(item("普通", 32)), MenuGroupV2.MineButton("按钮", "bili://button"))
        val settingsGroup = group("设置", item("设置", 10, MineSettingsEntryGuard.SETTINGS_URI))
        val value = AccountMineV2(null, arrayListOf(removed, emptied, buttonGroup, settingsGroup), arrayListOf("unrelated"),
            AccountMineV2.LiveTip("相同", 90L, "相同"))
        h.construct(value)
        assertEquals(listOf(buttonGroup, settingsGroup), value.directRenderGroups())
        assertNull(buttonGroup.button)
        assertNull(value.tip())
        assertTrue(h.entries().any { it.kind == "button" && !it.showing })
        assertTrue(h.entries().any { it.kind == "live_tip" && !it.showing })
        assertTrue(h.entries().any { it.key == "group:title:清空后不留白" && !it.showing })
    }

    @Test fun `hiding entire group keeps its protected settings and removes siblings`() {
        val h = Harness(keys = listOf("group:title:整组隐藏", "item:id:10"))
        val settings = item("设置", 10, MineSettingsEntryGuard.SETTINGS_URI + "?from=mine")
        val group = group("整组隐藏", item("其他", 42), settings)
        val value = model(group)
        h.construct(value)
        assertEquals(listOf(group), value.directRenderGroups())
        assertEquals(listOf(settings), group.directRenderItems())
    }

    @Test fun `all hidden before host adds settings leaves a real settings container for old last-index consumer`() {
        val h = Harness(keys = listOf("item:id:51", "group:title:全隐藏"))
        val value = model(group("全隐藏", item("唯一普通项", 51)))
        h.construct(value)
        val groups = value.directRenderGroups().filter { group -> group.directRenderItems().any { it.localShow } }
        // Models the host's real remove-empty -> append-to-last post-processing, including the
        // older unsafe index. The resulting group contains a usable settings route, not a spacer.
        val last = groups[groups.size - 1]
        assertEquals(listOf(MineSettingsEntryGuard.SETTINGS_URI), last.directRenderItems().map { it.uri })
        assertTrue(last.directRenderItems().all { it.visible == 1 && it.localShow })
        assertEquals(listOf("item:id:51"), h.entries().filter { it.kind == "item" && !it.showing }.map { it.key })
    }

    @Test fun `reconstructed filtered model and getter scan retain hidden selections`() {
        val h = Harness(keys = listOf("item:id:61"))
        val group = group("菜单", item("隐藏", 61), item("保留", 62))
        val value = model(group)
        h.construct(value)
        h.getter(group)
        val copied = AccountMineV2(null, value.directRenderGroups(), arrayListOf("another"), null)
        h.construct(copied)
        assertTrue(h.entries().any { it.key == "item:id:61" && !it.showing })
        assertEquals(listOf(62L), copied.directRenderGroups().single().directRenderItems().map { it.id })
    }

    @Test fun `no match preserves root and item list identities`() {
        val h = Harness()
        val items = arrayListOf(item("保留", 71))
        val group = MenuGroupV2("菜单", items, null)
        val groups = arrayListOf(group)
        val value = AccountMineV2(null, groups, arrayListOf("x"), null)
        h.construct(value)
        assertSame(groups, value.directRenderGroups())
        assertSame(items, group.directRenderItems())
        assertFalse(h.evidence.contains(FeatureRuntimeStage.APPLIED))
        assertTrue(h.statuses.first().startsWith("installed:mode=scan,"))
        assertEquals(listOf("observed:path=model,count=1"), h.statuses.drop(1))
    }

    @Test fun `many default models cannot exhaust later renamed field discovery`() {
        val locator = MineSerializedFields(AccountMineV2::class.java, setOf("sectionlist", "sectionlistv2", "livetip"))
        val empty = emptyList<MenuGroupV2>()
        repeat(12) { assertTrue(locator.resolve(AccountMineV2(empty, empty, empty, null)).isEmpty()) }
        assertEquals(0, locator.attempts())
        val old = arrayListOf(group("old", item("old", 81)))
        val modern = arrayListOf(group("modern", item("modern", 82)))
        val value = AccountMineV2(old, modern, arrayListOf("unrelated"), null)
        val slots = locator.resolve(value)
        assertSame(modern, slots.getValue("sectionlistv2").get(value))
        assertSame(old, slots.getValue("sectionlist").get(value))
        assertEquals(1, locator.attempts())
    }

    @Test fun `ambiguous nonempty shared lists are never guessed and retries are bounded`() {
        val locator = MineSerializedFields(AccountMineV2::class.java, setOf("sectionlistv2"))
        val shared = arrayListOf(group("shared", item("x", 1)))
        repeat(20) { assertTrue(locator.resolve(AccountMineV2(shared, shared, shared, null)).isEmpty()) }
        assertEquals(4, locator.attempts())
    }

    @Test fun `nested constructor callbacks process only after outer fields are complete`() {
        val h = Harness(keys = listOf("item:id:91"))
        val value = model(group("菜单", item("隐藏", 91), item("保留", 92)))
        val id = h.registrar.hooks.keys.first { it.startsWith("mine.model.") }
        h.registrar.invoke(id, value) {
            h.registrar.invoke(id, value)
            assertEquals(listOf(91L, 92L), value.directRenderGroups().single().directRenderItems().map { it.id })
            null
        }
        assertEquals(listOf(92L), value.directRenderGroups().single().directRenderItems().map { it.id })
        assertEquals(1, h.logs.count { "path=model stage=OBSERVED" in it })
    }

    @Test fun `failed root field write never reports a group as removed`() {
        val h = Harness()
        val hidden = group("隐藏组", item("成员", 95))
        val settings = group("设置", item("设置", 10, MineSettingsEntryGuard.SETTINGS_URI))
        val value = tv.danmaku.bili.ui.main2.api.MineWriteFailureModel(arrayListOf(hidden, settings))
        val snapshots = ArrayList<MineComponentScanEntry>()
        MineModelFilter(value.javaClass, { kind, title, _, _ -> kind == "group" && title == "隐藏组" },
            { _, entries -> snapshots.addAll(entries) }, h.environment).process(value)
        assertEquals(listOf(hidden, settings), value.z())
        assertTrue(snapshots.single { it.key == "group:title:隐藏组" }.showing)
    }

    class LegacyFragment {
        @JvmField var groups = arrayListOf<com.bilibili.lib.homepage.mine.MenuGroup>()
        fun build() = Unit
    }
    class LegacyButton(@JvmField val text: String, @JvmField val uri: String)
    class LegacyTip(@JvmField val text: String, @JvmField val id: Long)

    @Test fun `idle modern getter cannot suppress actual legacy field consumer or group and button filters`() {
        val legacyPoints = VersionAdapter.MineComponentPoints(
            modernPoints().itemListGetters, modernPoints().itemTitleGetters,
            legacyBuildMethods = listOf(point(LegacyFragment::class.java, "build")),
            legacyGroupListField = "groups", legacyGroupClassName = "com.bilibili.lib.homepage.mine.MenuGroup",
            legacyItemListField = "itemList", legacyItemClassName = "com.bilibili.lib.homepage.mine.MenuGroup\$Item",
            legacyItemTitleField = "title")
        val h = Harness(keys = listOf("item:id:101", "group:title:隐藏组", "button:uri:bili://button"), points = legacyPoints)
        val fragment = LegacyFragment()
        val empty = oldGroup("清空不留白", oldItem("普通", 101))
        val hidden = oldGroup("隐藏组", oldItem("分组成员", 102))
        val settings = oldGroup("保留", oldItem("设置", 10, MineSettingsEntryGuard.SETTINGS_URI))
        settings.button = LegacyButton("按钮", "bili://button")
        fragment.groups.addAll(listOf(empty, hidden, settings))
        h.registrar.invoke("mine.components.legacy.0", fragment)
        assertEquals(listOf(settings), fragment.groups)
        assertNull(settings.button)
        assertEquals(listOf(10L), fragment.groups.single().itemList.map { it.id })
        assertTrue(h.logs.any { "path=legacy stage=APPLIED" in it })
        assertTrue(h.entries().any { it.kind == "button" && !it.showing })
    }

    @Test fun `old typed AccountMine consumer gets union filtering and protected settings without blank groups`() {
        val accountPoint = VersionAdapter.locateMineAccountMinePoints(requireNotNull(javaClass.classLoader))
        assertNotNull(accountPoint)
        val h = Harness(rules = "标题", ids = listOf("112"), keys = listOf("item:id:111", "live_tip:id:120"),
            account = accountPoint)
        val value = tv.danmaku.bili.ui.main2.api.AccountMine()
        val onlyHidden = oldGroup("清空", oldItem("选择隐藏", 111), oldItem("旧ID", 112), oldItem("标题", 113))
        val settings = oldGroup("设置", oldItem("设置", 10, MineSettingsEntryGuard.SETTINGS_URI))
        value.sectionListV2 = arrayListOf(onlyHidden, settings)
        value.liveTip = LegacyTip("提示", 120)
        h.registrar.invoke("mine.account_mine.0", args = arrayOf(
            tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment(), value)) {
            assertEquals(listOf(settings), value.sectionListV2)
            assertEquals(listOf(10L), value.sectionListV2.single().itemList.map { item -> item.id })
            assertNull(value.liveTip)
            null
        }
        assertTrue(h.logs.any { "path=account stage=APPLIED" in it })
    }

    @Test fun `missing standard settings constructor safely restores one populated group without false applied evidence`() {
        // The old fixture intentionally has no no-arg Item constructor. The safe failure path must
        // keep host last-index post-processing usable, and must not claim the restored item hidden.
        val h = Harness(keys = listOf("item:id:121"),
            account = VersionAdapter.locateMineAccountMinePoints(requireNotNull(javaClass.classLoader)))
        val item = oldItem("暂保留", 121)
        val group = oldGroup("菜单", item)
        val value = tv.danmaku.bili.ui.main2.api.AccountMine().also { it.sectionListV2 = arrayListOf(group) }
        h.registrar.invoke("mine.account_mine.0", args = arrayOf(
            tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment(), value))
        assertEquals(listOf(item), value.sectionListV2.last().itemList)
        assertTrue(h.entries().single { it.key == "item:id:121" }.showing)
        assertFalse(h.evidence.contains(FeatureRuntimeStage.APPLIED))
        assertTrue(h.logs.any { "stage=UNKNOWN settings_container=1" in it })
    }

    @Test fun `failed constructor unwinds its depth without processing a partial model`() {
        val h = Harness(keys = listOf("item:id:131"))
        val value = model(group("菜单", item("隐藏", 131), item("保留", 132)))
        val id = h.registrar.hooks.keys.first { it.startsWith("mine.model.") }
        runCatching { h.registrar.invoke(id, value) { throw IllegalStateException("host constructor") } }
        assertEquals(2, value.directRenderGroups().single().directRenderItems().size)
        h.construct(value)
        assertEquals(listOf(132L), value.directRenderGroups().single().directRenderItems().map { it.id })
    }

    companion object {
        private fun oldItem(title: String, id: Long, uri: String = "bili://$id") =
            com.bilibili.lib.homepage.mine.MenuGroup.Item(title).also {
                it.id = id; it.uri = uri; it.visible = 1; it.localShow = true
            }
        private fun oldGroup(title: String, vararg items: com.bilibili.lib.homepage.mine.MenuGroup.Item) =
            com.bilibili.lib.homepage.mine.MenuGroup(arrayListOf(*items)).also { it.title = title }
        private fun modernPoints() = VersionAdapter.MineComponentPoints(
            listOf(point(MenuGroupV2::class.java, "getItemList")),
            listOf(point(MenuGroupV2.Item::class.java, "getTitle")),
            listOf(point(MenuGroupV2.Item::class.java, "getId")),
            listOf(point(MenuGroupV2.Item::class.java, "getUri")),
            listOf(point(MenuGroupV2::class.java, "getTitle")))
        private fun point(owner: Class<*>, name: String) = VersionAdapter.HookPoint(owner.name, name, emptyList())
        private fun item(title: String, id: Long, uri: String = "bili://$id") = MenuGroupV2.Item(title, id, uri)
        private fun group(title: String, vararg items: MenuGroupV2.Item) = MenuGroupV2(title, arrayListOf(*items), null)
        private fun model(group: MenuGroupV2, unrelated: List<*> = arrayListOf("unused")) =
            AccountMineV2(null, arrayListOf(group), unrelated, null)
    }
}
