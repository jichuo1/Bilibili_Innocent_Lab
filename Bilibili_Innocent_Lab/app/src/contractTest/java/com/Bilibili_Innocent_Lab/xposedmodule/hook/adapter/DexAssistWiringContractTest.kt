package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 所有依赖后台 DEX 辅助查询的家族共享同一条规则：
 * 绝不进入 `quickLocate` 或运行期 `FeatureInstaller`，统一受开关/计划门控并在后台 `adapt` 与
 * `DexFeatureAdapter` / `mergeRuntimeWithCached` 中完成计划、校验与合并。
 */
class DexAssistWiringContractTest {

    @Test
    fun `brand splash, follow feed, system environment, and wechat share use planned background dex queries only`() {
        for (forbidden in listOf(
            "DexRefHookBridge",
            "DexAssistSession(",
            "BrandSplashDecoderAdapter.locate(",
            "FollowFeedAdapter.locate(",
            "BrandSplashKntrAdapter.locate(",
            "CloudKeyFreezeAdapter.locate(",
            "SystemEnvironmentAdapter",
            "WeChatShareCompatAccess.assisted"
        )) {
            assertFalse("quickLocate must not contain $forbidden", quickLocate.contains(forbidden))
        }

        // BrandSplashDecoder
        assertTrue(adapter.contains("if (brandPlanned) add(BrandSplashDecoderAdapter.query)"))
        assertTrue(adapter.contains("BrandSplashDecoderAdapter.locate(loader, dexAssist, brandSplashCustomEnabled, previousBrand, brandPlanned)"))
        val decoderQuery = queries.after("DexAssistQuery.BRAND_SPLASH_DECODER ->")
            .before("DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE ->")
        for (mark in listOf("BrandSplashDecoderAdapter.INFO", "BrandSplashDecoderAdapter.MARK", "getThumbHash", "decodeFile", "Modifier.STATIC")) {
            assertTrue(mark, decoderQuery.contains(mark))
        }
        assertTrue(hook.contains("decoderPoint = hostAdaptResult?.brandSplashDecoder"))
        assertTrue(SourceContract.read("hook/feature/BrandSplashImageCache.kt").contains("BrandSplashBitmapDecoder(environment, decoderPoint)"))
        assertFalse(SourceContract.read("hook/feature/BrandSplashBitmapDecoder.kt").contains("DexRefHookBridge"))

        // FollowFeed
        assertTrue(adapter.contains("add(DexAssistQuery.FOLLOW_FEED_STYLE)"))
        assertTrue(adapter.contains("add(DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS)"))
        assertTrue(adapter.contains("FollowFeedAdapter.locate(loader, dexAssist, followFeedStyleEnabled, previousFollow, followPlanned)"))
        val followQuery = queries.after("DexAssistQuery.FOLLOW_FEED_STYLE ->")
            .before("DexAssistQuery.BRAND_SPLASH_DECODER ->")
        for (mark in listOf("getItemOffsets", "FollowFeedAdapter.DECORATION_SOURCE", "FollowFeedAdapter.OFFSETS_MARK", "RecyclerView")) {
            assertTrue(mark, followQuery.contains(mark))
        }
        assertTrue(hook.contains("points = hostAdaptResult?.followFeedStyle"))
        val followInstaller = SourceContract.read("hook/feature/FollowFeedFeatureInstaller.kt")
        assertTrue(followInstaller.contains("FollowFeedAdapter.resolve(loader, points)"))
        assertFalse(followInstaller.contains("DexRefHookBridge"))

        // BrandSplashKntr & CloudKeyFreeze
        assertTrue(registry.contains("BrandSplashKntrAdapter.DIAGNOSTIC"))
        assertTrue(registry.contains("CloudKeyFreezeAdapter.DIAGNOSTIC"))
        assertTrue(mergeRuntimeWithCached.contains("AdaptAssistGate.ID_PREFIX"))
        assertTrue(adapter.contains("DEX_ASSIST_BLOCK_UPDATE_ID = AdaptAssistGate.BLOCK_UPDATE_ID"))
        assertTrue(adapt.contains("BrandSplashKntrAdapter.needsQuery("))
        assertTrue(adapt.contains("CloudKeyFreezeAdapter.needsQuery("))
        assertTrue(adapt.contains("if (brandKntrPlanned) add(BrandSplashKntrAdapter.query)"))
        assertTrue(adapt.contains("if (cloudKeyContractPlanned) add(CloudKeyFreezeAdapter.query)"))
        assertTrue(adapt.contains("DexAssistAttemptGuard("))
        for (installer in listOf(
            "hook/feature/BrandSplashCustomFeatureInstaller.kt",
            "hook/feature/CloudKeyFreezeFeatureInstaller.kt",
            "hook/feature/HostVideoCardStyleFeatureInstaller.kt"
        )) {
            val source = SourceContract.read(installer)
            assertFalse(installer, source.contains("DexRefHookBridge"))
            assertFalse(installer, source.contains("DexAssistSession("))
        }
        assertTrue(hook.contains("kntrDrawClass = hostAdaptResult?.brandSplashKntrDraw"))
        assertTrue(hook.contains("cachedFeatureFlagContract = hostAdaptResult?.cloudKeyFreezeContract"))
        assertTrue(hook.contains("followFeedPoints = hostAdaptResult?.followFeedStyle"))
        assertTrue(hook.contains("cloudKeyFreezeEnabled ="))
        val fallback = queries.after("private fun fallbackRules(query: DexAssistQuery)").before("private fun composeMenuRules(")
        for (q in listOf(
            "PEGASUS_DIVIDER", "SEARCH_HOME_STATE", "PLAYER_END_PAGE_MERGER",
            "PLAYER_POPUP_FREE_DATA_GUIDE", "PLAYER_POPUP_ADVERT_FLOAT", "SPONSOR_CONTAINER_SCOPE"
        )) {
            assertTrue("Missing fallback rule for $q", fallback.contains("DexAssistQuery.$q"))
        }

        // SystemEnvironment
        assertTrue(adapt.contains("SystemEnvironmentAdapter.planned("))
        assertTrue(adapt.contains("systemEnvPlanned.forEach { add(it.query) }"))
        assertTrue(adapt.contains("SystemEnvironmentAdapter.locate("))
        assertTrue(adapt.contains("SystemEnvironmentAdapter.conclusive("))
        assertTrue(adapt.contains("\"feature-disabled:cached-attempt-preserved\""))
        assertTrue(mergeRuntimeWithCached.contains("SystemEnvironmentAdapter.merge("))
        val rootQuery = queries.after("DexAssistQuery.SYSTEM_ENV_ROOT_RESULT ->")
            .before("DexAssistQuery.SYSTEM_ENV_HOOK_RESULT ->")
        assertTrue(rootQuery.contains("SystemEnvironmentAdapter.ENV_PARAM_SET"))
        assertTrue(rootQuery.contains("SystemEnvironmentAdapter.ENV_STRINGS"))
        assertFalse(rootQuery.contains("/system/bin/su"))
        assertFalse(rootQuery.contains("name = \"b\""))
        val hookQuery = queries.after("DexAssistQuery.SYSTEM_ENV_HOOK_RESULT ->")
            .before("DexAssistQuery.BLOCK_UPDATE ->")
        assertTrue(hookQuery.contains("SystemEnvironmentAdapter.HOOK_STRINGS"))
        assertFalse(hookQuery.contains("/system/bin/su"))
        assertFalse(hookQuery.contains("name = \"c\""))
        val sysInstaller = SourceContract.read("hook/feature/SystemEnvironmentFeatureInstaller.kt")
        assertTrue(sysInstaller.contains("SystemEnvironmentAdapter.resolve("))
        assertTrue(sysInstaller.contains("environment.registrar.exact("))
        assertTrue(sysInstaller.contains("environment.forCapabilityRuntime("))
        assertFalse(sysInstaller.contains("DexRefHookBridge"))
        assertFalse(sysInstaller.contains("DexAssistSession"))
        val hookEnsureAdapted = hook.after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(hookEnsureAdapted.contains("systemEnvironmentEnabled = prefs.getBoolean(FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION, false)"))
        val outcome = hook.after("private fun applyAdaptOutcome(ok: Boolean, showToast: Boolean)")
            .before("startupCache = startupCache,")
        assertTrue(outcome.contains("featureInstallCoordinator.retryAdapted(adapted, ok = true)"))
        val retryRegistration = hook.after("systemEnvInstaller.asAdaptRetryable(")
            .before("featureInstallCoordinator.registerRetryable(")
        assertTrue(retryRegistration.contains("requiresRetry = { requiresAdaptationRetry }"))
        assertTrue(retryRegistration.contains("systemEnvPointsSlot.value = adapted?.systemEnvironment"))
        val presenter = SourceContract.read("ui/activity/SettingCommandsPresenter.kt")
        val executeToggle = presenter.after("internal fun MainActivity.executeModeledToggle(")
            .before("internal val modeledToggleKeys")
        assertTrue(executeToggle.contains("FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION"))
        val modeledKeys = presenter.after("internal val modeledToggleKeys =")
            .before("internal fun MainActivity.synchronizeSettingField")
        assertTrue(modeledKeys.contains("FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION"))
        assertTrue(modeledKeys.contains("HookEntry.PREF_PREDICTIVE_BACK_ENABLED"))

        // WeChatShare
        assertTrue(adapt.contains("if (weChatSharePlanned) addAll(WeChatShareCompatAccess.queries)"))
        assertTrue(adapt.contains("WeChatShareCompatAccess.refreshCache(weChatShareEnabled,"))
        val weChatInstaller = SourceContract.read("hook/feature/WeChatShareCompatFeatureInstaller.kt")
        assertFalse(weChatInstaller.contains("DexRefHookBridge"))
        assertFalse(weChatInstaller.contains("DexAssistSession("))
        assertTrue(weChatInstaller.contains("WeChatShareCompatAccess.locate(environment.classLoader, hostVersionCode(), cachedClasses)"))
        val weChatQueries = queries
            .after("DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY -> listOf(2, 3, 4)")
            .before("DexAssistQuery.STORY_QUALITY_DEFAULT ->")
        assertTrue(weChatQueries.contains("createWXAPI"))
    }

    @Test
    fun `comment classic, kotlin default words, player popup, speed, and sponsor use feature-gated dex sessions`() {
        for (forbidden in listOf(
            "CommentClassicStyleLocator.locate",
            "locateKotlinDefaultWordsByDex(",
            "PlayerPopupPromotionAdapter.locate(",
            "PlayerPopupPromotionAdapter.direct(",
            "PlayerSpeedLocator.assistedCalculator",
            "PlayerSpeedSessionLocator.assisted",
            "SponsorPlayerLocator.assisted"
        )) {
            assertFalse("quickLocate must not contain $forbidden", quickLocate.contains(forbidden))
        }

        // CommentClassic
        assertTrue(adapt.contains("if (commentClassicStyleEnabled) CommentClassicStyleLocator.missing(loader)"))
        assertTrue(adapt.contains("classicMissing.forEach { add(it.query) }"))
        assertTrue(adapt.contains("DexAssistAttemptGuard("))
        assertTrue(mergeRuntimeWithCached.contains("CommentClassicStyleLocator.merge(runtime.commentClassicStyle, cached.commentClassicStyle)"))
        val classicInstaller = SourceContract.read("hook/feature/CommentClassicStyleFeatureInstaller.kt")
        assertFalse(classicInstaller.contains("DexRefHookBridge"))
        assertFalse(classicInstaller.contains("DexAssistSession("))
        assertTrue(classicInstaller.contains("method in registered"))
        assertTrue(classicInstaller.contains("!nativeFallbackReady"))
        assertTrue(hook.contains("commentClassicStyleEnabled = prefs.getBoolean(FeaturePreferences.COMMENT_CLASSIC_STYLE, false)"))
        assertTrue(hook.contains("classicInstaller.asAdaptRetryable("))
        assertTrue(hook.contains("coordinator.installAll(listOf(this))"))
        val engine = SourceContract.read("hook/adapter/dex/DexMeguvizAssistEngine.kt")
        val collection = engine.after("private fun collectClassic(").before("private fun collectStoryDefault(")
        assertTrue(collection.contains("invokes(data)"))
        assertTrue(collection.contains("CommentClassicStyleLocator.verified"))
        assertFalse(collection.contains("bound"))
        assertTrue(collection.contains("resolveInvoke(callee)"))
        assertTrue(collection.contains("invokes(wrapper)"))
        assertTrue(collection.contains("CommentClassicStyleLocator.isDefaultDelegate"))

        // KotlinDefaultWords
        val defaultWordsQuery = queries.after("DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN ->")
            .before("DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR ->")
        assertTrue(defaultWordsQuery.contains("returnType = \"void\""))
        assertTrue(defaultWordsQuery.contains("paramCount(5)"))
        assertTrue(defaultWordsQuery.contains("addInvoke"))
        assertTrue(defaultWordsQuery.contains("KotlinDefaultWordsLocator.DESCRIPTOR_GETTER"))
        assertTrue(adapt.contains("if (defaultWordsNeedsAssist) add(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)"))
        assertTrue(adapt.contains("if (defaultWordsNeedsAssist) locateKotlinDefaultWordsByDex(dexAssist)"))
        val hookEnsure = hook.after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(hookEnsure.contains("kotlinDefaultWordsEnabled = prefs.getBoolean(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, false)"))
        val refreshFn = VersionAdapterSource.functions(ensureAdapted, "requiresFeatureRefresh").single()
        assertTrue(refreshFn.contains("DexFeatureRegistry.requiresFeatureRefresh("))
        val kotlinWordsAdapter = registry.after("private val kotlinDefaultWordsAdapter =").before("private val assistGateAdapter =")
        assertTrue(kotlinWordsAdapter.contains("ctx.flags.kotlinDefaultWordsEnabled && KotlinDefaultWordsLocator.refreshCache"))
        assertTrue(registry.contains("AdaptAssistGate::isAttemptedInCache"))
        assertFalse(registry.contains("AdaptAssistGate.isConclusiveMissing("))
        assertFalse(registry.contains("AdaptAssistGate::isConclusiveMissing"))
        // Repacked results must use the same feature-refresh gate as an ordinary startup cache hit.
        val worker = adapter.after("HostThreadGuard.run(\"adapter.adapt_worker\")").before("result = repacked ?:")
        assertTrue(worker.contains("reboundUsable?.let(::requiresFeatureRefresh)"))
        val topBarInstaller = SourceContract.read("hook/feature/HomeTopBarFeatureInstaller.kt")
        assertFalse(topBarInstaller.contains("DexRefHookBridge"))
        assertFalse(topBarInstaller.contains("DexAssistSession("))
        assertTrue(topBarInstaller.contains("resolvedEntry = points?.kotlinDefaultWords"))

        // PlayerPopup
        assertTrue(adapt.contains("if (playerPopupPromotionEnabled) PlayerPopupPromotionAdapter.missing(loader)"))
        assertTrue(adapt.contains("popupPlanned.forEach { add(it.query) }"))
        assertTrue(adapt.contains("PlayerPopupPromotionAdapter.planned(loader, previousPopup, previouslyMissingPopup, playerPopupPromotionEnabled)"))
        assertTrue(adapt.contains("PlayerPopupPromotionAdapter.locate(loader, dexAssist, true, previousPopup, popupPlanned)"))
        assertTrue(mergeRuntimeWithCached.contains("PlayerPopupPromotionAdapter.merge(runtime.playerPopupPromotion, cached.playerPopupPromotion)"))
        val popupRefresh = registry.after("private val playerPopupPromotionAdapter =").before("private val commentClassicStyleAdapter =")
        assertTrue(popupRefresh.contains("!value.isDiagnosticAttemptedInCache(family.diagnostic)"))
        val inheritance = adapter.after("val usable = startup.usableCache(highCandidateExists)").before("val cached = usable?.takeUnless")
        assertTrue(inheritance.contains("val popupHistory = rawPopupHistory?.let"))
        assertTrue(inheritance.contains("AdaptDomainCatalog.staleDomains("))
        assertTrue(inheritance.contains("previousPopup = history?.playerPopupPromotion"))
        assertTrue(inheritance.contains("val historyDiagnosticsById = history?.diagnosticsById.orEmpty()"))
        val popupInstaller = SourceContract.read("hook/feature/PlayerPopupPromotionFeatureInstaller.kt")
        assertFalse(popupInstaller.contains("DexRefHookBridge"))
        assertFalse(popupInstaller.contains("DexAssistSession("))
        assertTrue(hook.contains("playerPopupPromotionEnabled = prefs.getBoolean(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, false)"))
        assertTrue(hook.contains("cachedPoints = popupPointsSlot::read"))
        assertTrue(hook.contains("popupInstaller.asAdaptRetryable("))
        assertTrue(hook.contains("endPageInstaller,\n                    popupInstaller"))

        val popupLocator = SourceContract.read("hook/adapter/PlayerPopupPromotionAdapter.kt")
        val assisted = popupLocator.after("fun assisted(family: Family)").before("return PlayerPopupPromotionPoints(direct.freeDataGuide")
        assertTrue(assisted.before("session?.result(family.query)").contains("if (!enabled || family !in planned) return null"))
        val diagnostics = adapter.after("val popupDiagnostics =").before("val classicUnresolved =")
        assertTrue(diagnostics.contains("cached-attempt-preserved"))
        assertTrue(diagnostics.contains("feature-disabled:cached-attempt-preserved"))
        assertTrue(diagnostics.contains("playerPopupPromotionEnabled || family in previouslyMissingPopup"))
        assertTrue(diagnostics.before("dexAssist.result(family.query)").contains("family in previouslyMissingPopup && family in popupUnresolved && family !in popupPlanned"))
        val locatorEntry = popupLocator.after("fun locate(").before("val direct = direct(loader)")
        assertTrue(locatorEntry.contains("if (!enabled) return cached ?: PlayerPopupPromotionPoints()"))
        val disabledCarry = adapter.after("val popup = if (playerPopupPromotionEnabled)").before("val popupDiagnostics =")
        assertTrue(disabledCarry.contains("else previousPopup"))
        assertTrue(disabledCarry.contains("if (playerPopupPromotionEnabled && popup != null)"))

        val guide = queries.after("DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE ->").before("DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT ->")
        assertTrue(guide.contains("PlayerPopupPromotionAdapter.GUIDE_FLOW_MARK"))
        assertTrue(guide.contains("contains = true"))
        assertTrue(guide.contains("PlayerPopupPromotionAdapter.NETWORK_PACKAGE"))
        assertTrue(guide.contains("returnType = \"java.lang.Object\""))
        assertTrue(guide.contains("paramCount(1)"))
        assertTrue(popupLocator.contains("const val GUIDE_FLOW_MARK = \"already show free data guide toast in this cold start\""))
        // 8.84.0 的次数键、配置读取及曝光事件都在 helper，不能作为入口的必要 AND 条件。
        assertFalse(guide.contains("GUIDE_COUNTER")); assertFalse(guide.contains("GUIDE_SHOW_EVENT"))
        assertFalse(guide.contains("FREQUENCY_KEY")); assertFalse(guide.contains("addInvoke"))
        val floatQuery = queries.after("DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT ->").before("DexAssistQuery.COMMENT_CLASSIC_NATIVE,")
        for (name in listOf("isValidCommon", "isValidCommerce", "isValidGuide")) assertTrue(floatQuery.contains(name))
        val floatCollection = engine.after("if (query == DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT)")
            .before("if (query == DexAssistQuery.STORY_QUALITY_MATCH &&")
        assertTrue(floatCollection.contains("invokes(data)"))
        assertTrue(engine.contains("inspected.rowCount() > MAX_INVOKES"))
        assertTrue(engine.contains("MAX_INVOKES = 128"))
        assertTrue(engine.contains("isValidDanmaku"))
        assertTrue(engine.contains("PlayerPopupPromotionAdapter.verifiedAdvertFloat(method)"))

        // PlayerSpeedCalculator & PlayerSpeedSession
        assertTrue(adapt.contains("if (speedCalculatorPlanned) addAll(PlayerSpeedLocator.calculatorQueries)"))
        assertTrue(adapt.contains("if (speedSessionPlanned) addAll(PlayerSpeedSessionLocator.queries)"))
        assertTrue(adapt.contains("PlayerSpeedSessionLocator.refreshCache(playerSpeedSessionEnabled,"))
        val speedInstaller = SourceContract.read("hook/feature/PlayerSpeedFeatureInstaller.kt")
        assertFalse(speedInstaller.contains("DexRefHookBridge"))
        assertFalse(speedInstaller.contains("DexAssistSession("))
        assertTrue(speedInstaller.contains("cachedCalculatorPoint?.let { PlayerSpeedLocator.resolveCalculator(loader, it) }"))
        assertTrue(speedInstaller.contains("PlayerSpeedSessionLocator.modern(loader, cachedSessionClasses)"))
        val calcQueries = queries.after("DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR").before("DexAssistQuery.PLAYER_SPEED_MENU")
        assertTrue(calcQueries.contains("getScriptPace"))
        assertTrue(hook.contains("cachedSessionClasses = hostAdaptResult?.playerSpeedSession"))
        assertTrue(hook.contains("playerSpeedSessionEnabled = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.multiplier("))

        // Sponsor
        assertTrue(adapt.contains("if (sponsorNeedsAssist) addAll(SponsorPlayerLocator.queries)"))
        assertTrue(adapt.contains("sponsorBlockEnabled && SponsorPlayerLocator.needsQuery"))
        val sponsorInstaller = SourceContract.read("hook/feature/SponsorBlockFeatureInstaller.kt")
        assertFalse(sponsorInstaller.contains("DexRefHookBridge"))
        assertFalse(sponsorInstaller.contains("DexAssistSession("))
        assertTrue(sponsorInstaller.contains("registrationStarted.compareAndSet(false, true)"))
        assertTrue(hook.contains("sponsorBlockEnabled = prefs.getBoolean(FeaturePreferences.SPONSORBLOCK_ENABLED, false)"))
        assertTrue(hook.contains("sponsorInstaller,"))
        val sponsorRetry = hook.after("sponsorInstaller.asAdaptRetryable(").before("featureInstallCoordinator.registerRetryable(")
        assertTrue(sponsorRetry.contains("requiresRetry = { requiresAdaptationRetry }"))
    }

    @Test
    fun `comment compose, kmp wire, and privacy channels route through background dex session and prefer raw wire`() {
        for (forbidden in listOf(
            "CommentComposeMenuAdapter.locate(",
            "KmpGrpcWireAdapter.locate(",
            "PrivacyChannelAdapter.locate"
        )) {
            assertFalse("quickLocate must not contain $forbidden", quickLocate.contains(forbidden))
        }

        assertTrue(mergeRuntimeWithCached.contains("val retainedQuickLocateIds = DexFeatureRegistry.quickLocateMissingRetainedDiagnosticIds"))
        assertTrue(registry.contains("CommentComposeMenuAdapter.DIAGNOSTIC"))
        assertTrue(registry.contains("KmpGrpcWireAdapter.DIAGNOSTIC"))
        assertTrue(registry.contains("PrivacyChannelAdapter.DIAGNOSTIC_IGNET"))
        assertTrue(registry.contains("PrivacyChannelAdapter.DIAGNOSTIC_KNEURON"))
        assertTrue(mergeRuntimeWithCached.contains("CommentComposeMenuAdapter.merge("))
        assertTrue(mergeRuntimeWithCached.contains("id in retainedQuickLocateIds"))

        assertTrue(adapt.contains("CommentComposeMenuAdapter.needsQuery("))
        assertTrue(adapt.contains("KmpGrpcWireAdapter.needsQuery("))
        assertTrue(adapt.contains("if (composeMenuPlanned) add(CommentComposeMenuAdapter.query)"))
        assertTrue(adapt.contains("if (kmpGrpcWirePlanned) add(KmpGrpcWireAdapter.query)"))
        assertTrue(adapt.contains("PrivacyChannelAdapter.needsIgnetQuery("))
        assertTrue(adapt.contains("PrivacyChannelAdapter.needsKNeuronQuery("))
        assertTrue(adapt.contains("if (privacyIgnetPlanned) add(PrivacyChannelAdapter.queryIgnet)"))
        assertTrue(adapt.contains("if (privacyKNeuronPlanned) add(PrivacyChannelAdapter.queryKNeuron)"))

        for (installer in listOf(
            "hook/feature/CommentTopologyFeatureInstaller.kt",
            "hook/feature/comment/ReplyTopologySeedBridge.kt",
            "hook/feature/comment/ReplyTopologyAnchorBinder.kt",
            "hook/feature/comment/ReplyTopologyPager.kt",
            "hook/feature/MossRpcChannel.kt",
            "hook/feature/ReplyTopologyComposeMenu.kt",
            "hook/feature/CommentFilterFeatureInstaller.kt",
            "hook/feature/CommentPurifyFeatureInstaller.kt",
            "hook/feature/KmpGrpcWireChannel.kt",
            "hook/feature/privacy/IgnetHttpBoundary.kt",
            "hook/feature/privacy/IncognitoTelemetryHooks.kt",
            "hook/feature/privacy/PrivacyFeatureRuntime.kt"
        )) {
            val source = SourceContract.read(installer)
            assertFalse(installer, source.contains("DexRefHookBridge"))
            assertFalse(installer, source.contains("DexAssistSession("))
            assertFalse(installer, source.contains(".locate("))
        }

        assertTrue(hook.contains("composeMenuPoints = hostAdaptResult?.commentComposeMenu"))
        assertTrue(hook.contains("kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine"))
        assertTrue(hook.contains("featureInstallCoordinator.installAll(listOf(commentTopologyInstaller))"))
        assertTrue(
            SourceContract.read("hook/feature/CommentTopologyFeatureInstaller.kt")
                .after("override fun retryAdapted(").before("override fun install(")
                .contains("retryComposeInstallIfNeeded(environment)")
        )
        assertTrue(hook.contains("kmpGrpcWireEnabled ="))
        assertTrue(hook.contains("incognitoPrivacyEnabled ="))
        assertTrue(hook.contains("privacyIgnetHttp = hostAdaptResult?.privacyIgnetHttp"))
        assertTrue(hook.contains("privacyKNeuron = hostAdaptResult?.privacyKNeuron"))

        // Comment KMP installers & MossRpcChannel prefer raw wire byte channel over kotlinx roundtrip
        val topology = SourceContract.read("hook/feature/CommentTopologyFeatureInstaller.kt")
        assertTrue(topology.contains("seedBridge.installComposeSeeds("))
        val seedBridge = SourceContract.read("hook/feature/comment/ReplyTopologySeedBridge.kt")
        assertTrue(seedBridge.contains("MossRpcChannel.resolveWireSurface("))
        assertTrue(seedBridge.contains("MossRpcChannel.mountKmpWireOrKotlin("))
        val mossRpc = SourceContract.read("hook/feature/MossRpcChannel.kt")
        assertTrue(mossRpc.contains("KmpGrpcWireAdapter.resolve("))
        assertTrue(mossRpc.contains("KmpGrpcWireChannel.install("))
        assertTrue(
            "MossRpcChannel should check wire before KotlinMossChannel fallback",
            mossRpc.indexOf("KmpGrpcWireChannel.install(") < mossRpc.indexOf("KotlinMossChannel.install(")
        )
        for (file in listOf(
            "hook/feature/CommentFilterFeatureInstaller.kt",
            "hook/feature/CommentPurifyFeatureInstaller.kt"
        )) {
            val source = SourceContract.read(file)
            assertTrue("$file should resolve KmpGrpcWireAdapter", source.contains("KmpGrpcWireAdapter.resolve("))
            assertTrue("$file should install KmpGrpcWireChannel", source.contains("KmpGrpcWireChannel.install("))
            assertTrue(
                "$file should check wire before KotlinMossChannel fallback",
                source.indexOf("KmpGrpcWireChannel.install(") < source.indexOf("KotlinMossChannel.install(")
            )
        }
        val channel = SourceContract.read("hook/feature/KmpGrpcWireChannel.kt")
        assertFalse(channel.contains("encodeToByteArray"))

        // Migrated KMP wire features
        val wireFirstFiles = listOf(
            "hook/feature/DynamicPurifyFeatureInstaller.kt",
            "hook/feature/SearchPurifyFeatureInstaller.kt",
            "hook/feature/HomeTopBarFeatureInstaller.kt",
            "hook/feature/insight/InsightRpc.kt",
            "hook/feature/DanmakuPurifyFeatureInstaller.kt",
            "hook/feature/PlayerCapabilityFeatureInstaller.kt",
            "hook/feature/PlayerEndPageRecommendFeatureInstaller.kt",
            "hook/feature/DetailModulePurifyFeatureInstaller.kt",
            "hook/feature/DetailUnitedModulePurifyFeatureInstaller.kt",
            "hook/feature/AiDeclaredVideoFeatureInstaller.kt",
            "hook/feature/BlockComponentLibraryFeatureInstaller.kt",
            "hook/feature/PlayerInteractiveOverlayFeatureInstaller.kt",
            "hook/feature/PauseResponseAdapter.kt"
        )
        for (file in wireFirstFiles) {
            val source = SourceContract.read(file)
            assertFalse(file, source.contains("DexRefHookBridge"))
            assertFalse(file, source.contains("DexAssistSession("))
            assertTrue("$file should resolve KmpGrpcWireAdapter", source.contains("KmpGrpcWireAdapter.resolve("))
            val wireIdx = source.indexOf("KmpGrpcWireChannel.install(")
            assertTrue("$file should install KmpGrpcWireChannel", wireIdx >= 0)
            val fallbackIdx = listOf(
                "KotlinMossChannel.prepare(",
                "KotlinMossChannel.install(",
                "KotlinMossBridgeMembers.resolve(",
                "KotlinMossBridgeMembers.callbackEntry("
            ).map { source.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
            assertTrue("$file should keep KotlinMoss fallback", fallbackIdx >= 0)
            assertTrue("$file should check KmpGrpcWireChannel before KotlinMoss fallback", wireIdx < fallbackIdx)
        }
    }

    @Test
    fun `quality auto-switch, detail tags, reply buttons, early load, paused ad, and story bind stay off quickLocate`() {
        for (forbidden in listOf(
            "NormalQualityLocator.locateAutoSwitch(",
            "DetailSpecialTagAdapter.locate(",
            "CommentReplyButtonAdapter.locate(",
            "HomeRecommendEarlyLoadAdapter.locate(",
            "CommentQuickReplyAdapter.",
            "PausedAdDexAdapter.",
            "HomeTabResourceAdapter.",
            "BrandSplashKntrAdapter.locateExit",
            "BrandSplashKntrAdapter.locatePage",
            "AiPlaylistDirectorAdapter.locate",
            "FollowUpRecommendAdapter.locate",
            "DetailRelateGameAdapter.locate",
            "LegacyStoryDanmakuAdapter.locate",
            "PlayerSpeedLocator.locateTheseus2LongPress"
        )) {
            assertFalse("quickLocate must not contain $forbidden", quickLocate.contains(forbidden))
        }

        for (token in listOf(
            "NormalQualityLocator.DIAGNOSTIC",
            "DetailSpecialTagAdapter.DIAGNOSTIC",
            "CommentReplyButtonAdapter.DIAGNOSTIC",
            "HomeRecommendEarlyLoadAdapter.DIAGNOSTIC",
            "BrandSplashKntrAdapter.DIAGNOSTIC_EXIT",
            "BrandSplashKntrAdapter.DIAGNOSTIC_PAGE",
            "CommentQuickReplyAdapter.DIAGNOSTIC",
            "PausedAdDexAdapter.DIAGNOSTIC_COUNTDOWN",
            "PausedAdDexAdapter.DIAGNOSTIC_REQUEST_API",
            "HomeTabResourceAdapter.DIAGNOSTIC",
            "AiPlaylistDirectorAdapter.DIAGNOSTIC",
            "FollowUpRecommendAdapter.DIAGNOSTIC",
            "DetailRelateGameAdapter.DIAGNOSTIC",
            "LegacyStoryDanmakuAdapter.DIAGNOSTIC",
            "PlayerSpeedLocator.THESEUS2_LONG_PRESS_DIAGNOSTIC",
            "AiPlaylistDirectorAdapter.needsQuery(",
            "FollowUpRecommendAdapter.needsQuery(",
            "DetailRelateGameAdapter.needsQuery(",
            "LegacyStoryDanmakuAdapter.needsQuery(",
            "PlayerSpeedLocator.theseus2LongPressNeedsQuery("
        )) {
            assertTrue("DexFeatureAdapter must register $token", registry.contains(token))
        }
        for (token in listOf(
            "detailSpecialTagMapper",
            "commentReplyButtonOwners",
            "homeRecommendEarlyLoadListener",
            "mergeCommentPurify(",
            "brandSplashKntrExit",
            "brandSplashKntrPage",
            "apiEntry = runtime.pause.apiEntry ?: cached.pause.apiEntry",
            "aiPlaylistDirector",
            "followUpRecommendBodies",
            "detailRelateGameComponent",
            "legacyStoryDanmakuBind",
            "playerSpeedTheseus2LongPress"
        )) {
            assertTrue("mergeRuntimeWithCached must preserve $token", mergeRuntimeWithCached.contains(token))
        }

        for (token in listOf(
            "NormalQualityLocator.needsQuery(",
            "DetailSpecialTagAdapter.needsQuery(",
            "CommentReplyButtonAdapter.needsQuery(",
            "if (normalAutoSwitchPlanned) add(NormalQualityLocator.query)",
            "if (detailSpecialTagPlanned) add(DetailSpecialTagAdapter.query)",
            "if (commentReplyButtonPlanned) add(CommentReplyButtonAdapter.query)",
            "HomeRecommendEarlyLoadAdapter.needsQuery(",
            "HomeRecommendEarlyLoadAdapter.query",
            "BrandSplashKntrAdapter.needsExitQuery(",
            "BrandSplashKntrAdapter.needsPageQuery(",
            "CommentQuickReplyAdapter.needsQuery(",
            "PausedAdDexAdapter.needsCountdownQuery(",
            "PausedAdDexAdapter.needsRequestApiQuery(",
            "HomeTabResourceAdapter.needsQuery(",
            "if (brandKntrExitPlanned) add(BrandSplashKntrAdapter.queryExit)",
            "if (brandKntrPagePlanned) add(BrandSplashKntrAdapter.queryPage)",
            "if (commentQuickReplyPlanned) add(CommentQuickReplyAdapter.query)",
            "if (pausedCountdownPlanned) add(PausedAdDexAdapter.queryCountdown)",
            "if (pausedRequestApiPlanned) add(PausedAdDexAdapter.queryRequestApi)",
            "if (homeTabResourcePlanned) add(HomeTabResourceAdapter.query)",
            "AiPlaylistDirectorAdapter.needsQuery(",
            "FollowUpRecommendAdapter.needsQuery(",
            "DetailRelateGameAdapter.needsQuery(",
            "LegacyStoryDanmakuAdapter.needsQuery(",
            "PlayerSpeedLocator.theseus2LongPressNeedsQuery(",
            "if (aiPlaylistDirectorPlanned) add(AiPlaylistDirectorAdapter.query)",
            "if (followUpPlanned) add(FollowUpRecommendAdapter.query)",
            "if (detailRelateGamePlanned) add(DetailRelateGameAdapter.query)",
            "if (legacyStoryDanmakuPlanned) add(LegacyStoryDanmakuAdapter.query)",
            "if (theseus2LongPressPlanned) addAll(PlayerSpeedLocator.theseus2LongPressQueries)"
        )) {
            assertTrue("adapt must wire $token", adapt.contains(token))
        }

        val fallback = queries.after("private fun fallbackRules(query: DexAssistQuery)").before("private fun composeMenuRules(")
        assertTrue(fallback.contains("DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN"))
        assertTrue(fallback.contains("paramCount(4)"))
        assertTrue(fallback.contains("DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE"))
        assertTrue(queries.contains("DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER -> homeRecommendEarlyLoadRules()"))
        assertTrue(queries.contains("PegasusLoadMoreShape.PEGASUS_FRAGMENT"))
        assertTrue(queries.contains("addOnScrollListener"))

        for (file in listOf(
            "hook/feature/PlayerQualityFeatureInstaller.kt",
            "hook/feature/DetailUnitedPresentationPurifyFeatureInstaller.kt",
            "hook/feature/CommentReplyButtonFeatureInstaller.kt",
            "hook/feature/HomeRecommendEarlyLoadFeatureInstaller.kt",
            "hook/feature/CommentPurifyFeatureInstaller.kt",
            "hook/feature/PausedAdFeatureInstaller.kt",
            "hook/feature/BrandSplashSkipFeatureInstaller.kt",
            "hook/feature/BrandSplashCustomFeatureInstaller.kt",
            "hook/feature/BrandSplashKntrRenderer.kt",
            "hook/feature/HomeTabFilterFeatureInstaller.kt",
            "hook/feature/AiDeclaredVideoFeatureInstaller.kt",
            "hook/feature/FollowUpRecommendFeatureInstaller.kt",
            "hook/feature/DetailAppPromotionFeatureInstaller.kt",
            "hook/feature/StoryActionIconsFeatureInstaller.kt",
            "hook/feature/PlayerSpeedFeatureInstaller.kt"
        )) {
            val source = SourceContract.read(file)
            assertFalse(file, source.contains("DexRefHookBridge"))
            assertFalse(file, source.contains("DexAssistSession("))
            assertFalse(file, source.contains(".locate("))
        }

        for (token in listOf(
            "detailSpecialTagEnabled =",
            "commentReplyButtonEnabled =",
            "detailSpecialTagMapper",
            "commentReplyButtonOwners",
            "homeRecommendEarlyLoadEnabled =",
            "homeRecommendEarlyLoadListener",
            "pausedPointsSlot",
            "kntrExitClass = hostAdaptResult?.brandSplashKntrExit",
            "kntrPageMethod = hostAdaptResult?.brandSplashKntrPage",
            "commentQuickReplyEnabled =",
            "pausedAdEnabled =",
            "brandSplashSkipEnabled =",
            "aiPlaylistDirectorSlot",
            "followUpRecommendBodiesSlot",
            "detailRelateGameComponentSlot",
            "legacyStoryDanmakuBindSlot",
            "playerSpeedTheseus2LongPressSlot",
            "aiDeclaredVideoInstaller.asAdaptRetryable(",
            "followUpRecommendInstaller.asAdaptRetryable(",
            "detailAppPromotionInstaller.asAdaptRetryable(",
            "storyActionIconsInstaller.asAdaptRetryable(",
            "aiDeclaredVideoEnabled =",
            "followUpRecommendEnabled =",
            "detailRelateGameEnabled =",
            "storyDanmakuToggleHidden =",
            "playerSpeedLongPressEnabled ="
        )) {
            assertTrue("HookEntry must wire $token", hook.contains(token))
        }

        val graphPlans = SourceContract.read("hook/adapter/dex/DexMeguvizGraphPlans.kt")
        val plansDispatch = graphPlans
            .after("fun plans(query: DexAssistQuery): List<DexGraphPlan> = when (query) {")
            .before("fun commentReplyMethodsForBinding(")
        assertTrue(plansDispatch.contains("DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT"))
        assertTrue(plansDispatch.contains("detailRelateGameInPkgPlan()"))
        assertTrue(plansDispatch.contains("detailRelateGameBindUsesPlan()"))
        assertTrue(plansDispatch.contains("DexAssistQuery.PAUSED_AD_COUNTDOWN -> listOf(pausedAdCountdownPlan(), pausedAdCountdownInvokePlan())"))

        val relateBindFn = graphPlans
            .after("private fun detailRelateGameBindUsesPlan():")
            .before("private fun legacyStoryDanmakuBindPlan():")
        assertFalse(relateBindFn.contains("RELATE_GAME_PACKAGE"))
        assertFalse(relateBindFn.contains("MethodTextField.OWNER"))
        assertTrue(relateBindFn.contains("ReviewRatingBar"))
        assertTrue(relateBindFn.contains("foreign_link_from_spmid"))

        val pausedInvokeFn = graphPlans
            .after("private fun pausedAdCountdownInvokePlan():")
            .before("private fun pausedAdRequestApiPlan():")
        assertTrue(pausedInvokeFn.contains("IVDPausedPage"))
        assertTrue(pausedInvokeFn.contains("getCountDownView"))
        assertFalse(pausedInvokeFn.contains("KPauseBar"))
    }

    private companion object {
        val adapter: String by lazy { VersionAdapterSource.versionAdapter() }
        val quickLocate: String by lazy { adapter.after("fun quickLocate(").before("private fun adapt(") }
        val adapt: String by lazy { VersionAdapterSource.function("adapt") { it.contains("previousPopup:") } }
        val mergeRuntimeWithCached: String by lazy {
            VersionAdapterSource.function("mergeRuntimeWithCached") { it.contains("retainedQuickLocateIds") }
        }
        val ensureAdapted: String by lazy {
            VersionAdapterSource.function("ensureAdapted") { it.contains("startupCache: StartupCacheSnapshot") }
        }
        val registry: String by lazy { VersionAdapterSource.file("DexFeatureAdapter") }
        val hook: String by lazy { SourceContract.read("hook/HookEntry.kt") }
        val queries: String by lazy { SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt") }
    }
}
