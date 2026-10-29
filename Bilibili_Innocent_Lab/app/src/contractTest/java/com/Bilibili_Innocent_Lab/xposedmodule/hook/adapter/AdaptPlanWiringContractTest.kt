package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-01：同步定位计划的接线护栏。
 *
 * 行为由 `AdaptPlanCatalogTest` / `QuickLocatePlanTest` / `AdaptPlanCacheTest` 覆盖；这里锁住它们覆盖不到的
 * 源码事实：定位器与家族的配对、HookEntry 各安装器读取的字段与配置键没有漂移到目录之外、
 * 计划来自安装链已接受的配置快照，以及计划层不绑定 DexKit。
 */
class AdaptPlanWiringContractTest {
    private val adapter = SourceContract.read("hook/VersionAdapter.kt")
    private val entry = SourceContract.read("hook/HookEntry.kt")

    /** 家族 → 定位器函数名 → AdaptResult 属性名。 */
    private class Wiring(val locator: String, val property: String)

    private val wiring: Map<PointFamily, Wiring> = mapOf(
        PointFamily.COMMENT_LOW to Wiring("locateCommentLow", "commentLow"),
        PointFamily.COMMENT_HIGH to Wiring("locateCommentHigh", "commentHigh"),
        PointFamily.MINE_ENTRY to Wiring("locateMineEntry", "mineEntry"),
        PointFamily.PAUSE to Wiring("locatePausePoints", "pause"),
        PointFamily.BANNER to Wiring("locateBanner", "banner"),
        PointFamily.HOME_TOP_BAR to Wiring("locateHomeTopBar", "homeTopBar"),
        PointFamily.MINE_VIP to Wiring("locateMineVip", "mineVip"),
        PointFamily.BLOCK_UPDATE to Wiring("locateBlockUpdate", "blockUpdate"),
        PointFamily.DYNAMIC_TABS to Wiring("locateDynamicTabs", "dynamicTabs"),
        PointFamily.FULL_NUMBERS to Wiring("locateFullNumbers", "fullNumbers"),
        PointFamily.PLAYER_PORTRAIT to Wiring("locatePlayerPortrait", "playerPortrait"),
        PointFamily.PLAYER_INTERACTIVE_OVERLAYS to Wiring("locatePlayerInteractiveOverlays", "playerInteractiveOverlays"),
        PointFamily.PGC_AUTO_ACTIVITY_POPUP to Wiring("locatePgcAutoActivityPopup", "pgcAutoActivityPopup"),
        PointFamily.PLAYER_STATUS_BAR to Wiring("locatePlayerStatusBar", "playerStatusBar"),
        PointFamily.HOME_RECOMMEND_FEED to Wiring("locateHomeRecommendFeed", "homeRecommendFeed"),
        PointFamily.VIDEO_RELATE to Wiring("locateVideoRelate", "videoRelate"),
        PointFamily.HOME_TABS to Wiring("locateHomeTabs", "homeTabs"),
        PointFamily.HOME_COMPONENTS to Wiring("locateHomeComponents", "homeComponents"),
        PointFamily.MINE_COMPONENTS to Wiring("locateMineComponents", "mineComponents"),
        PointFamily.MINE_ACCOUNT_MINE to Wiring("locateMineAccountMinePoints", "mineAccountMine"),
        PointFamily.STORY_FEED to Wiring("locateStoryFeed", "storyFeed"),
        PointFamily.BOTTOM_BAR to Wiring("locateBottomBar", "bottomBar"),
        PointFamily.PLAYER_QUALITY to Wiring("locateDefaultVideoQuality", "playerQuality"),
        PointFamily.TEENAGERS_MODE to Wiring("locateTeenagersMode", "teenagersMode"),
        PointFamily.COMMENT_PURIFY to Wiring("locateCommentPurify", "commentPurify"),
        PointFamily.COMMENT_FILTER to Wiring("locateCommentFilter", "commentFilter"),
        PointFamily.COMMENT_TOPOLOGY to Wiring("locateCommentTopologyWithDiagnostic", "commentTopology"),
        PointFamily.COMMENT_SECTION to Wiring("locateCommentSection", "commentSection"),
        PointFamily.SPLASH_ADS to Wiring("locateSplashAds", "splashAds")
    )

    /** 不在同步定位计划里的字段：各自有按功能开关与历史门控的后台流程（DexKit 兜底家族）。 */
    private val outsidePlan = setOf(
        "homeChrome", "homeChromeSemantics", "pageBoundaries", "followFeedStyle", "playerSpeedMenu",
        "brandSplashDecoder", "sponsorPlayer", "commentClassicStyle", "playerPopupPromotion"
    )

    private fun locatorCalls(text: String): List<Triple<PointFamily, String, String?>> {
        val call = Regex(
            """(?:sync|locators\.locate)(?:<[^>]*>)?\(\s*PointFamily\.(\w+)(?:,\s*previous\?\.(\w+))?""" +
                """(?:,\s*found\s*=\s*\{[^}]*\})?\s*\)\s*\{\s*(locate\w+)\("""
        )
        return call.findAll(text).map { Triple(PointFamily.valueOf(it.groupValues[1]), it.groupValues[3], it.groupValues[2].ifEmpty { null }) }.toList()
    }

    @Test fun `quickLocate runs every family through the plan executor with the right locator`() {
        val quick = adapter.after("internal fun quickLocate(loader: ClassLoader, plan: AdaptPlan")
            .before("private val EMPTY_PAUSE_POINTS")
        val calls = locatorCalls(quick)
        assertEquals("每个家族恰好一次", PointFamily.entries.toSet(), calls.map { it.first }.toSet())
        assertEquals(PointFamily.entries.size, calls.size)
        for ((family, locator, _) in calls) assertEquals(family.name, wiring.getValue(family).locator, locator)
        // 同步路径不读配置、不碰 Context：计划是唯一输入。
        for (forbidden in listOf("getSharedPreferences", "prefs(", "Context", "DexKitBridge", "DexAssistSession("))
            assertFalse(forbidden, quick.contains(forbidden))
        // 直接调用定位器必须经过执行器：quickLocate 里不得再有裸调用。
        for (locator in wiring.values.map { it.locator })
            assertEquals(locator, 1, Regex("""\b$locator\(""").findAll(quick).count())
    }

    @Test fun `adapt applies the same plan and carries only covered families`() {
        val adapt = adapter.after("private fun adapt(context: Context").before("private data class ProtocolFingerprint")
        val calls = locatorCalls(adapt)
        assertEquals(PointFamily.entries.toSet(), calls.map { it.first }.toSet())
        assertEquals(PointFamily.entries.size, calls.size)
        for ((family, locator, previous) in calls) {
            assertEquals(family.name, wiring.getValue(family).locator, locator)
            // 沿用的旧值必须取自同一个家族对应的属性；带 DEX 兜底的三类走 AssistHistory，不在这里沿用。
            if (previous != null) assertEquals(family.name, wiring.getValue(family).property, previous)
        }
        val assist = setOf(PointFamily.BLOCK_UPDATE, PointFamily.PLAYER_QUALITY, PointFamily.COMMENT_TOPOLOGY)
        assertEquals(assist, calls.filter { it.third == null }.map { it.first }.toSet())
        assertTrue(adapt.contains("locators.locateOrCarry(family, prior, previousCoverage, found, block)"))
        assertTrue(adapt.contains("coverage = coverageOf(located, locators, plan, previous?.coverage)"))
        assertTrue(adapt.contains("buildDiagnostics(loader, plan, previous, located,"))
        assertTrue(adapt.contains("protocolFingerprint.toDiagnostic(plan)"))
        // 带 DEX 兜底的显式开关优先：计划比开关小时以多要为准。
        val ensure = adapter.after("internal fun ensureAdapted(").before("fun quickLocate(loader: ClassLoader)")
        assertTrue(ensure.contains("plan.including(buildSet {"))
        for (flag in listOf("blockUpdateEnabled", "playerQualityEnabled", "commentTopologyEnabled"))
            assertTrue(flag, ensure.after("plan.including(buildSet {").before("})").contains("if ($flag)"))
    }

    @Test fun `ensureAdapted judges completeness by coverage and keeps supplement scans silent`() {
        val ensure = adapter.after("internal fun ensureAdapted(").before("fun quickLocate(loader: ClassLoader)")
        assertTrue(ensure.contains("AdaptPlanner.missing(usable.coverage, adaptPlan)"))
        assertTrue(ensure.contains("refresh || coverageMissing.isNotEmpty()"))
        assertTrue(ensure.contains("adaptDecision(usable, refreshRequested, coverageMissing) == AdaptDecision.SUPPLEMENT_SILENTLY"))
        assertTrue(ensure.contains("if (!silentSupplement) runCatching { callback?.onAdaptStarted() }"))
        assertTrue(ensure.contains("if (!silentSupplement) runCatching { callback?.onAdaptFinished(result != null) }"))
        // 补适配用同一份有效缓存当历史，局部结果的沿用与覆盖范围都由 adapt 负责。
        assertTrue(ensure.contains("adaptPlan, popupHistory) }.getOrNull()"))
        // 缓存只在整轮适配成功后写入，实时 quickLocate 的结果（版本号为 0）从不写盘。
        assertEquals(1, Regex("""writeCache\(adapted\)""").findAll(ensure).count())
        val usable = adapter.after("fun usableCache(highCandidateExists: Boolean, plan: AdaptPlan)").before("fun missingCoverage(")
        assertTrue(usable.contains("AdaptPlanner.missing(it.coverage, plan).isEmpty()"))
    }

    @Test fun `plan is generated from the accepted configuration snapshot and passed on unchanged`() {
        val install = entry.after("fun installAuthorizedHooks(authorizationContext: Context) {")
        assertEquals(1, Regex("""AdaptPlanner\.build\(""").findAll(entry).count())
        assertTrue(install.contains("val adaptPlan = AdaptPlanner.build(hookConfig)"))
        assertTrue("先于任何定位", install.indexOf("val adaptPlan = ") < install.indexOf("val startupCache by lazy"))
        assertEquals(2, Regex("""VersionAdapter\.quickLocate\(biliClassLoader, adaptPlan(?:, locateTimings)?\)""").findAll(entry).count())
        // 计时观察点只在 debug 构建创建，release 的同步路径不计时。
        assertTrue(entry.contains("if (com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.DEBUG) LocateTimings() else null"))
        assertEquals("不得再有不带计划的同步定位", 0, Regex("""VersionAdapter\.quickLocate\(biliClassLoader\)""").findAll(entry).count())
        val ensure = entry.after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(ensure.contains("plan = adaptPlan"))
        // 配置代际随快照一起交付，计划记录自己由哪一代生成。
        assertTrue(entry.contains("SnapshotHookConfigSource(config.values, config.generation)"))
    }

    @Test fun `every adapt result field read by the install chain is catalogued`() {
        val install = entry.after("fun installAuthorizedHooks(authorizationContext: Context) {")
        val referenced = Regex("""(?:hostAdaptResult|adaptResult|adapted)\??\.(\w+)""").findAll(install)
            .map { it.groupValues[1] }.toSet() - setOf("diagnosticSummary", "orEmpty")
        val byProperty = wiring.entries.associate { it.value.property to it.key }
        val unknown = referenced.filter { it !in byProperty && it !in outsidePlan }
        assertTrue("安装链读取了目录之外的定位结果字段 $unknown：先为它们登记读取者再改 HookEntry", unknown.isEmpty())
        // 每个同步家族要么确有读取者，要么（仅 MINE_ENTRY）只是另一个家族的定位依赖。
        val unread = byProperty.filterKeys { it !in referenced }.values
        assertEquals(setOf(PointFamily.MINE_ENTRY), unread.toSet())
        assertTrue(AdaptPlanCatalog.spec(PointFamily.MINE_ENTRY)!!.readers.all { it.gate == AdaptPlanCatalog.Gate.Always })
    }

    private fun balanced(text: String, open: Int): String {
        var depth = 1
        var index = open
        while (depth > 0) {
            when (text[index++]) {
                '(' -> depth++
                ')' -> depth--
            }
        }
        return text.substring(open, index - 1)
    }

    private fun constantValue(name: String): String = when (name) {
        "PREF_ENABLED" -> HookEntry.PREF_ENABLED
        "PREF_BANNER_ENABLED" -> HookEntry.PREF_BANNER_ENABLED
        else -> FeaturePreferences::class.java.getField(name).get(null) as String
    }

    /** 构造参数里用局部变量间接引用的配置键。 */
    private val aliases: Map<String, List<String>> = mapOf(
        "videoSemanticJudge" to listOf("VIDEO_SEMANTIC_FILTER_ENABLED", "VIDEO_SEMANTIC_FILTER_RULES"),
        "commentTopologyEnabled" to listOf("REPLY_TOPOLOGY_ENABLED")
    )

    @Test fun `aliases still mean what the catalog assumes`() {
        assertTrue(entry.contains("val videoSemanticJudge = semanticJudge(\n                FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED,\n                FeaturePreferences.VIDEO_SEMANTIC_FILTER_RULES,"))
        assertTrue(entry.contains("val commentTopologyEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false)"))
    }

    @Test fun `gated readers match the configuration keys and defaults HookEntry hands to each installer`() {
        val gatedReaders = AdaptPlanCatalog.specs.flatMap { spec ->
            spec.readers.filter { it.gate is AdaptPlanCatalog.Gate.AnyOf }.map { spec.family to it }
        }
        assertTrue(gatedReaders.size > 25)
        val checked = mutableSetOf<String>()
        for ((family, reader) in gatedReaders) {
            val className = reader.installer.substringBefore('.')
            val starts = Regex("""\b$className\(""").findAll(entry).map { it.range.last + 1 }.toList()
            assertEquals("$className 在 HookEntry 里应恰好构造一次", 1, starts.size)
            val block = balanced(entry, starts.single())
            val usedNames = linkedSetOf<String>()
            Regex("""FeaturePreferences\.(\w+)|\b(PREF_\w+)""").findAll(block).forEach {
                usedNames += it.groupValues[1].ifEmpty { it.groupValues[2] }
            }
            aliases.forEach { (alias, names) -> if (Regex("""\b$alias\b""").containsMatchIn(block)) usedNames += names }
            val used = usedNames.map(::constantValue).toSet()
            val gate = reader.keys
            assertTrue("$family/${reader.installer} 的开关键没有出现在构造参数里: ${gate - used}", used.containsAll(gate))
            val undeclared = used - gate - reader.refines
            assertTrue("$family/${reader.installer} 的构造参数多了未登记的配置键 $undeclared：判断它是不是新的启用开关", undeclared.isEmpty())
            // 默认值必须与安装器实际读取时一致：默认开的开关被写成默认关，会把仍在生效的功能裁掉。
            for (signal in (reader.gate as AdaptPlanCatalog.Gate.AnyOf).signals) {
                val key = signal.keys.single()
                val name = usedNames.firstOrNull { constantValue(it) == key } ?: continue
                if (aliases.values.any { name in it }) continue
                when (signal) {
                    is AdaptPlanCatalog.Flag -> {
                        val read = Regex("""getBoolean\(\s*(?:FeaturePreferences\.)?$name\s*,\s*(true|false)""").find(block)
                        if (read != null) assertEquals("$className/$name 默认值", signal.defaultOn, read.groupValues[1].toBoolean())
                    }
                    is AdaptPlanCatalog.Text -> {
                        val read = Regex("""getString\(\s*(?:FeaturePreferences\.)?$name\s*,\s*([^)]*)\)""").find(block)
                        if (read != null) assertTrue("$className/$name 默认应为空串", read.groupValues[1].trim().startsWith("\"\""))
                    }
                    is AdaptPlanCatalog.NonZero -> {
                        val read = Regex("""getInt\(\s*(?:FeaturePreferences\.)?$name\s*,\s*(-?\d+)""").find(block)
                        if (read != null) assertEquals("$className/$name 默认值", signal.off, read.groupValues[1].toInt())
                    }
                }
                checked += "$className/$name"
            }
        }
        assertTrue("至少核对了大部分开关默认值: ${checked.size}", checked.size > 80)
    }

    @Test fun `scan surface installers still have no enable switch`() {
        // 这些安装器是常开读取者的依据：一旦出现启用开关，家族常开的结论需要重新核对。
        for ((name, anchor) in listOf(
            "HomeTabFilterFeatureInstaller" to "// 即使一项都没勾也要装",
            "HomeComponentFilterFeatureInstaller" to "// 空配置也要装",
            "BottomBarFeatureInstaller" to "// 空配置也要装"
        )) {
            val source = SourceContract.read("hook/feature/$name.kt")
            val install = source.after("override fun install(environment: HookEnvironment): FeatureInstallResult {")
            assertTrue("$name 的安装入口应先写明无开关: $anchor", install.trimStart().startsWith(anchor))
        }
        val mine = SourceContract.read("hook/feature/MineComponentFilterFeatureInstaller.kt")
            .after("override fun install(environment: HookEnvironment): FeatureInstallResult {").before("val snapshots")
        assertFalse(mine.contains("enabled"))
        val centering = SourceContract.read("hook/hostui/top/HomeTopTabCenteringHooks.kt")
        assertTrue(centering.contains("material/glow preferences do not control tab alignment"))
        assertTrue(entry.contains("homeTopTabCenteringEnabled = true"))
    }

    @Test fun `plan layer is independent of the dex backend`() {
        for (file in listOf("hook/adapter/AdaptPlan.kt", "hook/adapter/AdaptPlanCatalog.kt")) {
            val source = SourceContract.read(file)
            assertFalse(file, source.contains("DexKit") || source.contains("adapter.dex") || source.contains("DexAssist"))
        }
    }

    @Test fun `diagnostics are built lazily per planned family`() {
        val diagnostics = adapter.after("internal fun buildDiagnostics(").before("private fun Method.toHookPoint()")
        // 候选类探测必须是惰性的：计划外的家族不能被探测。
        assertFalse(Regex("""\n        val \w+CandidateExists = """).containsMatchIn(diagnostics))
        assertTrue(Regex("""\n        val \w+CandidateExists by lazy""").findAll(diagnostics).count() >= 30)
        assertTrue(diagnostics.contains("if (plan.needs(family)) {"))
        assertTrue(diagnostics.contains("AdaptDiagnostic(id, AdaptState.NOT_APPLICABLE, PLAN_NOT_REQUESTED)"))
    }
}
