package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailViewPurifyFeatureInstallerTest {

    private val statuses = mutableListOf<Pair<String, String>>()
    private val errors = mutableListOf<String>()
    private val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()

    private fun environment(
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = null
    ) = HookEnvironment(
        processName = process,
        classLoader = loader ?: javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { key, _ -> errors += key },
        reportStatus = { channel, status -> statuses += channel to status },
        capabilityEvidence = { id, result -> capabilities += id to result }
    )

    private val allKeys = DetailViewPurifyPolicy.preferenceKeys.toSet()

    @Test fun `skips without touching the host when every subitem is off`() {
        val result = DetailViewPurifyFeatureInstaller(emptySet()).install(environment())
        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("detail_view_purify_status" to "disabled"), statuses)
        assertTrue(capabilities.isEmpty())
    }

    @Test fun `skips in non main processes`() {
        val result = DetailViewPurifyFeatureInstaller(allKeys)
            .install(environment(process = "tv.danmaku.bili:web"))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), result)
        assertTrue(statuses.isEmpty())
    }

    /** 宿主没有 RecyclerView（或它被混淆改名）时整条降级，并留下诊断。 */
    @Test fun `degrades when the recycler view class is absent`() {
        val empty = object : ClassLoader(null) {}
        val result = DetailViewPurifyFeatureInstaller(allKeys).install(environment(loader = empty))
        assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), result)
        assertTrue("detail_view_purify_missing" in errors)
    }

    @Test fun `degrades when the class loader is missing`() {
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = null,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { key, _ -> errors += key },
            reportStatus = { c, s -> statuses += c to s }
        )
        assertEquals(
            FeatureInstallResult.Skipped("missing-class-loader"),
            DetailViewPurifyFeatureInstaller(allKeys).install(env)
        )
    }

    @Test fun `only the enabled rules are reported as capabilities`() {
        assertEquals(
            DetailViewPurifyPolicy.rules.map { it.capabilityId },
            DetailViewPurifyFeatureInstaller(allKeys).capabilityIds
        )
        assertEquals(
            listOf(DetailViewPurifyPolicy.HOT_BANNER.capabilityId),
            DetailViewPurifyFeatureInstaller(
                setOf(DetailViewPurifyPolicy.HOT_BANNER.preferenceKey)
            ).capabilityIds
        )
        assertTrue(DetailViewPurifyFeatureInstaller(emptySet()).capabilityIds.isEmpty())
    }

    /**
     * 规则要用到的资源名必须**全部**解析成功才启用。
     *
     * 只解析出一部分就装，等于每次子项挂载都白跑一次 `findViewById`，永远找不到东西。
     */
    @Test fun `a rule needs every one of its resource names resolved`() {
        val rule = DetailViewPurifyPolicy.STAFF_FOLLOW
        val all = rule.idNames.associateWith { 100 }
        assertTrue(DetailViewPurifyPolicy.usable(rule, all))
        rule.idNames.forEach { missing ->
            assertFalse(missing, DetailViewPurifyPolicy.usable(rule, all + (missing to 0)))
        }
        assertFalse(DetailViewPurifyPolicy.usable(rule, emptyMap()))
    }

    /** 关注按钮按 item 根 id 过滤（最便宜）；热搜横条的 item 根没有 id，只能靠结构指纹。 */
    @Test fun `rules declare the cheapest available discriminator`() {
        assertEquals("vfl_avatar", DetailViewPurifyPolicy.STAFF_FOLLOW.itemIdName)
        assertEquals("fl_follow_container", DetailViewPurifyPolicy.STAFF_FOLLOW.hideIdName)
        assertEquals(null, DetailViewPurifyPolicy.HOT_BANNER.itemIdName)
        // hideIdName 为 null = 隐藏 item 根自身，整条横条消失。
        assertEquals(null, DetailViewPurifyPolicy.HOT_BANNER.hideIdName)
        // 横条靠六个 id 同时存在认；少于这些就不是它。
        assertEquals(
            listOf(
                "tvTitle", "ivIcon", "endIconContainer", "card_view_background",
                "ivEndIcon", "tvSubtitle"
            ),
            DetailViewPurifyPolicy.HOT_BANNER.requiredIdNames
        )
    }

    /**
     * 判据余量：每个"几乎凑齐"的邻居都必须被**至少两个** id 挡住。
     *
     * 原来只要求四个 id 时余量是 1——`theseus_ogv_live_reserve_bar` 差
     * `endIconContainer` 一个就会被误当成热搜横条隐藏掉，而那是另一个组件。
     * 24 个存档宿主的 layout 扫描结果写在 `HOT_BANNER_MUST_NOT_MATCH` 里；
     * 谁想精简 requiredIdNames，这条测试会立刻告诉他动的是哪一道防线。
     */
    @Test fun `every near miss component is excluded by more than one required id`() {
        val required = DetailViewPurifyPolicy.HOT_BANNER.requiredIdNames
        DetailViewPurifyPolicy.HOT_BANNER_MUST_NOT_MATCH.forEach { (component, missing) ->
            val blocking = missing.filter { it in required }
            assertTrue(
                "$component 只被 $blocking 挡住，余量不足",
                blocking.size >= 2
            )
        }
    }

    /**
     * 两种隐藏策略必须与判据形态严格对应。
     *
     * - 隐 item **内部**控件（关注按钮）：父容器是正常 ViewGroup，普通 `GONE` 就重排了；
     * - 隐 **item 根**（热搜横条）：`LinearLayoutManager` 不跳过 GONE 的孩子，
     *   只 `GONE` 会留下整格空白（实测 175px），必须走 [ItemCollapseBook] 折叠尺寸。
     */
    @Test fun `only the item root rules are collapsed instead of merely hidden`() {
        assertFalse(DetailViewPurifyPolicy.STAFF_FOLLOW.hidesItemRoot)
        assertTrue(DetailViewPurifyPolicy.HOT_BANNER.hidesItemRoot)
        DetailViewPurifyPolicy.rules.forEach { rule ->
            assertEquals(rule.capabilityId, rule.hideIdName == null, rule.hidesItemRoot)
        }
    }

    /** 判别一律不看文字：热搜横条的文案「热搜第N名 · …」每次都不同。 */
    @Test fun `no rule matches on user visible text`() {
        DetailViewPurifyPolicy.rules.forEach { rule ->
            rule.idNames.forEach { name ->
                assertTrue(name, name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                assertFalse(name, name.contains("热"))
            }
        }
    }

    /** 定位只靠 androidx 类名与资源名，不许出现任何宿主业务类名。 */
    @Test fun `the policy anchors on resource names and androidx classes only`() {
        assertEquals("androidx.recyclerview.widget.RecyclerView",
            DetailViewPurifyPolicy.RECYCLER_VIEW_CLASS)
        assertTrue(DetailViewPurifyPolicy.CHILD_ATTACH_LISTENER_CLASS
            .startsWith("androidx.recyclerview.widget.RecyclerView\$"))
        listOf(
            DetailViewPurifyPolicy.RECYCLER_VIEW_CLASS,
            DetailViewPurifyPolicy.CHILD_ATTACH_LISTENER_CLASS
        ).forEach {
            assertFalse(it, it.contains("com.bilibili"))
            assertFalse(it, it.contains("tv.danmaku"))
        }
    }

    @Test fun `every rule is wired into both catalogs under this installer`() {
        DetailViewPurifyPolicy.rules.forEach { rule ->
            val spec = SettingsCatalog.byStorageKey[rule.preferenceKey]
            assertTrue("missing catalog entry for ${rule.preferenceKey}", spec != null)
            val capability = DiagnosticCapabilityCatalog.byId[rule.capabilityId]
            assertTrue("missing capability for ${rule.capabilityId}", capability != null)
            assertEquals(setOf(spec!!.id), capability!!.settingIds)
            // 这些是 View 层，与协议层那几项必须分属不同的安装器。
            assertEquals(DetailViewPurifyFeatureInstaller.ID, capability.parentId)
        }
    }

    /** 面板把两层的键拼给 UI，但两层各自的白名单不能互相污染。 */
    @Test fun `the view layer keys are disjoint from the protocol layer keys`() {
        val protocol = DetailModulePurifyPolicy.preferenceKeys.toSet()
        val view = DetailViewPurifyPolicy.preferenceKeys.toSet()
        assertTrue(protocol.intersect(view).isEmpty())
    }
}
