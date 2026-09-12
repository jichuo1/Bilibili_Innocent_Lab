package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.R

/** Build-time capability directory. Setting IDs are metadata, never setting values. */
internal data class DiagnosticCapabilityDefinition(
    val id: String,
    val parentId: String,
    val labelRes: Int,
    val settingIds: Set<String>,
    val locatorKey: String? = null,
    val introducedCatalogVersion: Int = 1
)

internal object DiagnosticCapabilityCatalog {
    /**
     * 目录版本。
     *
     * **每次新增条目都要把新条目的 `introducedCatalogVersion` 标成这个值**——
     * 客户端是按 "比我已知的版本更新" 做增量的，
     * VERSION 涨了却没有任何条目标在新版本上，增量就是空集（有测试钉住）。
     */
    const val VERSION = 8
    val definitions = listOf(
        DiagnosticCapabilityDefinition("search_home_recommend_hidden", "search_home_recommend_hidden", R.string.hide_search_home_recommend, setOf("search.home_recommend.hidden"), introducedCatalogVersion = 4),
        DiagnosticCapabilityDefinition("player_interactive_legacy_follow", "player_interactive_overlay", R.string.diag_cap_player_interactive_legacy_follow, setOf("player.interactive_overlays.hidden"), "legacy/guide/clearAttention"),
        DiagnosticCapabilityDefinition("player_interactive_legacy_commands", "player_interactive_overlay", R.string.diag_cap_player_interactive_legacy_commands, setOf("player.interactive_overlays.hidden"), "legacy/guide/clearCommandDms"),
        DiagnosticCapabilityDefinition("player_interactive_legacy_contract", "player_interactive_overlay", R.string.diag_cap_player_interactive_legacy_contract, setOf("player.interactive_overlays.hidden"), "legacy/guide/clearContractCard"),
        DiagnosticCapabilityDefinition("player_interactive_legacy_operations", "player_interactive_overlay", R.string.diag_cap_player_interactive_legacy_operations, setOf("player.interactive_overlays.hidden"), "legacy/guide/clearOperationCard"),
        DiagnosticCapabilityDefinition("player_interactive_legacy_operations_new", "player_interactive_overlay", R.string.diag_cap_player_interactive_legacy_operations_new, setOf("player.interactive_overlays.hidden"), "legacy/guide/clearOperationCardNew"),
        DiagnosticCapabilityDefinition("player_interactive_legacy_secondary_cards", "player_interactive_overlay", R.string.diag_cap_player_interactive_legacy_secondary_cards, setOf("player.interactive_overlays.hidden"), "legacy/guide/clearCardsSecond"),
        DiagnosticCapabilityDefinition("player_interactive_unified_contract", "player_interactive_overlay", R.string.diag_cap_player_interactive_unified_contract, setOf("player.interactive_overlays.hidden"), "unified/guide/clearContractCard"),
        DiagnosticCapabilityDefinition("player_interactive_unified_material", "player_interactive_overlay", R.string.diag_cap_player_interactive_unified_material, setOf("player.interactive_overlays.hidden"), "unified/guide/clearMaterial"),
        DiagnosticCapabilityDefinition("player_interactive_unified_right_material", "player_interactive_overlay", R.string.diag_cap_player_interactive_unified_right_material, setOf("player.interactive_overlays.hidden"), "unified/guide/clearRightMaterial"),
        DiagnosticCapabilityDefinition("player_interactive_resource_follow", "player_interactive_overlay", R.string.diag_cap_player_interactive_resource_follow, setOf("player.interactive_overlays.hidden"), "unified/dm/clearAttention"),
        DiagnosticCapabilityDefinition("player_interactive_resource_cards", "player_interactive_overlay", R.string.diag_cap_player_interactive_resource_cards, setOf("player.interactive_overlays.hidden"), "unified/dm/clearCards"),
        DiagnosticCapabilityDefinition("player_interactive_resource_commands", "player_interactive_overlay", R.string.diag_cap_player_interactive_resource_commands, setOf("player.interactive_overlays.hidden"), "unified/dm/clearCommandDms"),
        DiagnosticCapabilityDefinition("player_interactive_dm_commands", "player_interactive_overlay", R.string.diag_cap_player_interactive_dm_commands, setOf("player.interactive_overlays.hidden"), "command"),
        DiagnosticCapabilityDefinition("player_interactive_activity_banner", "player_interactive_overlay", R.string.diag_cap_player_interactive_activity_banner, setOf("player.interactive_overlays.hidden"), "activity"),
        DiagnosticCapabilityDefinition("paused_ad", "paused_ad", R.string.paused_page_ad_enable, setOf("ads.pause.hidden")),
        DiagnosticCapabilityDefinition("game_mentioned_promotion", "game_mentioned_promotion", R.string.gamecard_ad_enable, setOf("ads.game_card.hidden")),
        DiagnosticCapabilityDefinition("detail_promotion_nest", "detail_app_promotion", R.string.diag_cap_detail_promotion_nest, setOf("ads.video_detail.app_promotion.hidden")),
        DiagnosticCapabilityDefinition("detail_promotion_list", "detail_app_promotion", R.string.diag_cap_detail_promotion_list, setOf("ads.video_detail.app_promotion.hidden")),
        DiagnosticCapabilityDefinition("detail_promotion_hd", "detail_app_promotion", R.string.diag_cap_detail_promotion_hd, setOf("ads.video_detail.app_promotion.hidden")),
        DiagnosticCapabilityDefinition("detail_promotion_related_ad", "detail_app_promotion", R.string.diag_cap_detail_promotion_related_ad, setOf("ads.video_detail.app_promotion.hidden", "video.related.matching_enhancement.enabled", "video.related.strong_mode.enabled")),
        DiagnosticCapabilityDefinition("detail_promotion_related_game", "detail_app_promotion", R.string.diag_cap_detail_promotion_related_game, setOf("ads.video_detail.app_promotion.hidden", "video.related.matching_enhancement.enabled", "video.related.strong_mode.enabled")),
        DiagnosticCapabilityDefinition("home_banner_view", "home_banner", R.string.diag_cap_home_banner_view, setOf("ads.home_banner.hidden")),
        DiagnosticCapabilityDefinition("home_banner_feed", "home_banner", R.string.diag_cap_home_banner_feed, setOf("ads.home_banner.hidden")),
        DiagnosticCapabilityDefinition("merchandise", "merchandise", R.string.merch_ad_enable, setOf("ads.merchandise.hidden")),
        DiagnosticCapabilityDefinition("home_top_bar_game_menu_hidden", "home_top_bar_purify", R.string.hide_home_game_menu, setOf("home.top_bar.game_menu.hidden")),
        DiagnosticCapabilityDefinition("home_top_bar_search_word_hidden", "home_top_bar_purify", R.string.hide_home_search_default_word, setOf("home.top_bar.search_word.hidden")),
        DiagnosticCapabilityDefinition("home_vertical_detail", "home_vertical_detail", R.string.home_vertical_open_detail, setOf("home.vertical.open_detail")),
        DiagnosticCapabilityDefinition("home_recommend_ads_removed", "home_recommend_purify", R.string.remove_home_recommend_ads, setOf("home.recommend.ads.removed")),
        DiagnosticCapabilityDefinition("home_recommend_cm_v2_removed", "home_recommend_purify", R.string.remove_home_recommend_cm_v2, setOf("home.recommend.cm_v2.removed")),
        DiagnosticCapabilityDefinition("home_recommend_pictures_removed", "home_recommend_purify", R.string.remove_home_recommend_pictures, setOf("home.recommend.pictures.removed")),
        DiagnosticCapabilityDefinition("home_recommend_game_promotions_removed", "home_recommend_purify", R.string.remove_home_recommend_game_promotions, setOf("home.recommend.game_promotions.removed")),
        DiagnosticCapabilityDefinition("home_recommend_title_filter_enabled", "home_recommend_purify", R.string.home_recommend_title_filter, setOf("home.recommend.title_filter.enabled", "home.recommend.title_filter.keywords")),
        DiagnosticCapabilityDefinition("home_recommend_live_removed", "home_recommend_purify", R.string.remove_home_recommend_live, setOf("home.recommend.live.removed")),
        DiagnosticCapabilityDefinition("home_recommend_pgc_removed", "home_recommend_purify", R.string.remove_home_recommend_pgc, setOf("home.recommend.pgc.removed"), introducedCatalogVersion = 2),
        DiagnosticCapabilityDefinition("home_recommend_special_cards_removed", "home_recommend_purify", R.string.remove_home_recommend_special_cards, setOf("home.recommend.special_cards.removed"), introducedCatalogVersion = 2),
        DiagnosticCapabilityDefinition("home_recommend_courses_removed", "home_recommend_purify", R.string.remove_home_recommend_courses, setOf("home.recommend.courses.removed")),
        DiagnosticCapabilityDefinition("home_recommend_vertical_removed", "home_recommend_purify", R.string.remove_home_recommend_vertical, setOf("home.recommend.vertical.removed")),
        DiagnosticCapabilityDefinition("home_recommend_large_removed", "home_recommend_purify", R.string.remove_home_recommend_large, setOf("home.recommend.large.removed")),
        DiagnosticCapabilityDefinition("home_tab_filter", "home_tab_filter", R.string.custom_home_tab_hide, setOf("home.tabs.hidden_rules", "home.tabs.hidden_selectors")),
        DiagnosticCapabilityDefinition("home_component_filter", "home_component_filter", R.string.custom_home_component_hide, setOf("home.components.hidden_rules", "home.components.hidden_selectors")),
        DiagnosticCapabilityDefinition("mine_vip_hidden", "mine_vip_purify", R.string.hide_mine_vip, setOf("mine.vip.hidden")),
        DiagnosticCapabilityDefinition("mine_vip_space_kept", "mine_vip_purify", R.string.keep_mine_vip_space, setOf("mine.vip.space_kept")),
        DiagnosticCapabilityDefinition("mine_component_filter", "mine_component_filter", R.string.custom_mine_component_hide, setOf("mine.components.hidden_rules", "mine.components.hidden_ids", "mine.components.hidden_selectors")),
        DiagnosticCapabilityDefinition("block_app_update", "block_app_update", R.string.block_app_update, setOf("client.update_prompt.blocked")),
        DiagnosticCapabilityDefinition("dynamic_city_tab_hidden", "dynamic_tabs_purify", R.string.hide_dynamic_city_tab, setOf("dynamic.city_tab.hidden")),
        DiagnosticCapabilityDefinition("dynamic_school_tab_hidden", "dynamic_tabs_purify", R.string.hide_dynamic_school_tab, setOf("dynamic.school_tab.hidden")),
        DiagnosticCapabilityDefinition("dynamic_video_tab_preferred", "dynamic_tabs_purify", R.string.prefer_dynamic_video_tab, setOf("dynamic.video_tab.preferred")),
        DiagnosticCapabilityDefinition("dynamic_keyword_filter_enabled", "dynamic_purify", R.string.dynamic_keyword_filter, setOf("dynamic.keyword_filter.enabled", "dynamic.keyword_filter.keywords")),
        DiagnosticCapabilityDefinition("dynamic_author_filter_enabled", "dynamic_purify", R.string.dynamic_author_filter, setOf("dynamic.author_filter.enabled", "dynamic.author_filter.rules")),
        DiagnosticCapabilityDefinition("dynamic_promotions_removed", "dynamic_purify", R.string.remove_dynamic_promotions, setOf("dynamic.promotions.removed")),
        DiagnosticCapabilityDefinition("dynamic_charge_only_removed", "dynamic_purify", R.string.remove_dynamic_charge_only, setOf("dynamic.charge_only.removed")),
        DiagnosticCapabilityDefinition("dynamic_frequent_visits_hidden", "dynamic_purify", R.string.hide_dynamic_frequent_visits, setOf("dynamic.frequent_visits.hidden"), introducedCatalogVersion = 3),
        DiagnosticCapabilityDefinition("dynamic_topic_list_hidden", "dynamic_purify", R.string.hide_dynamic_topic_list, setOf("dynamic.topic_list.hidden")),
        DiagnosticCapabilityDefinition("dynamic_up_list_live_removed", "dynamic_purify", R.string.remove_dynamic_live_up_entries, setOf("dynamic.up_list.live.removed")),
        DiagnosticCapabilityDefinition("search_commercial_removed", "search_purify", R.string.remove_search_commercial, setOf("search.commercial.removed")),
        DiagnosticCapabilityDefinition("search_keyword_filter_enabled", "search_purify", R.string.search_keyword_filter, setOf("search.keyword_filter.enabled", "search.keyword_filter.keywords")),
        DiagnosticCapabilityDefinition("search_author_filter_enabled", "search_purify", R.string.search_author_filter, setOf("search.author_filter.enabled", "search.author_filter.rules")),
        DiagnosticCapabilityDefinition("full_number_display", "full_number_display", R.string.show_full_numbers, setOf("numbers.full.enabled")),
        DiagnosticCapabilityDefinition("player_portrait_control", "player_portrait_control", R.string.hide_player_portrait_control, setOf("player.portrait_control.hidden")),
        DiagnosticCapabilityDefinition("pgc_auto_activity_popup", "pgc_auto_activity_popup", R.string.hide_pgc_auto_activity_popup, setOf("pgc.auto_activity_popup.hidden")),
        DiagnosticCapabilityDefinition("player_status_bar", "player_status_bar", R.string.transparent_player_status_bar, setOf("player.status_bar.transparent")),
        DiagnosticCapabilityDefinition("player_danmaku_weight_filter_enabled", "danmaku_purify", R.string.danmaku_weight_filter, setOf("player.danmaku.weight_filter.enabled", "player.danmaku.weight_filter.minimum")),
        DiagnosticCapabilityDefinition("player_danmaku_vip_colorful_removed", "danmaku_purify", R.string.remove_vip_colorful_danmaku, setOf("player.danmaku.vip_colorful.removed")),
        DiagnosticCapabilityDefinition("live_room_switch_blocked", "live_room_widgets", R.string.block_live_room_switch, setOf("live.room_switch.blocked")),
        DiagnosticCapabilityDefinition("live_double_tap_pause", "live_room_widgets", R.string.live_room_double_tap_pause, setOf("live.double_tap.pause")),
        DiagnosticCapabilityDefinition("video_related_commercial_removed", "video_relate_filter", R.string.remove_relate_commercial, setOf("video.related.commercial.removed")),
        DiagnosticCapabilityDefinition("video_related_game_removed", "video_relate_filter", R.string.remove_relate_game, setOf("video.related.game.removed")),
        DiagnosticCapabilityDefinition("video_related_live_removed", "video_relate_filter", R.string.remove_relate_live, setOf("video.related.live.removed")),
        DiagnosticCapabilityDefinition("video_related_course_removed", "video_relate_filter", R.string.remove_relate_course, setOf("video.related.course.removed")),
        DiagnosticCapabilityDefinition("video_related_special_removed", "video_relate_filter", R.string.remove_relate_special, setOf("video.related.special.removed")),
        DiagnosticCapabilityDefinition("video_related_matching_enhancement_enabled", "video_relate_filter", R.string.video_relate_matching_enhancement, setOf("video.related.matching_enhancement.enabled")),
        DiagnosticCapabilityDefinition("video_related_strong_mode_enabled", "video_relate_filter", R.string.video_relate_strong_mode, setOf("video.related.strong_mode.enabled")),
        DiagnosticCapabilityDefinition("video_related_reason_filter_enabled", "video_relate_filter", R.string.video_relate_reason_filter, setOf("video.related.reason_filter.enabled", "video.related.reason_filter.keywords")),
        DiagnosticCapabilityDefinition("story_ads_removed", "story_purify", R.string.remove_story_ads, setOf("story.ads.removed")),
        DiagnosticCapabilityDefinition("story_live_removed", "story_purify", R.string.remove_story_live, setOf("story.live.removed")),
        DiagnosticCapabilityDefinition("story_games_removed", "story_purify", R.string.remove_story_games, setOf("story.games.removed")),
        DiagnosticCapabilityDefinition("story_bangumi_removed", "story_purify", R.string.remove_story_bangumi, setOf("story.bangumi.removed")),
        DiagnosticCapabilityDefinition("story_courses_removed", "story_purify", R.string.remove_story_courses, setOf("story.courses.removed")),
        DiagnosticCapabilityDefinition("story_short_drama_removed", "story_purify", R.string.remove_story_short_drama, setOf("story.short_drama.removed")),
        DiagnosticCapabilityDefinition("story_shopping_removed", "story_purify", R.string.remove_story_shopping, setOf("story.shopping.removed")),
        DiagnosticCapabilityDefinition("story_movies_removed", "story_purify", R.string.remove_story_movies, setOf("story.movies.removed")),
        DiagnosticCapabilityDefinition("story_documentaries_removed", "story_purify", R.string.remove_story_documentaries, setOf("story.documentaries.removed")),
        DiagnosticCapabilityDefinition("story_tv_removed", "story_purify", R.string.remove_story_tv, setOf("story.tv.removed")),
        DiagnosticCapabilityDefinition("story_variety_removed", "story_purify", R.string.remove_story_variety, setOf("story.variety.removed")),
        DiagnosticCapabilityDefinition("story_music_removed", "story_purify", R.string.remove_story_music, setOf("story.music.removed")),
        DiagnosticCapabilityDefinition("bottom_bar", "bottom_bar", R.string.custom_bottom_bar_hide, setOf("navigation.bottom_bar.hidden_rules", "navigation.bottom_bar.hidden_selectors")),
        DiagnosticCapabilityDefinition("player_default_quality", "player_default_quality", R.string.player_default_quality, setOf("player.default_quality.qn")),
        DiagnosticCapabilityDefinition("teenagers_mode_prompt", "teenagers_mode_prompt", R.string.block_teenagers_mode_prompt, setOf("prompt.teenagers_mode.blocked")),
        DiagnosticCapabilityDefinition("player_capability_background", "player_capabilities", R.string.player_unlock_background, setOf("player.capability.background")),
        DiagnosticCapabilityDefinition("player_capability_small_window", "player_capabilities", R.string.player_unlock_small_window, setOf("player.capability.small_window")),
        DiagnosticCapabilityDefinition("player_capability_cast", "player_capabilities", R.string.player_unlock_cast, setOf("player.capability.cast")),
        DiagnosticCapabilityDefinition("player_long_press_disabled", "player_speed", R.string.player_disable_long_press, setOf("player.long_press.disabled")),
        DiagnosticCapabilityDefinition("player_long_press_speed_percent", "player_speed", R.string.player_long_press_speed, setOf("player.long_press_speed.percent")),
        DiagnosticCapabilityDefinition("player_default_speed_percent", "player_speed", R.string.player_default_speed, setOf("player.default_speed.percent")),
        DiagnosticCapabilityDefinition("comments_search_links_removed", "comment_purify", R.string.remove_comment_search_links, setOf("comments.search_links.removed")),
        DiagnosticCapabilityDefinition("comments_empty_guide_removed", "comment_purify", R.string.remove_comment_empty_guide, setOf("comments.empty_guide.removed")),
        DiagnosticCapabilityDefinition("comments_vote_widgets_removed", "comment_purify", R.string.remove_comment_vote_widgets, setOf("comments.vote_widgets.removed")),
        DiagnosticCapabilityDefinition("comments_follow_buttons_removed", "comment_purify", R.string.remove_comment_follow_buttons, setOf("comments.follow_buttons.removed")),
        DiagnosticCapabilityDefinition("comments_qoe_removed", "comment_purify", R.string.remove_comment_qoe, setOf("comments.qoe.removed")),
        DiagnosticCapabilityDefinition("comments_operations_removed", "comment_purify", R.string.remove_comment_operations, setOf("comments.operations.removed")),
        DiagnosticCapabilityDefinition("comments_quick_reply_blocked", "comment_purify", R.string.block_comment_quick_reply, setOf("comments.quick_reply.blocked")),
        DiagnosticCapabilityDefinition("comment_section", "comment_section", R.string.hide_comment_section, setOf("comments.section.hidden")),
        DiagnosticCapabilityDefinition("comment_topology", "comment_topology", R.string.reply_topology_enabled, setOf("comments.reply_topology.enabled")),
        DiagnosticCapabilityDefinition("comments_keyword_filter_enabled", "comment_filter", R.string.comment_keyword_filter, setOf("comments.keyword_filter.enabled", "comments.keyword_filter.keywords")),
        DiagnosticCapabilityDefinition("comments_minimum_level_filter_enabled", "comment_filter", R.string.comment_min_level_filter, setOf("comments.minimum_level_filter.enabled", "comments.minimum_level_filter.level")),
        DiagnosticCapabilityDefinition("comments_at_only_removed", "comment_filter", R.string.remove_at_only_comments, setOf("comments.at_only.removed")),
        DiagnosticCapabilityDefinition("comments_user_filter_enabled", "comment_filter", R.string.comment_user_filter, setOf("comments.user_filter.enabled", "comments.user_filter.rules")),
        DiagnosticCapabilityDefinition("splash_ad_purify", "splash_ad_purify", R.string.purify_splash_ads, setOf("splash.ads.purified")),
        DiagnosticCapabilityDefinition("splash_auto_night", "splash_auto_night", R.string.splash_auto_night, setOf("splash.auto_night.enabled")),
        DiagnosticCapabilityDefinition("share_content_purified", "share_purify", R.string.purify_share_content, setOf("share.content.purified")),
        DiagnosticCapabilityDefinition("share_mini_program_direct_link", "share_purify", R.string.share_mini_program_direct_link, setOf("share.mini_program.direct_link")),
        DiagnosticCapabilityDefinition("external_browser", "external_browser", R.string.force_external_browser, setOf("links.external_browser.enabled")),
        DiagnosticCapabilityDefinition("system_media_notification", "system_media_notification", R.string.system_media_notification, setOf("system.media_notification.enabled")),
        DiagnosticCapabilityDefinition("bv_to_av", "bv_to_av", R.string.show_bv_as_av, setOf("numbers.bv_as_av.enabled")),
        DiagnosticCapabilityDefinition("free_copy_comment_enabled", "free_copy", R.string.free_copy_enable, setOf("free_copy.comment.enabled")),
        DiagnosticCapabilityDefinition("free_copy_description_enabled", "free_copy", R.string.free_copy_desc_enable, setOf("free_copy.description.enabled")),
        DiagnosticCapabilityDefinition("roaming_compat", "roaming_compat", R.string.roaming_compat_enable, setOf("compat.roaming.enabled")),
        DiagnosticCapabilityDefinition("home_recommend_duration_filter", "home_recommend_purify", R.string.diagnostics_capability_duration, setOf("recommend.video_duration.minimum_seconds", "recommend.video_duration.maximum_seconds")),
        DiagnosticCapabilityDefinition("video_related_duration_filter", "video_relate_filter", R.string.diagnostics_capability_duration, setOf("recommend.video_duration.minimum_seconds", "recommend.video_duration.maximum_seconds")),
        DiagnosticCapabilityDefinition("detail_honor_removed", "detail_module_purify", R.string.remove_detail_honor, setOf("purify.detail.honor.removed"), introducedCatalogVersion = 5),
        DiagnosticCapabilityDefinition("detail_live_order_removed", "detail_module_purify", R.string.remove_detail_live_order, setOf("purify.detail.live_order.removed"), introducedCatalogVersion = 5),
        DiagnosticCapabilityDefinition("detail_ugc_season_removed", "detail_module_purify", R.string.remove_detail_ugc_season, setOf("purify.detail.ugc_season.removed"), introducedCatalogVersion = 5),
        DiagnosticCapabilityDefinition("detail_up_vip_label_removed", "detail_module_purify", R.string.remove_detail_up_vip_label, setOf("purify.detail.up_vip_label.removed"), introducedCatalogVersion = 5),
        DiagnosticCapabilityDefinition("detail_topic_tags_removed", "detail_module_purify", R.string.remove_detail_topic_tags, setOf("purify.detail.topic_tags.removed"), introducedCatalogVersion = 5),
        DiagnosticCapabilityDefinition("detail_staff_follow_hidden", "detail_view_purify", R.string.remove_detail_staff_follow, setOf("purify.detail.staff_follow.hidden"), introducedCatalogVersion = 5),
        DiagnosticCapabilityDefinition("detail_hot_banner_hidden", "detail_view_purify", R.string.remove_detail_hot_banner, setOf("purify.detail.hot_banner.hidden"), introducedCatalogVersion = 5),
        DiagnosticCapabilityDefinition("detail_hot_badge_hidden", "detail_united_presentation_purify", R.string.remove_detail_hot_banner, setOf("purify.detail.hot_banner.hidden"), introducedCatalogVersion = 6),
        DiagnosticCapabilityDefinition("detail_united_special_topic_hidden", "detail_united_presentation_purify", R.string.remove_detail_topic_tags, setOf("purify.detail.topic_tags.removed"), introducedCatalogVersion = 6),
        // v7：详情页那五项的正确落点。与上面同名的 detail_* / detail_united_special_topic_*
        // 互为保底，capability id 刻意分开，诊断里才看得出是哪一层真的生效了。
        DiagnosticCapabilityDefinition("detail_united_honor_removed", "detail_united_module_purify", R.string.remove_detail_honor, setOf("purify.detail.honor.removed"), introducedCatalogVersion = 7),
        DiagnosticCapabilityDefinition("detail_united_live_order_removed", "detail_united_module_purify", R.string.remove_detail_live_order, setOf("purify.detail.live_order.removed"), introducedCatalogVersion = 7),
        DiagnosticCapabilityDefinition("detail_united_ugc_season_removed", "detail_united_module_purify", R.string.remove_detail_ugc_season, setOf("purify.detail.ugc_season.removed"), introducedCatalogVersion = 7),
        DiagnosticCapabilityDefinition("detail_united_topic_tags_removed", "detail_united_module_purify", R.string.remove_detail_topic_tags, setOf("purify.detail.topic_tags.removed"), introducedCatalogVersion = 7),
        DiagnosticCapabilityDefinition("detail_united_up_vip_label_removed", "detail_united_module_purify", R.string.remove_detail_up_vip_label, setOf("purify.detail.up_vip_label.removed"), introducedCatalogVersion = 7),
        DiagnosticCapabilityDefinition("detail_united_hot_banner_removed", "detail_united_module_purify", R.string.remove_detail_hot_banner, setOf("purify.detail.hot_banner.hidden"), introducedCatalogVersion = 7),
        // 视频提及的协议层总闸；与 game_mentioned_promotion 那 10+ 条渲染路由互为保底，
        // 共用同一个用户开关，capability id 分开以便看出是哪一层生效。
        DiagnosticCapabilityDefinition("detail_united_video_mentions_removed", "detail_united_module_purify", R.string.gamecard_ad_enable, setOf("ads.game_card.hidden"), introducedCatalogVersion = 7),
        // 好物商品卡的协议层总闸；与 merchandise 那条渲染层互为保底，共用同一个开关。
        DiagnosticCapabilityDefinition("detail_united_merchandise_removed", "detail_united_module_purify", R.string.merch_ad_enable, setOf("ads.merchandise.hidden"), introducedCatalogVersion = 7),
        // 按分区过滤推荐：名单非空即启用，没有独立开关，所以只挂名单那一个设置。
        DiagnosticCapabilityDefinition("home_recommend_tid_block", "home_recommend_purify", R.string.home_recommend_blocked_tids, setOf("home.recommend.blocked_tids"), introducedCatalogVersion = 8),
        // 详情页没有 tid，同一需求退到作者与标签两档。
        DiagnosticCapabilityDefinition("video_related_author_block", "video_relate_filter", R.string.video_relate_blocked_authors, setOf("video.related.blocked_authors"), introducedCatalogVersion = 8),
        DiagnosticCapabilityDefinition("video_related_tag_block", "video_relate_filter", R.string.video_relate_blocked_tags, setOf("video.related.blocked_tags"), introducedCatalogVersion = 8)
    )
    val localOnlySettings = mapOf(
        "free_copy.light_mode.enabled" to "LOCAL_APPEARANCE",
        "free_copy.auto_light.enabled" to "LOCAL_APPEARANCE",
        "module_ui.predictive_back.enabled" to "MODULE_UI",
        "module_ui.material_color_spec" to "MODULE_UI",
        // 只影响模块界面自己的弹窗动画，不进宿主，没有可诊断的宿主能力。
        "module_ui.appearance.panel_window_blur" to "MODULE_UI",
        "diagnostics.logging.enabled" to "LOCAL_DIAGNOSTICS",
        "diagnostics.logging.level" to "LOCAL_DIAGNOSTICS"
    )
    val byId = definitions.associateBy { it.id }
    val byLocatorKey = definitions.filter { it.locatorKey != null }.associateBy { it.locatorKey!! }
    val splitParents = definitions.filter { it.id != it.parentId }.mapTo(linkedSetOf()) { it.parentId }
    val leafIds = byId.keys
    private val verifiedRuntimeIds = setOf(
        "search_home_recommend_hidden",
        "dynamic_frequent_visits_hidden",
        "home_recommend_pgc_removed",
        "home_recommend_special_cards_removed",
        "home_banner_view",
        "home_banner_feed",
        "detail_promotion_nest",
        "detail_promotion_list",
        "detail_promotion_hd",
        "detail_promotion_related_ad",
        "detail_promotion_related_game",
        "comments_search_links_removed",
        "comments_empty_guide_removed",
        "comments_vote_widgets_removed",
        "comments_follow_buttons_removed",
        "comments_qoe_removed",
        "comments_operations_removed",
        "comments_quick_reply_blocked",
        "share_content_purified",
        "share_mini_program_direct_link",
        "live_room_switch_blocked",
        "live_double_tap_pause",
        "player_long_press_disabled",
        "player_long_press_speed_percent",
        "player_default_speed_percent",
        "player_capability_background",
        "player_capability_small_window",
        "player_capability_cast",
        "free_copy_comment_enabled",
        "free_copy_description_enabled"
    )
    fun explicitRuntimeSupport(id: String): Int = when {
        id in verifiedRuntimeIds -> 2
        id.startsWith("player_interactive_") -> 1
        else -> 0
    }
    fun runtimeSupport(id: String): Int {
        val explicit = explicitRuntimeSupport(id)
        return if (explicit > 0) explicit
        else if (DiagnosticFeatureRegistry.descriptorOrNull(id)?.runtimeEvidenceExpected == true) 2 else 0
    }
    fun childrenOf(parentId: String): List<DiagnosticCapabilityDefinition> =
        definitions.filter { it.parentId == parentId && it.id != parentId }
}
