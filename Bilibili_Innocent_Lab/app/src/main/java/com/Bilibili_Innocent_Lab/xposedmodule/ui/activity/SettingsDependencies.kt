package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings

/** UI availability only: saved child values and host configuration stay intact. */
internal object SettingsDependencies {
    // Model management is an action, not a backed-up preference or a search destination.
    const val FRAME_MODEL = "player.frame_interpolation.model"

    internal data class Requirement(val key: String, val enabled: Boolean = true)

    val requirements: Map<String, Requirement> = buildMap {
        fun children(parent: String, vararg ids: String) {
            ids.forEach { put(it, Requirement(parent)) }
        }
        children(FeaturePreferences.PLAYER_FRAME_INTERPOLATION,
            "player.frame_interpolation.max_fps", "player.frame_interpolation.status_toast", FRAME_MODEL)
        children(FeaturePreferences.SPONSORBLOCK_ENABLED, "player.sponsorblock.automatic")
        put("player.long_press_speed.percent", Requirement(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, false))
        children(FeaturePreferences.HIDE_MINE_VIP, "mine.vip.space_kept")
        children(HookEntry.PREF_LOG_ENABLED, "diagnostics.logging.level")
        children(FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED, "comments.keyword_filter.keywords")
        children(FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED, "comments.minimum_level_filter.level")
        children(FeaturePreferences.COMMENT_USER_FILTER_ENABLED, "comments.user_filter.rules")
        children(FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED, "player.danmaku.weight_filter.minimum")
        children(FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED, "dynamic.keyword_filter.keywords")
        children(FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED, "dynamic.author_filter.rules")
        children(FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED, "search.keyword_filter.keywords")
        children(FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED, "search.author_filter.rules")
        children(FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED, "home.recommend.title_filter.keywords")
        listOf(
            FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED to "comments",
            FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED to "player.danmaku",
            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED to "dynamic",
            FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED to "video"
        ).forEach { (key, prefix) ->
            children(key, "$prefix.semantic_filter.rules", "$prefix.semantic_filter.custom_rules",
                "$prefix.semantic_filter.source")
        }
    }

    val parentKeys: Set<String> = requirements.values.map { it.key }.toSet()

    fun available(id: String, settings: ModuleUiSettings): Boolean = requirements[id]?.let {
        settings.bool(it.key) == it.enabled
    } ?: true
}
