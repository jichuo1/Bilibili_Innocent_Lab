package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticJudge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticPresets
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSensitivity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSettings
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticBackend
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticCustomRule
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticGuidance
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticRoute
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.MaterialColorSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.MaterialColorSpecStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.ModalBackdropBlurStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial

/**
 * 可备份用户意图的唯一白名单。
 *
 * 这里故意不包含缓存、时间戳、自由复制修订号、更新检查节流、应用语言和桌面图标状态。
 * 新增设置必须显式登记；禁止改为导出 prefs.all。
 */
internal object SettingsCatalog {
    const val PRODUCT_ID = "bilibili-innocent-lab.settings"
    const val SCOPE_ID = "core-user-settings"
    const val CATALOG_VERSION = 67
    const val ID_PLAYER_DEFAULT_SPEED = "player.default_speed.percent"
    const val ID_PLAYER_LONG_PRESS_SPEED = "player.long_press_speed.percent"
    const val ID_PLAYER_SPEED_MENU = "player.speed_menu.percents"
    const val ID_FREE_COPY_COMMENT = "free_copy.comment.enabled"
    const val ID_FREE_COPY_DESCRIPTION = "free_copy.description.enabled"
    const val ID_RECOMMEND_VIDEO_MIN_DURATION =
        "recommend.video_duration.minimum_seconds"
    const val ID_RECOMMEND_VIDEO_MAX_DURATION =
        "recommend.video_duration.maximum_seconds"
    const val ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT =
        "recommend.video_play_count.minimum"
    const val ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT =
        "recommend.video_play_count.maximum"
    const val ID_RECOMMEND_VIDEO_PUBLISH_TIME_RULE =
        "recommend.video_publish_time.rule"
    const val ID_DANMAKU_WEIGHT_MINIMUM = "player.danmaku.weight_filter.minimum"
    const val ID_MATERIAL_COLOR_SPEC = "module_ui.material_color_spec"
    const val ID_AI_DECLARED_VIDEOS_BLOCKED = "video.ai_declared.blocked"
    const val ID_AI_DECLARED_VIDEOS_STRONG_MODE = "video.ai_declared.strong_mode"
    const val ID_BILI_ACCESS_KEY_AUTHORIZED = "communication.bili_access_key.authorized"
    const val ID_AI_DECLARED_VIDEOS_PRECHECK = "video.ai_declared.precheck"
    const val ID_DYNAMIC_SEMANTIC_FILTER = "dynamic.semantic_filter.enabled"
    const val ID_SEMANTIC_JEV_ENDPOINT = "compat.semantic_jev.endpoint"
    const val ID_SEMANTIC_JEV_SENSITIVITY = "compat.semantic_jev.sensitivity"
    const val ID_SEMANTIC_JEV_WAIT_FIRST_SCREEN = "compat.semantic_jev.wait_first_screen"
    const val ID_DYNAMIC_SEMANTIC_RULES = "dynamic.semantic_filter.rules"
    const val ID_DANMAKU_SEMANTIC_FILTER = "player.danmaku.semantic_filter.enabled"
    const val ID_DANMAKU_SEMANTIC_RULES = "player.danmaku.semantic_filter.rules"
    const val ID_COMMENT_SEMANTIC_FILTER = "comments.semantic_filter.enabled"
    const val ID_COMMENT_SEMANTIC_RULES = "comments.semantic_filter.rules"
    const val ID_VIDEO_SEMANTIC_FILTER = "video.semantic_filter.enabled"
    const val ID_VIDEO_SEMANTIC_RULES = "video.semantic_filter.rules"
    const val ID_SEMANTIC_JEV_CACHE_DAYS = "compat.semantic_jev.cache_days"
    const val ID_SEMANTIC_JEV_PROVIDER = "compat.semantic_jev.provider"
    const val ID_SEMANTIC_JEV_MODEL = "compat.semantic_jev.model"
    const val ID_SEMANTIC_JEV_TIMEOUT_MS = "compat.semantic_jev.timeout_ms"
    const val ID_SEMANTIC_JEV_GUIDANCE = "compat.semantic_jev.guidance"
    const val ID_SEMANTIC_SOURCES = "compat.semantic_source.2.provider"
    const val ID_DYNAMIC_SEMANTIC_SOURCE = "dynamic.semantic_filter.source"
    const val ID_DANMAKU_SEMANTIC_SOURCE = "player.danmaku.semantic_filter.source"
    const val ID_COMMENT_SEMANTIC_SOURCE = "comments.semantic_filter.source"
    const val ID_VIDEO_SEMANTIC_SOURCE = "video.semantic_filter.source"
    const val ID_DYNAMIC_SEMANTIC_CUSTOM_RULES = "dynamic.semantic_filter.custom_rules"
    const val ID_DANMAKU_SEMANTIC_CUSTOM_RULES = "player.danmaku.semantic_filter.custom_rules"
    const val ID_COMMENT_SEMANTIC_CUSTOM_RULES = "comments.semantic_filter.custom_rules"
    const val ID_VIDEO_SEMANTIC_CUSTOM_RULES = "video.semantic_filter.custom_rules"

    /** 各过滤面的"判定来源 / 自定义类型"目录 id。 */
    fun semanticRouteId(surface: SemanticSurface): String = when (surface) {
        SemanticSurface.DYNAMIC -> ID_DYNAMIC_SEMANTIC_SOURCE
        SemanticSurface.DANMAKU -> ID_DANMAKU_SEMANTIC_SOURCE
        SemanticSurface.COMMENT -> ID_COMMENT_SEMANTIC_SOURCE
        SemanticSurface.VIDEO -> ID_VIDEO_SEMANTIC_SOURCE
    }

    fun semanticCustomRulesId(surface: SemanticSurface): String = when (surface) {
        SemanticSurface.DYNAMIC -> ID_DYNAMIC_SEMANTIC_CUSTOM_RULES
        SemanticSurface.DANMAKU -> ID_DANMAKU_SEMANTIC_CUSTOM_RULES
        SemanticSurface.COMMENT -> ID_COMMENT_SEMANTIC_CUSTOM_RULES
        SemanticSurface.VIDEO -> ID_VIDEO_SEMANTIC_CUSTOM_RULES
    }

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
        maxStringLength: Int = SettingSpec.DEFAULT_MAX_STRING_LENGTH,
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
        maxStringLength = maxStringLength,
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

    private fun semanticRules(id: String, storageKey: String, surface: SemanticSurface) = text(
        id,
        storageKey,
        R.string.semantic_rules_title,
        default = SemanticPresets.defaultSelection(surface),
        maxStringLength = SemanticPresets.MAX_SELECTION_LENGTH,
        introducedCatalogVersion = 32
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
        bool("player.end_page_recommend.hidden", FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND,
            R.string.hide_player_end_page_recommend, introducedCatalogVersion = 23),
        bool("communication.compatibility.enabled", CommunicationCompatibilityStore.KEY,
            R.string.communication_compatibility_mode, default = CommunicationCompatibilityStore.DEFAULT,
            restorePolicy = RestorePolicy.MANUAL, introducedCatalogVersion = 22),
        bool(ID_BILI_ACCESS_KEY_AUTHORIZED, FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED,
            R.string.bili_access_key_authorize, restorePolicy = RestorePolicy.MANUAL, introducedCatalogVersion = 29),
        bool(com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences.CATALOG_ID,
            com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences.ENABLED, R.string.agent_title,
            restorePolicy = RestorePolicy.MANUAL, introducedCatalogVersion = 47),
        // JEV 语义判定的非敏感配置；API Key 是 hook_config 运行时键，不进目录、不进备份。
        text(ID_SEMANTIC_JEV_ENDPOINT, FeaturePreferences.SEMANTIC_JEV_ENDPOINT, R.string.semantic_jev_endpoint,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH, introducedCatalogVersion = 31),
        text(ID_SEMANTIC_JEV_SENSITIVITY, FeaturePreferences.SEMANTIC_JEV_SENSITIVITY, R.string.semantic_jev_sensitivity,
            default = SemanticSensitivity.DEFAULT.id, allowed = SemanticSensitivity.IDS, introducedCatalogVersion = 31),
        bool(ID_SEMANTIC_JEV_WAIT_FIRST_SCREEN, FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN,
            R.string.semantic_jev_wait_first_screen, introducedCatalogVersion = 31),
        integer(ID_SEMANTIC_JEV_CACHE_DAYS, FeaturePreferences.SEMANTIC_JEV_CACHE_DAYS, R.string.semantic_jev_cache_days,
            default = SemanticSettings.DEFAULT_CACHE_DAYS,
            range = SemanticSettings.MIN_CACHE_DAYS..SemanticSettings.MAX_CACHE_DAYS, introducedCatalogVersion = 33),
        text(ID_SEMANTIC_JEV_PROVIDER, FeaturePreferences.SEMANTIC_JEV_PROVIDER, R.string.semantic_jev_provider,
            default = SemanticBackend.JEV, allowed = SemanticBackend.IDS, introducedCatalogVersion = 34),
        text(ID_SEMANTIC_JEV_MODEL, FeaturePreferences.SEMANTIC_JEV_MODEL, R.string.semantic_jev_model,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH, introducedCatalogVersion = 34),
        // 0 = 自动（各面默认值）；非 0 时宿主侧再夹到 500–30000。
        integer(ID_SEMANTIC_JEV_TIMEOUT_MS, FeaturePreferences.SEMANTIC_JEV_TIMEOUT_MS, R.string.semantic_jev_timeout,
            default = 0, range = 0..SemanticSettings.MAX_TIMEOUT_MS, introducedCatalogVersion = 35),
        // v36：多来源（2–4 号，Key 是运行时键不进备份）、判定口径、各面判定来源与自定义类型。
        text(ID_SEMANTIC_JEV_GUIDANCE, FeaturePreferences.SEMANTIC_JEV_GUIDANCE, R.string.semantic_jev_guidance,
            maxStringLength = SemanticGuidance.MAX_LENGTH, introducedCatalogVersion = 36),
        text(ID_SEMANTIC_SOURCES, FeaturePreferences.SEMANTIC_SOURCE_2_PROVIDER, R.string.semantic_source_2_label,
            default = SemanticBackend.JEV, allowed = SemanticBackend.IDS, introducedCatalogVersion = 36),
        text("compat.semantic_source.2.endpoint", FeaturePreferences.SEMANTIC_SOURCE_2_ENDPOINT, R.string.semantic_source_2_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH, introducedCatalogVersion = 36),
        text("compat.semantic_source.2.model", FeaturePreferences.SEMANTIC_SOURCE_2_MODEL, R.string.semantic_source_2_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH, introducedCatalogVersion = 36),
        text("compat.semantic_source.3.provider", FeaturePreferences.SEMANTIC_SOURCE_3_PROVIDER, R.string.semantic_source_3_label,
            default = SemanticBackend.JEV, allowed = SemanticBackend.IDS, introducedCatalogVersion = 36),
        text("compat.semantic_source.3.endpoint", FeaturePreferences.SEMANTIC_SOURCE_3_ENDPOINT, R.string.semantic_source_3_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH, introducedCatalogVersion = 36),
        text("compat.semantic_source.3.model", FeaturePreferences.SEMANTIC_SOURCE_3_MODEL, R.string.semantic_source_3_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH, introducedCatalogVersion = 36),
        text("compat.semantic_source.4.provider", FeaturePreferences.SEMANTIC_SOURCE_4_PROVIDER, R.string.semantic_source_4_label,
            default = SemanticBackend.JEV, allowed = SemanticBackend.IDS, introducedCatalogVersion = 36),
        text("compat.semantic_source.4.endpoint", FeaturePreferences.SEMANTIC_SOURCE_4_ENDPOINT, R.string.semantic_source_4_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH, introducedCatalogVersion = 36),
        text("compat.semantic_source.4.model", FeaturePreferences.SEMANTIC_SOURCE_4_MODEL, R.string.semantic_source_4_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH, introducedCatalogVersion = 36),
        // v46：扩展到 8 个来源；新来源默认无凭据，不会自行启用。
        text("compat.semantic_source.5.provider", FeaturePreferences.SEMANTIC_SOURCE_5_PROVIDER, R.string.semantic_source_5_label,
            default = SemanticBackend.JEV, allowed = SemanticBackend.IDS, introducedCatalogVersion = 46),
        text("compat.semantic_source.5.endpoint", FeaturePreferences.SEMANTIC_SOURCE_5_ENDPOINT, R.string.semantic_source_5_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH, introducedCatalogVersion = 46),
        text("compat.semantic_source.5.model", FeaturePreferences.SEMANTIC_SOURCE_5_MODEL, R.string.semantic_source_5_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH, introducedCatalogVersion = 46),
        text("compat.semantic_source.6.provider", FeaturePreferences.SEMANTIC_SOURCE_6_PROVIDER, R.string.semantic_source_6_label,
            default = SemanticBackend.JEV, allowed = SemanticBackend.IDS, introducedCatalogVersion = 46),
        text("compat.semantic_source.6.endpoint", FeaturePreferences.SEMANTIC_SOURCE_6_ENDPOINT, R.string.semantic_source_6_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH, introducedCatalogVersion = 46),
        text("compat.semantic_source.6.model", FeaturePreferences.SEMANTIC_SOURCE_6_MODEL, R.string.semantic_source_6_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH, introducedCatalogVersion = 46),
        text("compat.semantic_source.7.provider", FeaturePreferences.SEMANTIC_SOURCE_7_PROVIDER, R.string.semantic_source_7_label,
            default = SemanticBackend.JEV, allowed = SemanticBackend.IDS, introducedCatalogVersion = 46),
        text("compat.semantic_source.7.endpoint", FeaturePreferences.SEMANTIC_SOURCE_7_ENDPOINT, R.string.semantic_source_7_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH, introducedCatalogVersion = 46),
        text("compat.semantic_source.7.model", FeaturePreferences.SEMANTIC_SOURCE_7_MODEL, R.string.semantic_source_7_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH, introducedCatalogVersion = 46),
        text("compat.semantic_source.8.provider", FeaturePreferences.SEMANTIC_SOURCE_8_PROVIDER, R.string.semantic_source_8_label,
            default = SemanticBackend.JEV, allowed = SemanticBackend.IDS, introducedCatalogVersion = 46),
        text("compat.semantic_source.8.endpoint", FeaturePreferences.SEMANTIC_SOURCE_8_ENDPOINT, R.string.semantic_source_8_label,
            maxStringLength = SemanticJudge.MAX_ENDPOINT_LENGTH, introducedCatalogVersion = 46),
        text("compat.semantic_source.8.model", FeaturePreferences.SEMANTIC_SOURCE_8_MODEL, R.string.semantic_source_8_label,
            maxStringLength = SemanticBackend.MAX_MODEL_LENGTH, introducedCatalogVersion = 46),
        text(ID_DYNAMIC_SEMANTIC_SOURCE, FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_SOURCE, R.string.semantic_route_title,
            default = SemanticRoute.AUTO, allowed = SemanticRoute.IDS, introducedCatalogVersion = 36),
        text(ID_DANMAKU_SEMANTIC_SOURCE, FeaturePreferences.DANMAKU_SEMANTIC_FILTER_SOURCE, R.string.semantic_route_title,
            default = SemanticRoute.AUTO, allowed = SemanticRoute.IDS, introducedCatalogVersion = 36),
        text(ID_COMMENT_SEMANTIC_SOURCE, FeaturePreferences.COMMENT_SEMANTIC_FILTER_SOURCE, R.string.semantic_route_title,
            default = SemanticRoute.AUTO, allowed = SemanticRoute.IDS, introducedCatalogVersion = 36),
        text(ID_VIDEO_SEMANTIC_SOURCE, FeaturePreferences.VIDEO_SEMANTIC_FILTER_SOURCE, R.string.semantic_route_title,
            default = SemanticRoute.AUTO, allowed = SemanticRoute.IDS, introducedCatalogVersion = 36),
        text(ID_DYNAMIC_SEMANTIC_CUSTOM_RULES, FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_CUSTOM_RULES, R.string.semantic_custom_rules_title,
            maxStringLength = SemanticCustomRule.MAX_STORAGE_LENGTH, introducedCatalogVersion = 36),
        text(ID_DANMAKU_SEMANTIC_CUSTOM_RULES, FeaturePreferences.DANMAKU_SEMANTIC_FILTER_CUSTOM_RULES, R.string.semantic_custom_rules_title,
            maxStringLength = SemanticCustomRule.MAX_STORAGE_LENGTH, introducedCatalogVersion = 36),
        text(ID_COMMENT_SEMANTIC_CUSTOM_RULES, FeaturePreferences.COMMENT_SEMANTIC_FILTER_CUSTOM_RULES, R.string.semantic_custom_rules_title,
            maxStringLength = SemanticCustomRule.MAX_STORAGE_LENGTH, introducedCatalogVersion = 36),
        text(ID_VIDEO_SEMANTIC_CUSTOM_RULES, FeaturePreferences.VIDEO_SEMANTIC_FILTER_CUSTOM_RULES, R.string.semantic_custom_rules_title,
            maxStringLength = SemanticCustomRule.MAX_STORAGE_LENGTH, introducedCatalogVersion = 36),
        bool("ads.home_banner.hidden", HookEntry.PREF_BANNER_ENABLED, R.string.banner_ad_enable, default = true),
        bool("ads.merchandise.hidden", HookEntry.PREF_MERCH_ENABLED, R.string.merch_ad_enable, default = true),

        bool("home.live.promotion.hidden", FeaturePreferences.HIDE_LIVE_HOME_PROMOTION, R.string.hide_live_home_promotion, introducedCatalogVersion = 63),
        bool("home.top_bar.game_menu.hidden", FeaturePreferences.HIDE_HOME_GAME_MENU, R.string.hide_home_game_menu),
        bool("home.top_bar.search_word.hidden", FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, R.string.hide_home_search_default_word),
        bool("home.vertical.open_detail", FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL, R.string.home_vertical_open_detail),
        bool("home.recommend.ads.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS, R.string.remove_home_recommend_ads),
        bool("home.recommend.cm_v2.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2, R.string.remove_home_recommend_cm_v2, introducedCatalogVersion = 9),
        bool("home.recommend.pictures.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES, R.string.remove_home_recommend_pictures),
        bool("home.recommend.game_promotions.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS, R.string.remove_home_recommend_game_promotions),
        bool("home.recommend.title_filter.enabled", FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED, R.string.home_recommend_title_filter),
        text("home.recommend.title_filter.keywords", FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS, R.string.home_recommend_title_rules),
        // 分区 id 名单存成 Text 而不是集合：授权链只搬 Bool/Int/Text，见 TidBlocklistCodec。
        text("home.recommend.blocked_tids", FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS,
            R.string.home_recommend_blocked_tids, introducedCatalogVersion = 19),
        bool("home.recommend.section_pick.enabled",
            FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED,
            R.string.home_recommend_section_pick, introducedCatalogVersion = 19),
        // UP 名单同样存成 Text；判据与详情页那档共用 ExactRuleSetCodec。
        text("home.recommend.blocked_authors", FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS,
            R.string.home_recommend_blocked_authors, introducedCatalogVersion = 20),
        // 纯模块 App 行为（宿主收下这个键但从不读），登记在这里只为了能被设置备份带走。
        // 重启宿主对它没有意义，所以只要求重建模块界面。
        bool("home.recommend.feedback_auto_confirm",
            FeaturePreferences.HOME_RECOMMEND_FEEDBACK_AUTO_CONFIRM,
            R.string.recommendation_blocklist_auto_confirm,
            introducedCatalogVersion = 25,
            effects = setOf(ImportEffect.RECREATE_MODULE_UI)),
        // 一个开关管详情页拦截补位 + 首页推荐 + 相关推荐三处，见 AiDeclaredVideoPolicy。
        bool(ID_AI_DECLARED_VIDEOS_BLOCKED, FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS,
            R.string.block_ai_declared_videos, introducedCatalogVersion = 28),
        // 宿主据此记下发布者；模块 App 据此把这类来源并入 UP 名单（不需要打开自动确认）。
        // 两边都读，所以重启宿主与重建模块界面都要。
        bool(ID_AI_DECLARED_VIDEOS_STRONG_MODE, FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE,
            R.string.block_ai_declared_videos_strong_mode, introducedCatalogVersion = 28),
        bool(ID_AI_DECLARED_VIDEOS_PRECHECK, FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_PRECHECK,
            R.string.ai_declared_precheck, introducedCatalogVersion = 30),
        bool("home.recommend.live.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE, R.string.remove_home_recommend_live),
        bool("home.recommend.pgc.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC,
            R.string.remove_home_recommend_pgc, introducedCatalogVersion = 14),
        bool("home.recommend.comic.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_COMIC,
            R.string.remove_home_recommend_comic, introducedCatalogVersion = 52),
        bool("home.recommend.special_cards.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS,
            R.string.remove_home_recommend_special_cards, introducedCatalogVersion = 14),
        bool("home.recommend.courses.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES, R.string.remove_home_recommend_courses),
        bool("home.recommend.vertical.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL, R.string.remove_home_recommend_vertical),
        bool("home.recommend.large.removed", FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE, R.string.remove_home_recommend_large),
        text("home.tabs.hidden_rules", FeaturePreferences.HOME_TAB_HIDDEN_RULES, R.string.custom_home_tab_hide),
        text(
            "home.tabs.hidden_selectors",
            FeaturePreferences.HOME_TAB_HIDDEN_SELECTORS,
            R.string.custom_home_tab_hide,
            introducedCatalogVersion = 9
        ),
        text("home.components.hidden_rules", FeaturePreferences.HOME_COMPONENT_HIDDEN_RULES, R.string.custom_home_component_hide),
        text(
            "home.components.hidden_selectors",
            FeaturePreferences.HOME_COMPONENT_HIDDEN_SELECTORS,
            R.string.custom_home_component_hide,
            introducedCatalogVersion = 9
        ),

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
        bool(
            "dynamic.keyword_filter.enabled",
            FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED,
            R.string.dynamic_keyword_filter,
            introducedCatalogVersion = 12
        ),
        text(
            "dynamic.keyword_filter.keywords",
            FeaturePreferences.DYNAMIC_FILTER_KEYWORDS,
            R.string.dynamic_keyword_rules,
            introducedCatalogVersion = 12
        ),
        bool(
            "dynamic.author_filter.enabled",
            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED,
            R.string.dynamic_author_filter,
            introducedCatalogVersion = 12
        ),
        text(
            "dynamic.author_filter.rules",
            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_RULES,
            R.string.dynamic_author_filter_rules,
            introducedCatalogVersion = 12
        ),
        bool(
            ID_DYNAMIC_SEMANTIC_FILTER,
            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED,
            R.string.dynamic_semantic_filter,
            introducedCatalogVersion = 31
        ),
        // 智能过滤：各面一个开关 + 一份勾选（v32）。勾选以预设 id 存储，未知 id 由宿主忽略。
        semanticRules(ID_DYNAMIC_SEMANTIC_RULES, FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_RULES, SemanticSurface.DYNAMIC),
        bool(ID_DANMAKU_SEMANTIC_FILTER, FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED,
            R.string.danmaku_semantic_filter, introducedCatalogVersion = 32),
        semanticRules(ID_DANMAKU_SEMANTIC_RULES, FeaturePreferences.DANMAKU_SEMANTIC_FILTER_RULES, SemanticSurface.DANMAKU),
        bool(ID_COMMENT_SEMANTIC_FILTER, FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED,
            R.string.comment_semantic_filter, introducedCatalogVersion = 32),
        semanticRules(ID_COMMENT_SEMANTIC_RULES, FeaturePreferences.COMMENT_SEMANTIC_FILTER_RULES, SemanticSurface.COMMENT),
        bool(ID_VIDEO_SEMANTIC_FILTER, FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED,
            R.string.video_semantic_filter, introducedCatalogVersion = 32),
        semanticRules(ID_VIDEO_SEMANTIC_RULES, FeaturePreferences.VIDEO_SEMANTIC_FILTER_RULES, SemanticSurface.VIDEO),
        bool(
            "dynamic.promotions.removed",
            FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS,
            R.string.remove_dynamic_promotions,
            introducedCatalogVersion = 12
        ),
        bool(
            "dynamic.charge_only.removed",
            FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY,
            R.string.remove_dynamic_charge_only,
            introducedCatalogVersion = 12
        ),
        bool("dynamic.frequent_visits.hidden", FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS,
            R.string.hide_dynamic_frequent_visits, introducedCatalogVersion = 15),
        bool(
            "dynamic.topic_list.hidden",
            FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST,
            R.string.hide_dynamic_topic_list,
            introducedCatalogVersion = 12
        ),
        bool(
            "dynamic.up_list.live.removed",
            FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES,
            R.string.remove_dynamic_live_up_entries,
            introducedCatalogVersion = 12
        ),
        bool("search.home_recommend.hidden", FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND,
            R.string.hide_search_home_recommend, introducedCatalogVersion = 16),
        bool(
            "search.commercial.removed",
            FeaturePreferences.REMOVE_SEARCH_COMMERCIAL,
            R.string.remove_search_commercial,
            introducedCatalogVersion = 12
        ),
        bool(
            "search.keyword_filter.enabled",
            FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED,
            R.string.search_keyword_filter,
            introducedCatalogVersion = 12
        ),
        text(
            "search.keyword_filter.keywords",
            FeaturePreferences.SEARCH_FILTER_KEYWORDS,
            R.string.search_keyword_rules,
            introducedCatalogVersion = 12
        ),
        bool(
            "search.author_filter.enabled",
            FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED,
            R.string.search_author_filter,
            introducedCatalogVersion = 12
        ),
        text(
            "search.author_filter.rules",
            FeaturePreferences.SEARCH_AUTHOR_FILTER_RULES,
            R.string.search_author_filter_rules,
            introducedCatalogVersion = 12
        ),
        bool("numbers.full.enabled", FeaturePreferences.SHOW_FULL_NUMBERS, R.string.show_full_numbers),
        bool("player.portrait_control.hidden", FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL, R.string.hide_player_portrait_control),
        bool(
            "player.interactive_overlays.hidden",
            FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS,
            R.string.hide_player_interactive_overlays,
            introducedCatalogVersion = 7
        ),
        bool(
            "player.popup_promotion.hidden",
            FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION,
            R.string.hide_player_popup_promotion,
            introducedCatalogVersion = 21
        ),
        bool(
            "pgc.auto_activity_popup.hidden",
            FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP,
            R.string.hide_pgc_auto_activity_popup,
            introducedCatalogVersion = 10
        ),
        bool("player.status_bar.transparent", FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR, R.string.transparent_player_status_bar),
        bool(
            "player.danmaku.weight_filter.enabled",
            FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED,
            R.string.danmaku_weight_filter,
            introducedCatalogVersion = 11
        ),
        integer(
            ID_DANMAKU_WEIGHT_MINIMUM,
            FeaturePreferences.DANMAKU_WEIGHT_FILTER_MINIMUM,
            R.string.danmaku_weight_dialog_title,
            default = DanmakuPurifyPolicy.DEFAULT_MINIMUM_WEIGHT,
            range = DanmakuPurifyPolicy.MIN_WEIGHT..DanmakuPurifyPolicy.MAX_WEIGHT,
            introducedCatalogVersion = 11
        ),
        bool(
            "player.danmaku.vip_colorful.removed",
            FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU,
            R.string.remove_vip_colorful_danmaku,
            introducedCatalogVersion = 11
        ),
        bool(
            "live.room_switch.blocked",
            FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH,
            R.string.block_live_room_switch,
            introducedCatalogVersion = 11
        ),
        bool(
            "live.double_tap.pause",
            FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE,
            R.string.live_room_double_tap_pause,
            introducedCatalogVersion = 11
        ),

        // 详情页模块净化：UGC 详情页 view.v1 协议面的四个顶层字段，各自独立、默认全关。
        bool(
            "purify.detail.honor.removed",
            FeaturePreferences.REMOVE_DETAIL_HONOR,
            R.string.remove_detail_honor,
            introducedCatalogVersion = 18
        ),
        bool(
            "purify.detail.live_order.removed",
            FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER,
            R.string.remove_detail_live_order,
            introducedCatalogVersion = 18
        ),
        bool(
            "purify.detail.ugc_season.removed",
            FeaturePreferences.REMOVE_DETAIL_UGC_SEASON,
            R.string.remove_detail_ugc_season,
            introducedCatalogVersion = 18
        ),
        bool(
            "purify.detail.up_vip_label.removed",
            FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL,
            R.string.remove_detail_up_vip_label,
            introducedCatalogVersion = 18
        ),
        bool(
            "purify.detail.topic_tags.removed",
            FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS,
            R.string.remove_detail_topic_tags,
            introducedCatalogVersion = 18
        ),
        bool(
            "purify.detail.staff_follow.hidden",
            FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW,
            R.string.remove_detail_staff_follow,
            introducedCatalogVersion = 18
        ),
        bool(
            "purify.detail.hot_banner.hidden",
            FeaturePreferences.REMOVE_DETAIL_HOT_BANNER,
            R.string.remove_detail_hot_banner,
            introducedCatalogVersion = 18
        ),

        bool("video.related.commercial.removed", FeaturePreferences.REMOVE_RELATE_COMMERCIAL, R.string.remove_relate_commercial),
        bool("video.related.game.removed", FeaturePreferences.REMOVE_RELATE_GAME, R.string.remove_relate_game),
        bool("video.related.live.removed", FeaturePreferences.REMOVE_RELATE_LIVE, R.string.remove_relate_live),
        bool("video.related.course.removed", FeaturePreferences.REMOVE_RELATE_COURSE, R.string.remove_relate_course),
        bool("video.related.special.removed", FeaturePreferences.REMOVE_RELATE_SPECIAL, R.string.remove_relate_special),
        bool(
            "video.related.matching_enhancement.enabled",
            FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
            R.string.video_relate_matching_enhancement,
            introducedCatalogVersion = 5
        ),
        bool(
            "video.related.strong_mode.enabled",
            FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED,
            R.string.video_relate_strong_mode,
            introducedCatalogVersion = 6
        ),
        bool(
            "video.related.reason_filter.enabled",
            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED,
            R.string.video_relate_reason_filter,
            introducedCatalogVersion = 5
        ),
        text(
            "video.related.reason_filter.keywords",
            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_KEYWORDS,
            R.string.video_relate_reason_filter_keywords,
            maxStringLength = 4_096,
            introducedCatalogVersion = 5
        ),
        // 详情页没有 tid，"按分区过滤"在这里退到作者与标签两档；判据是整串相等。
        text(
            "video.related.blocked_authors",
            FeaturePreferences.VIDEO_RELATE_BLOCKED_AUTHORS,
            R.string.video_relate_blocked_authors,
            maxStringLength = 4_096,
            introducedCatalogVersion = 19
        ),
        text(
            "video.related.blocked_tags",
            FeaturePreferences.VIDEO_RELATE_BLOCKED_TAGS,
            R.string.video_relate_blocked_tags,
            maxStringLength = 4_096,
            introducedCatalogVersion = 19
        ),

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
        // v37：Story 竖屏流右侧互动图标，每个图标单独开关。
        bool("story.action.like.hidden", FeaturePreferences.HIDE_STORY_ACTION_LIKE, R.string.hide_story_action_like,
            introducedCatalogVersion = 37),
        bool("story.action.comment.hidden", FeaturePreferences.HIDE_STORY_ACTION_COMMENT, R.string.hide_story_action_comment,
            introducedCatalogVersion = 37),
        bool("story.action.coin.hidden", FeaturePreferences.HIDE_STORY_ACTION_COIN, R.string.hide_story_action_coin,
            introducedCatalogVersion = 37),
        bool("story.action.favorite.hidden", FeaturePreferences.HIDE_STORY_ACTION_FAVORITE, R.string.hide_story_action_favorite,
            introducedCatalogVersion = 37),
        bool("story.action.share.hidden", FeaturePreferences.HIDE_STORY_ACTION_SHARE, R.string.hide_story_action_share,
            introducedCatalogVersion = 37),
        bool("story.action.danmaku_toggle.hidden", FeaturePreferences.HIDE_STORY_ACTION_DANMAKU_TOGGLE, R.string.hide_story_action_danmaku_toggle,
            introducedCatalogVersion = 37),

        text("navigation.bottom_bar.hidden_rules", FeaturePreferences.BOTTOM_BAR_HIDDEN_RULES, R.string.custom_bottom_bar_hide),
        text(
            "navigation.bottom_bar.hidden_selectors",
            FeaturePreferences.BOTTOM_BAR_HIDDEN_SELECTORS,
            R.string.custom_bottom_bar_hide,
            introducedCatalogVersion = 9
        ),
        bool(
            "host.bottom_bar.liquid_glass.enabled",
            FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS,
            R.string.host_bottom_bar_liquid_glass,
            default = false,
            introducedCatalogVersion = 39
        ),
        text("host.background.preset", FeaturePreferences.HOST_BACKGROUND_PRESET, R.string.host_background_title,
            default = "off", allowed = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset.entries.map { it.value }.toSet(),
            introducedCatalogVersion = 49),
        integer("host.background.blur", FeaturePreferences.HOST_BACKGROUND_BLUR, R.string.host_background_blur,
            default = 16, range = 0..60, introducedCatalogVersion = 49),
        integer("host.background.saturation", FeaturePreferences.HOST_BACKGROUND_SATURATION, R.string.host_background_saturation,
            default = 85, range = 0..200, introducedCatalogVersion = 49),
        integer("host.background.veil", FeaturePreferences.HOST_BACKGROUND_VEIL, R.string.host_background_veil,
            default = 35, range = 0..90, introducedCatalogVersion = 49),
        // 图片仍在本机私有存储，资产引用只随 Hook 快照发布，不跨设备自动恢复。
        SettingSpec("host.background.asset", FeaturePreferences.HOST_BACKGROUND_ASSET, R.string.host_background_custom,
            SettingValueType.STRING, SettingValue.Text(""), introducedCatalogVersion = 49,
            restorePolicy = RestorePolicy.MANUAL, maxStringLength = 36),
        bool(
            "host.video_cards.enabled",
            FeaturePreferences.HOST_VIDEO_CARDS,
            R.string.host_video_cards,
            default = false,
            introducedCatalogVersion = 42
        ),
        text("host.cards.material", FeaturePreferences.HOST_CARD_MATERIAL,
            R.string.host_card_material, default = "original",
            allowed = HostCardMaterial.entries.map { it.value }.toSet(),
            introducedCatalogVersion = 55),
        integer(
            "host.video_cards.radius_dp",
            FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP,
            R.string.host_video_card_radius,
            default = -1,
            range = -1..40,
            introducedCatalogVersion = 43
        ),
        integer("host.home_cards.horizontal_dp", FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP,
            R.string.host_home_card_horizontal, default = -1, range = -1..40, introducedCatalogVersion = 50),
        integer("host.home_cards.vertical_dp", FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP,
            R.string.host_home_card_vertical, default = -1, range = -1..40, introducedCatalogVersion = 50),
        bool(
            "host.bottom_bar.compact.enabled",
            FeaturePreferences.HOST_BOTTOM_BAR_COMPACT,
            R.string.host_bottom_bar_compact,
            default = false,
            introducedCatalogVersion = 41
        ),
        text("host.bottom_bar.glass.material", FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL,
            R.string.host_glass_material, default = HostGlassMaterial.SOFT.value,
            allowed = HostGlassMaterial.entries.map { it.value }.toSet(), introducedCatalogVersion = 45),
        text("host.bottom_bar.glass.renderer", FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER,
            R.string.host_glass_renderer, default = HostGlassRenderer.LEGACY,
            allowed = HostGlassRenderer.entries.map { it.value }.toSet() + HostGlassRenderer.LEGACY, introducedCatalogVersion = 45),
        text("host.top_bar.glass.material", FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL,
            R.string.host_glass_material, default = HostGlassMaterial.SOFT.value,
            allowed = HostGlassMaterial.entries.map { it.value }.toSet(), introducedCatalogVersion = 45),
        text("host.top_bar.glass.renderer", FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER,
            R.string.host_glass_renderer, default = HostGlassRenderer.LEGACY,
            allowed = HostGlassRenderer.entries.map { it.value }.toSet() + HostGlassRenderer.LEGACY, introducedCatalogVersion = 45),
        bool(
            "host.bottom_bar.icon_only.enabled",
            FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY,
            R.string.host_bottom_bar_icon_only,
            default = false,
            introducedCatalogVersion = 41
        ),
        bool("host.mine.garb_dim.enabled", FeaturePreferences.HOST_MINE_GARB_DIM,
            R.string.host_mine_garb_dim, default = true, introducedCatalogVersion = 65),
        bool(
            "host.bottom_bar.touch_glow.enabled",
            FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW,
            R.string.host_bottom_bar_touch_glow,
            default = false,
            introducedCatalogVersion = 39
        ),
        bool(
            "host.top_bar.liquid_glass.enabled",
            FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
            R.string.host_top_bar_liquid_glass,
            default = false,
            introducedCatalogVersion = 40
        ),
        bool(
            "host.top_bar.touch_glow.enabled",
            FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW,
            R.string.host_top_bar_touch_glow,
            default = false,
            introducedCatalogVersion = 40
        ),
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
            ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT,
            FeaturePreferences.RECOMMEND_VIDEO_MIN_PLAY_COUNT,
            R.string.recommend_video_min_play_count,
            default = 0,
            range = 0..Int.MAX_VALUE,
            introducedCatalogVersion = 27
        ),
        integer(
            ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT,
            FeaturePreferences.RECOMMEND_VIDEO_MAX_PLAY_COUNT,
            R.string.recommend_video_max_play_count,
            default = 0,
            range = 0..Int.MAX_VALUE,
            introducedCatalogVersion = 27
        ),
        // 规则文本由宿主侧 VideoPublishTimeRule 解析；导入的坏值在那里整体失效（保守放行）。
        text(
            ID_RECOMMEND_VIDEO_PUBLISH_TIME_RULE,
            FeaturePreferences.RECOMMEND_VIDEO_PUBLISH_TIME_RULE,
            R.string.recommend_video_publish_time_range,
            default = "",
            maxStringLength = 64,
            introducedCatalogVersion = 57
        ),
        integer(
            "player.default_quality.qn",
            FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN,
            R.string.player_default_quality,
            default = 0,
            allowed = PlayerQualityConfig.supportedQns.toSet()
        ),
        integer(
            "player.codec.preference",
            FeaturePreferences.PLAYER_CODEC_PREFERENCE,
            R.string.player_codec_preference,
            default = 0,
            range = 0..3,
            introducedCatalogVersion = 24
        ),
        integer(
            "player.decode.mode",
            FeaturePreferences.PLAYER_DECODE_MODE,
            R.string.player_decode_mode,
            default = 0,
            range = 0..2,
            introducedCatalogVersion = 24
        ),
        integer(
            "player.buffer.mode", FeaturePreferences.PLAYER_BUFFER_MODE,
            R.string.player_buffer_strategy, default = 0,
            allowed = PlayerBufferConfig.supportedModes, introducedCatalogVersion = 51
        ),
        integer(
            "player.buffer.seconds", FeaturePreferences.PLAYER_BUFFER_SECONDS,
            R.string.player_buffer_seconds, default = PlayerBufferConfig.DEFAULT_SECONDS,
            range = PlayerBufferConfig.MIN_SECONDS..PlayerBufferConfig.MAX_SECONDS,
            introducedCatalogVersion = 51
        ),
        integer(
            "player.buffer.memory_mb", FeaturePreferences.PLAYER_BUFFER_MEMORY_MB,
            R.string.player_buffer_memory_mb, default = PlayerBufferConfig.DEFAULT_MEMORY_MB,
            range = PlayerBufferConfig.MIN_MEMORY_MB..PlayerBufferConfig.MAX_MEMORY_MB,
            introducedCatalogVersion = 51
        ),
        integer(
            "player.buffer.disk_mb", FeaturePreferences.PLAYER_BUFFER_DISK_MB,
            R.string.player_buffer_disk_mb, default = PlayerBufferConfig.DEFAULT_DISK_MB,
            range = PlayerBufferConfig.MIN_DISK_MB..PlayerBufferConfig.MAX_DISK_MB,
            introducedCatalogVersion = 51
        ),
        bool(
            "player.buffer.wifi_only", FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY,
            R.string.player_buffer_wifi_only, default = PlayerBufferConfig.DEFAULT_WIFI_ONLY,
            introducedCatalogVersion = 51
        ),
        bool(
            "client.component_library.download.blocked",
            FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD,
            R.string.block_component_library_download,
            introducedCatalogVersion = 24
        ),
        // 勾选面板的两把钥匙，与其余四个面同构：selectors 是勾出来的，rules 是手填的
        //（`*` 即「全量禁止」开关写的哨兵），两者取并集。
        text(
            "client.component_library.blocked_pools.rules",
            FeaturePreferences.COMPONENT_POOL_BLOCKED_RULES,
            R.string.component_pool_block,
            introducedCatalogVersion = 26
        ),
        text(
            "client.component_library.blocked_pools.selectors",
            FeaturePreferences.COMPONENT_POOL_BLOCKED_SELECTORS,
            R.string.component_pool_block,
            introducedCatalogVersion = 26
        ),
        bool("prompt.teenagers_mode.blocked", FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT, R.string.block_teenagers_mode_prompt),
        bool("player.capability.background", FeaturePreferences.PLAYER_UNLOCK_BACKGROUND,
            R.string.player_unlock_background, introducedCatalogVersion = 13),
        bool("player.capability.small_window", FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW,
            R.string.player_unlock_small_window, introducedCatalogVersion = 13),
        bool("player.capability.cast", FeaturePreferences.PLAYER_UNLOCK_CAST,
            R.string.player_unlock_cast, introducedCatalogVersion = 13),
        bool("player.subtitle.style.enabled", FeaturePreferences.PLAYER_SUBTITLE_STYLE,
            R.string.player_subtitle_style, introducedCatalogVersion = 62),
        bool("player.subtitle.ai.enabled", FeaturePreferences.PLAYER_AI_SUBTITLE,
            R.string.player_ai_subtitle, introducedCatalogVersion = 62),
        bool("player.frame_interpolation.enabled", FeaturePreferences.PLAYER_FRAME_INTERPOLATION,
            R.string.player_frame_interpolation, introducedCatalogVersion = 58),
        integer("player.frame_interpolation.max_fps", FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS,
            R.string.player_frame_max_fps, default = 60, allowed = setOf(60, 90, 120, 144, 165, 185), introducedCatalogVersion = 59),
        bool("player.frame_interpolation.status_toast", FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST,
            R.string.player_frame_status_toast, introducedCatalogVersion = 61),
        bool("player.frame_interpolation.disable_thermal_control", FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL,
            R.string.player_frame_disable_thermal_control, introducedCatalogVersion = 64),
        bool("player.long_press.disabled", FeaturePreferences.PLAYER_DISABLE_LONG_PRESS,
            R.string.player_disable_long_press, introducedCatalogVersion = 13),
        integer(ID_PLAYER_LONG_PRESS_SPEED, FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT,
            R.string.player_long_press_speed, default = PlayerSpeedConfig.FOLLOW_HOST,
            allowed = PlayerSpeedConfig.supportedPercents, introducedCatalogVersion = 13),
        integer(ID_PLAYER_DEFAULT_SPEED, FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT,
            R.string.player_default_speed, default = PlayerSpeedConfig.FOLLOW_HOST,
            allowed = PlayerSpeedConfig.supportedPercents, introducedCatalogVersion = 13),
        text(ID_PLAYER_SPEED_MENU, FeaturePreferences.PLAYER_SPEED_MENU_PERCENTS,
            R.string.player_speed_menu, default = "",
            maxStringLength = PlayerSpeedConfig.MAX_MENU_SPEC_LENGTH, introducedCatalogVersion = 60),
        bool("player.sponsorblock.enabled", FeaturePreferences.SPONSORBLOCK_ENABLED,
            R.string.sponsorblock_enabled, introducedCatalogVersion = 44),
        bool("player.sponsorblock.automatic", FeaturePreferences.SPONSORBLOCK_AUTOMATIC,
            R.string.sponsorblock_automatic, introducedCatalogVersion = 44),

        bool("comments.search_links.removed", FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS, R.string.remove_comment_search_links),
        bool("comments.empty_guide.removed", FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE, R.string.remove_comment_empty_guide),
        bool("comments.vote_widgets.removed", FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS, R.string.remove_comment_vote_widgets),
        bool("comments.follow_buttons.removed", FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS, R.string.remove_comment_follow_buttons),
        bool("comments.qoe.removed", FeaturePreferences.REMOVE_COMMENT_QOE, R.string.remove_comment_qoe),
        bool("comments.operations.removed", FeaturePreferences.REMOVE_COMMENT_OPERATIONS, R.string.remove_comment_operations),
        bool("comments.reply_button.removed", FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON,
            R.string.remove_comment_reply_button, introducedCatalogVersion = 53),
        bool("comments.quick_reply.blocked", FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY, R.string.block_comment_quick_reply),
        bool("comments.section.hidden", FeaturePreferences.HIDE_COMMENT_SECTION, R.string.hide_comment_section),
        bool("comments.reply_topology.enabled", FeaturePreferences.REPLY_TOPOLOGY_ENABLED, R.string.reply_topology_enabled),
        bool("comments.classic_style.enabled", FeaturePreferences.COMMENT_CLASSIC_STYLE, R.string.comment_classic_style, introducedCatalogVersion = 48),
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
        bool(
            "comments.at_only.removed",
            FeaturePreferences.REMOVE_AT_ONLY_COMMENTS,
            R.string.remove_at_only_comments,
            introducedCatalogVersion = 11
        ),
        bool(
            "comments.user_filter.enabled",
            FeaturePreferences.COMMENT_USER_FILTER_ENABLED,
            R.string.comment_user_filter,
            introducedCatalogVersion = 11
        ),
        text(
            "comments.user_filter.rules",
            FeaturePreferences.COMMENT_USER_FILTER_RULES,
            R.string.comment_user_filter_rules,
            introducedCatalogVersion = 11
        ),
        bool("splash.ads.purified", FeaturePreferences.PURIFY_SPLASH_ADS, R.string.purify_splash_ads),
        bool("splash.brand.skipped", FeaturePreferences.BRAND_SPLASH_SKIP, R.string.brand_splash_skip,
            introducedCatalogVersion = 38),
        bool("splash.custom_selection.enabled", FeaturePreferences.BRAND_SPLASH_CUSTOM, R.string.brand_splash_custom,
            introducedCatalogVersion = 38),
        bool(
            "splash.auto_night.enabled",
            FeaturePreferences.SPLASH_AUTO_NIGHT,
            R.string.splash_auto_night,
            introducedCatalogVersion = 11
        ),
        bool(
            "share.content.purified",
            FeaturePreferences.PURIFY_SHARE_CONTENT,
            R.string.purify_share_content,
            introducedCatalogVersion = 11
        ),
        bool(
            "share.mini_program.direct_link",
            FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK,
            R.string.share_mini_program_direct_link,
            introducedCatalogVersion = 11
        ),
        bool(
            "links.external_browser.enabled",
            FeaturePreferences.FORCE_EXTERNAL_BROWSER,
            R.string.force_external_browser,
            introducedCatalogVersion = 11
        ),
        bool(
            "system.media_notification.enabled",
            FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION,
            R.string.system_media_notification,
            introducedCatalogVersion = 11
        ),
        bool(
            "home.frame.legacy_lock.enabled",
            FeaturePreferences.HOME_FRAME_LEGACY_LOCK,
            R.string.home_frame_legacy_lock,
            introducedCatalogVersion = 66
        ),
        bool(
            "cloud.key_freeze.enabled",
            FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED,
            R.string.cloud_key_freeze,
            introducedCatalogVersion = 67
        ),
        text(
            "cloud.key_freeze.rules",
            FeaturePreferences.CLOUD_KEY_FREEZE_RULES,
            R.string.cloud_key_freeze_rules,
            introducedCatalogVersion = 67
        ),
        bool(
            "cloud.key_freeze.observe",
            FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE,
            R.string.cloud_key_freeze_observe,
            introducedCatalogVersion = 67
        ),
        bool(
            "numbers.bv_as_av.enabled",
            FeaturePreferences.SHOW_BV_AS_AV,
            R.string.show_bv_as_av,
            introducedCatalogVersion = 11
        ),

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
        text(
            ID_MATERIAL_COLOR_SPEC,
            MaterialColorSpecStore.PREF_KEY,
            R.string.material_color_spec_title,
            default = MaterialColorSpec.DEFAULT.storageValue,
            allowed = setOf(
                MaterialColorSpec.SPEC_2021.storageValue,
                MaterialColorSpec.SPEC_2025.storageValue
            ),
            introducedCatalogVersion = 4,
            effects = setOf(ImportEffect.RECREATE_MODULE_UI)
        ),
        bool(
            "module_ui.appearance.panel_window_blur",
            ModalBackdropBlurStore.PREF_KEY,
            R.string.panel_window_blur_title,
            default = ModalBackdropBlurStore.DEFAULT,
            introducedCatalogVersion = 17,
            // 只影响模块界面自己的弹窗动画，不进宿主，不需要重启哔哩哔哩。
            effects = setOf(ImportEffect.RECREATE_MODULE_UI)
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

    /**
     * 按**偏好存储键**索引，供设置页读取默认值用（`ModuleUiSettings`）。
     *
     * 存储键的唯一性由下面的 `init` 保证，所以 `associateBy` 不会静默丢条目。
     * 有了这张表，"某个开关未设置时是什么"就只有目录一个来源——
     * 设置页原来把默认值抄在每个 `getBoolean(key, default)` 里，抄了 114 份。
     */
    val byStorageKey: Map<String, SettingSpec> = specs.associateBy(SettingSpec::storageKey)

    init {
        check(specs.size == 249) { "Expected 249 catalog settings, found ${specs.size}" }
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
