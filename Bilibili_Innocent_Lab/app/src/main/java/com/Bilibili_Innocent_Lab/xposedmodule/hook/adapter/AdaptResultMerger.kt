package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.StartupAdapterPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDecision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentPurifyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PausePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.MediaQualityAndPlayerLocator

/**
 * 启动期适配决策、`AdaptResult` 缓存合并与启动排除裁剪器。
 */
internal object AdaptResultMerger {

    internal fun adaptDecision(
        usable: AdaptResult?,
        refreshRequested: Boolean,
        coverageMissing: Set<PointFamily>,
        staleDomains: Set<AdaptDomain> = emptySet()
    ): AdaptDecision = when {
        usable == null || refreshRequested -> AdaptDecision.ADAPT
        coverageMissing.isNotEmpty() || staleDomains.isNotEmpty() -> AdaptDecision.SUPPLEMENT_SILENTLY
        else -> AdaptDecision.REUSE
    }

    /**
     * 以实时结构定位为主，只用已经过宿主指纹校验的缓存补齐缺失点。
     *
     * 该合并使后台 DexMeguviz 的结果能在下一次冷启动参与 Hook 注册，同时禁止缓存覆盖当前
     * ClassLoader 已确认的点。调用方必须给 `loadCached` 传入宿主 Context 后再传到这里。
     */
    internal fun mergeRuntimeWithCached(
        runtime: AdaptResult?,
        rawCached: AdaptResult?
    ): AdaptResult? {
        val cached = rawCached?.let {
            val stale = AdaptDomainCatalog.staleDomains(it.domainRuleVersions)
            if (stale.isEmpty()) it else it.withoutDomains(stale)
        }
        if (runtime == null) return cached
        if (cached == null) return runtime
        val retainedQuickLocateIds = DexFeatureRegistry.quickLocateMissingRetainedDiagnosticIds
        val mergedDiagnostics = (runtime.diagnostics.asSequence().map { it.id } +
            cached.diagnostics.asSequence().map { it.id })
            .distinct()
            .mapNotNull { id ->
                // 使用 AdaptResult.diagnosticsById 提供的 O(1) 惰性索引，避免 O(N^2) 线性扫描。
                val live = runtime.diagnosticsById[id]
                val stored = cached.diagnosticsById[id]
                when {
                    live == null -> stored
                    stored == null -> live
                    // 本进程的计划没有要求该家族：不能用「未请求」盖掉缓存里真实尝试过的结论（找到/确认缺失）。
                    live.state == AdaptState.NOT_APPLICABLE && live.detail == VersionAdapter.PLAN_NOT_REQUESTED -> stored
                    live.state == AdaptState.NOT_REQUESTED -> live
                    stored.state == AdaptState.FOUND && live.state != AdaptState.FOUND -> stored
                    id.startsWith(AdaptAssistGate.ID_PREFIX) &&
                        stored.state == AdaptState.FOUND -> stored
                    id == KotlinDefaultWordsLocator.DIAGNOSTIC &&
                        runtime.homeTopBar?.kotlinDefaultWords == null &&
                        stored.state == AdaptState.MISSING -> stored
                    id in retainedQuickLocateIds &&
                        stored.state == AdaptState.MISSING && live.state == AdaptState.NOT_APPLICABLE &&
                        live.detail == "quick-locate" -> stored
                    else -> live
                }
            }
            .toList()
        return runtime.copy(
            biliVersionCode = cached.biliVersionCode,
            ts = cached.ts,
            commentLow = runtime.commentLow ?: cached.commentLow,
            commentHigh = runtime.commentHigh ?: cached.commentHigh,
            mineEntry = runtime.mineEntry ?: cached.mineEntry,
            pause = PausePoints(
                requestMethods = (runtime.pause.requestMethods + cached.pause.requestMethods)
                    .distinct(),
                legacyCallback = runtime.pause.legacyCallback ?: cached.pause.legacyCallback,
                panelShow = runtime.pause.panelShow ?: cached.pause.panelShow,
                countdown = runtime.pause.countdown ?: cached.pause.countdown,
                apiEntry = runtime.pause.apiEntry ?: cached.pause.apiEntry
            ),
            banner = runtime.banner ?: cached.banner,
            homeTopBar = runtime.homeTopBar?.let { live ->
                live.copy(kotlinDefaultWords = live.kotlinDefaultWords ?: cached.homeTopBar?.kotlinDefaultWords)
            } ?: cached.homeTopBar,
            mineVip = runtime.mineVip ?: cached.mineVip,
            blockUpdate = runtime.blockUpdate ?: cached.blockUpdate,
            dynamicTabs = runtime.dynamicTabs ?: cached.dynamicTabs,
            fullNumbers = runtime.fullNumbers ?: cached.fullNumbers,
            playerPortrait = runtime.playerPortrait ?: cached.playerPortrait,
            playerInteractiveOverlays = runtime.playerInteractiveOverlays
                ?: cached.playerInteractiveOverlays,
            pgcAutoActivityPopup = runtime.pgcAutoActivityPopup ?: cached.pgcAutoActivityPopup,
            playerStatusBar = runtime.playerStatusBar ?: cached.playerStatusBar,
            homeRecommendFeed = runtime.homeRecommendFeed ?: cached.homeRecommendFeed,
            videoRelate = runtime.videoRelate ?: cached.videoRelate,
            homeTabs = runtime.homeTabs ?: cached.homeTabs,
            homeComponents = runtime.homeComponents ?: cached.homeComponents,
            mineComponents = runtime.mineComponents ?: cached.mineComponents,
            mineAccountMine = runtime.mineAccountMine ?: cached.mineAccountMine,
            storyFeed = runtime.storyFeed ?: cached.storyFeed,
            bottomBar = runtime.bottomBar ?: cached.bottomBar,
            // 实时定位只落到稳定包装层、而后台 DexMeguviz 已找到真实实现时，用缓存里的实现。
            playerQuality = mergePlayerQuality(runtime.playerQuality, cached.playerQuality),
            teenagersMode = runtime.teenagersMode ?: cached.teenagersMode,
            commentPurify = mergeCommentPurify(runtime.commentPurify, cached.commentPurify),
            commentFilter = runtime.commentFilter ?: cached.commentFilter,
            commentTopology = runtime.commentTopology ?: cached.commentTopology,
            commentSection = runtime.commentSection ?: cached.commentSection,
            splashAds = runtime.splashAds ?: cached.splashAds,
            hostFingerprint = cached.hostFingerprint,
            diagnostics = mergedDiagnostics,
            dexSourceFingerprint = cached.dexSourceFingerprint,
            sponsorPlayer = runtime.sponsorPlayer ?: cached.sponsorPlayer,
            playerSpeedSession = runtime.playerSpeedSession ?: cached.playerSpeedSession,
            playerSpeedCalculator = runtime.playerSpeedCalculator ?: cached.playerSpeedCalculator,
            weChatShare = runtime.weChatShare ?: cached.weChatShare,
            commentClassicStyle = CommentClassicStyleLocator.merge(runtime.commentClassicStyle, cached.commentClassicStyle),
            playerPopupPromotion = PlayerPopupPromotionAdapter.merge(runtime.playerPopupPromotion, cached.playerPopupPromotion),
            brandSplashDecoder = runtime.brandSplashDecoder ?: cached.brandSplashDecoder,
            followFeedStyle = runtime.followFeedStyle ?: cached.followFeedStyle,
            playerSpeedMenu = runtime.playerSpeedMenu ?: cached.playerSpeedMenu,
            pageBoundaries = runtime.pageBoundaries ?: cached.pageBoundaries,
            homeChrome = cached.homeChrome + runtime.homeChrome,
            homeChromeSemantics = runtime.homeChromeSemantics ?: cached.homeChromeSemantics,
            systemEnvironment = SystemEnvironmentAdapter.merge(runtime.systemEnvironment, cached.systemEnvironment),
            commentComposeMenu = CommentComposeMenuAdapter.merge(runtime.commentComposeMenu, cached.commentComposeMenu),
            kmpGrpcWireEngine = runtime.kmpGrpcWireEngine ?: cached.kmpGrpcWireEngine,
            brandSplashKntrDraw = runtime.brandSplashKntrDraw ?: cached.brandSplashKntrDraw,
            brandSplashKntrExit = runtime.brandSplashKntrExit ?: cached.brandSplashKntrExit,
            brandSplashKntrPage = runtime.brandSplashKntrPage ?: cached.brandSplashKntrPage,
            cloudKeyFreezeContract = runtime.cloudKeyFreezeContract ?: cached.cloudKeyFreezeContract,
            privacyIgnetHttp = runtime.privacyIgnetHttp ?: cached.privacyIgnetHttp,
            privacyKNeuron = runtime.privacyKNeuron ?: cached.privacyKNeuron,
            guestPlayerCore = runtime.guestPlayerCore ?: cached.guestPlayerCore,
            detailSpecialTagMapper = runtime.detailSpecialTagMapper ?: cached.detailSpecialTagMapper,
            commentReplyButtonOwners = cached.commentReplyButtonOwners + runtime.commentReplyButtonOwners,
            homeRecommendEarlyLoadListener = runtime.homeRecommendEarlyLoadListener ?: cached.homeRecommendEarlyLoadListener,
            aiPlaylistDirector = runtime.aiPlaylistDirector ?: cached.aiPlaylistDirector,
            followUpRecommendBodies = cached.followUpRecommendBodies + runtime.followUpRecommendBodies,
            detailRelateGameComponent = runtime.detailRelateGameComponent ?: cached.detailRelateGameComponent,
            legacyStoryDanmakuBind = runtime.legacyStoryDanmakuBind ?: cached.legacyStoryDanmakuBind,
            playerSpeedTheseus2LongPress = runtime.playerSpeedTheseus2LongPress ?: cached.playerSpeedTheseus2LongPress,
            coverage = AdaptCoverage.merge(runtime.coverage, cached.coverage),
            domainRuleVersions = cached.domainRuleVersions
        ).let(::applyStartupExclusions)
    }

    internal fun applyStartupExclusions(result: AdaptResult): AdaptResult {
        if (result.startupExcluded.isEmpty()) return result
        fun requested(family: StartupAdapterPlan.Family) = family.name !in result.startupExcluded
        return result.copy(
            dynamicTabs = result.dynamicTabs.takeIf { requested(StartupAdapterPlan.Family.DYNAMIC_TABS) },
            fullNumbers = result.fullNumbers.takeIf { requested(StartupAdapterPlan.Family.FULL_NUMBERS) },
            playerPortrait = result.playerPortrait.takeIf { requested(StartupAdapterPlan.Family.PLAYER_PORTRAIT) },
            playerInteractiveOverlays = result.playerInteractiveOverlays.takeIf { requested(StartupAdapterPlan.Family.PLAYER_INTERACTIVE) },
            pgcAutoActivityPopup = result.pgcAutoActivityPopup.takeIf { requested(StartupAdapterPlan.Family.PGC_POPUP) },
            playerStatusBar = result.playerStatusBar.takeIf { requested(StartupAdapterPlan.Family.PLAYER_STATUS_BAR) },
            playerQuality = result.playerQuality.takeIf { requested(StartupAdapterPlan.Family.PLAYER_QUALITY) },
            teenagersMode = result.teenagersMode.takeIf { requested(StartupAdapterPlan.Family.TEENAGERS) },
            commentPurify = result.commentPurify.takeIf { requested(StartupAdapterPlan.Family.COMMENT_PURIFY) },
            commentFilter = result.commentFilter.takeIf { requested(StartupAdapterPlan.Family.COMMENT_FILTER) },
            commentTopology = result.commentTopology.takeIf { requested(StartupAdapterPlan.Family.COMMENT_TOPOLOGY) },
            commentSection = result.commentSection.takeIf { requested(StartupAdapterPlan.Family.COMMENT_SECTION) },
            splashAds = result.splashAds.takeIf { requested(StartupAdapterPlan.Family.SPLASH) },
            videoRelate = result.videoRelate.takeIf { requested(StartupAdapterPlan.Family.VIDEO_RELATE) },
            diagnostics = result.diagnostics.map { diagnostic ->
                val family = StartupAdapterPlan.diagnosticFamily(diagnostic.id)
                if (diagnostic.id == "protocol.structure" || family?.name in result.startupExcluded)
                    diagnostic.copy(state = AdaptState.NOT_REQUESTED, detail = "startup-not-requested")
                else diagnostic
            }
        )
    }

    internal fun mergePlayerQuality(live: PlayerQualityPoints?, cached: PlayerQualityPoints?): PlayerQualityPoints? {
        live ?: return cached
        cached ?: return live
        val global = if (
            MediaQualityAndPlayerLocator.usesLegacyQualityHelper(live) &&
            cached.defaultQualityMethod != null &&
            !MediaQualityAndPlayerLocator.usesLegacyQualityHelper(cached)
        ) {
            cached.defaultQualityMethod
        } else {
            live.defaultQualityMethod ?: cached.defaultQualityMethod
        }
        return PlayerQualityPoints(
            global,
            (live.capabilitySignals + cached.capabilitySignals).distinct(),
            StoryQualityLocator.merge(live.story, cached.story),
            live.normalAutoSwitch ?: cached.normalAutoSwitch
        )
    }

    internal fun mergeCommentPurify(live: CommentPurifyPoints?, cached: CommentPurifyPoints?): CommentPurifyPoints? = when {
        live == null -> cached
        cached == null -> live
        else -> live.copy(
            quickReplyDialogMethods = live.quickReplyDialogMethods.ifEmpty { cached.quickReplyDialogMethods }
        )
    }
}
