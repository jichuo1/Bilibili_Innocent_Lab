package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences

internal enum class PortraitContentFilterGroup {
    HOME,
    STORY,
    SERIES
}

internal data class PortraitContentFilterOption(
    val preferenceKey: String,
    val group: PortraitContentFilterGroup,
    val coveredByKey: String? = null
)

/**
 * 竖屏内容过滤仍以已有 13 个布尔偏好为唯一数据源；此目录只负责稳定的 UI 顺序与覆盖关系。
 */
internal object PortraitContentFilterCatalog {
    val options = listOf(
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL,
            PortraitContentFilterGroup.HOME
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_ADS,
            PortraitContentFilterGroup.STORY
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_LIVE,
            PortraitContentFilterGroup.STORY
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_GAMES,
            PortraitContentFilterGroup.STORY
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_COURSES,
            PortraitContentFilterGroup.STORY
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_SHORT_DRAMA,
            PortraitContentFilterGroup.STORY
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_SHOPPING,
            PortraitContentFilterGroup.STORY
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_MUSIC,
            PortraitContentFilterGroup.STORY
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_BANGUMI,
            PortraitContentFilterGroup.SERIES
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_MOVIES,
            PortraitContentFilterGroup.SERIES,
            FeaturePreferences.REMOVE_STORY_BANGUMI
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_DOCUMENTARIES,
            PortraitContentFilterGroup.SERIES,
            FeaturePreferences.REMOVE_STORY_BANGUMI
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_TV,
            PortraitContentFilterGroup.SERIES,
            FeaturePreferences.REMOVE_STORY_BANGUMI
        ),
        PortraitContentFilterOption(
            FeaturePreferences.REMOVE_STORY_VARIETY,
            PortraitContentFilterGroup.SERIES,
            FeaturePreferences.REMOVE_STORY_BANGUMI
        )
    )
}

internal class PortraitContentFilterDraft(initialValues: Map<String, Boolean>) {
    private val initial = PortraitContentFilterCatalog.options.associate { option ->
        option.preferenceKey to (initialValues[option.preferenceKey] == true)
    }
    private val current = initial.toMutableMap()

    operator fun get(preferenceKey: String): Boolean = current[preferenceKey] == true

    operator fun set(preferenceKey: String, enabled: Boolean) {
        require(preferenceKey in current) { "Unknown portrait filter key: $preferenceKey" }
        current[preferenceKey] = enabled
    }

    fun isCovered(option: PortraitContentFilterOption): Boolean =
        option.coveredByKey?.let { current[it] == true } == true

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
