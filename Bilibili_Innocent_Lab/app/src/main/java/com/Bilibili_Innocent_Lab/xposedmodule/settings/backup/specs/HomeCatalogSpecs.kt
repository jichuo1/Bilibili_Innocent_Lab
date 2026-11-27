package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportEffect
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

internal object HomeCatalogSpecs {

    val homeFeedAndMineSpecs: List<SettingSpec> = listOf(
        boolSpec("home.live.promotion.hidden", FeaturePreferences.HIDE_LIVE_HOME_PROMOTION, R.string.hide_live_home_promotion, introducedCatalogVersion = 63),
        boolSpec("home.top_bar.game_menu.hidden", FeaturePreferences.HIDE_HOME_GAME_MENU, R.string.hide_home_game_menu),
        boolSpec("home.top_bar.search_word.hidden", FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, R.string.hide_home_search_default_word),
        boolSpec("home.vertical.open_detail", FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL, R.string.home_vertical_open_detail),
        boolSpec("home.recommend.ads.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS, R.string.remove_home_recommend_ads),
        boolSpec("home.recommend.cm_v2.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2, R.string.remove_home_recommend_cm_v2, introducedCatalogVersion = 9),
        boolSpec("home.recommend.pictures.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES, R.string.remove_home_recommend_pictures),
        boolSpec("home.recommend.game_promotions.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS, R.string.remove_home_recommend_game_promotions),
        boolSpec("home.recommend.title_filter.enabled", FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED, R.string.home_recommend_title_filter),
        textSpec("home.recommend.title_filter.keywords", FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS, R.string.home_recommend_title_rules),
        // 分区 id 名单存成 Text 而不是集合：授权链只搬 Bool/Int/Text，见 TidBlocklistCodec。
        textSpec(
            "home.recommend.blocked_tids",
            FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS,
            R.string.home_recommend_blocked_tids,
            introducedCatalogVersion = 19
        ),
        boolSpec(
            "home.recommend.section_pick.enabled",
            FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED,
            R.string.home_recommend_section_pick,
            introducedCatalogVersion = 19
        ),
        // UP 名单同样存成 Text；判据与详情页那档共用 ExactRuleSetCodec。
        textSpec(
            "home.recommend.blocked_authors",
            FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS,
            R.string.home_recommend_blocked_authors,
            introducedCatalogVersion = 20
        ),
        // 纯模块 App 行为（宿主收下这个键但从不读），登记在这里只为了能被设置备份带走。
        // 重启宿主对它没有意义，所以只要求重建模块界面。
        boolSpec(
            "home.recommend.feedback_auto_confirm",
            FeaturePreferences.HOME_RECOMMEND_FEEDBACK_AUTO_CONFIRM,
            R.string.recommendation_blocklist_auto_confirm,
            introducedCatalogVersion = 25,
            effects = setOf(ImportEffect.RECREATE_MODULE_UI)
        ),
        // 一个开关管详情页拦截补位 + 首页推荐 + 相关推荐三处，见 AiDeclaredVideoPolicy。
        boolSpec(
            SettingsCatalog.ID_AI_DECLARED_VIDEOS_BLOCKED,
            FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS,
            R.string.block_ai_declared_videos,
            introducedCatalogVersion = 28
        ),
        // 宿主据此记下发布者；模块 App 据此把这类来源并入 UP 名单（不需要打开自动确认）。
        // 两边都读，所以重启宿主与重建模块界面都要。
        boolSpec(
            SettingsCatalog.ID_AI_DECLARED_VIDEOS_STRONG_MODE,
            FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE,
            R.string.block_ai_declared_videos_strong_mode,
            introducedCatalogVersion = 28
        ),
        boolSpec(
            SettingsCatalog.ID_AI_DECLARED_VIDEOS_PRECHECK,
            FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_PRECHECK,
            R.string.ai_declared_precheck,
            introducedCatalogVersion = 30
        ),
        boolSpec("home.recommend.live.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE, R.string.remove_home_recommend_live),
        boolSpec(
            "home.recommend.pgc.removed",
            FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC,
            R.string.remove_home_recommend_pgc,
            introducedCatalogVersion = 14
        ),
        boolSpec(
            "home.recommend.comic.removed",
            FeaturePreferences.REMOVE_HOME_RECOMMEND_COMIC,
            R.string.remove_home_recommend_comic,
            introducedCatalogVersion = 52
        ),
        boolSpec(
            "home.recommend.special_cards.removed",
            FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS,
            R.string.remove_home_recommend_special_cards,
            introducedCatalogVersion = 14
        ),
        boolSpec("home.recommend.courses.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES, R.string.remove_home_recommend_courses),
        boolSpec("home.recommend.vertical.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL, R.string.remove_home_recommend_vertical),
        boolSpec("home.recommend.large.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE, R.string.remove_home_recommend_large),
        textSpec("home.tabs.hidden_rules", FeaturePreferences.HOME_TAB_HIDDEN_RULES, R.string.custom_home_tab_hide),
        textSpec(
            "home.tabs.hidden_selectors",
            FeaturePreferences.HOME_TAB_HIDDEN_SELECTORS,
            R.string.custom_home_tab_hide,
            introducedCatalogVersion = 9
        ),
        textSpec("home.components.hidden_rules", FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES, R.string.custom_home_component_hide),
        textSpec(
            "home.components.hidden_selectors",
            FeaturePreferences.HOME_COMPONENT_HIDDEN_SELECTORS,
            R.string.custom_home_component_hide,
            introducedCatalogVersion = 9
        ),
        boolSpec("mine.vip.hidden", FeaturePreferences.HIDE_MINE_VIP, R.string.hide_mine_vip),
        boolSpec("mine.vip.space_kept", FeaturePreferences.KEEP_MINE_VIP_SPACE, R.string.keep_mine_vip_space),
        textSpec("mine.components.hidden_rules", FeaturePreferences.MINE_COMPONENT_HIDDEN_RULES, R.string.custom_mine_component_hide),
        textSpec("mine.components.hidden_ids", FeaturePreferences.MINE_COMPONENT_HIDDEN_IDS, R.string.custom_mine_component_hide),
        textSpec(
            "mine.components.hidden_selectors",
            FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS,
            R.string.custom_mine_component_hide,
            introducedCatalogVersion = 3
        )
    )

    val searchSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "search.home_recommend.hidden",
            FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND,
            R.string.hide_search_home_recommend,
            introducedCatalogVersion = 16
        ),
        boolSpec(
            "search.commercial.removed",
            FeaturePreferences.REMOVE_SEARCH_COMMERCIAL,
            R.string.remove_search_commercial,
            introducedCatalogVersion = 12
        ),
        boolSpec(
            "search.keyword_filter.enabled",
            FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED,
            R.string.search_keyword_filter,
            introducedCatalogVersion = 12
        ),
        textSpec(
            "search.keyword_filter.keywords",
            FeaturePreferences.SEARCH_FILTER_KEYWORDS,
            R.string.search_keyword_rules,
            introducedCatalogVersion = 12
        ),
        boolSpec(
            "search.author_filter.enabled",
            FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED,
            R.string.search_author_filter,
            introducedCatalogVersion = 12
        ),
        textSpec(
            "search.author_filter.rules",
            FeaturePreferences.SEARCH_AUTHOR_FILTER_RULES,
            R.string.search_author_filter_rules,
            introducedCatalogVersion = 12
        )
    )

    val detailAndRelatedSpecs: List<SettingSpec> = listOf(
        // 详情页模块净化：UGC 详情页 view.v1 协议面的四个顶层字段，各自独立、默认全关。
        boolSpec(
            "purify.detail.honor.removed",
            FeaturePreferences.REMOVE_DETAIL_HONOR,
            R.string.remove_detail_honor,
            introducedCatalogVersion = 18
        ),
        boolSpec(
            "purify.detail.live_order.removed",
            FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER,
            R.string.remove_detail_live_order,
            introducedCatalogVersion = 18
        ),
        boolSpec(
            "purify.detail.ugc_season.removed",
            FeaturePreferences.REMOVE_DETAIL_UGC_SEASON,
            R.string.remove_detail_ugc_season,
            introducedCatalogVersion = 18
        ),
        boolSpec(
            "purify.detail.up_vip_label.removed",
            FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL,
            R.string.remove_detail_up_vip_label,
            introducedCatalogVersion = 18
        ),
        boolSpec(
            "purify.detail.topic_tags.removed",
            FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS,
            R.string.remove_detail_topic_tags,
            introducedCatalogVersion = 18
        ),
        boolSpec(
            "purify.detail.staff_follow.hidden",
            FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW,
            R.string.remove_detail_staff_follow,
            introducedCatalogVersion = 18
        ),
        boolSpec(
            "purify.detail.hot_banner.hidden",
            FeaturePreferences.REMOVE_DETAIL_HOT_BANNER,
            R.string.remove_detail_hot_banner,
            introducedCatalogVersion = 18
        ),
        boolSpec("video.related.commercial.removed", FeaturePreferences.REMOVE_RELATE_COMMERCIAL, R.string.remove_relate_commercial),
        boolSpec("video.related.game.removed", FeaturePreferences.REMOVE_RELATE_GAME, R.string.remove_relate_game),
        boolSpec("video.related.live.removed", FeaturePreferences.REMOVE_RELATE_LIVE, R.string.remove_relate_live),
        boolSpec("video.related.course.removed", FeaturePreferences.REMOVE_RELATE_COURSE, R.string.remove_relate_course),
        boolSpec("video.related.special.removed", FeaturePreferences.REMOVE_RELATE_SPECIAL, R.string.remove_relate_special),
        boolSpec(
            "video.related.matching_enhancement.enabled",
            FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
            R.string.video_relate_matching_enhancement,
            introducedCatalogVersion = 5
        ),
        boolSpec(
            "video.related.strong_mode.enabled",
            FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED,
            R.string.video_relate_strong_mode,
            introducedCatalogVersion = 6
        ),
        boolSpec(
            "video.related.reason_filter.enabled",
            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED,
            R.string.video_relate_reason_filter,
            introducedCatalogVersion = 5
        ),
        textSpec(
            "video.related.reason_filter.keywords",
            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_KEYWORDS,
            R.string.video_relate_reason_filter_keywords,
            maxStringLength = 4_096,
            introducedCatalogVersion = 5
        ),
        // 详情页没有 tid，"按分区过滤"在这里退到作者与标签两档；判据是整串相等。
        textSpec(
            "video.related.blocked_authors",
            FeaturePreferences.VIDEO_RELATE_BLOCKED_AUTHORS,
            R.string.video_relate_blocked_authors,
            maxStringLength = 4_096,
            introducedCatalogVersion = 19
        ),
        textSpec(
            "video.related.blocked_tags",
            FeaturePreferences.VIDEO_RELATE_BLOCKED_TAGS,
            R.string.video_relate_blocked_tags,
            maxStringLength = 4_096,
            introducedCatalogVersion = 19
        )
    )

    val recommendMetricSpecs: List<SettingSpec> = listOf(
        intSpec(
            SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_DURATION,
            FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS,
            R.string.recommend_video_min_duration,
            default = 0,
            range = 0..Int.MAX_VALUE,
            introducedCatalogVersion = 2
        ),
        intSpec(
            SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_DURATION,
            FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS,
            R.string.recommend_video_max_duration,
            default = 0,
            range = 0..Int.MAX_VALUE,
            introducedCatalogVersion = 2
        ),
        intSpec(
            SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT,
            FeaturePreferences.RECOMMEND_VIDEO_MIN_PLAY_COUNT,
            R.string.recommend_video_min_play_count,
            default = 0,
            range = 0..Int.MAX_VALUE,
            introducedCatalogVersion = 27
        ),
        intSpec(
            SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT,
            FeaturePreferences.RECOMMEND_VIDEO_MAX_PLAY_COUNT,
            R.string.recommend_video_max_play_count,
            default = 0,
            range = 0..Int.MAX_VALUE,
            introducedCatalogVersion = 27
        ),
        // 规则文本由宿主侧 VideoPublishTimeRule 解析；导入的坏值在那里整体失效（保守放行）。
        textSpec(
            SettingsCatalog.ID_RECOMMEND_VIDEO_PUBLISH_TIME_RULE,
            FeaturePreferences.RECOMMEND_VIDEO_PUBLISH_TIME_RULE,
            R.string.recommend_video_publish_time_range,
            default = "",
            maxStringLength = 64,
            introducedCatalogVersion = 57
        )
    )

    val homeLoadingSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "home.frame.legacy_lock.enabled",
            FeaturePreferences.HOME_FRAME_LEGACY_LOCK,
            R.string.home_frame_legacy_lock,
            introducedCatalogVersion = 66
        ),
        boolSpec(
            "home.recommend.early_load.enabled",
            FeaturePreferences.HOME_RECOMMEND_EARLY_LOAD,
            R.string.home_recommend_early_load,
            introducedCatalogVersion = 73
        )
    )
}
