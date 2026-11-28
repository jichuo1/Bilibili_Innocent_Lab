package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntrySource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 适配计划、生命周期与缓存维护的源码契约护栏：
 * - 同步定位计划与后台 adapt 的家族/定位器配对、安装链配置键对齐，以及计划层不绑定 DEX 后端；
 * - 历史兜底经 AdaptAssistGate 入队，补充扫描与配置快照同源；
 * - 适配完成回调只读一次缓存，缓存维护/重打包复用全部在后台线程完成。
 */
class AdaptPlanAndLifecycleContractTest {

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
        "brandSplashDecoder", "brandSplashKntrDraw", "brandSplashKntrExit", "brandSplashKntrPage",
        "cloudKeyFreezeContract", "systemEnvironment",
        "sponsorPlayer", "playerSpeedSession", "playerSpeedCalculator", "weChatShare",
        "commentClassicStyle", "playerPopupPromotion", "commentComposeMenu", "kmpGrpcWireEngine",
        "privacyIgnetHttp", "privacyKNeuron", "guestPlayerCore", "detailSpecialTagMapper", "commentReplyButtonOwners",
        "homeRecommendEarlyLoadListener",
        "aiPlaylistDirector", "followUpRecommendBodies", "detailRelateGameComponent",
        "legacyStoryDanmakuBind", "playerSpeedTheseus2LongPress"
    )

    /** 构造参数里用局部变量间接引用的配置键。 */
    private val aliases: Map<String, List<String>> = mapOf(
        "videoSemanticJudge" to listOf("VIDEO_SEMANTIC_FILTER_ENABLED", "VIDEO_SEMANTIC_FILTER_RULES"),
        "commentTopologyEnabled" to listOf("REPLY_TOPOLOGY_ENABLED")
    )

    private fun locatorCalls(text: String): List<Triple<PointFamily, String, String?>> {
        val call = Regex(
            """(?:sync|locators\.locate)(?:<[^>]*>)?\(\s*PointFamily\.(\w+)(?:,\s*previous\?\.(\w+))?""" +
                """(?:,\s*found\s*=\s*\{[^}]*\})?\s*\)\s*\{\s*(locate\w+)\("""
        )
        return call.findAll(text)
            .map { Triple(PointFamily.valueOf(it.groupValues[1]), it.groupValues[3], it.groupValues[2].ifEmpty { null }) }
            .toList()
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

    @Test
    fun `quickLocate, adapt, and diagnostics honor plan wiring and stay independent of dex backend`() {
        val quickCalls = locatorCalls(quickLocate)
        assertEquals("每个家族恰好一次", PointFamily.entries.toSet(), quickCalls.map { it.first }.toSet())
        assertEquals(PointFamily.entries.size, quickCalls.size)
        for ((family, locator, _) in quickCalls) assertEquals(family.name, wiring.getValue(family).locator, locator)
        // 同步路径不读配置、不碰 Context：计划是唯一输入。
        for (forbidden in listOf("getSharedPreferences", "prefs(", "Context", "DexKitBridge", "DexAssistSession(")) {
            assertFalse(forbidden, quickLocate.contains(forbidden))
        }
        // 直接调用定位器必须经过执行器：quickLocate 里不得再有裸调用。
        for (locator in wiring.values.map { it.locator }) {
            assertEquals(locator, 1, Regex("""\b$locator\(""").findAll(quickLocate).count())
        }
        // 同步路径的诊断同样必须带版本码（quickLocatePlanned 透传 hostVersionCode）。
        assertTrue(quickLocate.contains("commentTopologyOutcome?.failureDetail.orEmpty(), hostVersionCode"))

        val adaptCalls = locatorCalls(adapt)
        assertEquals(PointFamily.entries.toSet(), adaptCalls.map { it.first }.toSet())
        assertEquals(PointFamily.entries.size, adaptCalls.size)
        for ((family, locator, previous) in adaptCalls) {
            assertEquals(family.name, wiring.getValue(family).locator, locator)
            // 沿用的旧值必须取自同一个家族对应的属性；带 DEX 兜底的三类走 AssistHistory，不在这里沿用。
            if (previous != null) assertEquals(family.name, wiring.getValue(family).property, previous)
        }
        val assist = setOf(PointFamily.BLOCK_UPDATE, PointFamily.PLAYER_QUALITY, PointFamily.COMMENT_TOPOLOGY)
        assertEquals(assist, adaptCalls.filter { it.third == null }.map { it.first }.toSet())
        assertTrue(adapt.contains("locators.locateOrCarry(family, prior, previousCoverage, found, block)"))
        assertTrue(adapt.contains("coverage = coverageOf(located, locators, plan, previous?.coverage)"))
        // 诊断必须拿到本轮版本码：已验真 owner 的候选存在性按构建判定，漏传会把适配失败误记 NOT_APPLICABLE。
        assertTrue(adapt.contains("buildDiagnostics(loader, plan, previous, located, commentTopologyOutcome.failureDetail, vc)"))
        assertTrue(adapt.contains("protocolFingerprint.toDiagnostic(plan)"))
        // 带 DEX 兜底的显式开关优先：计划比开关小时以多要为准。
        assertTrue(ensureAdapted.contains("plan.including(buildSet {"))
        for (flag in listOf("blockUpdateEnabled", "playerQualityEnabled", "commentTopologyEnabled")) {
            assertTrue(flag, ensureAdapted.after("plan.including(buildSet {").before("})").contains("if ($flag)"))
        }

        for (file in listOf("hook/adapter/AdaptPlan.kt", "hook/adapter/AdaptPlanCatalog.kt")) {
            val source = SourceContract.read(file)
            for (forbidden in listOf("DexKit", "DexMeguviz", "DexRefHookBridge", "adapter.dex", "DexAssist")) {
                assertFalse("$file must not reference $forbidden", source.contains(forbidden))
            }
        }

        val diagnostics = adapter.after("internal fun buildDiagnostics(").before("fun locateMineEntry(")
        // 候选类探测必须是惰性的：计划外的家族不能被探测。
        assertFalse(Regex("""\n        val \w+CandidateExists = """).containsMatchIn(diagnostics))
        assertTrue(Regex("""\n        val \w+CandidateExists by lazy""").findAll(diagnostics).count() >= 30)
        assertTrue(diagnostics.contains("if (plan.needs(family)) {"))
        assertTrue(diagnostics.contains("AdaptDiagnostic(id, AdaptState.NOT_APPLICABLE, PLAN_NOT_REQUESTED)"))
        assertTrue(VersionAdapterSource.file("locators/LocatorReflectUtils").contains("fun Method.toHookPoint()"))
    }

    @Test
    fun `plan snapshot, catalogued readers, defaults, and scan surfaces match HookEntry`() {
        val install = entry.after("fun installAuthorizedHooks(authorizationContext: Context) {")
        assertEquals(1, Regex("""AdaptPlanner\.build\(""").findAll(entry).count())
        assertTrue(install.contains("val adaptPlan = AdaptPlanner.build(hookConfig)"))
        assertTrue("先于任何定位", install.indexOf("val adaptPlan = ") < install.indexOf("val startupCache by lazy"))
        // 两处同步定位都必须带版本码：9.18+ 的媒体 owner 只有按构建查 VERIFIED_MEDIA_OWNERS 才能命中，
        // 漏传会静默退化成候选表，丢掉已验真实现并让诊断误分类。精确锁住两种形态，禁止传 null 绕过。
        val freeCopyInstaller = SourceContract.read("hook/feature/freecopy/FreeCopyFeatureInstaller.kt")
        val quickEntry = Regex("""VersionAdapter\.quickLocate\(\s*biliClassLoader,\s*adaptPlan[^)]*\)""")
            .findAll(entry).map { it.value }.toList()
        val quickFreeCopy = Regex("""VersionAdapter\.quickLocate\(\s*loader,\s*adaptPlan[^)]*\)""")
            .findAll(freeCopyInstaller).map { it.value }.toList()
        val quickCalls = quickEntry + quickFreeCopy
        assertEquals(2, quickCalls.size)
        assertTrue(quickCalls.toString(), quickEntry.any { it.contains("adaptPlan, locateTimings, hostVersionCode)") })
        assertTrue(quickCalls.toString(), quickFreeCopy.any { it.contains("adaptPlan, hostVersionCode = hostVersionCode()") })
        assertTrue(install.contains("hostVersionCode = { startupCache.identity.versionCode }"))
        assertTrue(install.contains("val hostVersionCode = startupCache.identity.versionCode"))
        // 计时观察点只在 debug 构建创建，release 的同步路径不计时。
        assertTrue(entry.contains("if (com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.DEBUG) LocateTimings() else null"))
        assertEquals(
            "不得再有不带计划的同步定位",
            0,
            Regex("""VersionAdapter\.quickLocate\((?:biliClassLoader|loader)\)""").findAll(entry + "\n" + freeCopyInstaller).count()
        )
        val ensureCall = entry.after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(ensureCall.contains("plan = adaptPlan"))
        // 配置代际随快照一起交付，计划记录自己由哪一代生成。
        assertTrue(entry.contains("SnapshotHookConfigSource(config.values, config.generation)"))

        val referenced = Regex("""(?:hostAdaptResult|adaptResult|adapted)\??\.(\w+)""").findAll(install)
            .map { it.groupValues[1] }.toSet() - setOf("diagnosticSummary", "orEmpty")
        val byProperty = wiring.entries.associate { it.value.property to it.key }
        val unknown = referenced.filter { it !in byProperty && it !in outsidePlan }
        assertTrue("安装链读取了目录之外的定位结果字段 $unknown：先为它们登记读取者再改 HookEntry", unknown.isEmpty())
        // 每个同步家族要么确有读取者，要么（仅 MINE_ENTRY）只是另一个家族的定位依赖。
        val unread = byProperty.filterKeys { it !in referenced }.values
        assertEquals(setOf(PointFamily.MINE_ENTRY), unread.toSet())
        assertTrue(AdaptPlanCatalog.spec(PointFamily.MINE_ENTRY)!!.readers.all { it.gate == AdaptPlanCatalog.Gate.Always })

        assertTrue(entry.contains("val videoSemanticJudge = semanticJudge(\n                FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED,\n                FeaturePreferences.VIDEO_SEMANTIC_FILTER_RULES,"))
        assertTrue(entry.contains("val commentTopologyEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false)"))

        val gatedReaders = AdaptPlanCatalog.specs.flatMap { spec ->
            spec.readers.filter { it.gate is AdaptPlanCatalog.Gate.AnyOf }.map { spec.family to it }
        }
        assertTrue(gatedReaders.size > 25)
        val checked = mutableSetOf<String>()
        for ((family, reader) in gatedReaders) {
            val className = reader.installer.split('.').first()
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

        // 这些安装器是常开读取者的依据：一旦出现启用开关，家族常开的结论需要重新核对。
        for ((name, anchor) in listOf(
            "HomeTabFilterFeatureInstaller" to "// 即使一项都没勾也要装",
            "HomeComponentFilterFeatureInstaller" to "// 空配置也要装",
            "BottomBarFeatureInstaller" to "// 空配置也要装"
        )) {
            val source = SourceContract.read("hook/feature/$name.kt")
            val installBody = source.after("override fun install(environment: HookEnvironment): FeatureInstallResult {")
            assertTrue("$name 的安装入口应先写明无开关: $anchor", installBody.trimStart().startsWith(anchor))
        }
        val mine = SourceContract.read("hook/feature/MineComponentFilterFeatureInstaller.kt")
            .after("override fun install(environment: HookEnvironment): FeatureInstallResult {").before("val snapshots")
        assertFalse(mine.contains("enabled"))
        val centering = SourceContract.read("hook/hostui/top/HomeTopTabCenteringHooks.kt")
        assertTrue(centering.contains("material/glow preferences do not control tab alignment"))
        assertTrue(entry.contains("homeTopTabCenteringEnabled = true"))
    }

    @Test
    fun `assist gate controls dex queries, skipped cache carry-over, and supplemental refresh`() {
        val planned = adapt.after("planned = buildSet {").before("classicMissing.forEach")
        assertTrue(planned.contains("if (blockUpdatePlanned) add(DexAssistQuery.BLOCK_UPDATE)"))
        assertTrue(planned.contains("if (playerQualityPlanned) add(DexAssistQuery.PLAYER_DEFAULT_QUALITY)"))
        assertTrue(planned.contains("if (topologyPlanned) add(DexAssistQuery.COMMENT_REPLY_MAPPER)"))
        assertFalse(planned.contains("if (directBlockUpdate == null)"))
        assertFalse(planned.contains("if (playerQualityNeedsAssist)"))
        assertFalse(planned.contains("if (directTopologyOutcome.points == null)"))

        for ((flag, plannedVar) in listOf(
            "blockUpdateEnabled" to "blockUpdatePlanned",
            "playerQualityEnabled" to "playerQualityPlanned",
            "commentTopologyEnabled" to "topologyPlanned"
        )) {
            val line = adapt.after("val $plannedVar = AdaptAssistGate.needsQuery($flag,").before("val ")
            assertTrue(plannedVar, line.contains("assistHistory."))
        }

        assertTrue(adapt.contains("blockUpdatePlanned -> locateBlockUpdateByDex(loader, dexAssist)"))
        assertTrue(adapt.contains("playerQualityPlanned -> locatePlayerQualityByDex(loader, dexAssist)"))
        assertTrue(adapt.contains("topologyPlanned -> locateCommentTopologyMapperByDex(loader, dexAssist)"))
        for (call in listOf("locateBlockUpdateByDex(", "locatePlayerQualityByDex(", "locateCommentTopologyMapperByDex(")) {
            assertEquals(call, 1, Regex(Regex.escape(call)).findAll(adapt).count())
        }

        assertTrue(adapt.contains("point = assistHistory.blockUpdate"))
        assertTrue(adapt.contains("val carriedQuality = if (playerQualityNeedsAssist && !playerQualityPlanned) assistHistory.assistedQuality else null"))
        assertTrue(adapt.contains("?: if (topologyPlanned) null else assistHistory.commentTopology"))
        assertEquals(3, Regex("""AdaptAssistGate\.skippedDiagnostic\(""").findAll(adapt).count())

        val refreshCheck = VersionAdapterSource.functions(ensureAdapted, "requiresFeatureRefresh").single()
        assertTrue(refreshCheck.contains("DexFeatureRegistry.requiresFeatureRefresh("))
        assertTrue(registryFile.contains("VersionAdapter.assistRefreshNeeded("))
        // 冷启动快路径只能查「从未尝试过」的家族（isAttemptedInCache），禁止因可重试失败反复弹适配提示。
        assertTrue(registryFile.contains("AdaptAssistGate::isAttemptedInCache"))
        assertFalse(registryFile.contains("AdaptAssistGate.isConclusiveMissing("))
        assertFalse(registryFile.contains("AdaptAssistGate::isConclusiveMissing"))
        val chromeAdapter = registryFile.after("private val homeChromeAdapter =").before("private val homeChromeSemanticsAdapter =")
        assertTrue(chromeAdapter.contains("val attempted = HomeChromeAdapter.families.keys.filterTo(linkedSetOf())"))
        assertTrue(chromeAdapter.contains("value.isDiagnosticAttemptedInCache(HomeChromeAdapter.diagnostic(query))"))
        assertTrue(registryFile.contains("private val homeChromeSemanticsAdapter ="))
        val ensureHistory = ensureAdapted.after("val historyDiagnosticsById =").before("return adapt(")
        assertTrue(ensureHistory.contains("AdaptAssistGate::isConclusiveMissing"))
        assertTrue(adapt.contains("semanticsPlanned && dexAssist == null -> \"no-dex-source\""))
        assertTrue(adapt.contains("query in chromePlanned && dexAssist == null -> \"no-dex-source\""))
        assertTrue(adapter.contains("val cached = usable?.takeUnless { refreshRequested || coverageMissing.isNotEmpty() || staleDomains.isNotEmpty() }"))
        // 功能关闭时 internal 判定在求值直接定位前就短路。
        val gate = SourceContract.read("hook/adapter/AdaptAssistGate.kt")
        assertTrue(gate.contains("enabled && !cachedPresent && attempt != Attempt.CONCLUSIVE_MISSING && !directFound()"))
        assertTrue(gate.contains("enabled && !cachedPresent && attempt == Attempt.NOT_REQUESTED && !directFound()"))
        val refresh = adapter.after("internal fun assistRefreshNeeded(").before("private data class BlockUpdateDexAssist")
        assertEquals(3, Regex("""AdaptAssistGate\.needsSupplementalScan\(""").findAll(refresh).count())
        assertFalse(refresh.contains("AdaptAssistGate.needsQuery("))

        val ensureHook = entry.after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(ensureHook.contains("blockUpdateEnabled = prefs.getBoolean(FeaturePreferences.BLOCK_APP_UPDATE, false)"))
        assertTrue(ensureHook.contains("commentTopologyEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false)"))
        // 同一份 prefs 也驱动对应 installer，需求与安装不会各读一份。
        assertTrue(entry.contains("enabled = prefs.getBoolean(FeaturePreferences.BLOCK_APP_UPDATE, false)"))
        assertTrue(entry.contains("val commentTopologyEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false)"))
    }

    @Test
    fun `completion snapshot, cache maintenance, and repack reuse preserve single-read and retry guards`() {
        val finished = entry.after("override fun onAdaptFinished(ok: Boolean) {")
            .before("startupCache = startupCache,")
        val outcome = finished.after("private fun applyAdaptOutcome(ok: Boolean, showToast: Boolean) {")
        val success = outcome.after("if (ok) {").before("if (showToast)")
        val failure = outcome.after("} else {").before("if (showToast)")
        assertEquals(1, Regex("""VersionAdapter\.loadCached\(""").findAll(success).count())
        // 读取发生在统一重适配分发之前，而不是藏在各 installer 分支或重适配循环里。
        assertTrue(
            success.indexOf("VersionAdapter.loadCached(") in
                0 until success.indexOf("featureInstallCoordinator.retryAdapted(adapted, ok = true)")
        )
        assertTrue(failure.contains("featureInstallCoordinator.retryAdapted(adapted = null, ok = false)"))
        assertTrue(
            entry
                .after("homeRecommendEarlyLoadInstaller.asAdaptRetryable(")
                .before("featureInstallCoordinator.registerRetryable(")
                .contains("onFail = { finishAdaptationWait() }")
        )
        assertFalse(featureInstaller.contains("VersionAdapter.loadCached("))
        assertTrue(finished.contains("override fun onSilentCacheReady(ok: Boolean)"))
        assertTrue(finished.contains("applyAdaptOutcome(ok, showToast = false)"))

        val installChain = entry.after("val featureInstallCoordinator = FeatureInstallCoordinator(hookEnvironment)")
            .before("val commentTopologyEnabled =")
        for (field in listOf(
            "playerQuality", "playerPopupPromotion", "commentClassicStyle", "sponsorPlayer",
            "pageBoundaries", "homeChrome", "homeChromeSemantics",
            "blockUpdate", "weChatShare", "playerSpeedCalculator", "homeRecommendEarlyLoadListener",
            "cloudKeyFreezeContract", "homeTabs", "commentPurify",
            "aiPlaylistDirector", "followUpRecommendBodies", "detailRelateGameComponent", "legacyStoryDanmakuBind"
        )) {
            assertTrue(
                "installChain consumes adapted snapshot field: $field",
                installChain.contains("adapted?.$field") || installChain.contains("adapted.$field")
            )
        }
        assertFalse(entry.contains("adaptedChrome"))

        val retryBlock = entry
            .after("val featureInstallCoordinator = FeatureInstallCoordinator(hookEnvironment)")
            .before("val commentTopologyEnabled =")
        val registrations = retryBlock
            .split("featureInstallCoordinator.registerRetryable(")
            .drop(1)
        assertTrue(registrations.size >= 16)
        for (installer in listOf(
            "qualityInstaller", "popupInstaller", "classicInstaller", "sponsorInstaller",
            "pausedAdInstaller", "blockUpdateInstaller", "weChatShareInstaller", "playerSpeedInstaller",
            "homeRecommendEarlyLoadInstaller", "cloudKeyFreezeInstaller", "homeTabFilterInstaller",
            "commentPurifyInstaller", "aiDeclaredVideoInstaller", "followUpRecommendInstaller",
            "detailAppPromotionInstaller", "storyActionIconsInstaller"
        )) {
            val registration = registrations.single { it.contains("$installer.asAdaptRetryable(") }
            assertTrue("$installer must gate retry on requiresAdaptationRetry", registration.contains("requiresRetry = { requiresAdaptationRetry }"))
        }
        // 双阶段检查与单一主线程提交收敛在 FeatureInstallCoordinator.retryAdapted 与 asAdaptRetryable。
        val asRetryable = HookEntrySource.function("asAdaptRetryable", listOf("hook/feature/FeatureInstaller.kt" to featureInstaller))
        assertEquals(
            2,
            Regex("""if \(!requiresRetry\(\)\) return false|return requiresRetry\(\)""")
                .findAll(asRetryable.after("fun prepareAdaptedRetry(").before("override fun retryAdapted(")).count()
        )
        val retryAdapted = HookEntrySource.function(
            "retryAdapted",
            listOf("hook/feature/FeatureInstaller.kt" to featureInstaller)
        ) { it.contains("adapted: VersionAdapter.AdaptResult?, ok: Boolean") }
        assertTrue(retryAdapted.indexOf("retryable.prepareAdaptedRetry(adapted)") in 0 until retryAdapted.indexOf("environment.postToMain"))
        assertTrue(retryAdapted.after("postToMain.invoke {").contains("if (retryable.isAdaptationRetryPending)"))
        assertTrue(featureInstaller.contains("coordinator.installAll(listOf(this))"))
        assertTrue(retryAdapted.after("if (!ok) {").contains("retryable.onAdaptationFailed()"))

        assertTrue(ensureAdapted.contains("AdaptPlanner.missing(effectiveUsable.coverage, adaptPlan)"))
        assertTrue(ensureAdapted.contains("refreshRequested || coverageMissing.isNotEmpty() || staleDomains.isNotEmpty()"))
        assertTrue(ensureAdapted.contains("adaptDecision(usable, refreshRequested, coverageMissing, staleDomains) == AdaptDecision.SUPPLEMENT_SILENTLY"))
        assertTrue(ensureAdapted.contains("runCatching { callback?.onAdaptStarted() }"))
        assertTrue(ensureAdapted.contains("runCatching { callback?.onAdaptFinished(succeeded) }"))
        assertTrue(ensureAdapted.contains("runCatching { callback?.onSilentCacheReady(succeeded) }"))
        // 补适配与增量重绑定复用同一份带历史拆包入口，局部结果的沿用与覆盖范围都由 adapt 负责。
        assertTrue(ensureAdapted.contains("val vc = startup.identity.versionCode"))
        assertTrue(ensureAdapted.contains("adaptPlan, history, dexSource, vc"))
        // 缓存只在整轮适配成功后写入，实时 quickLocate 的结果（版本号为 0）从不写盘。
        assertEquals(1, Regex("""writeCache\(adapted\)""").findAll(ensureAdapted).count())
        val usable = adapter.after("fun usableCache(highCandidateExists: Boolean, plan: AdaptPlan)").before("fun missingCoverage(")
        assertTrue(usable.contains("AdaptPlanner.missing(it.coverage, plan).isEmpty()"))

        val hit = adapter.after("internal fun ensureAdapted(").after("if (cached != null) {")
            .before("if (!adaptationRunning.compareAndSet(false, true))")
        assertTrue(hit.contains("auditDexSourceAsync(context, cached)"))
        for (operation in listOf("cacheFile()", "writeCache(", "exists()", "inspectHostDexSource(", "codeCacheDir")) {
            assertFalse(operation, hit.contains(operation))
        }
        val auditWorker = adapter.after("private fun auditDexSourceAsync(").before("fun ensureAdapted(")
        assertTrue(auditWorker.contains("HostThreadGuard.run(\"adapter.dex_audit\")"))
        assertTrue(Regex("""if\s*\(!AdaptCacheStore\.cacheFile\(\)\.exists\(\)\)\s*\{\s*AdaptCacheStore\.writeCache\(cached\)\s*\}""").containsMatchIn(auditWorker))
        assertTrue(auditWorker.contains("if (expected == DEX_SOURCE_UNAVAILABLE) return@run"))
        assertTrue(auditWorker.contains("dexAuditRunning.compareAndSet(false, true)"))

        val cacheStore = VersionAdapterSource.file("AdaptCacheStore")
        val startupFacade = adapter.after("internal fun readStartupCache(").before("fun cacheStatus()")
        val startupImpl = VersionAdapterSource.functions(cacheStore, "readStartupCache").single()
        for (startup in listOf(startupFacade, startupImpl)) {
            assertFalse(startup.contains("rebindRepackedCache("))
            assertFalse(startup.contains("inspectHostDexSource("))
            assertFalse(startup.contains("readRawCacheCandidates("))
        }
        val ensureBeforeWorker = adapter.after("internal fun ensureAdapted(").before("Thread({")
        assertFalse("onAdaptStarted must be decided inside worker after rebind check", ensureBeforeWorker.contains("onAdaptStarted("))
        val adaptWorker = adapter.after("HostThreadGuard.run(\"adapter.adapt_worker\")").before("result = repacked ?:")
        assertFalse("All-mode rebind must not be gated to NPatch only", adaptWorker.contains("HookRuntimeMode.NPATCH_LEGACY"))
        assertTrue(adaptWorker.contains("AdaptCacheStore.readRawCacheCandidates(context, resetTimestamp)"))
        assertTrue(adaptWorker.contains("verifyProtocolFingerprint(classLoader, previous)"))
        assertTrue(adaptWorker.contains("rebindRepackedCache(previous, startup.identity, resetTimestamp, dexSource?.value, actualProtocol)"))
        assertTrue(adaptWorker.contains("AdaptDomainCatalog.staleDomains("))
    }

    private companion object {
        val adapter: String by lazy { VersionAdapterSource.versionAdapter() }
        val entry: String by lazy { HookEntrySource.hookEntry() }
        val adapt: String by lazy { VersionAdapterSource.function("adapt") { it.contains("previousPopup:") } }
        val ensureAdapted: String by lazy {
            VersionAdapterSource.function("ensureAdapted") { it.contains("startupCache: StartupCacheSnapshot") }
        }
        val quickLocate: String by lazy {
            adapter.after("internal fun quickLocate(").before("private val EMPTY_PAUSE_POINTS")
        }
        val featureInstaller: String by lazy { HookEntrySource.featureInstaller() }
        val registryFile: String by lazy { VersionAdapterSource.file("DexFeatureAdapter") }
    }
}
