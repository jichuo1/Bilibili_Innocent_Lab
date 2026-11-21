package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.*
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class BrandDecoderDexWiringContractTest {
    @Test fun `new decoder uses shared background session and cached descriptor reaches worker`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("DexRefHookBridge"))
        assertFalse(quick.contains("BrandSplashDecoderAdapter.locate("))
        assertTrue(adapter.contains("if (brandPlanned) add(BrandSplashDecoderAdapter.query)"))
        assertTrue(adapter.contains("BrandSplashDecoderAdapter.locate(loader, dexAssist, brandSplashCustomEnabled, previousBrand, brandPlanned)"))
        val query = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
            .after("DexAssistQuery.BRAND_SPLASH_DECODER ->").before("DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE ->")
        for (mark in listOf("BrandSplashDecoderAdapter.INFO", "BrandSplashDecoderAdapter.MARK", "getThumbHash", "decodeFile", "Modifier.STATIC"))
            assertTrue(mark, query.contains(mark))
        assertTrue(SourceContract.read("hook/HookEntry.kt").contains("decoderPoint = hostAdaptResult?.brandSplashDecoder"))
        assertTrue(SourceContract.read("hook/feature/BrandSplashImageCache.kt").contains("BrandSplashBitmapDecoder(environment, decoderPoint)"))
        assertFalse(SourceContract.read("hook/feature/BrandSplashBitmapDecoder.kt").contains("DexRefHookBridge"))
    
        CommentClassicDexWiringContractCases().verifyAllCases()
        FollowFeedDexWiringContractCases().`new style query runs only through planned background session and installs revalidated descriptors`()
        KotlinDefaultWordsDexWiringContractCases().verifyAllCases()
        PlayerPopupDexWiringContractCases().verifyAllCases()
        PlayerSpeedCalculatorDexWiringContractCases().calculatorDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup()
        PlayerSpeedSessionDexWiringContractCases().speedSessionDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup()
        SponsorDexWiringContractCases().sponsorDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup()
    }
}

private class CommentClassicDexWiringContractCases {
    fun `dex is feature gated in the shared background session and cached per family`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("DexAssistSession("))
        assertFalse(quick.contains("CommentClassicStyleLocator.locate"))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (commentClassicStyleEnabled) CommentClassicStyleLocator.missing(loader)"))
        assertTrue(background.contains("classicMissing.forEach { add(it.query) }"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(adapter.contains("CommentClassicStyleLocator.merge(runtime.commentClassicStyle, cached.commentClassicStyle)"))
        val installer = SourceContract.read("hook/feature/CommentClassicStyleFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge"))
        assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("method in registered"))
        assertTrue(installer.contains("!nativeFallbackReady"))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("commentClassicStyleEnabled = prefs.getBoolean(FeaturePreferences.COMMENT_CLASSIC_STYLE, false)"))
        assertTrue(hook.contains("featureInstallCoordinator.installAll(listOf(classicInstaller))"))
    }

    fun `literal callers are never installed as replacements`() {
        val engine = SourceContract.read("hook/adapter/dex/DexMeguvizAssistEngine.kt")
        val collection = engine.after("private fun collectClassic(").before("private fun collectStoryDefault(")
        assertTrue(collection.contains("invokes(data)"))
        assertTrue(collection.contains("CommentClassicStyleLocator.verified"))
        assertFalse(collection.contains("bound"))
        assertTrue(collection.contains("resolveInvoke(callee)"))
        assertTrue(collection.contains("invokes(wrapper)"))
        assertTrue(collection.contains("CommentClassicStyleLocator.isDefaultDelegate"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentClassicDexWiringContractCases) -> Unit) {
            val s = CommentClassicDexWiringContractCases()
            block(s)
        }
        runIsolated { s -> s.`dex is feature gated in the shared background session and cached per family`() }
        runIsolated { s -> s.`literal callers are never installed as replacements`() }
    }
}

private class FollowFeedDexWiringContractCases {
    fun `new style query runs only through planned background session and installs revalidated descriptors`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("DexRefHookBridge"))
        assertFalse(quick.contains("FollowFeedAdapter.locate("))
        assertTrue(adapter.contains("add(DexAssistQuery.FOLLOW_FEED_STYLE)"))
        assertTrue(adapter.contains("add(DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS)"))
        assertTrue(adapter.contains("FollowFeedAdapter.locate(loader, dexAssist, followFeedStyleEnabled, previousFollow, followPlanned)"))
        val query = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
            .after("DexAssistQuery.FOLLOW_FEED_STYLE ->").before("DexAssistQuery.BRAND_SPLASH_DECODER ->")
        for (mark in listOf("getItemOffsets", "FollowFeedAdapter.DECORATION_SOURCE", "FollowFeedAdapter.OFFSETS_MARK", "RecyclerView"))
            assertTrue(mark, query.contains(mark))
        assertTrue(SourceContract.read("hook/HookEntry.kt").contains("points = hostAdaptResult?.followFeedStyle"))
        val installer = SourceContract.read("hook/feature/FollowFeedFeatureInstaller.kt")
        assertTrue(installer.contains("FollowFeedAdapter.resolve(loader, points)"))
        assertFalse(installer.contains("DexRefHookBridge"))
    }
}

private class KotlinDefaultWordsDexWiringContractCases {
    fun queryUsesSemanticInvokeAndRunsOnlyInSharedBackgroundPlan() {
        val engine = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
        val query = engine.after("DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN ->").before("DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR ->")
        assertTrue(query.contains("returnType = \"void\""))
        assertTrue(query.contains("paramCount(5)"))
        assertTrue(query.contains("addInvoke"))
        assertTrue(query.contains("KotlinDefaultWordsLocator.DESCRIPTOR_GETTER"))
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("DexAssistSession("))
        assertFalse(quick.contains("locateKotlinDefaultWordsByDex("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (defaultWordsNeedsAssist) add(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(background.contains("if (defaultWordsNeedsAssist) locateKotlinDefaultWordsByDex(dexAssist)"))
    }

    fun featureFlagAndCachedFailurePreventUnrequestedAndRepeatedScans() {
        val hook = SourceContract.read("hook/HookEntry.kt").after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(hook.contains("kotlinDefaultWordsEnabled = prefs.getBoolean(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, false)"))
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        // 相同宿主的快照先被保留给其它家族；默认词 refresh 仍只能拒绝已经 usable 的快照。
        val refresh = adapter.after("fun requiresFeatureRefresh(value: AdaptResult): Boolean")
            .before("val refreshRequested = usable?.let(::requiresFeatureRefresh)")
        assertTrue(refresh.contains("kotlinDefaultWordsEnabled && KotlinDefaultWordsLocator.refreshCache"))
        assertTrue(refresh.contains("it.state == AdaptState.MISSING"))
        // Repacked results must use the same feature-refresh gate as an ordinary startup cache hit.
        val worker = adapter.after("HostThreadGuard.run(\"adapter.adapt_worker\")").before("result = repacked ?:")
        assertTrue(worker.contains("!requiresFeatureRefresh(value)"))
        val installer = SourceContract.read("hook/feature/HomeTopBarFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge"))
        assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("resolvedEntry = points?.kotlinDefaultWords"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (KotlinDefaultWordsDexWiringContractCases) -> Unit) {
            val s = KotlinDefaultWordsDexWiringContractCases()
            block(s)
        }
        runIsolated { s -> s.queryUsesSemanticInvokeAndRunsOnlyInSharedBackgroundPlan() }
        runIsolated { s -> s.featureFlagAndCachedFailurePreventUnrequestedAndRepeatedScans() }
    }
}

private class PlayerPopupDexWiringContractCases {
    fun `popup dex queries remain gated in the shared background session and cached per family`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("DexAssistSession("))
        assertFalse(quick.contains("PlayerPopupPromotionAdapter.locate("))
        assertFalse(quick.contains("PlayerPopupPromotionAdapter.direct("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (playerPopupPromotionEnabled) PlayerPopupPromotionAdapter.missing(loader)"))
        assertTrue(background.contains("popupPlanned.forEach { add(it.query) }"))
        assertTrue(background.contains("PlayerPopupPromotionAdapter.planned(loader, previousPopup, previouslyMissingPopup, playerPopupPromotionEnabled)"))
        assertTrue(background.contains("PlayerPopupPromotionAdapter.locate(loader, dexAssist, true, previousPopup, popupPlanned)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(adapter.contains("PlayerPopupPromotionAdapter.merge(runtime.playerPopupPromotion, cached.playerPopupPromotion)"))
        val refresh = adapter.after("val popupRefresh =").before("val classicRefresh =")
        assertTrue(refresh.contains("it.id == family.diagnostic && it.state == AdaptState.MISSING"))
        val inheritance = adapter.after("val usable = startup.usableCache(highCandidateExists)").before("val cached = usable?.takeUnless")
        assertTrue(inheritance.contains("val popupHistory = startup.usableCache(highCandidateExists = false)"))
        assertTrue(inheritance.contains("previousPopup = popupHistory?.playerPopupPromotion"))
        assertTrue(inheritance.contains("popupHistory?.diagnostics.orEmpty()"))
        val installer = SourceContract.read("hook/feature/PlayerPopupPromotionFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("playerPopupPromotionEnabled = prefs.getBoolean(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, false)"))
        assertTrue(hook.contains("cachedPoints = { popupPointsRef.get() }"))
        assertTrue(hook.contains("featureInstallCoordinator.installAll(listOf(popupInstaller))"))
    }

    fun `skipped cache families cannot trigger the sessions unplanned fallback query`() {
        val locator = SourceContract.read("hook/adapter/PlayerPopupPromotionAdapter.kt")
        val assisted = locator.after("fun assisted(family: Family)").before("return PlayerPopupPromotionPoints(direct.freeDataGuide")
        assertTrue(assisted.before("session?.result(family.query)").contains("if (!enabled || family !in planned) return null"))
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val diagnostics = adapter.after("val popupDiagnostics =").before("val classicUnresolved =")
        assertTrue(diagnostics.contains("cached-attempt-preserved"))
        assertTrue(diagnostics.contains("feature-disabled:cached-attempt-preserved"))
        assertTrue(diagnostics.contains("playerPopupPromotionEnabled || family in previouslyMissingPopup"))
        assertTrue(diagnostics.before("dexAssist.result(family.query)").contains("family in previouslyMissingPopup && family in popupUnresolved && family !in popupPlanned"))
        val locatorEntry = locator.after("fun locate(").before("val direct = direct(loader)")
        assertTrue(locatorEntry.contains("if (!enabled) return cached ?: PlayerPopupPromotionPoints()"))
        val disabledCarry = adapter.after("val popup = if (playerPopupPromotionEnabled)").before("val popupDiagnostics =")
        assertTrue(disabledCarry.contains("else previousPopup"))
        assertTrue(disabledCarry.contains("if (playerPopupPromotionEnabled && popup != null)"))
    }

    fun `dex signatures require promotion semantics and exclude ordinary danmaku panel candidates`() {
        val engine = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
        val guide = engine.after("DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE ->").before("DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT ->")
        assertTrue(guide.contains("PlayerPopupPromotionAdapter.GUIDE_FLOW_MARK"))
        assertTrue(guide.contains("contains = true"))
        assertTrue(guide.contains("PlayerPopupPromotionAdapter.NETWORK_PACKAGE"))
        assertTrue(guide.contains("returnType = \"java.lang.Object\""))
        assertTrue(guide.contains("paramCount(1)"))
        val locator = SourceContract.read("hook/adapter/PlayerPopupPromotionAdapter.kt")
        assertTrue(locator.contains("const val GUIDE_FLOW_MARK = \"already show free data guide toast in this cold start\""))
        // 8.84.0 的次数键、配置读取及曝光事件都在 helper，不能作为入口的必要 AND 条件。
        assertFalse(guide.contains("GUIDE_COUNTER")); assertFalse(guide.contains("GUIDE_SHOW_EVENT"))
        assertFalse(guide.contains("FREQUENCY_KEY")); assertFalse(guide.contains("addInvoke"))
        val float = engine.after("DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT ->").before("DexAssistQuery.COMMENT_CLASSIC_NATIVE,")
        for (name in listOf("isValidCommon", "isValidCommerce", "isValidGuide")) assertTrue(float.contains(name))
        val collector = SourceContract.read("hook/adapter/dex/DexMeguvizAssistEngine.kt")
        val collection = collector.after("if (query == DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT)")
            .before("if (query == DexAssistQuery.STORY_QUALITY_MATCH &&")
        assertTrue(collection.contains("invokes(data)"))
        assertTrue(collector.contains("inspected.rowCount() > MAX_INVOKES"))
        assertTrue(collector.contains("MAX_INVOKES = 128"))
        assertTrue(collection.contains("isValidDanmaku"))
        assertTrue(collection.contains("PlayerPopupPromotionAdapter.verifiedAdvertFloat(method)"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerPopupDexWiringContractCases) -> Unit) {
            val s = PlayerPopupDexWiringContractCases()
            block(s)
        }
        runIsolated { s -> s.`popup dex queries remain gated in the shared background session and cached per family`() }
        runIsolated { s -> s.`skipped cache families cannot trigger the sessions unplanned fallback query`() }
        runIsolated { s -> s.`dex signatures require promotion semantics and exclude ordinary danmaku panel candidates`() }
    }
}

private class PlayerSpeedCalculatorDexWiringContractCases {
    fun calculatorDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("PlayerSpeedLocator.assistedCalculator"))
        assertFalse(quick.contains("DexAssistSession("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (speedCalculatorPlanned) addAll(PlayerSpeedLocator.calculatorQueries)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        val installer = SourceContract.read("hook/feature/PlayerSpeedFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("cachedCalculatorPoint?.let { PlayerSpeedLocator.resolveCalculator(loader, it) }"))
        val queries = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
            .after("DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR").before("DexAssistQuery.PLAYER_SPEED_MENU")
        assertTrue(queries.contains("getScriptPace"))
    }
}

private class PlayerSpeedSessionDexWiringContractCases {
    fun speedSessionDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("PlayerSpeedSessionLocator.assisted"))
        assertFalse(quick.contains("DexAssistSession("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (speedSessionPlanned) addAll(PlayerSpeedSessionLocator.queries)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(background.contains("PlayerSpeedSessionLocator.refreshCache(playerSpeedSessionEnabled,"))
        val installer = SourceContract.read("hook/feature/PlayerSpeedFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("PlayerSpeedSessionLocator.modern(loader, cachedSessionClasses)"))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("cachedSessionClasses = hostAdaptResult?.playerSpeedSession"))
        assertTrue(hook.contains("playerSpeedSessionEnabled = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.multiplier("))
    }
}

private class SponsorDexWiringContractCases {
    fun sponsorDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("SponsorPlayerLocator.assisted"))
        assertFalse(quick.contains("DexAssistSession("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (sponsorNeedsAssist) addAll(SponsorPlayerLocator.queries)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(background.contains("sponsorBlockEnabled && SponsorPlayerLocator.needsQuery"))
        val installer = SourceContract.read("hook/feature/SponsorBlockFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("registrationStarted.compareAndSet(false, true)"))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("sponsorBlockEnabled = prefs.getBoolean(FeaturePreferences.SPONSORBLOCK_ENABLED, false)"))
        assertTrue(hook.contains("if (sponsorInstaller.requiresAdaptationRetry)"))
        assertTrue(hook.contains("featureInstallCoordinator.installAll(listOf(sponsorInstaller))"))
    }
}
