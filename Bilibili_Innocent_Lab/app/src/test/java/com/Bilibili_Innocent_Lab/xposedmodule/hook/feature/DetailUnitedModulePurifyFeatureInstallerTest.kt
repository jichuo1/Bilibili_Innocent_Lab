package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.bapis.bilibili.app.viewunite.common.ActivityGuidanceBar
import com.bapis.bilibili.app.viewunite.common.Module
import com.bapis.bilibili.app.viewunite.common.ModuleType
import com.bapis.bilibili.app.viewunite.common.Owner
import com.bapis.bilibili.app.viewunite.common.Vip
import com.bapis.bilibili.app.viewunite.v1.IntroductionTab
import com.bapis.bilibili.app.viewunite.v1.Tab
import com.bapis.bilibili.app.viewunite.v1.TabModule
import com.bapis.bilibili.app.viewunite.v1.ViewReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailUnitedModulePurifyFeatureInstallerTest {

    private val statuses = mutableListOf<Pair<String, String>>()
    private val errors = mutableListOf<String>()
    private val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
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
        runtimeEvidence = { id, stage, _ -> evidence += id to stage },
        capabilityEvidence = { id, result -> capabilities += id to result }
    )

    private val policy = DetailUnitedModulePurifyPolicy
    private val allKeys = policy.preferenceKeys.toSet()

    private fun cleaner(
        targets: Collection<DetailUnitedModulePurifyPolicy.ModuleTarget> = policy.moduleTargets,
        upVipLabel: Boolean = true
    ) = requireNotNull(
        UnitedModuleReplyCleaner.resolve(javaClass.classLoader!!, targets, upVipLabel)
    )

    private fun reply(
        modules: List<Module> = emptyList(),
        vip: Vip? = null,
        extraTabs: List<TabModule> = emptyList(),
        failBuild: Boolean = false
    ): ViewReply {
        val intro = TabModule("introduction", IntroductionTab("简介", modules))
        return ViewReply(
            Tab("bg", listOf(intro) + extraTabs),
            Owner("UP", vip),
            failBuild
        )
    }

    private fun modulesOf(reply: ViewReply): List<String> =
        reply.tab.tabModuleList
            .filter { it.hasIntroduction() }
            .flatMap { it.introduction.modulesList }
            .map { it.label() }

    // ---------- 安装期 ----------

    @Test fun `skips without touching the host when every subitem is off`() {
        val result = DetailUnitedModulePurifyFeatureInstaller(emptySet()).install(environment())
        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("detail_united_module_status" to "disabled"), statuses)
        assertTrue(evidence.isEmpty())
    }

    @Test fun `skips in non main processes`() {
        val result = DetailUnitedModulePurifyFeatureInstaller(allKeys)
            .install(environment(process = "tv.danmaku.bili:web"))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), result)
        assertTrue(statuses.isEmpty())
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
            DetailUnitedModulePurifyFeatureInstaller(allKeys).install(env)
        )
        assertTrue("detail_united_module_missing" in errors)
    }

    @Test fun `degrades when the viewunite classes are absent`() {
        val empty = object : ClassLoader(null) {}
        val result = DetailUnitedModulePurifyFeatureInstaller(allKeys)
            .install(environment(loader = empty))
        assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), result)
        assertTrue("detail_united_module_missing" in errors)
    }

    @Test fun `installs both routes and reports every enabled subitem`() {
        val installer = DetailUnitedModulePurifyFeatureInstaller(allKeys)
        assertEquals(policy.capabilityIds, installer.capabilityIds)
        assertEquals(
            FeatureInstallResult.Installed(2, complete = true),
            installer.install(environment())
        )
        assertEquals(listOf("detail_united_module_status" to "success"), statuses)
        assertEquals(policy.capabilityIds.toSet(), capabilities.map { it.first }.toSet())
    }

    @Test fun `only the enabled subitems are reported as capabilities`() {
        val installer = DetailUnitedModulePurifyFeatureInstaller(setOf(policy.HONOR.preferenceKey))
        assertEquals(listOf(policy.HONOR.capabilityId), installer.capabilityIds)
        installer.install(environment())
        assertEquals(listOf(policy.HONOR.capabilityId), capabilities.map { it.first })
    }

    // ---------- 模块删除 ----------

    @Test fun `removes only the modules whose type matches an enabled subitem`() {
        val original = reply(
            listOf(
                Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"),
                Module(ModuleType.HONOR_VALUE, "荣誉"),
                Module(ModuleType.SPECIALTAG_VALUE, "话题"),
                Module(ModuleType.OWNER_VALUE, "UP行")
            )
        )
        val updated = cleaner(targets = listOf(policy.HONOR), upVipLabel = false)
            .clean(original, environment()) as ViewReply
        assertNotSame(original, updated)
        assertEquals(listOf("简介", "话题", "UP行"), modulesOf(updated))
        // 原响应一字未动。
        assertEquals(listOf("简介", "荣誉", "话题", "UP行"), modulesOf(original))
    }

    @Test fun `removes every enabled module type in one copy and keeps order`() {
        val original = reply(
            listOf(
                Module(ModuleType.HONOR_VALUE, "荣誉"),
                Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"),
                Module(ModuleType.LIVE_ORDER_VALUE, "直播预约"),
                Module(ModuleType.UGC_SEASON_VALUE, "合辑"),
                Module(ModuleType.OWNER_VALUE, "UP行"),
                Module(ModuleType.SPECIALTAG_VALUE, "话题")
            )
        )
        val updated = cleaner(upVipLabel = false).clean(original, environment()) as ViewReply
        assertEquals(listOf("简介", "UP行"), modulesOf(updated))
    }

    /** 正常视频没有这些模块：不能分配副本，也不能报 APPLIED。 */
    @Test fun `returns the same instance when nothing matches`() {
        val original = reply(listOf(Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介")))
        val result = cleaner().clean(original, environment())
        assertSame(original, result)
        assertTrue(evidence.none { it.second == FeatureRuntimeStage.APPLIED })
    }

    /** 只给真的删掉的子项记证据——「报了 APPLIED」必须等于「真的删掉了一个东西」。 */
    @Test fun `evidence is reported only for the subitems actually present`() {
        val original = reply(listOf(Module(ModuleType.HONOR_VALUE, "荣誉")))
        cleaner(upVipLabel = false).clean(original, environment())
        val applied = evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
        assertTrue(policy.HONOR.capabilityId in applied)
        assertFalse(policy.UGC_SEASON.capabilityId in applied)
        assertFalse(policy.TOPIC_TAGS.capabilityId in applied)
    }

    /** 评论页之类没有 introduction 的 tab 必须原样留下，不能被改写或丢弃。 */
    @Test fun `tabs without an introduction are left untouched`() {
        val reply = reply(
            modules = listOf(Module(ModuleType.HONOR_VALUE, "荣誉")),
            extraTabs = listOf(TabModule("reply", null), TabModule("catalog", null))
        )
        val updated = cleaner(upVipLabel = false).clean(reply, environment()) as ViewReply
        assertEquals(
            listOf("introduction", "reply", "catalog"),
            updated.tab.tabModuleList.map { it.name }
        )
        assertFalse(updated.tab.tabModuleList[1].hasIntroduction())
    }

    @Test fun `a reply without a tab is passed through untouched`() {
        val original = ViewReply(null, Owner("UP", null))
        assertSame(original, cleaner().clean(original, environment()))
    }

    /** 默认实例是进程级单例，改写它会污染整个宿主进程。 */
    @Test fun `the default instance is never rewritten`() {
        val original = ViewReply.getDefaultInstance()
        assertSame(original, cleaner().clean(original, environment()))
    }

    @Test fun `a foreign payload is passed through untouched`() {
        val original = "not a reply"
        assertSame(original, cleaner().clean(original, environment()))
        assertTrue(evidence.isEmpty())
    }

    /** build 失败必须交付原响应，不能让详情页白屏。 */
    @Test fun `fails open to the original reply when the copy cannot be built`() {
        val original = reply(listOf(Module(ModuleType.HONOR_VALUE, "荣誉")), failBuild = true)
        val result = cleaner(upVipLabel = false).clean(original, environment())
        assertSame(original, result)
        assertTrue("detail_united_module_copy_failed" in errors)
        assertTrue(evidence.any { it.second == FeatureRuntimeStage.ERROR })
    }

    // ---------- 热搜横条（条件判据） ----------

    private val hotSearchUrl =
        "bilibili://search?keyword=%E6%B5%8B%E8%AF%95&from=apphotword_search_huangtiao"

    private fun bannerCleaner(unmatched: MutableList<String> = mutableListOf()) =
        requireNotNull(
            UnitedModuleReplyCleaner.resolve(
                javaClass.classLoader!!, emptyList(), upVipLabel = false, hotBanner = true
            ) { unmatched += it }
        )

    @Test fun `removes a guidance bar whose url is a trending search jump`() {
        val original = reply(
            listOf(
                Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"),
                Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "热搜横条",
                    ActivityGuidanceBar(hotSearchUrl))
            )
        )
        val updated = bannerCleaner().clean(original, environment()) as ViewReply
        assertEquals(listOf("简介"), modulesOf(updated))
        assertTrue(
            DetailUnitedModulePurifyPolicy.HotBannerGuidanceBar.CAPABILITY_ID in
                evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
        )
    }

    /**
     * 最重要的一条：`ACTIVITY_GUIDANCE_BAR` 也承载普通活动引导条。
     *
     * 光按类型删会把用户没要求隐藏的活动条一起拿掉，所以 URL 对不上必须原样放行。
     */
    @Test fun `an ordinary activity guidance bar is left untouched`() {
        val original = reply(
            listOf(Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "活动条",
                ActivityGuidanceBar("https://www.bilibili.com/blackboard/activity.html")))
        )
        assertSame(original, bannerCleaner().clean(original, environment()))
    }

    /** 没匹配上的引导条只记**一次** URL：留给真机确认判据，又不会每条响应刷日志。 */
    @Test fun `the url of an unmatched guidance bar is logged exactly once`() {
        val unmatched = mutableListOf<String>()
        val cleaner = bannerCleaner(unmatched)
        val original = reply(
            listOf(Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "活动条",
                ActivityGuidanceBar("bilibili://pegasus/channel/v2")))
        )
        repeat(3) { cleaner.clean(original, environment()) }
        assertEquals(listOf("bilibili://pegasus/channel/v2"), unmatched)
    }

    /**
     * 判据是**类别**（跳搜索），所以 from 的其它变体也命中——这是有意的。
     *
     * 「热搜」`apphotword_search_huangtiao` 与「活动」`app_comment_topic_search`
     * 是同一套结构，运营文案和来源标记都会变；按某个精确 from 值判必然漏网。
     * 这条测试和 `an ordinary activity guidance bar is left untouched` 一起
     * 界定了范围：**跳搜索的引导条都清，不跳搜索的一律不动。**
     */
    @Test fun `guidance bars are matched by category so every from variant is covered`() {
        listOf(
            "bilibili://search?from=apphotword_search_huangtiao",
            "bilibili://search?from=app_comment_topic_search",
            "bilibili://search?from=whatever_comes_next"
        ).forEach { url ->
            val original = reply(
                listOf(Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "条", ActivityGuidanceBar(url)))
            )
            val updated = bannerCleaner().clean(original, environment())
            assertNotSame(url, original, updated)
            assertEquals(url, emptyList<String>(), modulesOf(updated as ViewReply))
        }
    }

    /** 类型对上但载荷缺失（oneof 是别的分支）时也不能动。 */
    @Test fun `a guidance bar type without a payload is left untouched`() {
        val original = reply(listOf(Module(ModuleType.ACTIVITY_GUIDANCE_BAR_VALUE, "空载荷")))
        assertSame(original, bannerCleaner().clean(original, environment()))
    }

    // ---------- 视频提及（游戏推广卡）协议层总闸 ----------

    @Test fun `removes the video mentions module when the game card switch is on`() {
        val cleaner = requireNotNull(
            UnitedModuleReplyCleaner.resolve(
                javaClass.classLoader!!,
                listOf(DetailUnitedModulePurifyPolicy.VideoMentions.TARGET),
                upVipLabel = false
            )
        )
        val original = reply(
            listOf(
                Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"),
                Module(ModuleType.VIDEO_MENTIONS_VALUE, "游戏推广卡")
            )
        )
        val updated = cleaner.clean(original, environment()) as ViewReply
        assertEquals(listOf("简介"), modulesOf(updated))
    }

    /**
     * 沿用"默认开"老开关的子项**一律不许**混进
     * [DetailUnitedModulePurifyPolicy.preferenceKeys]。
     *
     * 那条路径按 `prefs.getBoolean(key, false)`（默认关）过滤；
     * 混进去会把老用户的默认行为从"开"悄悄改成"关"，是行为回退。
     */
    @Test fun `default-on switches stay out of the default-off key list`() {
        DetailUnitedModulePurifyPolicy.defaultOnTargets.forEach { target ->
            assertFalse(
                target.preferenceKey,
                target.preferenceKey in DetailUnitedModulePurifyPolicy.preferenceKeys
            )
            // 不显式传 flag 就不该出现在能力表里。
            assertFalse(
                target.capabilityId,
                target.capabilityId in
                    DetailUnitedModulePurifyPolicy.capabilityIdsFor(allKeys)
            )
        }
        assertTrue(
            DetailUnitedModulePurifyPolicy.VideoMentions.CAPABILITY_ID in
                DetailUnitedModulePurifyPolicy.capabilityIdsFor(allKeys, videoMentions = true)
        )
        assertTrue(
            DetailUnitedModulePurifyPolicy.Merchandise.CAPABILITY_ID in
                DetailUnitedModulePurifyPolicy.capabilityIdsFor(allKeys, merchandise = true)
        )
    }

    // ---------- 好物商品卡协议层总闸 ----------

    @Test fun `removes the merchandise module when the goods switch is on`() {
        val cleaner = requireNotNull(
            UnitedModuleReplyCleaner.resolve(
                javaClass.classLoader!!,
                listOf(DetailUnitedModulePurifyPolicy.Merchandise.TARGET),
                upVipLabel = false
            )
        )
        val original = reply(
            listOf(
                Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介"),
                Module(ModuleType.MERCHANDISE_VALUE, "好物商品卡"),
                Module(ModuleType.OWNER_VALUE, "UP行")
            )
        )
        val updated = cleaner.clean(original, environment()) as ViewReply
        assertEquals(listOf("简介", "UP行"), modulesOf(updated))
    }

    /**
     * 每个"默认开"子项都只删自己那一类模块，不许互相波及。
     *
     * 视频提及与好物是两个独立开关，用户可能只关一个。
     */
    @Test fun `each default-on target removes only its own module type`() {
        val modules = listOf(
            Module(ModuleType.VIDEO_MENTIONS_VALUE, "提及"),
            Module(ModuleType.MERCHANDISE_VALUE, "好物"),
            Module(ModuleType.UGC_INTRODUCTION_VALUE, "简介")
        )
        val onlyMentions = requireNotNull(
            UnitedModuleReplyCleaner.resolve(
                javaClass.classLoader!!,
                listOf(DetailUnitedModulePurifyPolicy.VideoMentions.TARGET),
                upVipLabel = false
            )
        ).clean(reply(modules), environment()) as ViewReply
        assertEquals(listOf("好物", "简介"), modulesOf(onlyMentions))

        val onlyGoods = requireNotNull(
            UnitedModuleReplyCleaner.resolve(
                javaClass.classLoader!!,
                listOf(DetailUnitedModulePurifyPolicy.Merchandise.TARGET),
                upVipLabel = false
            )
        ).clean(reply(modules), environment()) as ViewReply
        assertEquals(listOf("提及", "简介"), modulesOf(onlyGoods))
    }

    // ---------- UP 会员标 ----------

    @Test fun `clears the uploader vip badge without dropping the uploader row`() {
        val original = reply(vip = Vip("年度大会员"))
        val updated = cleaner(targets = emptyList()).clean(original, environment()) as ViewReply
        assertNotSame(original, updated)
        assertFalse(updated.owner.hasVip())
        assertNull(updated.owner.vip)
        // UP 主本人的信息行必须还在——整模块删掉是过度处理。
        assertEquals("UP", updated.owner.name)
        assertTrue(original.owner.hasVip())
    }

    @Test fun `a reply whose uploader has no vip badge is passed through untouched`() {
        val original = reply(vip = null)
        assertSame(original, cleaner(targets = emptyList()).clean(original, environment()))
    }

    /** 两条链共用同一次 ViewReply 副本：一次响应最多复制一次顶层消息。 */
    @Test fun `modules and the vip badge are written back in one reply copy`() {
        val original = reply(listOf(Module(ModuleType.HONOR_VALUE, "荣誉")), vip = Vip("大会员"))
        val updated = cleaner().clean(original, environment()) as ViewReply
        assertEquals(emptyList<String>(), modulesOf(updated))
        assertFalse(updated.owner.hasVip())
        val applied = evidence.filter { it.second == FeatureRuntimeStage.APPLIED }.map { it.first }
        assertTrue(policy.HONOR.capabilityId in applied)
        assertTrue(DetailUnitedModulePurifyPolicy.UpVipLabel.CAPABILITY_ID in applied)
    }

    // ---------- 判据与降级 ----------

    /** 每条子项的枚举常量名必须真的存在；写错（比如加下划线）会静默失效。 */
    @Test fun `every module target resolves a distinct integer type constant`() {
        val values = policy.moduleTargets.map { target ->
            val field = ModuleType::class.java.getDeclaredField(target.typeConstant)
            assertEquals(target.typeConstant, Int::class.javaPrimitiveType, field.type)
            field.getInt(null)
        }
        assertEquals(values.size, values.toSet().size)
    }

    /** 话题标签的枚举名没有下划线，单独钉住，别人重构时容易顺手写成 SPECIAL_TAG_VALUE。 */
    @Test fun `the topic tag constant is spelled without an underscore`() {
        assertEquals("SPECIALTAG_VALUE", policy.TOPIC_TAGS.typeConstant)
    }

    /** 某个枚举常量在将来版本消失时，只有那一项降级，其余照常删。 */
    @Test fun `an unknown type constant degrades only its own subitem`() {
        val bogus = DetailUnitedModulePurifyPolicy.ModuleTarget(
            "detail_united_bogus", "bogus_key", "NO_SUCH_VALUE"
        )
        val resolved = requireNotNull(
            UnitedModuleReplyCleaner.resolve(
                javaClass.classLoader!!, listOf(policy.HONOR, bogus), upVipLabel = false
            )
        )
        assertEquals(setOf(policy.HONOR.capabilityId), resolved.capabilityIds)
        val original = reply(listOf(Module(ModuleType.HONOR_VALUE, "荣誉")))
        assertEquals(emptyList<String>(), modulesOf(resolved.clean(original, environment()) as ViewReply))
    }

    /** 常量类型漂移（int 变别的）也必须被拒绝，不能拿一个 String 去比 int。 */
    @Test fun `a type constant that is not an int is rejected`() {
        val drifted = DetailUnitedModulePurifyPolicy.ModuleTarget(
            "detail_united_drifted", "drifted_key", "NOT_AN_INT_VALUE"
        )
        assertNull(
            UnitedModuleReplyCleaner.resolve(
                javaClass.classLoader!!, listOf(drifted), upVipLabel = false
            )
        )
    }

    @Test fun `resolution returns null when nothing at all can be wired`() {
        val empty = object : ClassLoader(null) {}
        assertNull(UnitedModuleReplyCleaner.resolve(empty, policy.moduleTargets, upVipLabel = true))
    }

    // ---------- 与其它层的边界 ----------

    /**
     * 三层互为保底，但**键不能互相污染**：每一层只拿自己那份白名单。
     *
     * 键集合故意是重叠的（同一个用户开关驱动多层），但 capability id 必须完全不相交，
     * 否则诊断里就分不出"是哪一层真的生效了"。
     */
    @Test fun `the three layers share preference keys but never share capability ids`() {
        val united = policy.capabilityIds.toSet()
        val legacy = DetailModulePurifyPolicy.targets.map { it.capabilityId }.toSet() +
            DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID
        val view = DetailViewPurifyPolicy.rules.map { it.capabilityId }.toSet()
        val presentation = setOf(
            DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID,
            DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID
        )
        val all = listOf(united, legacy, view, presentation)
        assertEquals(all.sumOf { it.size }, all.flatten().toSet().size)
        // 而偏好键是共享的：本层覆盖 legacy 那五项完全相同的开关，另外多兜热搜横条。
        assertTrue(policy.preferenceKeys.containsAll(DetailModulePurifyPolicy.preferenceKeys))
        assertTrue(DetailViewPurifyPolicy.HOT_BANNER.preferenceKey in policy.preferenceKeys)
    }

    /**
     * 话题标签必须有一条**不含混淆类名**的主路径。
     *
     * 展示模型层那条走的是 `...module.tags.f` / `.j` 这种 R8 重命名过的类名
     * （见 [DetailUnitedPresentationPurifyPolicy.specialTagMapperClasses] 的说明：
     * 24 版扫描后确认没有更稳的锚点，只能作次级保底）。
     * 所以协议层这条按 `ModuleType.SPECIALTAG` 删模块的路径是主路径，
     * 谁把它删掉，这条测试会立刻拦住。
     */
    @Test fun `the topic tag feature keeps a primary path free of obfuscated class names`() {
        val primary = policy.TOPIC_TAGS
        assertEquals(FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS, primary.preferenceKey)
        assertEquals("SPECIALTAG_VALUE", primary.typeConstant)
        // 主路径用到的类名都是未混淆的 bapis 生成类。只看**简单类名**——
        // 包里的 `v1` 之类是正常的版本段，混淆表现在简单名上（`f`、`j`、`b`）。
        listOf(policy.MODULE_CLASS, policy.MODULE_TYPE_CLASS, policy.INTRODUCTION_TAB_CLASS,
            policy.TAB_CLASS, policy.TAB_MODULE_CLASS, policy.OWNER_CLASS).forEach { name ->
            val simple = name.substringAfterLast('.')
            assertTrue("$name 的简单名疑似被混淆: $simple", simple.length > 2)
        }
        // 次级那条确实是混淆名——这里把"它是次级"这件事写死，避免有人误以为可以只留它。
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.specialTagMapperClasses
                .any { it.substringAfterLast('.').length <= 2 }
        )
    }

    @Test fun `every subitem is wired into both catalogs under this installer`() {
        (policy.moduleTargets.map { it.capabilityId to it.preferenceKey } +
            (DetailUnitedModulePurifyPolicy.UpVipLabel.CAPABILITY_ID to DetailUnitedModulePurifyPolicy.UpVipLabel.PREFERENCE_KEY)
            ).forEach { (capabilityId, preferenceKey) ->
            val spec = SettingsCatalog.byStorageKey[preferenceKey]
            assertTrue("missing catalog entry for $preferenceKey", spec != null)
            val capability = DiagnosticCapabilityCatalog.byId[capabilityId]
            assertTrue("missing capability for $capabilityId", capability != null)
            assertEquals(setOf(spec!!.id), capability!!.settingIds)
            assertEquals(DetailUnitedModulePurifyPolicy.ID, capability.parentId)
        }
    }

    /** 挂点必须是 viewunite 那条，不能又挂回 view.v1——那是原来不生效的根源。 */
    @Test fun `the policy anchors on the viewunite protocol face`() {
        assertTrue(policy.MOSS_CLASS.startsWith("com.bapis.bilibili.app.viewunite.v1."))
        assertTrue(policy.REPLY_CLASS.startsWith("com.bapis.bilibili.app.viewunite."))
        listOf(policy.TAB_CLASS, policy.TAB_MODULE_CLASS, policy.INTRODUCTION_TAB_CLASS,
            policy.MODULE_CLASS, policy.MODULE_TYPE_CLASS, policy.OWNER_CLASS).forEach {
            assertTrue(it, it.startsWith("com.bapis.bilibili.app.viewunite."))
            assertFalse(it, it.contains(".view.v1."))
        }
    }
}
