package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec

/** Explicit existing navigation destinations; this does not expand the backup whitelist. */
internal object SettingsActionCatalog {
    fun descriptors(): List<SettingDescriptor> = buildList {
        fun action(spec: SettingSpec, page: Int, section: String) {
            add(SettingDescriptor(spec.id, spec.storageKey, spec.labelRes, page, section,
                directToggle = false, favoriteEligible = false))
        }
        fun byId(id: String, page: Int, section: String) =
            action(requireNotNull(SettingsCatalog.byId[id]) { "Unknown UI destination: $id" }, page, section)
        fun byKey(key: String, page: Int, section: String) =
            action(requireNotNull(SettingsCatalog.byStorageKey[key]) { "Unknown UI destination key: $key" }, page, section)

        HomeRecommendFilterCatalog.preferenceKeys.forEach { byKey(it, 1, "PURIFICATION_ADVANCED") }

        listOf(
            SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT,
            SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT,
            SettingsCatalog.ID_RECOMMEND_VIDEO_PUBLISH_TIME_RULE,
        ).forEach { byId(it, 1, "PURIFICATION_ADVANCED") }
        listOf(
            FeaturePreferences.COMMENT_FILTER_KEYWORDS,
            FeaturePreferences.COMMENT_MIN_LEVEL,
            FeaturePreferences.COMMENT_USER_FILTER_RULES,
            FeaturePreferences.DANMAKU_WEIGHT_FILTER_MINIMUM,
            FeaturePreferences.SEARCH_FILTER_KEYWORDS,
            FeaturePreferences.SEARCH_AUTHOR_FILTER_RULES,
            FeaturePreferences.DYNAMIC_FILTER_KEYWORDS,
            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_RULES,
            FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS,
        ).forEach { byKey(it, 1, "PURIFICATION_ADVANCED") }
        listOf(
            SettingsCatalog.ID_DYNAMIC_SEMANTIC_RULES,
            SettingsCatalog.ID_DANMAKU_SEMANTIC_RULES,
            SettingsCatalog.ID_COMMENT_SEMANTIC_RULES,
            SettingsCatalog.ID_VIDEO_SEMANTIC_RULES,
        ).forEach { byId(it, 1, "PURIFICATION_ADVANCED") }
        SemanticSurface.entries.forEach { surface ->
            byId(SettingsCatalog.semanticRouteId(surface), 1, "PURIFICATION_ADVANCED")
            byId(SettingsCatalog.semanticCustomRulesId(surface), 1, "PURIFICATION_ADVANCED")
        }

        listOf(
            FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT,
            FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT,
            FeaturePreferences.PLAYER_BUFFER_MODE,
            FeaturePreferences.PLAYER_BUFFER_SECONDS,
            FeaturePreferences.PLAYER_BUFFER_MEMORY_MB,
            FeaturePreferences.PLAYER_BUFFER_DISK_MB,
            FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY,
            FeaturePreferences.CLOUD_KEY_FREEZE_RULES,
        ).forEach { byKey(it, 2, "ENHANCEMENT_ADVANCED") }
        if (FrameInterpolationEntryGate.entryOpen()) {
            byKey(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS, 2, "ENHANCEMENT_ADVANCED")
        }
        listOf(
            SettingsCatalog.ID_PLAYER_SPEED_MENU,
            "home.recommend.feedback_auto_confirm",
            SettingsCatalog.ID_AI_DECLARED_VIDEOS_STRONG_MODE,
            SettingsCatalog.ID_AI_DECLARED_VIDEOS_PRECHECK,
        ).forEach { byId(it, 2, "ENHANCEMENT_ADVANCED") }

        listOf(
            FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL,
            FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER,
            FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL,
            FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER,
            FeaturePreferences.HOST_CARD_MATERIAL,
            FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP,
            FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP,
            FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP,
            FeaturePreferences.HOST_BACKGROUND_PRESET,
        ).forEach { byKey(it, 3, "BEAUTIFICATION") }

        listOf(
            SettingsCatalog.ID_BILI_ACCESS_KEY_AUTHORIZED,
            SettingsCatalog.ID_SEMANTIC_JEV_ENDPOINT,
            SettingsCatalog.ID_SEMANTIC_JEV_SENSITIVITY,
            SettingsCatalog.ID_SEMANTIC_JEV_WAIT_FIRST_SCREEN,
            SettingsCatalog.ID_SEMANTIC_JEV_CACHE_DAYS,
            SettingsCatalog.ID_SEMANTIC_JEV_PROVIDER,
            SettingsCatalog.ID_SEMANTIC_JEV_MODEL,
            SettingsCatalog.ID_SEMANTIC_JEV_TIMEOUT_MS,
            SettingsCatalog.ID_SEMANTIC_JEV_GUIDANCE,
        ).forEach { byId(it, 4, "COMPATIBILITY") }
        (2..SemanticSource.MAX_SOURCES).forEach { index ->
            listOf("provider", "endpoint", "model").forEach { field ->
                byId("compat.semantic_source.$index.$field", 4, "COMPATIBILITY")
            }
        }
        byKey(AgentPreferences.ENABLED, 4, "COMPATIBILITY")
        byKey(HookEntry.PREF_LOG_LEVEL, 4, "GENERAL")
    }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) { "Duplicate action identity" } }
}
