package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery

/**
 * 适配缓存的功能域划分（与 [VersionAdapterContract.DOMAIN_RULE_VERSIONS] 一一对应）。
 *
 * 每个 [PointFamily]、[DexAssistQuery] 与 `outsidePlan` 定位字段都归属且仅归属一个域，
 * 使单个功能域规则版本递增时只清除并重定位该域，避免牵连其余 12 个功能域重扫 DEX。
 */
internal enum class AdaptDomain(val key: String) {
    UPDATE("update"),
    PLAYER_QUALITY("player_quality"),
    PLAYER_SPEED("player_speed"),
    PLAYER_UI("player_ui"),
    COMMENT("comment"),
    HOME_FEED("home_feed"),
    HOME_CHROME("home_chrome"),
    DETAIL_RELATE("detail_relate"),
    FOLLOW_PAGE("follow_page"),
    MINE("mine"),
    SPLASH("splash"),
    KMP_WIRE("kmp_wire"),
    PRIVACY_COMPAT("privacy_compat");

    companion object {
        private val byKey = entries.associateBy(AdaptDomain::key)
        fun fromKey(key: String): AdaptDomain? = byKey[key]
    }
}

internal object AdaptDomainCatalog {
    private val emptyPause = VersionAdapter.PausePoints(emptyList(), null, null, null)

    val pointFamiliesByDomain: Map<AdaptDomain, Set<PointFamily>> = mapOf(
        AdaptDomain.UPDATE to setOf(PointFamily.BLOCK_UPDATE),
        AdaptDomain.PLAYER_QUALITY to setOf(PointFamily.PLAYER_QUALITY),
        AdaptDomain.PLAYER_SPEED to emptySet(),
        AdaptDomain.PLAYER_UI to setOf(
            PointFamily.PAUSE,
            PointFamily.PLAYER_PORTRAIT,
            PointFamily.PLAYER_INTERACTIVE_OVERLAYS,
            PointFamily.PGC_AUTO_ACTIVITY_POPUP,
            PointFamily.PLAYER_STATUS_BAR
        ),
        AdaptDomain.COMMENT to setOf(
            PointFamily.COMMENT_LOW,
            PointFamily.COMMENT_HIGH,
            PointFamily.COMMENT_PURIFY,
            PointFamily.COMMENT_FILTER,
            PointFamily.COMMENT_TOPOLOGY,
            PointFamily.COMMENT_SECTION
        ),
        AdaptDomain.HOME_FEED to setOf(
            PointFamily.BANNER,
            PointFamily.HOME_TOP_BAR,
            PointFamily.HOME_RECOMMEND_FEED,
            PointFamily.HOME_TABS,
            PointFamily.HOME_COMPONENTS,
            PointFamily.STORY_FEED,
            PointFamily.DYNAMIC_TABS,
            PointFamily.FULL_NUMBERS
        ),
        AdaptDomain.HOME_CHROME to setOf(PointFamily.BOTTOM_BAR),
        AdaptDomain.DETAIL_RELATE to setOf(PointFamily.VIDEO_RELATE),
        AdaptDomain.FOLLOW_PAGE to emptySet(),
        AdaptDomain.MINE to setOf(
            PointFamily.MINE_ENTRY,
            PointFamily.MINE_VIP,
            PointFamily.MINE_COMPONENTS,
            PointFamily.MINE_ACCOUNT_MINE
        ),
        AdaptDomain.SPLASH to setOf(PointFamily.SPLASH_ADS),
        AdaptDomain.KMP_WIRE to emptySet(),
        AdaptDomain.PRIVACY_COMPAT to setOf(PointFamily.TEENAGERS_MODE)
    )

    val dexQueriesByDomain: Map<AdaptDomain, Set<DexAssistQuery>> = mapOf(
        AdaptDomain.UPDATE to setOf(DexAssistQuery.BLOCK_UPDATE),
        AdaptDomain.PLAYER_QUALITY to setOf(
            DexAssistQuery.PLAYER_DEFAULT_QUALITY,
            DexAssistQuery.STORY_QUALITY_DEFAULT,
            DexAssistQuery.STORY_QUALITY_RESOURCE,
            DexAssistQuery.STORY_QUALITY_MATCH,
            DexAssistQuery.NORMAL_QUALITY_AUTO_SWITCH
        ),
        AdaptDomain.PLAYER_SPEED to setOf(
            DexAssistQuery.SPONSOR_RUN_PLAYABLE,
            DexAssistQuery.SPONSOR_PLAYER_WRAPPER,
            DexAssistQuery.SPONSOR_CONTAINER_SCOPE,
            DexAssistQuery.PLAYER_SPEED_MENU,
            DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR,
            DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS
        ),
        AdaptDomain.PLAYER_UI to setOf(
            DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE,
            DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT,
            DexAssistQuery.GUEST_PLAYER_CORE_SERVICE,
            DexAssistQuery.PAUSED_AD_COUNTDOWN,
            DexAssistQuery.PAUSED_AD_REQUEST_API,
            DexAssistQuery.LEGACY_STORY_DANMAKU_BIND
        ),
        AdaptDomain.COMMENT to setOf(
            DexAssistQuery.COMMENT_REPLY_MAPPER,
            DexAssistQuery.COMMENT_CLASSIC_NATIVE,
            DexAssistQuery.COMMENT_CLASSIC_KOTLIN,
            DexAssistQuery.COMMENT_COMPOSE_MORE_MENU,
            DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE,
            DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR
        ),
        AdaptDomain.HOME_FEED to setOf(
            DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN,
            DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER,
            DexAssistQuery.HOME_TAB_RESOURCE
        ),
        AdaptDomain.HOME_CHROME to setOf(
            DexAssistQuery.HOME_CHROME_TOP,
            DexAssistQuery.HOME_CHROME_TOP_LAYOUT,
            DexAssistQuery.HOME_CHROME_TOP_BACKDROP,
            DexAssistQuery.HOME_CHROME_TOP_CONTENT,
            DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS,
            DexAssistQuery.HOME_CHROME_BOTTOM,
            DexAssistQuery.HOME_CHROME_BOTTOM_ITEM,
            DexAssistQuery.HOME_CHROME_BOTTOM_CONTENT,
            DexAssistQuery.HOME_CHROME_SEMANTICS
        ),
        AdaptDomain.DETAIL_RELATE to setOf(
            DexAssistQuery.DETAIL_SPECIAL_TAG_MAPPER,
            DexAssistQuery.AI_PLAYLIST_DIRECTOR,
            DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY,
            DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT
        ),
        AdaptDomain.FOLLOW_PAGE to setOf(
            DexAssistQuery.FOLLOW_FEED_STYLE,
            DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS,
            DexAssistQuery.SEARCH_HOME_DELIVERY,
            DexAssistQuery.SEARCH_HOME_STATE,
            DexAssistQuery.PLAYER_END_PAGE_MERGER,
            DexAssistQuery.OGV_TRENDING_CARD,
            DexAssistQuery.OGV_DOUBLE_CARD,
            DexAssistQuery.OGV_UPCOMING_CARD,
            DexAssistQuery.PEGASUS_DIVIDER
        ),
        AdaptDomain.MINE to emptySet(),
        AdaptDomain.SPLASH to setOf(
            DexAssistQuery.BRAND_SPLASH_DECODER,
            DexAssistQuery.BRAND_SPLASH_KNTR_DRAW,
            DexAssistQuery.BRAND_SPLASH_KNTR_EXIT,
            DexAssistQuery.BRAND_SPLASH_KNTR_PAGE
        ),
        AdaptDomain.KMP_WIRE to setOf(DexAssistQuery.KMP_GRPC_WIRE_ENGINE),
        AdaptDomain.PRIVACY_COMPAT to setOf(
            DexAssistQuery.SYSTEM_ENV_ROOT_RESULT,
            DexAssistQuery.SYSTEM_ENV_HOOK_RESULT,
            DexAssistQuery.CLOUD_KEY_FREEZE_CONTRACT,
            DexAssistQuery.PRIVACY_IGNET_HTTP,
            DexAssistQuery.PRIVACY_KNEURON,
            DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY
        )
    )

    val diagnosticIdsByDomain: Map<AdaptDomain, Set<String>> = mapOf(
        AdaptDomain.UPDATE to setOf(
            "update.block",
            VersionAdapter.DEX_ASSIST_BLOCK_UPDATE_ID
        ),
        AdaptDomain.PLAYER_QUALITY to setOf(
            "player.default_quality",
            VersionAdapter.DEX_ASSIST_PLAYER_QUALITY_ID,
            StoryQualityLocator.DIAGNOSTIC,
            NormalQualityLocator.DIAGNOSTIC
        ),
        AdaptDomain.PLAYER_SPEED to setOf(
            SponsorPlayerLocator.DIAGNOSTIC,
            PlayerSpeedSessionLocator.DIAGNOSTIC,
            PlayerSpeedLocator.CALCULATOR_DIAGNOSTIC,
            PlayerSpeedLocator.THESEUS2_LONG_PRESS_DIAGNOSTIC,
            PlayerSpeedMenuAdapter.DIAGNOSTIC
        ),
        AdaptDomain.PLAYER_UI to buildSet {
            add("paused.request")
            add("paused.panel")
            add("paused.countdown")
            add("player.portrait")
            add("player.interactive_overlay")
            add("pgc.auto_activity_popup")
            add("player.status_bar")
            PlayerPopupPromotionAdapter.Family.entries.forEach { add(it.diagnostic) }
            add(GuestPlayerCoreAdapter.DIAGNOSTIC)
            add(PausedAdDexAdapter.DIAGNOSTIC_COUNTDOWN)
            add(PausedAdDexAdapter.DIAGNOSTIC_REQUEST_API)
            add(LegacyStoryDanmakuAdapter.DIAGNOSTIC)
        },
        AdaptDomain.COMMENT to setOf(
            "comment.low",
            "comment.high",
            "comment.purify.search",
            "comment.purify.empty_page",
            "comment.purify.vote",
            "comment.purify.follow",
            "comment.purify.qoe",
            "comment.purify.operation",
            "comment.quick_reply",
            "comment.filter",
            "comment.topology",
            "comment.section",
            VersionAdapter.DEX_ASSIST_COMMENT_TOPOLOGY_ID,
            CommentClassicStyleLocator.DIAGNOSTIC,
            CommentComposeMenuAdapter.DIAGNOSTIC,
            CommentReplyButtonAdapter.DIAGNOSTIC,
            CommentQuickReplyAdapter.DIAGNOSTIC
        ),
        AdaptDomain.HOME_FEED to setOf(
            "home.banner",
            "home.top_bar.game",
            "home.top_bar.game_compose",
            "home.top_bar.search",
            "home.recommend_feed",
            "home.tabs",
            "home.components",
            "story.feed",
            "dynamic.tabs",
            "number.full",
            KotlinDefaultWordsLocator.DIAGNOSTIC,
            HomeRecommendEarlyLoadAdapter.DIAGNOSTIC,
            HomeTabResourceAdapter.DIAGNOSTIC
        ),
        AdaptDomain.HOME_CHROME to buildSet {
            add("home.bottom_bar")
            HomeChromeAdapter.families.keys.forEach { add(HomeChromeAdapter.diagnostic(it)) }
            add(HomeChromeAdapter.SEMANTICS_DIAGNOSTIC)
        },
        AdaptDomain.DETAIL_RELATE to setOf(
            "video.relate",
            DetailSpecialTagAdapter.DIAGNOSTIC,
            AiPlaylistDirectorAdapter.DIAGNOSTIC,
            FollowUpRecommendAdapter.DIAGNOSTIC,
            DetailRelateGameAdapter.DIAGNOSTIC
        ),
        AdaptDomain.FOLLOW_PAGE to buildSet {
            add(FollowFeedAdapter.DIAGNOSTIC)
            PageBoundaryAdapter.queries.forEach { add(PageBoundaryAdapter.diagnostic(it)) }
        },
        AdaptDomain.MINE to setOf(
            "mine.entry",
            "mine.vip",
            "mine.components"
        ),
        AdaptDomain.SPLASH to setOf(
            "splash.ads",
            BrandSplashDecoderAdapter.DIAGNOSTIC,
            BrandSplashKntrAdapter.DIAGNOSTIC,
            BrandSplashKntrAdapter.DIAGNOSTIC_EXIT,
            BrandSplashKntrAdapter.DIAGNOSTIC_PAGE
        ),
        AdaptDomain.KMP_WIRE to setOf(KmpGrpcWireAdapter.DIAGNOSTIC),
        AdaptDomain.PRIVACY_COMPAT to buildSet {
            add("teenagers.mode")
            SystemEnvironmentAdapter.Family.entries.forEach { add(it.diagnostic) }
            add(CloudKeyFreezeAdapter.DIAGNOSTIC)
            add(PrivacyChannelAdapter.DIAGNOSTIC_IGNET)
            add(PrivacyChannelAdapter.DIAGNOSTIC_KNEURON)
            add(WeChatShareCompatAccess.DIAGNOSTIC)
        }
    )

    private val familyToDomain: Map<PointFamily, AdaptDomain> = buildMap {
        pointFamiliesByDomain.forEach { (domain, families) ->
            families.forEach { family -> put(family, domain) }
        }
    }

    private val queryToDomain: Map<DexAssistQuery, AdaptDomain> = buildMap {
        dexQueriesByDomain.forEach { (domain, queries) ->
            queries.forEach { query -> put(query, domain) }
        }
    }

    fun domainOf(family: PointFamily): AdaptDomain = familyToDomain.getValue(family)

    fun domainOf(query: DexAssistQuery): AdaptDomain = queryToDomain.getValue(query)

    fun staleDomains(
        cachedVersions: Map<String, Int>,
        expectedVersions: Map<String, Int> = VersionAdapterContract.DOMAIN_RULE_VERSIONS
    ): Set<AdaptDomain> = AdaptDomain.entries.filterTo(linkedSetOf()) { domain ->
        (cachedVersions[domain.key] ?: 0) != (expectedVersions[domain.key] ?: 0)
    }

    fun stripDomains(
        result: VersionAdapter.AdaptResult,
        stale: Set<AdaptDomain>
    ): VersionAdapter.AdaptResult {
        if (stale.isEmpty()) return result
        val staleFamilies = stale.flatMapTo(linkedSetOf()) { pointFamiliesByDomain.getValue(it) }
        val staleDiagIds = stale.flatMapTo(linkedSetOf()) { diagnosticIdsByDomain.getValue(it) }
        val staleKeys = stale.mapTo(linkedSetOf()) { it.key }
        return result.copy(
            commentLow = result.commentLow.takeIf { AdaptDomain.COMMENT !in stale },
            commentHigh = result.commentHigh.takeIf { AdaptDomain.COMMENT !in stale },
            mineEntry = result.mineEntry.takeIf { AdaptDomain.MINE !in stale },
            pause = if (AdaptDomain.PLAYER_UI in stale) emptyPause else result.pause,
            banner = result.banner.takeIf { AdaptDomain.HOME_FEED !in stale },
            homeTopBar = result.homeTopBar.takeIf { AdaptDomain.HOME_FEED !in stale },
            mineVip = result.mineVip.takeIf { AdaptDomain.MINE !in stale },
            blockUpdate = result.blockUpdate.takeIf { AdaptDomain.UPDATE !in stale },
            dynamicTabs = result.dynamicTabs.takeIf { AdaptDomain.HOME_FEED !in stale },
            fullNumbers = result.fullNumbers.takeIf { AdaptDomain.HOME_FEED !in stale },
            playerPortrait = result.playerPortrait.takeIf { AdaptDomain.PLAYER_UI !in stale },
            playerInteractiveOverlays = result.playerInteractiveOverlays.takeIf { AdaptDomain.PLAYER_UI !in stale },
            pgcAutoActivityPopup = result.pgcAutoActivityPopup.takeIf { AdaptDomain.PLAYER_UI !in stale },
            playerStatusBar = result.playerStatusBar.takeIf { AdaptDomain.PLAYER_UI !in stale },
            homeRecommendFeed = result.homeRecommendFeed.takeIf { AdaptDomain.HOME_FEED !in stale },
            videoRelate = result.videoRelate.takeIf { AdaptDomain.DETAIL_RELATE !in stale },
            homeTabs = result.homeTabs.takeIf { AdaptDomain.HOME_FEED !in stale },
            homeComponents = result.homeComponents.takeIf { AdaptDomain.HOME_FEED !in stale },
            mineComponents = result.mineComponents.takeIf { AdaptDomain.MINE !in stale },
            mineAccountMine = result.mineAccountMine.takeIf { AdaptDomain.MINE !in stale },
            storyFeed = result.storyFeed.takeIf { AdaptDomain.HOME_FEED !in stale },
            bottomBar = result.bottomBar.takeIf { AdaptDomain.HOME_CHROME !in stale },
            playerQuality = result.playerQuality.takeIf { AdaptDomain.PLAYER_QUALITY !in stale },
            teenagersMode = result.teenagersMode.takeIf { AdaptDomain.PRIVACY_COMPAT !in stale },
            commentPurify = result.commentPurify.takeIf { AdaptDomain.COMMENT !in stale },
            commentFilter = result.commentFilter.takeIf { AdaptDomain.COMMENT !in stale },
            commentTopology = result.commentTopology.takeIf { AdaptDomain.COMMENT !in stale },
            commentSection = result.commentSection.takeIf { AdaptDomain.COMMENT !in stale },
            splashAds = result.splashAds.takeIf { AdaptDomain.SPLASH !in stale },
            diagnostics = result.diagnostics.filterNot { it.id in staleDiagIds },
            sponsorPlayer = result.sponsorPlayer.takeIf { AdaptDomain.PLAYER_SPEED !in stale },
            playerSpeedSession = result.playerSpeedSession.takeIf { AdaptDomain.PLAYER_SPEED !in stale },
            playerSpeedCalculator = result.playerSpeedCalculator.takeIf { AdaptDomain.PLAYER_SPEED !in stale },
            weChatShare = result.weChatShare.takeIf { AdaptDomain.PRIVACY_COMPAT !in stale },
            commentClassicStyle = result.commentClassicStyle.takeIf { AdaptDomain.COMMENT !in stale },
            playerPopupPromotion = result.playerPopupPromotion.takeIf { AdaptDomain.PLAYER_UI !in stale },
            brandSplashDecoder = result.brandSplashDecoder.takeIf { AdaptDomain.SPLASH !in stale },
            followFeedStyle = result.followFeedStyle.takeIf { AdaptDomain.FOLLOW_PAGE !in stale },
            playerSpeedMenu = result.playerSpeedMenu.takeIf { AdaptDomain.PLAYER_SPEED !in stale },
            pageBoundaries = result.pageBoundaries.takeIf { AdaptDomain.FOLLOW_PAGE !in stale },
            homeChrome = if (AdaptDomain.HOME_CHROME in stale) emptyMap() else result.homeChrome,
            homeChromeSemantics = result.homeChromeSemantics.takeIf { AdaptDomain.HOME_CHROME !in stale },
            systemEnvironment = result.systemEnvironment.takeIf { AdaptDomain.PRIVACY_COMPAT !in stale },
            commentComposeMenu = result.commentComposeMenu.takeIf { AdaptDomain.COMMENT !in stale },
            kmpGrpcWireEngine = result.kmpGrpcWireEngine.takeIf { AdaptDomain.KMP_WIRE !in stale },
            brandSplashKntrDraw = result.brandSplashKntrDraw.takeIf { AdaptDomain.SPLASH !in stale },
            brandSplashKntrExit = result.brandSplashKntrExit.takeIf { AdaptDomain.SPLASH !in stale },
            brandSplashKntrPage = result.brandSplashKntrPage.takeIf { AdaptDomain.SPLASH !in stale },
            cloudKeyFreezeContract = result.cloudKeyFreezeContract.takeIf { AdaptDomain.PRIVACY_COMPAT !in stale },
            privacyIgnetHttp = result.privacyIgnetHttp.takeIf { AdaptDomain.PRIVACY_COMPAT !in stale },
            privacyKNeuron = result.privacyKNeuron.takeIf { AdaptDomain.PRIVACY_COMPAT !in stale },
            guestPlayerCore = result.guestPlayerCore.takeIf { AdaptDomain.PLAYER_UI !in stale },
            detailSpecialTagMapper = result.detailSpecialTagMapper.takeIf { AdaptDomain.DETAIL_RELATE !in stale },
            aiPlaylistDirector = result.aiPlaylistDirector.takeIf { AdaptDomain.DETAIL_RELATE !in stale },
            followUpRecommendBodies = if (AdaptDomain.DETAIL_RELATE in stale) emptyMap() else result.followUpRecommendBodies,
            detailRelateGameComponent = result.detailRelateGameComponent.takeIf { AdaptDomain.DETAIL_RELATE !in stale },
            commentReplyButtonOwners = if (AdaptDomain.COMMENT in stale) emptyMap() else result.commentReplyButtonOwners,
            homeRecommendEarlyLoadListener = result.homeRecommendEarlyLoadListener.takeIf { AdaptDomain.HOME_FEED !in stale },
            legacyStoryDanmakuBind = result.legacyStoryDanmakuBind.takeIf { AdaptDomain.PLAYER_UI !in stale },
            playerSpeedTheseus2LongPress = result.playerSpeedTheseus2LongPress.takeIf { AdaptDomain.PLAYER_SPEED !in stale },
            coverage = result.coverage?.without(staleFamilies),
            domainRuleVersions = result.domainRuleVersions - staleKeys
        )
    }
}

internal fun VersionAdapter.AdaptResult.withoutDomains(
    stale: Set<AdaptDomain>
): VersionAdapter.AdaptResult = AdaptDomainCatalog.stripDomains(this, stale)
