package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings

/** UI availability only: saved child values and host configuration stay intact. */
internal object SettingsDependencies {
    // Model management is an action, not a backed-up preference or a search destination.
    const val FRAME_MODEL = "player.frame_interpolation.model"

    /** Available while [key] (or any of [alternatives]) has the [enabled] value. */
    internal data class Requirement(
        val key: String,
        val enabled: Boolean = true,
        val alternatives: List<String> = emptyList()
    ) {
        val keys: List<String> get() = listOf(key) + alternatives
    }

    val requirements: Map<String, Requirement> = buildMap {
        fun children(parent: String, vararg ids: String) {
            ids.forEach { put(it, Requirement(parent)) }
        }
        children(FeaturePreferences.PLAYER_FRAME_INTERPOLATION,
            "player.frame_interpolation.max_fps", "player.frame_interpolation.status_toast",
            "player.frame_interpolation.disable_thermal_control", FRAME_MODEL)
        children(FeaturePreferences.SPONSORBLOCK_ENABLED, "player.sponsorblock.automatic", "player.sponsorblock.story")
        children(FeaturePreferences.AI_INSIGHT_ENABLED, "compat.ai_insight.auto_chapters")
        // Onboarding is skipped inside the guest installer, which either browsing mode starts.
        put("browsing.guest.skip_onboarding",
            Requirement(FeaturePreferences.NO_LOGIN_ENABLED, alternatives = listOf(FeaturePreferences.INCOGNITO_ENABLED)))
        put("player.long_press_speed.percent", Requirement(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, false))
        children(FeaturePreferences.HIDE_MINE_VIP, "mine.vip.space_kept")
        children(FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, "host.mine.garb_dim.enabled")
        children(HookEntry.PREF_LOG_ENABLED, "diagnostics.logging.level")
        children(FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED, "comments.keyword_filter.keywords")
        children(FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED, "comments.minimum_level_filter.level")
        children(FeaturePreferences.COMMENT_USER_FILTER_ENABLED, "comments.user_filter.rules")
        children(FeaturePreferences.COMMENT_FISH_FILTER_ENABLED, "comments.fish_filter.sensitivity",
            "comments.fish_filter.nickname_words", "comments.fish_filter.text_words",
            "comments.fish_filter.whitelist", "comments.fish_filter.protect_high_level", "comments.fish_filter.strong_mode")
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

    val parentKeys: Set<String> = requirements.values.flatMap { it.keys }.toSet()

    fun available(id: String, settings: ModuleUiSettings): Boolean = requirements[id]?.let {
        it.keys.any { key -> settings.bool(key) == it.enabled }
    } ?: true
}
