package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig

/**
 * 可备份用户意图的唯一白名单。
 *
 * 这里故意不包含缓存、时间戳、自由复制修订号、更新检查节流、应用语言和桌面图标状态。
 * 新增设置必须显式登记；禁止改为导出 prefs.all。
 */
internal object SettingsCatalog {
    const val PRODUCT_ID = "bilibili-innocent-lab.settings"
    const val SCOPE_ID = "core-user-settings"
    const val CATALOG_VERSION = 3
    const val ID_FREE_COPY_COMMENT = "free_copy.comment.enabled"
    const val ID_FREE_COPY_DESCRIPTION = "free_copy.description.enabled"
    const val ID_RECOMMEND_VIDEO_MIN_DURATION =
        "recommend.video_duration.minimum_seconds"
    const val ID_RECOMMEND_VIDEO_MAX_DURATION =
        "recommend.video_duration.maximum_seconds"

    private fun bool(
        id: String,
        storageKey: String,
        labelRes: Int,
        default: Boolean = false,
        restorePolicy: RestorePolicy = RestorePolicy.AUTOMATIC,
        introducedCatalogVersion: Int = 1,
        effects: Set<ImportEffect> = setOf(
            ImportEffect.RECREATE_MODULE_UI,
            ImportEffect.RESTART_BILIBILI
        )
    ) = SettingSpec(
        id = id,
        storageKey = storageKey,
        labelRes = labelRes,
        type = SettingValueType.BOOLEAN,
        defaultValue = SettingValue.Bool(default),
        restorePolicy = restorePolicy,
        introducedCatalogVersion = introducedCatalogVersion,
        effects = effects
    )

    private fun text(
        id: String,
        storageKey: String,
        labelRes: Int,
        default: String = "",
        allowed: Set<String>? = null,
        introducedCatalogVersion: Int = 1,
        effects: Set<ImportEffect> = setOf(
            ImportEffect.RECREATE_MODULE_UI,
            ImportEffect.RESTART_BILIBILI
        )
    ) = SettingSpec(
        id = id,
        storageKey = storageKey,
        labelRes = labelRes,
        type = SettingValueType.STRING,
        defaultValue = SettingValue.Text(default),
        allowedStrings = allowed,
        introducedCatalogVersion = introducedCatalogVersion,
        effects = effects
    )

    private fun integer(
        id: String,
        storageKey: String,
        labelRes: Int,
        default: Int,
        allowed: Set<Int>? = null,
        range: IntRange? = null,
        introducedCatalogVersion: Int = 1,
        effects: Set<ImportEffect> = setOf(
            ImportEffect.RECREATE_MODULE_UI,
            ImportEffect.RESTART_BILIBILI
        )
    ) = SettingSpec(
        id = id,
        storageKey = storageKey,
        labelRes = labelRes,
        type = SettingValueType.INTEGER,
        defaultValue = SettingValue.IntValue(default),
        allowedIntegers = allowed,
        integerRange = range,
        introducedCatalogVersion = introducedCatalogVersion,
        effects = effects
    )

    val specs: List<SettingSpec> = listOf(
        bool("ads.pause.hidden", HookEntry.PREF_ENABLED, R.string.paused_page_ad_enable, default = true),
        bool("ads.game_card.hidden", HookEntry.PREF_GAMECARD_ENABLED, R.string.gamecard_ad_enable, default = true),
        bool(
            "ads.video_detail.app_promotion.hidden",
            FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION,
            R.string.hide_video_detail_app_promotion,
            introducedCatalogVersion = 2
        ),
        bool("ads.home_banner.hidden", HookEntry.PREF_BANNER_ENABLED, R.string.banner_ad_enable, default = true),
        bool("ads.merchandise.hidden", HookEntry.PREF_MERCH_ENABLED, R.string.merch_ad_enable, default = true),

        bool("home.top_bar.game_menu.hidden", FeaturePreferences.HIDE_HOME_GAME_MENU, R.string.hide_home_game_menu),
        bool("home.top_bar.search_word.hidden", FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, R.string.hide_home_search_default_word),
        bool("home.vertical.open_detail", FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL, R.string.home_vertical_open_detail),
        bool("home.recommend.ads.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS, R.string.remove_home_recommend_ads),
        bool("home.recommend.pictures.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES, R.string.remove_home_recommend_pictures),
        bool("home.recommend.game_promotions.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS, R.string.remove_home_recommend_game_promotions),
        bool("home.recommend.title_filter.enabled", FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED, R.string.home_recommend_title_filter),
        text("home.recommend.title_filter.keywords", FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS, R.string.home_recommend_title_rules),
        bool("home.recommend.live.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE, R.string.remove_home_recommend_live),
        bool("home.recommend.courses.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES, R.string.remove_home_recommend_courses),
        bool("home.recommend.vertical.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL, R.string.remove_home_recommend_vertical),
        bool("home.recommend.large.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE, R.string.remove_home_recommend_large),
        text("home.tabs.hidden_rules", FeaturePreferences.HOME_TAB_HIDDEN_RULES, R.string.custom_home_tab_hide),
        text("home.components.hidden_rules", FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES, R.string.custom_home_component_hide),

        bool("mine.vip.hidden", FeaturePreferences.HIDE_MINE_VIP, R.string.hide_mine_vip),
        bool("mine.vip.space_kept", FeaturePreferences.KEEP_MINE_VIP_SPACE, R.string.keep_mine_vip_space),
        text("mine.components.hidden_rules", FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES, R.string.custom_mine_component_hide),
        text("mine.components.hidden_ids", FeaturePreferences.MINE_COMPONENT_HIDDEN_IDS, R.string.custom_mine_component_hide),
        text(
            "mine.components.hidden_selectors",
            FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS,
            R.string.custom_mine_component_hide,
            introducedCatalogVersion = 3
        ),
        bool("client.update_prompt.blocked", FeaturePreferences.BLOCK_APP_UPDATE, R.string.block_app_update),
        bool("dynamic.city_tab.hidden", FeaturePreferences.HIDE_DYNAMIC_CITY_TAB, R.string.hide_dynamic_city_tab),
        bool("dynamic.school_tab.hidden", FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB, R.string.hide_dynamic_school_tab),
        bool("dynamic.video_tab.preferred", FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB, R.string.prefer_dynamic_video_tab),
        bool("numbers.full.enabled", FeaturePreferences.SHOW_FULL_NUMBERS, R.string.show_full_numbers),
        bool("player.portrait_control.hidden", FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL, R.string.hide_player_portrait_control),
        bool("player.status_bar.transparent", FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR, R.string.transparent_player_status_bar),

        bool("video.related.commercial.removed", FeaturePreferences.REMOVE_RELATE_COMMERCIAL, R.string.remove_relate_commercial),
        bool("video.related.game.removed", FeaturePreferences.REMOVE_RELATE_GAME, R.string.remove_relate_game),
        bool("video.related.live.removed", FeaturePreferences.REMOVE_RELATE_LIVE, R.string.remove_relate_live),
        bool("video.related.course.removed", FeaturePreferences.REMOVE_RELATE_COURSE, R.string.remove_relate_course),
        bool("video.related.special.removed", FeaturePreferences.REMOVE_RELATE_SPECIAL, R.string.remove_relate_special),

        bool("story.ads.removed", FeaturePreferences.REMOVE_STORY_ADS, R.string.remove_story_ads),
        bool("story.live.removed", FeaturePreferences.REMOVE_STORY_LIVE, R.string.remove_story_live),
        bool("story.games.removed", FeaturePreferences.REMOVE_STORY_GAMES, R.string.remove_story_games),
        bool("story.bangumi.removed", FeaturePreferences.REMOVE_STORY_BANGUMI, R.string.remove_story_bangumi),
        bool("story.courses.removed", FeaturePreferences.REMOVE_STORY_COURSES, R.string.remove_story_courses),
        bool("story.short_drama.removed", FeaturePreferences.REMOVE_STORY_SHORT_DRAMA, R.string.remove_story_short_drama),
        bool("story.shopping.removed", FeaturePreferences.REMOVE_STORY_SHOPPING, R.string.remove_story_shopping),
        bool("story.movies.removed", FeaturePreferences.REMOVE_STORY_MOVIES, R.string.remove_story_movies),
        bool("story.documentaries.removed", FeaturePreferences.REMOVE_STORY_DOCUMENTARIES, R.string.remove_story_documentaries),
        bool("story.tv.removed", FeaturePreferences.REMOVE_STORY_TV, R.string.remove_story_tv),
        bool("story.variety.removed", FeaturePreferences.REMOVE_STORY_VARIETY, R.string.remove_story_variety),
        bool("story.music.removed", FeaturePreferences.REMOVE_STORY_MUSIC, R.string.remove_story_music),

        text("navigation.bottom_bar.hidden_rules", FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES, R.string.custom_bottom_bar_hide),
        integer(
            ID_RECOMMEND_VIDEO_MIN_DURATION,
            FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS,
            R.string.recommend_video_min_duration,
            default = 0,
            range = 0..Int.MAX_VALUE,
            introducedCatalogVersion = 2
        ),
        integer(
            ID_RECOMMEND_VIDEO_MAX_DURATION,
            FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS,
            R.string.recommend_video_max_duration,
            default = 0,
            range = 0..Int.MAX_VALUE,
            introducedCatalogVersion = 2
        ),
        integer(
            "player.default_quality.qn",
            FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN,
            R.string.player_default_quality,
            default = 0,
            allowed = PlayerQualityConfig.supportedQns.toSet()
        ),
        bool("prompt.teenagers_mode.blocked", FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT, R.string.block_teenagers_mode_prompt),

        bool("comments.search_links.removed", FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS, R.string.remove_comment_search_links),
        bool("comments.empty_guide.removed", FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE, R.string.remove_comment_empty_guide),
        bool("comments.vote_widgets.removed", FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS, R.string.remove_comment_vote_widgets),
        bool("comments.follow_buttons.removed", FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS, R.string.remove_comment_follow_buttons),
        bool("comments.qoe.removed", FeaturePreferences.REMOVE_COMMENT_QOE, R.string.remove_comment_qoe),
        bool("comments.operations.removed", FeaturePreferences.REMOVE_COMMENT_OPERATIONS, R.string.remove_comment_operations),
        bool("comments.quick_reply.blocked", FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY, R.string.block_comment_quick_reply),
        bool("comments.section.hidden", FeaturePreferences.HIDE_COMMENT_SECTION, R.string.hide_comment_section),
        bool("comments.reply_topology.enabled", FeaturePreferences.REPLY_TOPOLOGY_ENABLED, R.string.reply_topology_enabled),
        bool("comments.keyword_filter.enabled", FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED, R.string.comment_keyword_filter),
        text("comments.keyword_filter.keywords", FeaturePreferences.COMMENT_FILTER_KEYWORDS, R.string.comment_keyword_rules),
        bool("comments.minimum_level_filter.enabled", FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED, R.string.comment_min_level_filter),
        integer(
            "comments.minimum_level_filter.level",
            FeaturePreferences.COMMENT_MIN_LEVEL,
            R.string.comment_min_level_dialog_title,
            default = CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL,
            range = 1..6
        ),
        bool("splash.ads.purified", FeaturePreferences.PURIFY_SPLASH_ADS, R.string.purify_splash_ads),

        bool(
            ID_FREE_COPY_COMMENT,
            HookEntry.PREF_FREE_COPY_ENABLED,
            R.string.free_copy_enable,
            default = true,
            effects = setOf(
                ImportEffect.REBUILD_FREE_COPY_MIRROR,
                ImportEffect.RECREATE_MODULE_UI,
                ImportEffect.RESTART_BILIBILI
            )
        ),
        bool(
            ID_FREE_COPY_DESCRIPTION,
            HookEntry.PREF_FREE_COPY_DESC_ENABLED,
            R.string.free_copy_desc_enable,
            default = true,
            effects = setOf(
                ImportEffect.REBUILD_FREE_COPY_MIRROR,
                ImportEffect.RECREATE_MODULE_UI,
                ImportEffect.RESTART_BILIBILI
            )
        ),
        bool("free_copy.light_mode.enabled", HookEntry.PREF_FREE_COPY_LIGHT_MODE, R.string.free_copy_light_mode),
        bool("free_copy.auto_light.enabled", HookEntry.PREF_FREE_COPY_AUTO_LIGHT, R.string.free_copy_auto_light),

        bool(
            "compat.roaming.enabled",
            HookEntry.PREF_ROAMING_COMPAT_ENABLED,
            R.string.roaming_compat_enable,
            restorePolicy = RestorePolicy.MANUAL,
            effects = emptySet()
        ),
        bool(
            "module_ui.predictive_back.enabled",
            HookEntry.PREF_PREDICTIVE_BACK_ENABLED,
            R.string.predictive_back_enable,
            effects = setOf(
                ImportEffect.REAPPLY_PREDICTIVE_BACK,
                ImportEffect.RECREATE_MODULE_UI
            )
        ),
        bool("diagnostics.logging.enabled", HookEntry.PREF_LOG_ENABLED, R.string.log_capture_enable, default = true),
        text(
            "diagnostics.logging.level",
            HookEntry.PREF_LOG_LEVEL,
            R.string.log_level_label,
            default = HookEntry.LOG_LEVEL_COMPLETE,
            allowed = setOf(HookEntry.LOG_LEVEL_MINIMAL, HookEntry.LOG_LEVEL_COMPLETE)
        )
    )

    val byId: Map<String, SettingSpec> = specs.associateBy(SettingSpec::id)

    init {
        check(specs.size == 75) { "Expected 75 catalog settings, found ${specs.size}" }
        check(byId.size == specs.size) { "Duplicate logical setting id" }
        check(specs.map(SettingSpec::storageKey).distinct().size == specs.size) {
            "Duplicate settings storage key"
        }
        check(specs.all { it.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")) }) {
            "Invalid logical setting id"
        }
        check(specs.all { it.valueVersion > 0 }) { "Invalid setting value version" }
        check(specs.all { it.type.accepts(it.defaultValue) }) { "Invalid catalog default type" }
        check(specs.all { it.accepts(it.defaultValue) }) { "Invalid catalog default value" }
        check(specs.all { it.introducedCatalogVersion in 1..CATALOG_VERSION }) {
            "Invalid introduced catalog version"
        }
    }
}
