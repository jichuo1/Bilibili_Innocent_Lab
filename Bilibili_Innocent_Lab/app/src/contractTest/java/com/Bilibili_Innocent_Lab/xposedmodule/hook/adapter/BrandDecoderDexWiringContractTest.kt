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
        CommentComposeAndWireDexWiringContractCases().verifyAllCases()
        BrandKntrAndCloudKeyDexWiringContractCases().verifyAllCases()
        Wave2PrivacyAndKmpWireWiringContractCases().verifyAllCases()
        Wave3DexWiringContractCases().verifyAllCases()
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
        assertTrue(refresh.contains("AdaptAssistGate.isAttemptedInCache(it)"))
        assertFalse(refresh.contains("AdaptAssistGate.isConclusiveMissing(it)"))
        // Repacked results must use the same feature-refresh gate as an ordinary startup cache hit.
        val worker = adapter.after("HostThreadGuard.run(\"adapter.adapt_worker\")").before("result = repacked ?:")
        assertTrue(worker.contains("reboundUsable?.let(::requiresFeatureRefresh)"))
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
        assertTrue(refresh.contains("it.id == family.diagnostic && AdaptAssistGate.isAttemptedInCache(it)"))
        val inheritance = adapter.after("val usable = startup.usableCache(highCandidateExists)").before("val cached = usable?.takeUnless")
        assertTrue(inheritance.contains("val popupHistory = rawPopupHistory?.let"))
        assertTrue(inheritance.contains("AdaptDomainCatalog.staleDomains("))
        assertTrue(inheritance.contains("previousPopup = history?.playerPopupPromotion"))
        assertTrue(inheritance.contains("history?.diagnostics.orEmpty()"))
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

private class CommentComposeAndWireDexWiringContractCases {
    fun `compose menu and kmp wire engine use shared background dex session only`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("CommentComposeMenuAdapter.locate("))
        assertFalse(quick.contains("KmpGrpcWireAdapter.locate("))
        assertFalse(quick.contains("DexAssistSession("))

        val merge = adapter.after("internal fun mergeRuntimeWithCached(").before("internal fun ensureAdapted(")
        assertTrue(merge.contains("CommentComposeMenuAdapter.DIAGNOSTIC"))
        assertTrue(merge.contains("KmpGrpcWireAdapter.DIAGNOSTIC"))
        assertTrue(merge.contains("CommentComposeMenuAdapter.merge("))

        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("CommentComposeMenuAdapter.needsQuery("))
        assertTrue(background.contains("KmpGrpcWireAdapter.needsQuery("))
        assertTrue(background.contains("if (composeMenuPlanned) add(CommentComposeMenuAdapter.query)"))
        assertTrue(background.contains("if (kmpGrpcWirePlanned) add(KmpGrpcWireAdapter.query)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))

        for (installer in listOf(
            "hook/feature/CommentTopologyFeatureInstaller.kt",
            "hook/feature/ReplyTopologyComposeMenu.kt",
            "hook/feature/CommentFilterFeatureInstaller.kt",
            "hook/feature/CommentPurifyFeatureInstaller.kt",
            "hook/feature/KmpGrpcWireChannel.kt"
        )) {
            val source = SourceContract.read(installer)
            assertFalse(installer, source.contains("DexRefHookBridge"))
            assertFalse(installer, source.contains("DexAssistSession("))
            assertFalse(installer, source.contains(".locate("))
        }

        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("composeMenuPoints = hostAdaptResult?.commentComposeMenu"))
        assertTrue(hook.contains("kmpGrpcWireEngine = hostAdaptResult?.kmpGrpcWireEngine"))
        assertTrue(hook.contains("retryComposeInstallIfNeeded(hookEnvironment)"))
        assertTrue(hook.contains("kmpGrpcWireEnabled ="))
    }

    fun `comment kmp installers prefer raw wire byte channel over kotlinx roundtrip`() {
        for (file in listOf(
            "hook/feature/CommentTopologyFeatureInstaller.kt",
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
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentComposeAndWireDexWiringContractCases) -> Unit) {
            val s = CommentComposeAndWireDexWiringContractCases()
            block(s)
        }
        runIsolated { s -> s.`compose menu and kmp wire engine use shared background dex session only`() }
        runIsolated { s -> s.`comment kmp installers prefer raw wire byte channel over kotlinx roundtrip`() }
    }
}

private class BrandKntrAndCloudKeyDexWiringContractCases {
    fun `kntr brand draw and cloud key freeze contract use shared background dex session only`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("BrandSplashKntrAdapter.locate("))
        assertFalse(quick.contains("CloudKeyFreezeAdapter.locate("))
        assertFalse(quick.contains("DexAssistSession("))

        val merge = adapter.after("internal fun mergeRuntimeWithCached(").before("internal fun ensureAdapted(")
        assertTrue(merge.contains("BrandSplashKntrAdapter.DIAGNOSTIC"))
        assertTrue(merge.contains("CloudKeyFreezeAdapter.DIAGNOSTIC"))
        assertTrue(merge.contains("DEX_ASSIST_BLOCK_UPDATE_ID"))

        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("BrandSplashKntrAdapter.needsQuery("))
        assertTrue(background.contains("CloudKeyFreezeAdapter.needsQuery("))
        assertTrue(background.contains("if (brandKntrPlanned) add(BrandSplashKntrAdapter.query)"))
        assertTrue(background.contains("if (cloudKeyContractPlanned) add(CloudKeyFreezeAdapter.query)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))

        for (installer in listOf(
            "hook/feature/BrandSplashCustomFeatureInstaller.kt",
            "hook/feature/CloudKeyFreezeFeatureInstaller.kt",
            "hook/feature/HostVideoCardStyleFeatureInstaller.kt"
        )) {
            val source = SourceContract.read(installer)
            assertFalse(installer, source.contains("DexRefHookBridge"))
            assertFalse(installer, source.contains("DexAssistSession("))
        }

        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("kntrDrawClass = hostAdaptResult?.brandSplashKntrDraw"))
        assertTrue(hook.contains("cachedFeatureFlagContract = hostAdaptResult?.cloudKeyFreezeContract"))
        assertTrue(hook.contains("followFeedPoints = hostAdaptResult?.followFeedStyle"))
        assertTrue(hook.contains("cloudKeyFreezeEnabled ="))

        val queries = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
        val fallback = queries.after("private fun fallbackRules(query: DexAssistQuery)").before("private fun composeMenuRules(")
        for (q in listOf(
            "PEGASUS_DIVIDER", "SEARCH_HOME_STATE", "PLAYER_END_PAGE_MERGER",
            "PLAYER_POPUP_FREE_DATA_GUIDE", "PLAYER_POPUP_ADVERT_FLOAT", "SPONSOR_CONTAINER_SCOPE"
        )) {
            assertTrue("Missing Wave 1 fallback rule for $q", fallback.contains("DexAssistQuery.$q"))
        }
    }

    fun verifyAllCases() {
        val s = BrandKntrAndCloudKeyDexWiringContractCases()
        s.`kntr brand draw and cloud key freeze contract use shared background dex session only`()
    }
}

private class Wave2PrivacyAndKmpWireWiringContractCases {
    fun `privacy ignet and kneuron adapters run only through shared background dex session`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("PrivacyChannelAdapter.locate"))
        assertFalse(quick.contains("DexAssistSession("))

        val merge = adapter.after("internal fun mergeRuntimeWithCached(").before("internal fun ensureAdapted(")
        assertTrue(merge.contains("PrivacyChannelAdapter.DIAGNOSTIC_IGNET"))
        assertTrue(merge.contains("PrivacyChannelAdapter.DIAGNOSTIC_KNEURON"))

        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("PrivacyChannelAdapter.needsIgnetQuery("))
        assertTrue(background.contains("PrivacyChannelAdapter.needsKNeuronQuery("))
        assertTrue(background.contains("if (privacyIgnetPlanned) add(PrivacyChannelAdapter.queryIgnet)"))
        assertTrue(background.contains("if (privacyKNeuronPlanned) add(PrivacyChannelAdapter.queryKNeuron)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))

        for (file in listOf(
            "hook/feature/privacy/IgnetHttpBoundary.kt",
            "hook/feature/privacy/IncognitoTelemetryHooks.kt",
            "hook/feature/privacy/PrivacyFeatureRuntime.kt"
        )) {
            val source = SourceContract.read(file)
            assertFalse(file, source.contains("DexRefHookBridge"))
            assertFalse(file, source.contains("DexAssistSession("))
            assertFalse(file, source.contains(".locate("))
        }

        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("incognitoPrivacyEnabled ="))
        assertTrue(hook.contains("privacyIgnetHttp = hostAdaptResult?.privacyIgnetHttp"))
        assertTrue(hook.contains("privacyKNeuron = hostAdaptResult?.privacyKNeuron"))
    }

    fun `wave 2 migrated features resolve wire adapter and prefer KmpGrpcWireChannel over KotlinMoss fallback`() {
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

    fun verifyAllCases() {
        val s = Wave2PrivacyAndKmpWireWiringContractCases()
        s.`privacy ignet and kneuron adapters run only through shared background dex session`()
        s.`wave 2 migrated features resolve wire adapter and prefer KmpGrpcWireChannel over KotlinMoss fallback`()
    }
}

private class Wave3DexWiringContractCases {
    fun `wave 3 adapters run only through shared background dex session and never inside quickLocate or installers`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private val EMPTY_PAUSE_POINTS")
        assertFalse(quick.contains("NormalQualityLocator.locateAutoSwitch("))
        assertFalse(quick.contains("DetailSpecialTagAdapter.locate("))
        assertFalse(quick.contains("CommentReplyButtonAdapter.locate("))
        assertFalse(quick.contains("DexAssistSession("))

        val merge = adapter.after("internal fun mergeRuntimeWithCached(").before("internal fun ensureAdapted(")
        assertTrue(merge.contains("NormalQualityLocator.DIAGNOSTIC"))
        assertTrue(merge.contains("DetailSpecialTagAdapter.DIAGNOSTIC"))
        assertTrue(merge.contains("CommentReplyButtonAdapter.DIAGNOSTIC"))
        assertTrue(merge.contains("detailSpecialTagMapper"))
        assertTrue(merge.contains("commentReplyButtonOwners"))

        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("NormalQualityLocator.needsQuery("))
        assertTrue(background.contains("DetailSpecialTagAdapter.needsQuery("))
        assertTrue(background.contains("CommentReplyButtonAdapter.needsQuery("))
        assertTrue(background.contains("if (normalAutoSwitchPlanned) add(NormalQualityLocator.query)"))
        assertTrue(background.contains("if (detailSpecialTagPlanned) add(DetailSpecialTagAdapter.query)"))
        assertTrue(background.contains("if (commentReplyButtonPlanned) add(CommentReplyButtonAdapter.query)"))

        val queries = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
        val fallback = queries.after("private fun fallbackRules(query: DexAssistQuery)").before("private fun composeMenuRules(")
        assertTrue(fallback.contains("DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN"))
        assertTrue(fallback.contains("paramCount(4)"))
        assertTrue(fallback.contains("DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE"))

        for (file in listOf(
            "hook/feature/PlayerQualityFeatureInstaller.kt",
            "hook/feature/DetailUnitedPresentationPurifyFeatureInstaller.kt",
            "hook/feature/CommentReplyButtonFeatureInstaller.kt"
        )) {
            val source = SourceContract.read(file)
            assertFalse(file, source.contains("DexRefHookBridge"))
            assertFalse(file, source.contains("DexAssistSession("))
            assertFalse(file, source.contains(".locate("))
        }

        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("detailSpecialTagEnabled ="))
        assertTrue(hook.contains("commentReplyButtonEnabled ="))
        assertTrue(hook.contains("detailSpecialTagMapper"))
        assertTrue(hook.contains("commentReplyButtonOwners"))
    }

    fun verifyAllCases() {
        val s = Wave3DexWiringContractCases()
        s.`wave 3 adapters run only through shared background dex session and never inside quickLocate or installers`()
        HomeEarlyLoadDexWiringContractCases().verifyAllCases()
    }
}

private class HomeEarlyLoadDexWiringContractCases {
    fun `home recommend early load adapter runs only through shared background dex session and never inside quickLocate or installer`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private val EMPTY_PAUSE_POINTS")
        assertFalse(quick.contains("HomeRecommendEarlyLoadAdapter.locate("))
        assertFalse(quick.contains("DexAssistSession("))

        val merge = adapter.after("internal fun mergeRuntimeWithCached(").before("internal fun ensureAdapted(")
        assertTrue(merge.contains("HomeRecommendEarlyLoadAdapter.DIAGNOSTIC"))
        assertTrue(merge.contains("homeRecommendEarlyLoadListener"))

        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("HomeRecommendEarlyLoadAdapter.needsQuery("))
        assertTrue(background.contains("HomeRecommendEarlyLoadAdapter.query"))

        val queries = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
        assertTrue(queries.contains("DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER -> homeRecommendEarlyLoadRules()"))
        assertTrue(queries.contains("PegasusLoadMoreShape.PEGASUS_FRAGMENT"))
        assertTrue(queries.contains("addOnScrollListener"))

        val installer = SourceContract.read("hook/feature/HomeRecommendEarlyLoadFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge"))
        assertFalse(installer.contains("DexAssistSession("))
        assertFalse(installer.contains(".locate("))

        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("homeRecommendEarlyLoadEnabled ="))
        assertTrue(hook.contains("homeRecommendEarlyLoadListener"))
    }

    fun verifyAllCases() {
        val s = HomeEarlyLoadDexWiringContractCases()
        s.`home recommend early load adapter runs only through shared background dex session and never inside quickLocate or installer`()
        Phase3Batch2DexWiringContractCases().verifyAllCases()
    }
}

private class Phase3Batch2DexWiringContractCases {
    fun `batch 2 tier-a adapters run only in shared background session and never inside quickLocate or installers`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private val EMPTY_PAUSE_POINTS")
        for (forbidden in listOf(
            "CommentQuickReplyAdapter.",
            "PausedAdDexAdapter.",
            "HomeTabResourceAdapter.",
            "BrandSplashKntrAdapter.locateExit",
            "BrandSplashKntrAdapter.locatePage",
            "DexAssistSession("
        )) {
            assertFalse("quickLocate must not call $forbidden", quick.contains(forbidden))
        }

        val merge = adapter.after("internal fun mergeRuntimeWithCached(").before("internal fun ensureAdapted(")
        for (token in listOf(
            "BrandSplashKntrAdapter.DIAGNOSTIC_EXIT",
            "BrandSplashKntrAdapter.DIAGNOSTIC_PAGE",
            "CommentQuickReplyAdapter.DIAGNOSTIC",
            "PausedAdDexAdapter.DIAGNOSTIC_COUNTDOWN",
            "PausedAdDexAdapter.DIAGNOSTIC_REQUEST_API",
            "HomeTabResourceAdapter.DIAGNOSTIC",
            "mergeCommentPurify(",
            "brandSplashKntrExit",
            "brandSplashKntrPage",
            "apiEntry = runtime.pause.apiEntry ?: cached.pause.apiEntry"
        )) {
            assertTrue("mergeRuntimeWithCached must preserve $token", merge.contains(token))
        }

        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        for (token in listOf(
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
            "if (homeTabResourcePlanned) add(HomeTabResourceAdapter.query)"
        )) {
            assertTrue("adapt must wire $token", background.contains(token))
        }

        for (file in listOf(
            "hook/feature/CommentPurifyFeatureInstaller.kt",
            "hook/feature/PausedAdFeatureInstaller.kt",
            "hook/feature/BrandSplashSkipFeatureInstaller.kt",
            "hook/feature/BrandSplashCustomFeatureInstaller.kt",
            "hook/feature/BrandSplashKntrRenderer.kt",
            "hook/feature/HomeTabFilterFeatureInstaller.kt"
        )) {
            val source = SourceContract.read(file)
            assertFalse(file, source.contains("DexRefHookBridge"))
            assertFalse(file, source.contains("DexAssistSession("))
            assertFalse(file, source.contains(".locate("))
        }

        val hook = SourceContract.read("hook/HookEntry.kt")
        for (token in listOf(
            "pausedPointsRef",
            "kntrExitClass = hostAdaptResult?.brandSplashKntrExit",
            "kntrPageMethod = hostAdaptResult?.brandSplashKntrPage",
            "commentQuickReplyEnabled =",
            "pausedAdEnabled =",
            "brandSplashSkipEnabled ="
        )) {
            assertTrue("HookEntry must wire $token", hook.contains(token))
        }
    }

    fun verifyAllCases() {
        val s = Phase3Batch2DexWiringContractCases()
        s.`batch 2 tier-a adapters run only in shared background session and never inside quickLocate or installers`()
        Phase3Batch3DexWiringContractCases().verifyAllCases()
    }
}

private class Phase3Batch3DexWiringContractCases {
    fun `batch 3 tier-b adapters run only in shared background session and never inside quickLocate or installers`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        for (forbidden in listOf(
            "AiPlaylistDirectorAdapter.locate",
            "FollowUpRecommendAdapter.locate",
            "DetailRelateGameAdapter.locate",
            "LegacyStoryDanmakuAdapter.locate",
            "PlayerSpeedLocator.locateTheseus2LongPress",
            "DexAssistSession("
        )) {
            assertFalse("quickLocate must not contain $forbidden", quick.contains(forbidden))
        }

        val merge = adapter.after("internal fun mergeRuntimeWithCached(").before("internal fun ensureAdapted(")
        for (token in listOf(
            "AiPlaylistDirectorAdapter.DIAGNOSTIC",
            "FollowUpRecommendAdapter.DIAGNOSTIC",
            "DetailRelateGameAdapter.DIAGNOSTIC",
            "LegacyStoryDanmakuAdapter.DIAGNOSTIC",
            "PlayerSpeedLocator.THESEUS2_LONG_PRESS_DIAGNOSTIC",
            "aiPlaylistDirector",
            "followUpRecommendBodies",
            "detailRelateGameComponent",
            "legacyStoryDanmakuBind",
            "playerSpeedTheseus2LongPress"
        )) {
            assertTrue("mergeRuntimeWithCached must preserve $token", merge.contains(token))
        }

        val refresh = adapter.after("fun requiresFeatureRefresh(value: AdaptResult): Boolean").before("val refreshRequested =")
        for (token in listOf(
            "AiPlaylistDirectorAdapter.needsQuery(",
            "FollowUpRecommendAdapter.needsQuery(",
            "DetailRelateGameAdapter.needsQuery(",
            "LegacyStoryDanmakuAdapter.needsQuery(",
            "PlayerSpeedLocator.theseus2LongPressNeedsQuery("
        )) {
            assertTrue("requiresFeatureRefresh must check $token", refresh.contains(token))
        }
        assertFalse(refresh.contains("AdaptAssistGate.isConclusiveMissing("))

        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        for (token in listOf(
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
            assertTrue("adapt must wire $token", background.contains(token))
        }

        for (file in listOf(
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

        val hook = SourceContract.read("hook/HookEntry.kt")
        for (token in listOf(
            "aiPlaylistDirectorRef",
            "followUpRecommendBodiesRef",
            "detailRelateGameComponentRef",
            "legacyStoryDanmakuBindRef",
            "playerSpeedTheseus2LongPressRef",
            "aiDeclaredVideoInstaller.requiresAdaptationRetry",
            "followUpRecommendInstaller.requiresAdaptationRetry",
            "detailAppPromotionInstaller.requiresAdaptationRetry",
            "storyActionIconsInstaller.requiresAdaptationRetry",
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

    fun verifyAllCases() {
        val s = Phase3Batch3DexWiringContractCases()
        s.`batch 3 tier-b adapters run only in shared background session and never inside quickLocate or installers`()
    }
}
