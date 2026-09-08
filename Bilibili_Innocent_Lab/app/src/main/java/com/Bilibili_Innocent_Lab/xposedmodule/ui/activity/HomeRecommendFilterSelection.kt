package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences

/** 首页推荐面板的独立偏好白名单；伪装广告、标题关键词和竖屏过滤保持独立。 */
internal object HomeRecommendFilterCatalog {
    val preferenceKeys = listOf(
        FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES,
        FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE
    )
}

/** 勾选仅修改弹窗草稿；保存时只返回白名单键中的变化项。 */
internal class HomeRecommendFilterDraft(initialValues: Map<String, Boolean>) {
    private val initial = HomeRecommendFilterCatalog.preferenceKeys.associateWith {
        initialValues[it] == true
    }
    private val current = initial.toMutableMap()

    operator fun get(preferenceKey: String): Boolean = current[preferenceKey] == true

    operator fun set(preferenceKey: String, enabled: Boolean) {
        require(preferenceKey in current) { "Unknown home recommendation filter key: $preferenceKey" }
        current[preferenceKey] = enabled
    }

    fun selectedCount(): Int = current.values.count { it }

    fun selectAll() {
        current.keys.forEach { current[it] = true }
    }

    fun clear() {
        current.keys.forEach { current[it] = false }
    }

    fun changedValues(): Map<String, Boolean> = current.filter { (key, value) ->
        initial[key] != value
    }
}
