package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BannerPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BottomBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentComposeMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentFilterPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentPurifyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentSectionPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentTopologyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.DynamicTabsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.FullNumberPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeComponentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeRecommendFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeTabPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeTopBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineAccountMinePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineComponentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineEntryPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineVipPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PausePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PgcAutoActivityPopupPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerInteractiveOverlayPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerPortraitPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerStatusBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplashAdPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StoryFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.TeenagersModePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.VideoRelatePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * [AdaptResult] JSON 编解码器。
 *
 * 严格保持 68 个 JSON 缓存键、`SCHEMA_VERSION = 62` 与 `AdaptResultValidator` 结构校验口径，
 * 避免编解码期间触发 `VersionAdapter.<clinit>`。
 */
internal object AdaptResultCodec {

    private fun JSONObject.strictStringOrNull(key: String): String? =
        if (isNull(key)) null else opt(key) as? String

    internal fun toJson(result: AdaptResult): JSONObject = JSONObject().apply {
        check(result.startupExcluded.isEmpty() && result.diagnostics.none { it.state == AdaptState.NOT_REQUESTED }) {
            "Partial startup adaptation cannot be persisted"
        }
        put("sv", VersionAdapterContract.SCHEMA_VERSION)
        put("v", result.biliVersionCode)
        put("ts", result.ts)
        result.commentLow?.let { put("low", it.toJson()) }
        result.commentHigh?.let { put("high", it.toJson()) }
        result.mineEntry?.let { put("mine", it.toJson()) }
        put("pause", result.pause.toJson())
        result.banner?.let { put("banner", it.toJson()) }
        result.homeTopBar?.let { put("home_top", it.toJson()) }
        result.mineVip?.let { put("mine_vip", it.toJson()) }
        result.blockUpdate?.let { put("block_update", it.toJson()) }
        result.dynamicTabs?.let { put("dynamic_tabs", it.toJson()) }
        result.fullNumbers?.let { put("full_numbers", it.toJson()) }
        result.playerPortrait?.let { put("player_portrait", it.toJson()) }
        result.playerInteractiveOverlays?.let { put("player_interactive_overlays", it.toJson()) }
        result.pgcAutoActivityPopup?.let { put("pgc_auto_activity_popup", it.toJson()) }
        result.playerStatusBar?.let { put("player_status_bar", it.toJson()) }
        result.homeRecommendFeed?.let { put("home_recommend_feed", it.toJson()) }
        result.videoRelate?.let { put("video_relate", it.toJson()) }
        result.homeTabs?.let { put("home_tabs", it.toJson()) }
        result.homeComponents?.let { put("home_components", it.toJson()) }
        result.mineComponents?.let { put("mine_components", it.toJson()) }
        result.mineAccountMine?.let { put("mine_account_mine", it.toJson()) }
        result.storyFeed?.let { put("story_feed", it.toJson()) }
        result.bottomBar?.let { put("bottom_bar", it.toJson()) }
        result.playerQuality?.let { put("player_quality", it.toJson()) }
        result.teenagersMode?.let { put("teenagers_mode", it.toJson()) }
        result.commentPurify?.let { put("comment_purify", it.toJson()) }
        result.commentFilter?.let { put("comment_filter", it.toJson()) }
        result.commentTopology?.let { put("comment_topology", it.toJson()) }
        result.commentSection?.let { put("comment_section", it.toJson()) }
        result.splashAds?.let { put("splash_ads", it.toJson()) }
        result.sponsorPlayer?.let { put("sponsor_player", it.toJson()) }
        result.playerSpeedSession?.let { put("player_speed_session", it.toJson()) }
        result.playerSpeedCalculator?.let { put("player_speed_calculator", it.toJson()) }
        result.weChatShare?.let { put("wechat_share", it.toJson()) }
        result.commentClassicStyle?.let { put("comment_classic", it.toJson()) }
        result.playerPopupPromotion?.let { put("player_popup_promotion", it.toJson()) }
        result.brandSplashDecoder?.let { put("brand_splash_decoder", it.toJson()) }
        result.followFeedStyle?.let { put("follow_feed_style", it.toJson()) }
        result.playerSpeedMenu?.let { put("player_speed_menu", it.toJson()) }
        result.pageBoundaries?.let { put("page_boundaries", it.toJson()) }
        if (result.homeChrome.isNotEmpty()) put("home_chrome", HomeChromeAdapter.toJson(result.homeChrome))
        result.homeChromeSemantics?.let { put("home_chrome_semantics", it.toJson()) }
        result.systemEnvironment?.let { put("system_environment", it.toJson()) }
        result.commentComposeMenu?.let { put("comment_compose_menu", it.toJson()) }
        result.kmpGrpcWireEngine?.let { put("kmp_grpc_wire_engine", it.toJson()) }
        result.brandSplashKntrDraw?.let { put("brand_splash_kntr_draw", it) }
        result.brandSplashKntrExit?.let { put("brand_splash_kntr_exit", it) }
        result.brandSplashKntrPage?.let { put("brand_splash_kntr_page", it.toJson()) }
        result.cloudKeyFreezeContract?.let { put("cloud_key_freeze_contract", it) }
        result.privacyIgnetHttp?.let { put("privacy_ignet_http", it.toJson()) }
        result.privacyKNeuron?.let { put("privacy_kneuron", it) }
        result.guestPlayerCore?.let { put("guest_player_core", it) }
        result.detailSpecialTagMapper?.let { put("detail_special_tag_mapper", it.toJson()) }
        result.aiPlaylistDirector?.let { put("ai_playlist_director", it) }
        if (result.followUpRecommendBodies.isNotEmpty()) {
            put("follow_up_recommend_bodies", FollowUpRecommendAdapter.toJson(result.followUpRecommendBodies))
        }
        result.detailRelateGameComponent?.let { put("detail_relate_game_component", it) }
        if (result.commentReplyButtonOwners.isNotEmpty()) {
            put("comment_reply_button_owners", CommentReplyButtonAdapter.toJson(result.commentReplyButtonOwners))
        }
        result.homeRecommendEarlyLoadListener?.let { put("home_recommend_early_load_listener", it) }
        result.legacyStoryDanmakuBind?.let { put("legacy_story_danmaku_bind", it.toJson()) }
        result.playerSpeedTheseus2LongPress?.let { put("player_speed_theseus2_long_press", it.toJson()) }
        result.coverage?.let { put("cov", it.toJson()) }
        if (result.domainRuleVersions.isNotEmpty()) {
            put("drv", JSONObject().apply {
                result.domainRuleVersions.toSortedMap().forEach { (k, v) -> put(k, v) }
            })
        }
        put("fp", result.hostFingerprint)
        put("protocol_fp", result.protocolFingerprint)
        put("dex_fp", result.dexSourceFingerprint)
        put("diag", JSONArray().apply { result.diagnostics.forEach { put(it.toJson()) } })
    }

    internal fun fromJson(o: JSONObject): AdaptResult? {
        if (o.optInt("sv", 0) != VersionAdapterContract.SCHEMA_VERSION) return null
        val diagnosticsArray = o.optJSONArray("diag") ?: return null
        val diagnostics = (0 until diagnosticsArray.length()).map { index ->
            AdaptDiagnostic.fromJson(diagnosticsArray.getJSONObject(index)) ?: return null
        }
        val drvObj: JSONObject? = if (o.has("drv")) {
            if (o.isNull("drv")) return null
            o.optJSONObject("drv") ?: return null
        } else {
            null
        }
        val domainRuleVersions: Map<String, Int> = if (drvObj == null) {
            emptyMap()
        } else {
            val parsed = linkedMapOf<String, Int>()
            val keys = drvObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in VersionAdapterContract.DOMAIN_RULE_VERSIONS) continue
                val version = when (val raw = drvObj.get(key)) {
                    is Int -> raw
                    is Long -> if (raw in 0L..Int.MAX_VALUE.toLong()) raw.toInt() else return null
                    else -> return null
                }
                if (version < 0) return null
                parsed[key] = version
            }
            parsed
        }
        return AdaptResult(
            biliVersionCode = o.getInt("v"),
            ts = o.optLong("ts", 0L),
            commentLow = if (o.has("low")) HookPoint.fromJson(o.getJSONObject("low")) else null,
            commentHigh = if (o.has("high")) HookPoint.fromJson(o.getJSONObject("high")) else null,
            mineEntry = o.optJSONObject("mine")?.let(MineEntryPoint::fromJson),
            pause = o.optJSONObject("pause")?.let(PausePoints::fromJson)
                ?: PausePoints(emptyList(), null, null, null),
            banner = o.optJSONObject("banner")?.let(BannerPoint::fromJson),
            homeTopBar = o.optJSONObject("home_top")?.let(HomeTopBarPoints::fromJson),
            mineVip = o.optJSONObject("mine_vip")?.let(MineVipPoint::fromJson),
            blockUpdate = o.optJSONObject("block_update")?.let(HookPoint::fromJson),
            dynamicTabs = o.optJSONObject("dynamic_tabs")?.let(DynamicTabsPoint::fromJson),
            fullNumbers = o.optJSONObject("full_numbers")?.let(FullNumberPoints::fromJson),
            playerPortrait = o.optJSONObject("player_portrait")?.let(PlayerPortraitPoints::fromJson),
            playerInteractiveOverlays = o.optJSONObject("player_interactive_overlays")
                ?.let(PlayerInteractiveOverlayPoints::fromJson),
            pgcAutoActivityPopup = o.optJSONObject("pgc_auto_activity_popup")
                ?.let(PgcAutoActivityPopupPoints::fromJson),
            playerStatusBar = o.optJSONObject("player_status_bar")?.let(PlayerStatusBarPoints::fromJson),
            homeRecommendFeed = o.optJSONObject("home_recommend_feed")
                ?.let(HomeRecommendFeedPoints::fromJson),
            videoRelate = o.optJSONObject("video_relate")?.let(VideoRelatePoints::fromJson),
            homeTabs = o.optJSONObject("home_tabs")?.let(HomeTabPoints::fromJson),
            homeComponents = o.optJSONObject("home_components")?.let(HomeComponentPoints::fromJson),
            mineComponents = o.optJSONObject("mine_components")?.let(MineComponentPoints::fromJson),
            mineAccountMine = o.optJSONObject("mine_account_mine")?.let(MineAccountMinePoints::fromJson),
            storyFeed = o.optJSONObject("story_feed")?.let(StoryFeedPoints::fromJson),
            bottomBar = o.optJSONObject("bottom_bar")?.let(BottomBarPoints::fromJson),
            playerQuality = o.optJSONObject("player_quality")?.let(PlayerQualityPoints::fromJson),
            teenagersMode = o.optJSONObject("teenagers_mode")?.let(TeenagersModePoints::fromJson),
            commentPurify = o.optJSONObject("comment_purify")?.let(CommentPurifyPoints::fromJson),
            commentFilter = o.optJSONObject("comment_filter")?.let(CommentFilterPoints::fromJson),
            commentTopology = o.optJSONObject("comment_topology")?.let(CommentTopologyPoints::fromJson),
            commentSection = o.optJSONObject("comment_section")?.let(CommentSectionPoints::fromJson),
            splashAds = o.optJSONObject("splash_ads")?.let(SplashAdPoints::fromJson),
            hostFingerprint = o.optString("fp"),
            protocolFingerprint = o.optString("protocol_fp"),
            diagnostics = diagnostics,
            dexSourceFingerprint = o.getString("dex_fp"),
            sponsorPlayer = o.optJSONObject("sponsor_player")?.let(SponsorPlayerClasses::fromJson),
            playerSpeedSession = if (o.has("player_speed_session"))
                PlayerSpeedSessionClasses.fromJson(o.getJSONObject("player_speed_session")) ?: return null else null,
            playerSpeedCalculator = if (o.has("player_speed_calculator"))
                LongPressCalculatorPoint.fromJson(o.getJSONObject("player_speed_calculator")) ?: return null else null,
            weChatShare = if (o.has("wechat_share"))
                WeChatShareClasses.fromJson(o.getJSONObject("wechat_share")) ?: return null else null,
            commentClassicStyle = if (o.has("comment_classic"))
                CommentClassicStylePoints.fromJson(o.getJSONObject("comment_classic")) ?: return null else null,
            playerPopupPromotion = if (o.has("player_popup_promotion"))
                PlayerPopupPromotionPoints.fromJson(o.getJSONObject("player_popup_promotion")) ?: return null else null,
            brandSplashDecoder = if (o.has("brand_splash_decoder")) {
                if (o.isNull("brand_splash_decoder")) return null
                val rawObj = o.optJSONObject("brand_splash_decoder") ?: return null
                HookPoint.fromJson(rawObj).also { point ->
                    if (point.viewField != null || point.paramClassNames != listOf(BrandSplashDecoderAdapter.INFO)) {
                        return null
                    }
                }
            } else null,
            followFeedStyle = if (o.has("follow_feed_style"))
                FollowFeedPoints.fromJson(o.getJSONObject("follow_feed_style")) ?: return null else null,
            playerSpeedMenu = if (o.has("player_speed_menu"))
                PlayerSpeedMenuPoints.fromJson(o.getJSONObject("player_speed_menu")) ?: return null else null,
            pageBoundaries = if (o.has("page_boundaries"))
                PageBoundaryPoints.fromJson(o.getJSONObject("page_boundaries")) ?: return null else null,
            homeChrome = if (o.has("home_chrome"))
                HomeChromeAdapter.fromJson(o.getJSONObject("home_chrome")) ?: return null else emptyMap(),
            homeChromeSemantics = if (o.has("home_chrome_semantics"))
                HomeChromeSemanticsPoint.fromJson(o.getJSONObject("home_chrome_semantics")) ?: return null else null,
            systemEnvironment = if (o.has("system_environment"))
                SystemEnvironmentPoints.fromJson(o.getJSONObject("system_environment")) ?: return null else null,
            commentComposeMenu = if (o.has("comment_compose_menu"))
                CommentComposeMenuPoints.fromJson(o.getJSONObject("comment_compose_menu")) ?: return null else null,
            kmpGrpcWireEngine = if (o.has("kmp_grpc_wire_engine")) {
                if (o.isNull("kmp_grpc_wire_engine")) return null
                val rawObj = o.optJSONObject("kmp_grpc_wire_engine") ?: return null
                HookPoint.fromJson(rawObj).also { point ->
                    if (point.viewField != null || point.paramClassNames?.size !in 4..6) return null
                }
            } else null,
            brandSplashKntrDraw = if (o.has("brand_splash_kntr_draw"))
                o.strictStringOrNull("brand_splash_kntr_draw")
                    ?.takeIf(BrandSplashKntrAdapter::allowedOwnerName) ?: return null else null,
            brandSplashKntrExit = if (o.has("brand_splash_kntr_exit"))
                o.strictStringOrNull("brand_splash_kntr_exit")
                    ?.takeIf(BrandSplashKntrAdapter::allowedOwnerName) ?: return null else null,
            brandSplashKntrPage = if (o.has("brand_splash_kntr_page")) {
                if (o.isNull("brand_splash_kntr_page")) return null
                val rawObj = o.optJSONObject("brand_splash_kntr_page") ?: return null
                HookPoint.fromJson(rawObj).also { point ->
                    if (point.viewField != null || point.paramClassNames?.size !in 6..7 ||
                        !BrandSplashKntrAdapter.allowedOwnerName(point.className)
                    ) return null
                }
            } else null,
            cloudKeyFreezeContract = if (o.has("cloud_key_freeze_contract"))
                o.strictStringOrNull("cloud_key_freeze_contract")
                    ?.takeIf(CloudKeyFreezeAdapter::allowedOwnerName) ?: return null else null,
            privacyIgnetHttp = if (o.has("privacy_ignet_http")) {
                if (o.isNull("privacy_ignet_http")) return null
                val rawObj = o.optJSONObject("privacy_ignet_http") ?: return null
                HookPoint.fromJson(rawObj).also { point ->
                    if (point.viewField != null || !point.paramClassNames.isNullOrEmpty() ||
                        !PrivacyChannelAdapter.allowedIgnetOwnerName(point.className)
                    ) return null
                }
            } else null,
            privacyKNeuron = if (o.has("privacy_kneuron"))
                o.strictStringOrNull("privacy_kneuron")
                    ?.takeIf(PrivacyChannelAdapter::allowedKNeuronOwnerName) ?: return null else null,
            guestPlayerCore = if (o.has("guest_player_core"))
                o.strictStringOrNull("guest_player_core")
                    ?.takeIf(GuestPlayerCoreAdapter::allowedOwnerName) ?: return null else null,
            detailSpecialTagMapper = if (o.has("detail_special_tag_mapper")) {
                if (o.isNull("detail_special_tag_mapper")) return null
                val rawObj = o.optJSONObject("detail_special_tag_mapper") ?: return null
                HookPoint.fromJson(rawObj).also { point ->
                    if (point.viewField != null ||
                        point.paramClassNames != listOf(DetailUnitedPresentationPurifyPolicy.SPECIAL_TAG_CLASS) ||
                        !DetailSpecialTagAdapter.allowedOwnerName(point.className)
                    ) return null
                }
            } else null,
            aiPlaylistDirector = if (o.has("ai_playlist_director"))
                o.strictStringOrNull("ai_playlist_director")
                    ?.takeIf(AiPlaylistDirectorAdapter::allowedOwnerName) ?: return null else null,
            followUpRecommendBodies = if (o.has("follow_up_recommend_bodies")) {
                if (o.isNull("follow_up_recommend_bodies")) return null
                o.optJSONObject("follow_up_recommend_bodies")
                    ?.let(FollowUpRecommendAdapter::fromJson) ?: return null
            } else emptyMap(),
            detailRelateGameComponent = if (o.has("detail_relate_game_component"))
                o.strictStringOrNull("detail_relate_game_component")
                    ?.takeIf(DetailRelateGameAdapter::allowedComponentName) ?: return null else null,
            commentReplyButtonOwners = if (o.has("comment_reply_button_owners")) {
                if (o.isNull("comment_reply_button_owners")) return null
                o.optJSONObject("comment_reply_button_owners")
                    ?.let(CommentReplyButtonAdapter::fromJson) ?: return null
            } else emptyMap(),
            homeRecommendEarlyLoadListener = if (o.has("home_recommend_early_load_listener"))
                o.strictStringOrNull("home_recommend_early_load_listener")
                    ?.takeIf(HomeRecommendEarlyLoadAdapter::allowedClassName) ?: return null else null,
            legacyStoryDanmakuBind = if (o.has("legacy_story_danmaku_bind")) {
                if (o.isNull("legacy_story_danmaku_bind")) return null
                val rawObj = o.optJSONObject("legacy_story_danmaku_bind") ?: return null
                HookPoint.fromJson(rawObj).also { point ->
                    if (!AdaptResultValidator.isValidLegacyStoryDanmakuBind(point)) return null
                }
            } else null,
            playerSpeedTheseus2LongPress = if (o.has("player_speed_theseus2_long_press")) {
                if (o.isNull("player_speed_theseus2_long_press")) return null
                o.optJSONObject("player_speed_theseus2_long_press")
                    ?.let(Theseus2LongPressPoint::fromJson) ?: return null
            } else null,
            // 旧缓存没有 cov：覆盖范围未知（null），不会被当成覆盖完整。
            coverage = o.optJSONObject("cov")?.let(AdaptCoverage::fromJson),
            domainRuleVersions = domainRuleVersions
        ).takeIf(AdaptResultValidator::isStructurallyValid)
    }
}
