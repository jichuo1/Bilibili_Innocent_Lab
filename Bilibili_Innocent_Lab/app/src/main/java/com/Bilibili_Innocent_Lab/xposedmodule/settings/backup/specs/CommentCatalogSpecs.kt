package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportEffect
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

internal object CommentCatalogSpecs {

    val commentSpecs: List<SettingSpec> = listOf(
        boolSpec("comments.search_links.removed", FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS, R.string.remove_comment_search_links),
        boolSpec("comments.empty_guide.removed", FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE, R.string.remove_comment_empty_guide),
        boolSpec("comments.vote_widgets.removed", FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS, R.string.remove_comment_vote_widgets),
        boolSpec("comments.follow_buttons.removed", FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS, R.string.remove_comment_follow_buttons),
        boolSpec("comments.qoe.removed", FeaturePreferences.REMOVE_COMMENT_QOE, R.string.remove_comment_qoe),
        boolSpec("comments.operations.removed", FeaturePreferences.REMOVE_COMMENT_OPERATIONS, R.string.remove_comment_operations),
        boolSpec(
            "comments.reply_button.removed",
            FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON,
            R.string.remove_comment_reply_button,
            introducedCatalogVersion = 53
        ),
        boolSpec("comments.quick_reply.blocked", FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY, R.string.block_comment_quick_reply),
        boolSpec("comments.section.hidden", FeaturePreferences.HIDE_COMMENT_SECTION, R.string.hide_comment_section),
        boolSpec("comments.reply_topology.enabled", FeaturePreferences.REPLY_TOPOLOGY_ENABLED, R.string.reply_topology_enabled),
        boolSpec(
            "comments.classic_style.enabled",
            FeaturePreferences.COMMENT_CLASSIC_STYLE,
            R.string.comment_classic_style,
            introducedCatalogVersion = 48
        ),
        boolSpec("comments.keyword_filter.enabled", FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED, R.string.comment_keyword_filter),
        textSpec("comments.keyword_filter.keywords", FeaturePreferences.COMMENT_FILTER_KEYWORDS, R.string.comment_keyword_rules),
        boolSpec("comments.minimum_level_filter.enabled", FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED, R.string.comment_min_level_filter),
        intSpec(
            "comments.minimum_level_filter.level",
            FeaturePreferences.COMMENT_MIN_LEVEL,
            R.string.comment_min_level_dialog_title,
            default = CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL,
            range = 1..6
        ),
        boolSpec(
            "comments.at_only.removed",
            FeaturePreferences.REMOVE_AT_ONLY_COMMENTS,
            R.string.remove_at_only_comments,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            "comments.user_filter.enabled",
            FeaturePreferences.COMMENT_USER_FILTER_ENABLED,
            R.string.comment_user_filter,
            introducedCatalogVersion = 11
        ),
        textSpec(
            "comments.user_filter.rules",
            FeaturePreferences.COMMENT_USER_FILTER_RULES,
            R.string.comment_user_filter_rules,
            introducedCatalogVersion = 11
        ),
        boolSpec(
            SettingsCatalog.ID_COMMENT_FISH_FILTER,
            FeaturePreferences.COMMENT_FISH_FILTER_ENABLED,
            R.string.comment_fish_filter,
            introducedCatalogVersion = 76
        ),
        boolSpec(
            SettingsCatalog.ID_COMMENT_FISH_STRONG_MODE,
            FeaturePreferences.COMMENT_FISH_STRONG_MODE,
            R.string.comment_fish_strong_mode,
            introducedCatalogVersion = 77
        ),
        textSpec(
            SettingsCatalog.ID_COMMENT_FISH_SENSITIVITY,
            FeaturePreferences.COMMENT_FISH_SENSITIVITY,
            R.string.comment_fish_sensitivity,
            default = FishScorePolicy.Sensitivity.DEFAULT.id,
            allowed = FishScorePolicy.Sensitivity.IDS,
            introducedCatalogVersion = 76
        ),
        textSpec(
            "comments.fish_filter.nickname_words",
            FeaturePreferences.COMMENT_FISH_NICKNAME_WORDS,
            R.string.comment_fish_nickname_words,
            introducedCatalogVersion = 76
        ),
        textSpec(
            "comments.fish_filter.text_words",
            FeaturePreferences.COMMENT_FISH_TEXT_WORDS,
            R.string.comment_fish_text_words,
            introducedCatalogVersion = 76
        ),
        textSpec(
            "comments.fish_filter.whitelist",
            FeaturePreferences.COMMENT_FISH_WHITELIST,
            R.string.comment_fish_whitelist,
            introducedCatalogVersion = 76
        ),
        boolSpec(
            "comments.fish_filter.protect_high_level",
            FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL,
            R.string.comment_fish_protect_high_level,
            default = true,
            introducedCatalogVersion = 76
        )
    )

    val freeCopySpecs: List<SettingSpec> = listOf(
        boolSpec(
            SettingsCatalog.ID_FREE_COPY_COMMENT,
            HookEntry.PREF_FREE_COPY_ENABLED,
            R.string.free_copy_enable,
            default = true,
            effects = setOf(
                ImportEffect.REBUILD_FREE_COPY_MIRROR,
                ImportEffect.RECREATE_MODULE_UI,
                ImportEffect.RESTART_BILIBILI
            )
        ),
        boolSpec(
            SettingsCatalog.ID_FREE_COPY_DESCRIPTION,
            HookEntry.PREF_FREE_COPY_DESC_ENABLED,
            R.string.free_copy_desc_enable,
            default = true,
            effects = setOf(
                ImportEffect.REBUILD_FREE_COPY_MIRROR,
                ImportEffect.RECREATE_MODULE_UI,
                ImportEffect.RESTART_BILIBILI
            )
        ),
        boolSpec("free_copy.light_mode.enabled", HookEntry.PREF_FREE_COPY_LIGHT_MODE, R.string.free_copy_light_mode),
        boolSpec("free_copy.auto_light.enabled", HookEntry.PREF_FREE_COPY_AUTO_LIGHT, R.string.free_copy_auto_light)
    )

    val freeCopyTitleSpecs: List<SettingSpec> = listOf(
        // v80：视频详情页标题长按自由复制。默认关闭，现有行为不变；改动需重启宿主。
        boolSpec(
            SettingsCatalog.ID_FREE_COPY_TITLE,
            FeaturePreferences.FREE_COPY_TITLE_ENABLED,
            R.string.free_copy_video_title_enable,
            default = false,
            introducedCatalogVersion = 80
        )
    )
}
