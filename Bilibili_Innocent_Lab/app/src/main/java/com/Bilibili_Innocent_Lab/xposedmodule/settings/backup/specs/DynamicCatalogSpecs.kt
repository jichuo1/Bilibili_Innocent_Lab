package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

internal object DynamicCatalogSpecs {

    val dynamicLeadSpecs: List<SettingSpec> = listOf(
        boolSpec("dynamic.city_tab.hidden", FeaturePreferences.HIDE_DYNAMIC_CITY_TAB, R.string.hide_dynamic_city_tab),
        boolSpec("dynamic.school_tab.hidden", FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB, R.string.hide_dynamic_school_tab),
        boolSpec("dynamic.video_tab.preferred", FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB, R.string.prefer_dynamic_video_tab),
        boolSpec(
            "dynamic.keyword_filter.enabled",
            FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED,
            R.string.dynamic_keyword_filter,
            introducedCatalogVersion = 12
        ),
        textSpec(
            "dynamic.keyword_filter.keywords",
            FeaturePreferences.DYNAMIC_FILTER_KEYWORDS,
            R.string.dynamic_keyword_rules,
            introducedCatalogVersion = 12
        ),
        boolSpec(
            "dynamic.author_filter.enabled",
            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED,
            R.string.dynamic_author_filter,
            introducedCatalogVersion = 12
        ),
        textSpec(
            "dynamic.author_filter.rules",
            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_RULES,
            R.string.dynamic_author_filter_rules,
            introducedCatalogVersion = 12
        ),
        boolSpec(
            SettingsCatalog.ID_DYNAMIC_SEMANTIC_FILTER,
            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED,
            R.string.dynamic_semantic_filter,
            introducedCatalogVersion = 31
        ),
        // 智能过滤：各面一个开关 + 一份勾选（v32）。勾选以预设 id 存储，未知 id 由宿主忽略。
        semanticRulesSpec(
            SettingsCatalog.ID_DYNAMIC_SEMANTIC_RULES,
            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_RULES,
            SemanticSurface.DYNAMIC
        )
    )

    val dynamicTailSpecs: List<SettingSpec> = listOf(
        boolSpec(
            "dynamic.promotions.removed",
            FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS,
            R.string.remove_dynamic_promotions,
            introducedCatalogVersion = 12
        ),
        boolSpec(
            "dynamic.charge_only.removed",
            FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY,
            R.string.remove_dynamic_charge_only,
            introducedCatalogVersion = 12
        ),
        boolSpec(
            "dynamic.frequent_visits.hidden",
            FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS,
            R.string.hide_dynamic_frequent_visits,
            introducedCatalogVersion = 15
        ),
        boolSpec(
            "dynamic.topic_list.hidden",
            FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST,
            R.string.hide_dynamic_topic_list,
            introducedCatalogVersion = 12
        ),
        boolSpec(
            "dynamic.up_list.live.removed",
            FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES,
            R.string.remove_dynamic_live_up_entries,
            introducedCatalogVersion = 12
        )
    )
}
