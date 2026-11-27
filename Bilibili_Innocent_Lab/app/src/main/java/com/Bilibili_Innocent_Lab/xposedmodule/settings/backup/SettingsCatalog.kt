package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs.CommentCatalogSpecs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs.DynamicCatalogSpecs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs.HomeCatalogSpecs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs.HostUiCatalogSpecs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs.PlayerCatalogSpecs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs.PrivacyAndCompatCatalogSpecs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs.SystemAndAgentCatalogSpecs

/**
 * 可备份用户意图的唯一白名单。
 *
 * 这里故意不包含缓存、时间戳、自由复制修订号、更新检查节流、应用语言和桌面图标状态。
 * 新增设置必须显式登记；禁止改为导出 prefs.all。
 */
internal object SettingsCatalog {
    const val PRODUCT_ID = "bilibili-innocent-lab.settings"
    const val SCOPE_ID = "core-user-settings"
    const val CATALOG_VERSION = 86
    private val LOGICAL_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    const val ID_PLAYER_DEFAULT_SPEED = "player.default_speed.percent"
    const val ID_PLAYER_LONG_PRESS_SPEED = "player.long_press_speed.percent"
    const val ID_PLAYER_SPEED_MENU = "player.speed_menu.percents"
    const val ID_FREE_COPY_COMMENT = "free_copy.comment.enabled"
    const val ID_FREE_COPY_DESCRIPTION = "free_copy.description.enabled"
    const val ID_FREE_COPY_TITLE = "free_copy.title.enabled"
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
    const val ID_COMMENT_FISH_FILTER = "comments.fish_filter.enabled"
    const val ID_COMMENT_FISH_SENSITIVITY = "comments.fish_filter.sensitivity"
    const val ID_COMMENT_FISH_STRONG_MODE = "comments.fish_filter.strong_mode"
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
    const val ID_AI_INSIGHT_PROTOCOL = "ai_insight.protocol"
    const val ID_AI_INSIGHT_BASE_URL = "ai_insight.base_url"
    const val ID_AI_INSIGHT_AUTO_V1 = "ai_insight.auto_v1"
    const val ID_HOST_BOTTOM_BAR_BADGE_ACCENT = "host.bottom_bar.badge_accent.enabled"
    const val ID_AI_INSIGHT_MODEL = "ai_insight.model"
    const val ID_HOST_PREDICTIVE_BACK = "compat.host_predictive_back.enabled"

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

    val specs: List<SettingSpec> =
        SystemAndAgentCatalogSpecs.adsLeadSpecs +
            PlayerCatalogSpecs.endPageRecommendSpecs +
            PrivacyAndCompatCatalogSpecs.communicationAndAccessSpecs +
            SystemAndAgentCatalogSpecs.agentSpecs +
            PrivacyAndCompatCatalogSpecs.aiInsightAndSemanticLeadSpecs +
            SystemAndAgentCatalogSpecs.homeAdsSpecs +
            HomeCatalogSpecs.homeFeedAndMineSpecs +
            SystemAndAgentCatalogSpecs.clientUpdateSpecs +
            DynamicCatalogSpecs.dynamicLeadSpecs +
            PrivacyAndCompatCatalogSpecs.surfaceSemanticToggleSpecs +
            DynamicCatalogSpecs.dynamicTailSpecs +
            HomeCatalogSpecs.searchSpecs +
            SystemAndAgentCatalogSpecs.fullNumbersSpecs +
            PlayerCatalogSpecs.playbackAndDanmakuLiveSpecs +
            HomeCatalogSpecs.detailAndRelatedSpecs +
            PlayerCatalogSpecs.storySpecs +
            HostUiCatalogSpecs.hostNavigationAndSurfaceSpecs +
            HomeCatalogSpecs.recommendMetricSpecs +
            PlayerCatalogSpecs.qualityAndBufferSpecs +
            SystemAndAgentCatalogSpecs.componentLibraryAndTeenagersSpecs +
            PlayerCatalogSpecs.capabilityAudioInterpolationAndSponsorSpecs +
            CommentCatalogSpecs.commentSpecs +
            SystemAndAgentCatalogSpecs.splashShareAndSystemSpecs +
            HomeCatalogSpecs.homeLoadingSpecs +
            SystemAndAgentCatalogSpecs.cloudKeyFreezeAndBvSpecs +
            PrivacyAndCompatCatalogSpecs.browsingAndIncognitoSpecs +
            CommentCatalogSpecs.freeCopySpecs +
            PrivacyAndCompatCatalogSpecs.roamingCompatSpecs +
            HostUiCatalogSpecs.moduleUiSpecs +
            SystemAndAgentCatalogSpecs.diagnosticsLoggingSpecs +
            PrivacyAndCompatCatalogSpecs.aiInsightServiceSpecs +
            HostUiCatalogSpecs.hostBottomBarBadgeAccentSpecs +
            CommentCatalogSpecs.freeCopyTitleSpecs +
            SystemAndAgentCatalogSpecs.systemEnvDetectionSpecs +
            PrivacyAndCompatCatalogSpecs.hostPredictiveBackSpecs

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
        check(specs.size == 281) { "Expected 281 catalog settings, found ${specs.size}" }
        check(byId.size == specs.size) { "Duplicate logical setting id" }
        check(byStorageKey.size == specs.size) {
            "Duplicate settings storage key"
        }
        check(specs.all { LOGICAL_ID_PATTERN.matches(it.id) }) {
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
