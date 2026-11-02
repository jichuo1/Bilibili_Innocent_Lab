package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.HookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences as Keys
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityConfig

/** Audited optional consumers only. Unknown/shared families retain the original full path. */
internal object StartupAdapterPlan {
    enum class Family {
        DYNAMIC_TABS, FULL_NUMBERS, PLAYER_PORTRAIT, PLAYER_INTERACTIVE, PGC_POPUP, PLAYER_STATUS_BAR,
        PLAYER_QUALITY, TEENAGERS, COMMENT_PURIFY, COMMENT_FILTER, COMMENT_TOPOLOGY, COMMENT_SECTION, SPLASH,
        /** Main-process-only consumer; configuration dependencies remain full in the main process. */
        VIDEO_RELATE
    }

    private val switches = mapOf(
        Family.DYNAMIC_TABS to listOf(Keys.HIDE_DYNAMIC_CITY_TAB, Keys.HIDE_DYNAMIC_SCHOOL_TAB, Keys.PREFER_DYNAMIC_VIDEO_TAB),
        Family.FULL_NUMBERS to listOf(Keys.SHOW_FULL_NUMBERS),
        Family.PLAYER_PORTRAIT to listOf(Keys.HIDE_PLAYER_PORTRAIT_CONTROL),
        Family.PLAYER_INTERACTIVE to listOf(Keys.HIDE_PLAYER_INTERACTIVE_OVERLAYS),
        Family.PGC_POPUP to listOf(Keys.HIDE_PGC_AUTO_ACTIVITY_POPUP),
        Family.PLAYER_STATUS_BAR to listOf(Keys.TRANSPARENT_PLAYER_STATUS_BAR),
        Family.TEENAGERS to listOf(Keys.BLOCK_TEENAGERS_MODE_PROMPT),
        Family.COMMENT_PURIFY to listOf(Keys.REMOVE_COMMENT_SEARCH_LINKS, Keys.REMOVE_COMMENT_EMPTY_GUIDE,
            Keys.REMOVE_COMMENT_VOTE_WIDGETS, Keys.REMOVE_COMMENT_FOLLOW_BUTTONS, Keys.REMOVE_COMMENT_QOE,
            Keys.REMOVE_COMMENT_OPERATIONS, Keys.BLOCK_COMMENT_QUICK_REPLY),
        Family.COMMENT_FILTER to listOf(Keys.COMMENT_KEYWORD_FILTER_ENABLED, Keys.COMMENT_MIN_LEVEL_FILTER_ENABLED,
            Keys.COMMENT_USER_FILTER_ENABLED, Keys.REMOVE_AT_ONLY_COMMENTS, Keys.COMMENT_SEMANTIC_FILTER_ENABLED),
        Family.COMMENT_TOPOLOGY to listOf(Keys.REPLY_TOPOLOGY_ENABLED),
        Family.COMMENT_SECTION to listOf(Keys.HIDE_COMMENT_SECTION),
        Family.SPLASH to listOf(Keys.PURIFY_SPLASH_ADS)
    )

    fun excluded(config: HookConfigSource): Set<String> {
        val requested = switches.filterValues { keys -> keys.any { config.getBoolean(it, false) } }.keys.toMutableSet()
        if (PlayerQualityConfig.normalize(config.getInt(Keys.PLAYER_DEFAULT_QUALITY_QN, 0)) != 0)
            requested += Family.PLAYER_QUALITY
        // Topology shares ReplyInfo/member roles; retaining this preparation is conservative even
        // when the standalone filter is off. Copy's low/high bind families are never excluded here.
        if (Family.COMMENT_TOPOLOGY in requested) requested += Family.COMMENT_FILTER
        val auditedOptional = switches.keys + Family.PLAYER_QUALITY
        return (auditedOptional - requested).mapTo(linkedSetOf()) { it.name }
    }

    fun excludedForProcess(config: HookConfigSource, processName: String, nPatchRuntime: Boolean): Set<String> {
        if (!nPatchRuntime) return emptySet()
        return when (processName) {
            "tv.danmaku.bili" -> excluded(config)
            // These families' installers already return non-main-process before using their points.
            // Retain all other families and common copy/Banner/game/merchandise registration.
            "tv.danmaku.bili:web", "tv.danmaku.bili:download", "tv.danmaku.bili:ijkservice" ->
                Family.entries.mapTo(linkedSetOf()) { it.name }
            else -> emptySet()
        }
    }

    fun diagnosticFamily(id: String): Family? = when {
        id == "dynamic.tabs" -> Family.DYNAMIC_TABS
        id == "number.full" -> Family.FULL_NUMBERS
        id == "player.portrait" -> Family.PLAYER_PORTRAIT
        id == "player.interactive_overlay" -> Family.PLAYER_INTERACTIVE
        id == "pgc.auto_activity_popup" -> Family.PGC_POPUP
        id == "player.status_bar" -> Family.PLAYER_STATUS_BAR
        id in setOf("player.default_quality", "dex.assist.player_quality", "dex.assist.story_quality") -> Family.PLAYER_QUALITY
        id == "teenagers.mode" -> Family.TEENAGERS
        id.startsWith("comment.purify.") || id == "comment.quick_reply" -> Family.COMMENT_PURIFY
        id == "comment.filter" -> Family.COMMENT_FILTER
        id in setOf("comment.topology", "dex.assist.comment_topology") -> Family.COMMENT_TOPOLOGY
        id == "comment.section" -> Family.COMMENT_SECTION
        id == "splash.ads" -> Family.SPLASH
        id == "video.relate" -> Family.VIDEO_RELATE
        else -> null
    }
}
