package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSource
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

internal enum class HighlightKind { NEW, IMPROVED, FIXED }
/**
 * [alsoCovers]：同一项功能附带的其它设置（例如 2–4 号来源的类型/地址/模型、四个过滤面各自的判定来源），
 * 显式逐个列出算作"已审阅"，不必每个都占一条亮点（完整历史仍有条数上限）。只用于覆盖审阅，不参与跳转。
 */
internal data class HighlightDestination(
    val settingId: String,
    val homeFilterOption: Boolean = false,
    val alsoCovers: Set<String> = emptySet()
)
internal data class ReleaseHighlight(
    val id: String, val kind: HighlightKind, val descriptionRes: Int,
    val destination: HighlightDestination? = null, val standaloneTitleRes: Int? = null
) {
    val titleRes: Int get() = standaloneTitleRes ?: SettingsCatalog.byId.getValue(requireNotNull(destination).settingId).labelRes
}
internal data class ReleaseHighlightsBatch(
    val revision: Int, val entries: List<ReleaseHighlight>, val automatic: Boolean = true
)

/** Bundled and reviewed with the APK; never executes remote release-note links as navigation. */
internal object ReleaseHighlightsCatalog {
    // 16 → 17（1.1.6）：复核结论是批次内容不变。本轮强化的视频提及、好物商品、
    // 开屏广告、更新检查、暂停页广告都复用既有开关，没有引入新设置，所以
    // "新设置必须有公告与导航目标"这条门禁不需要新增条目；批次 1 本身在
    // v1.1.5 时还不存在，从未随发布展示过，也不该拆成新批次。
    // 18 → 19（1.1.8）：新增批次 2，见下方批次 2 的注释。
    // 19 → 20（1.1.9）：复核结论是批次内容不变。本轮是浅色模式适配、动画跳变修复与性能优化，
    // 没有引入新设置，不需要新增条目。
    // 20 → 21（1.2.0）：新增的设置（智能过滤的接口类型 / 模型 / 超时 / 多来源 / 各面判定来源 / 自定义类型 /
    // 判定口径 / 缓存保留天数）都已有公告条目与导航目标；其余改动（NPatch 下设置同步与重启入口、
    // 9.14.0 适配、竖屏进入详情页的来源放行、新通道过滤）没有引入新设置，不需要新增条目。
    // 21 → 22（1.2.1）：1.2.0 之后新增的设置随各自功能另开批次 4 起的公告条目与导航目标，
    // 本次改版本号不新增条目。
    // 注意以后改版本号都要改一下这个地方的版本号，下面这个REVIEWED，不然过不了ci
    const val REVIEWED_VERSION_CODE = 22
    const val SETTINGS_BASELINE_VERSION = 13
    // 历史已达 64 项；新增功能时保留已有公告，目录继续以 96 项为硬上限。
    const val MAX_BUNDLED_ENTRIES = 96
    val batches = listOf(ReleaseHighlightsBatch(1, listOf(
        ReleaseHighlight("player-end-page-recommend", HighlightKind.NEW,
            R.string.hide_player_end_page_recommend_tip,
            HighlightDestination("player.end_page_recommend.hidden")),
        ReleaseHighlight("communication-compatibility", HighlightKind.NEW,
            R.string.communication_compatibility_tip,
            HighlightDestination("communication.compatibility.enabled")),
        // 四个子项各自成条：门禁要求每个新增设置都有导航目标，而
        // HighlightDestination 一条只能指一个 settingId。标题自动取设置自己的 labelRes。
        ReleaseHighlight("detail-honor", HighlightKind.NEW,
            R.string.highlights_detail_honor,
            HighlightDestination("purify.detail.honor.removed")),
        ReleaseHighlight("detail-live-order", HighlightKind.NEW,
            R.string.highlights_detail_live_order,
            HighlightDestination("purify.detail.live_order.removed")),
        ReleaseHighlight("detail-ugc-season", HighlightKind.NEW,
            R.string.highlights_detail_ugc_season,
            HighlightDestination("purify.detail.ugc_season.removed")),
        ReleaseHighlight("detail-up-vip-label", HighlightKind.NEW,
            R.string.highlights_detail_up_vip_label,
            HighlightDestination("purify.detail.up_vip_label.removed")),
        ReleaseHighlight("detail-topic-tags", HighlightKind.NEW,
            R.string.highlights_detail_topic_tags,
            HighlightDestination("purify.detail.topic_tags.removed")),
        ReleaseHighlight("detail-staff-follow", HighlightKind.NEW,
            R.string.highlights_detail_staff_follow,
            HighlightDestination("purify.detail.staff_follow.hidden")),
        ReleaseHighlight("detail-hot-banner", HighlightKind.NEW,
            R.string.highlights_detail_hot_banner,
            HighlightDestination("purify.detail.hot_banner.hidden")),
        ReleaseHighlight("panel-window-blur", HighlightKind.NEW, R.string.highlights_panel_blur,
            HighlightDestination("module_ui.appearance.panel_window_blur")),
        ReleaseHighlight("search-home-hidden", HighlightKind.NEW, R.string.highlights_search,
            HighlightDestination("search.home_recommend.hidden")),
        ReleaseHighlight("dynamic-frequent-hidden", HighlightKind.NEW, R.string.highlights_dynamic,
            HighlightDestination("dynamic.frequent_visits.hidden")),
        ReleaseHighlight("home-pgc-filter", HighlightKind.NEW, R.string.highlights_pgc,
            HighlightDestination("home.recommend.pgc.removed", true)),
        ReleaseHighlight("home-special-filter", HighlightKind.NEW, R.string.highlights_special,
            HighlightDestination("home.recommend.special_cards.removed", true)),
        ReleaseHighlight("default-speed-sessions", HighlightKind.IMPROVED, R.string.highlights_speed,
            HighlightDestination(SettingsCatalog.ID_PLAYER_DEFAULT_SPEED)),
        ReleaseHighlight("interactive-response-coverage", HighlightKind.FIXED, R.string.highlights_interactive,
            HighlightDestination("player.interactive_overlays.hidden")),
        ReleaseHighlight("home-blocked-tids", HighlightKind.NEW,
            R.string.highlights_home_blocked_tids,
            HighlightDestination("home.recommend.blocked_tids")),
        ReleaseHighlight("home-section-pick", HighlightKind.NEW,
            R.string.highlights_home_section_pick,
            HighlightDestination("home.recommend.section_pick.enabled")),
        ReleaseHighlight("home-blocked-authors", HighlightKind.NEW,
            R.string.highlights_home_blocked_authors,
            HighlightDestination("home.recommend.blocked_authors")),
        // 一条 destination 只能指一个 settingId，所以作者与标签各成一条。
        ReleaseHighlight("relate-blocked-authors", HighlightKind.NEW,
            R.string.highlights_video_relate_blocked_authors,
            HighlightDestination("video.related.blocked_authors")),
        ReleaseHighlight("relate-blocked-tags", HighlightKind.NEW,
            R.string.highlights_video_relate_blocked_authors,
            HighlightDestination("video.related.blocked_tags")),
        ReleaseHighlight("player-popup-promotion", HighlightKind.NEW, R.string.hide_player_popup_promotion_tip,
            HighlightDestination("player.popup_promotion.hidden")),
        ReleaseHighlight("independent-adaptation", HighlightKind.FIXED, R.string.highlights_stability,
            standaloneTitleRes = R.string.highlights_stability_title)
    )),
    // 批次 2（1.1.8）：凝光视效 / 新设置界面 / 手感 / 宿主兼容四条重构亮点，加上 v1.1.7 发布后
    // 误追加进批次 1 的八条新功能——1.1.7 用户已看过批次 1，留在那里永远不会再弹。装过
    // 1.1.8 Alpha 的用户会再看到其中六条，这是有意取舍（正式版用户优先）。
    ReleaseHighlightsBatch(2, listOf(
        ReleaseHighlight("glow-engine", HighlightKind.IMPROVED, R.string.highlights_glow_engine,
            standaloneTitleRes = R.string.highlights_glow_engine_title),
        ReleaseHighlight("settings-home-pages", HighlightKind.NEW, R.string.highlights_settings_home,
            standaloneTitleRes = R.string.highlights_settings_home_title),
        ReleaseHighlight("motion-and-feel", HighlightKind.IMPROVED, R.string.highlights_motion,
            standaloneTitleRes = R.string.highlights_motion_title),
        ReleaseHighlight("tablet-home-frame", HighlightKind.FIXED, R.string.highlights_host_compat,
            standaloneTitleRes = R.string.highlights_host_compat_title),
        ReleaseHighlight("player-codec-preference", HighlightKind.NEW, R.string.highlights_player_codec_preference,
            HighlightDestination("player.codec.preference")),
        ReleaseHighlight("player-decode-mode", HighlightKind.NEW, R.string.highlights_player_decode_mode,
            HighlightDestination("player.decode.mode")),
        ReleaseHighlight("component-library-download", HighlightKind.NEW, R.string.highlights_component_library,
            HighlightDestination("client.component_library.download.blocked")),
        // selectors / rules 各自成条（门禁要求每个新增设置都有导航目标），两条指向同一个
        // 面板入口；第二条借 standaloneTitleRes 取「全量禁止」的标题，免得列表里两行同名。
        ReleaseHighlight("component-library-pools", HighlightKind.NEW,
            R.string.highlights_component_pool_pick,
            HighlightDestination("client.component_library.blocked_pools.selectors")),
        ReleaseHighlight("component-library-pools-all", HighlightKind.NEW,
            R.string.highlights_component_pool_block_all,
            HighlightDestination("client.component_library.blocked_pools.rules"),
            standaloneTitleRes = R.string.component_picker_block_all),
        // 开关本体住在「管理推荐屏蔽」弹窗里，所以导航目标绑在那一行入口上
        // （MainActivity 里 settingsDestinations.bind 的那处），点进去再打开面板。
        ReleaseHighlight("recommend-feedback-auto-confirm", HighlightKind.NEW,
            R.string.highlights_recommendation_auto_confirm,
            HighlightDestination("home.recommend.feedback_auto_confirm")),
        ReleaseHighlight("recommend-play-count-min", HighlightKind.NEW,
            R.string.highlights_recommend_play_count,
            HighlightDestination(SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT)),
        ReleaseHighlight("recommend-play-count-max", HighlightKind.NEW,
            R.string.highlights_recommend_play_count,
            HighlightDestination(SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT))
    )),
    // 批次 3（下一版）：屏蔽 AI 生成声明视频、它的强力模式，以及强力模式所需的 access_key 授权。1.1.9 用户已看过批次 2，
    // 追加进去永远不会再弹，所以另开批次；REVIEWED_VERSION_CODE 随下一次改版本号复核。
    ReleaseHighlightsBatch(3, listOf(
        ReleaseHighlight("ai-declared-videos", HighlightKind.NEW,
            R.string.highlights_ai_declared_videos,
            HighlightDestination(SettingsCatalog.ID_AI_DECLARED_VIDEOS_BLOCKED)),
        ReleaseHighlight("ai-declared-videos-strong-mode", HighlightKind.NEW,
            R.string.highlights_ai_declared_videos_strong_mode,
            HighlightDestination(SettingsCatalog.ID_AI_DECLARED_VIDEOS_STRONG_MODE)),
        ReleaseHighlight("ai-declared-videos-precheck", HighlightKind.NEW,
            R.string.highlights_ai_declared_precheck,
            HighlightDestination(SettingsCatalog.ID_AI_DECLARED_VIDEOS_PRECHECK)),
        ReleaseHighlight("bili-access-key", HighlightKind.NEW,
            R.string.highlights_bili_access_key,
            HighlightDestination(SettingsCatalog.ID_BILI_ACCESS_KEY_AUTHORIZED)),
        // 智能过滤动态：开关在净化 → 动态；JEV 三项配置同住兼容区的一行入口，共用一段说明。
        ReleaseHighlight("dynamic-semantic-filter", HighlightKind.NEW,
            R.string.highlights_dynamic_semantic_filter,
            HighlightDestination(SettingsCatalog.ID_DYNAMIC_SEMANTIC_FILTER)),
        ReleaseHighlight("semantic-jev-endpoint", HighlightKind.NEW,
            R.string.highlights_semantic_jev,
            HighlightDestination(SettingsCatalog.ID_SEMANTIC_JEV_ENDPOINT,
                alsoCovers = setOf(SettingsCatalog.ID_SEMANTIC_JEV_SENSITIVITY,
                    SettingsCatalog.ID_SEMANTIC_JEV_WAIT_FIRST_SCREEN))),
        // v32：弹幕 / 评论 / 推荐视频三个智能过滤开关，外加四个面的屏蔽类型勾选。
        ReleaseHighlight("danmaku-semantic-filter", HighlightKind.NEW,
            R.string.highlights_semantic_more_surfaces,
            HighlightDestination(SettingsCatalog.ID_DANMAKU_SEMANTIC_FILTER)),
        ReleaseHighlight("comment-semantic-filter", HighlightKind.NEW,
            R.string.highlights_semantic_more_surfaces,
            HighlightDestination(SettingsCatalog.ID_COMMENT_SEMANTIC_FILTER)),
        ReleaseHighlight("video-semantic-filter", HighlightKind.NEW,
            R.string.highlights_semantic_more_surfaces,
            HighlightDestination(SettingsCatalog.ID_VIDEO_SEMANTIC_FILTER)),
        ReleaseHighlight("dynamic-semantic-rules", HighlightKind.NEW,
            R.string.highlights_semantic_rules,
            HighlightDestination(SettingsCatalog.ID_DYNAMIC_SEMANTIC_RULES)),
        ReleaseHighlight("danmaku-semantic-rules", HighlightKind.NEW,
            R.string.highlights_semantic_rules,
            HighlightDestination(SettingsCatalog.ID_DANMAKU_SEMANTIC_RULES)),
        ReleaseHighlight("comment-semantic-rules", HighlightKind.NEW,
            R.string.highlights_semantic_rules,
            HighlightDestination(SettingsCatalog.ID_COMMENT_SEMANTIC_RULES)),
        ReleaseHighlight("video-semantic-rules", HighlightKind.NEW,
            R.string.highlights_semantic_rules,
            HighlightDestination(SettingsCatalog.ID_VIDEO_SEMANTIC_RULES)),
        ReleaseHighlight("semantic-jev-cache-days", HighlightKind.NEW,
            R.string.highlights_semantic_cache,
            HighlightDestination(SettingsCatalog.ID_SEMANTIC_JEV_CACHE_DAYS)),
        ReleaseHighlight("semantic-provider", HighlightKind.NEW,
            R.string.highlights_semantic_provider,
            HighlightDestination(SettingsCatalog.ID_SEMANTIC_JEV_PROVIDER)),
        ReleaseHighlight("semantic-model", HighlightKind.NEW,
            R.string.highlights_semantic_provider,
            HighlightDestination(SettingsCatalog.ID_SEMANTIC_JEV_MODEL)),
        ReleaseHighlight("semantic-timeout", HighlightKind.NEW,
            R.string.highlights_semantic_timeout,
            HighlightDestination(SettingsCatalog.ID_SEMANTIC_JEV_TIMEOUT_MS)),
        ReleaseHighlight("semantic-multi-source", HighlightKind.NEW,
            R.string.highlights_semantic_multi_source,
            HighlightDestination(SettingsCatalog.ID_SEMANTIC_SOURCES, alsoCovers = (2..4).flatMap { index ->
                listOf("provider", "endpoint", "model").map { "compat.semantic_source.$index.$it" }
            }.toSet())),
        ReleaseHighlight("semantic-comment-route", HighlightKind.NEW,
            R.string.highlights_semantic_multi_source,
            HighlightDestination(SettingsCatalog.ID_COMMENT_SEMANTIC_SOURCE,
                alsoCovers = SemanticSurface.entries.map(SettingsCatalog::semanticRouteId).toSet())),
        ReleaseHighlight("semantic-guidance", HighlightKind.NEW,
            R.string.highlights_semantic_custom,
            HighlightDestination(SettingsCatalog.ID_SEMANTIC_JEV_GUIDANCE)),
        ReleaseHighlight("semantic-comment-custom", HighlightKind.NEW,
            R.string.highlights_semantic_custom,
            HighlightDestination(SettingsCatalog.ID_COMMENT_SEMANTIC_CUSTOM_RULES,
                alsoCovers = SemanticSurface.entries.map(SettingsCatalog::semanticCustomRulesId).toSet()))
    )),
    // 批次 4（下一版）：Story 竖屏流右侧互动图标逐个隐藏。批次 3 已随 v1.2.0 发布，追加进去永远不会再弹，
    // 所以另开批次。六个图标共用一条：点赞为导航目标，其余五个按同一功能的配套项列入 alsoCovers。
    ReleaseHighlightsBatch(4, listOf(
        ReleaseHighlight("brand-splash-skip", HighlightKind.NEW,
            R.string.brand_splash_skip_tip, HighlightDestination("splash.brand.skipped")),
        ReleaseHighlight("brand-splash-custom", HighlightKind.NEW,
            R.string.brand_splash_custom_tip, HighlightDestination("splash.custom_selection.enabled")),
        ReleaseHighlight("host-video-cards", HighlightKind.NEW,
            R.string.host_video_cards_tip,
            HighlightDestination("host.video_cards.enabled", alsoCovers = setOf("host.video_cards.radius_dp"))),
        ReleaseHighlight("story-action-icons", HighlightKind.NEW,
            R.string.highlights_story_action_icons,
            HighlightDestination("story.action.like.hidden",
                alsoCovers = listOf("comment", "coin", "favorite", "share", "danmaku_toggle")
                    .map { "story.action.$it.hidden" }.toSet())),
        ReleaseHighlight("host-bottom-bar-fx", HighlightKind.NEW,
            R.string.host_bottom_bar_liquid_glass_tip,
            HighlightDestination("host.bottom_bar.liquid_glass.enabled",
                alsoCovers = setOf("host.bottom_bar.touch_glow.enabled",
                    "host.bottom_bar.compact.enabled", "host.bottom_bar.icon_only.enabled"))),
        ReleaseHighlight("host-top-bar-fx", HighlightKind.NEW,
            R.string.host_top_bar_liquid_glass_tip,
            HighlightDestination("host.top_bar.liquid_glass.enabled",
                alsoCovers = setOf("host.top_bar.touch_glow.enabled")))
    )), ReleaseHighlightsBatch(6, listOf(
        ReleaseHighlight("reply-topology-keyword-export", HighlightKind.IMPROVED,
            R.string.highlights_reply_topology_keyword_export,
            HighlightDestination("comments.reply_topology.enabled"))
    )), ReleaseHighlightsBatch(7, listOf(
        ReleaseHighlight("reply-topology-tree-explorer", HighlightKind.IMPROVED,
            R.string.highlights_reply_topology_tree_explorer,
            HighlightDestination("comments.reply_topology.enabled"))
    )), ReleaseHighlightsBatch(8, listOf(
        ReleaseHighlight("sponsorblock", HighlightKind.NEW, R.string.highlights_sponsorblock,
            HighlightDestination("player.sponsorblock.enabled",
                alsoCovers = setOf("player.sponsorblock.automatic")))
    )), ReleaseHighlightsBatch(9, listOf(
        ReleaseHighlight("host-glass-materials", HighlightKind.IMPROVED, R.string.highlights_host_glass_materials,
            HighlightDestination("host.top_bar.glass.material", alsoCovers = setOf(
                "host.top_bar.glass.renderer", "host.bottom_bar.glass.material", "host.bottom_bar.glass.renderer")))
    )), ReleaseHighlightsBatch(10, listOf(
        ReleaseHighlight("semantic-eight-sources", HighlightKind.IMPROVED, R.string.highlights_semantic_eight_sources,
            HighlightDestination(SettingsCatalog.ID_SEMANTIC_SOURCES,
                alsoCovers = (5..SemanticSource.MAX_SOURCES).flatMap { index ->
                    listOf("provider", "endpoint", "model").map { "compat.semantic_source.$index.$it" }
                }.toSet()))
    )), ReleaseHighlightsBatch(11, listOf(
        ReleaseHighlight("agent-task-assistant", HighlightKind.NEW, R.string.highlights_agent,
            HighlightDestination(com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences.CATALOG_ID))
    )), ReleaseHighlightsBatch(12, listOf(
        ReleaseHighlight("comment-classic-style", HighlightKind.NEW, R.string.comment_classic_style_tip,
            HighlightDestination("comments.classic_style.enabled"))
    )), ReleaseHighlightsBatch(13, listOf(
        ReleaseHighlight("host-background", HighlightKind.NEW, R.string.host_background_tip,
            HighlightDestination("host.background.preset", alsoCovers = setOf(
                "host.background.blur", "host.background.saturation", "host.background.veil", "host.background.asset")))
    )), ReleaseHighlightsBatch(14, listOf(
        ReleaseHighlight("home-card-spacing", HighlightKind.NEW, R.string.host_home_card_spacing_tip,
            HighlightDestination("host.home_cards.horizontal_dp", alsoCovers = setOf("host.home_cards.vertical_dp")))
    )), ReleaseHighlightsBatch(15, listOf(
        ReleaseHighlight("home-ad-coverage", HighlightKind.FIXED, R.string.highlights_home_ad_coverage,
            HighlightDestination("home.recommend.ads.removed", homeFilterOption = true)),
        ReleaseHighlight("related-ad-payload", HighlightKind.FIXED, R.string.highlights_related_ad_payload,
            HighlightDestination("video.related.commercial.removed",
                alsoCovers = setOf("video.related.special.removed")))
    )), ReleaseHighlightsBatch(16, listOf(
        ReleaseHighlight("panel-viewport-and-rebound", HighlightKind.FIXED, R.string.highlights_panel_viewport_rebound,
            standaloneTitleRes = R.string.highlights_glow_engine_title)
    )), ReleaseHighlightsBatch(17, listOf(
        ReleaseHighlight("player-buffer-strategy", HighlightKind.NEW, R.string.player_buffer_tip,
            HighlightDestination("player.buffer.mode", alsoCovers = setOf(
                "player.buffer.seconds", "player.buffer.memory_mb", "player.buffer.disk_mb",
                "player.buffer.wifi_only")))
    ), automatic = false), ReleaseHighlightsBatch(18, listOf(
        ReleaseHighlight("home-comic-filter", HighlightKind.NEW, R.string.highlights_home_comic,
            HighlightDestination("home.recommend.comic.removed", homeFilterOption = true))
    )), ReleaseHighlightsBatch(19, listOf(
        ReleaseHighlight("comment-reply-button", HighlightKind.NEW, R.string.remove_comment_reply_button_tip,
            HighlightDestination("comments.reply_button.removed"))
    )), ReleaseHighlightsBatch(20, listOf(
        ReleaseHighlight("host-card-acrylic", HighlightKind.NEW, R.string.host_card_material_tip,
            HighlightDestination("host.cards.material"))
    )), ReleaseHighlightsBatch(21, listOf(
        ReleaseHighlight("host-card-material", HighlightKind.IMPROVED, R.string.host_card_material_mica_tip,
            HighlightDestination("host.cards.material"))
    )), ReleaseHighlightsBatch(22, listOf(
        ReleaseHighlight("recommend-publish-time", HighlightKind.NEW, R.string.highlights_recommend_publish_time,
            HighlightDestination(SettingsCatalog.ID_RECOMMEND_VIDEO_PUBLISH_TIME_RULE))
    )), ReleaseHighlightsBatch(23, listOf(
        ReleaseHighlight("player-frame-interpolation", HighlightKind.NEW, R.string.highlights_player_frame_interpolation,
            HighlightDestination("player.frame_interpolation.enabled"))
    )), ReleaseHighlightsBatch(24, listOf(
        ReleaseHighlight("player-frame-max-fps", HighlightKind.NEW, R.string.highlights_player_frame_max_fps,
            HighlightDestination("player.frame_interpolation.max_fps"))
    )), ReleaseHighlightsBatch(25, listOf(
        ReleaseHighlight("player-speed-menu", HighlightKind.NEW, R.string.highlights_player_speed_menu,
            HighlightDestination(SettingsCatalog.ID_PLAYER_SPEED_MENU))
    )), ReleaseHighlightsBatch(26, listOf(
        ReleaseHighlight("player-frame-status-toast", HighlightKind.NEW, R.string.highlights_player_frame_status_toast,
            HighlightDestination("player.frame_interpolation.status_toast"))
    )), ReleaseHighlightsBatch(27, listOf(
        ReleaseHighlight("player-subtitle-style", HighlightKind.NEW, R.string.player_subtitle_style_tip,
            HighlightDestination("player.subtitle.style.enabled")),
        ReleaseHighlight("player-ai-subtitle", HighlightKind.NEW, R.string.player_ai_subtitle_tip,
            HighlightDestination("player.subtitle.ai.enabled"))
    )), ReleaseHighlightsBatch(28, listOf(
        ReleaseHighlight("live-home-promotion", HighlightKind.NEW, R.string.highlights_live_home_promotion,
            HighlightDestination("home.live.promotion.hidden"))
    )), ReleaseHighlightsBatch(29, listOf(
        ReleaseHighlight("player-frame-thermal-control", HighlightKind.NEW, R.string.highlights_player_frame_thermal_control,
            HighlightDestination("player.frame_interpolation.disable_thermal_control"))
    )), ReleaseHighlightsBatch(30, listOf(
        ReleaseHighlight("host-mine-garb-dim", HighlightKind.NEW, R.string.host_mine_garb_dim_tip,
            HighlightDestination("host.mine.garb_dim.enabled"))
    )), ReleaseHighlightsBatch(31, listOf(
        ReleaseHighlight("home-frame-legacy-lock", HighlightKind.NEW, R.string.home_frame_legacy_lock_tip,
            HighlightDestination("home.frame.legacy_lock.enabled"))
    )))
    val currentRevision: Int get() = batches.maxOf { it.revision }
    val destinations get() = batches.sortedByDescending { it.revision }.flatMap { it.entries }
        .mapNotNull { it.destination }.distinctBy { it.settingId }

    fun entriesAfter(revision: Int, automatic: Boolean = false): List<ReleaseHighlight> =
        ReleaseHighlightsPolicy.entriesAfter(batches,revision,automatic)

    fun currentEntries(): List<ReleaseHighlight> = entriesAfter(currentRevision - 1)
}
