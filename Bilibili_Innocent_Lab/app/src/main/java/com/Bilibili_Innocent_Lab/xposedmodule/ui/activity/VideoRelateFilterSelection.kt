package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences

internal data class VideoRelateFilterOption(
    val preferenceKey: String,
    val isMatchingEnhancement: Boolean = false
)

/** 相关推荐勾选面板的稳定顺序；前五项沿用既有偏好，匹配增强固定在末尾。 */
internal object VideoRelateFilterCatalog {
    val contentOptions = listOf(
        VideoRelateFilterOption(FeaturePreferences.REMOVE_RELATE_COMMERCIAL),
        VideoRelateFilterOption(FeaturePreferences.REMOVE_RELATE_GAME),
        VideoRelateFilterOption(FeaturePreferences.REMOVE_RELATE_LIVE),
        VideoRelateFilterOption(FeaturePreferences.REMOVE_RELATE_COURSE),
        VideoRelateFilterOption(FeaturePreferences.REMOVE_RELATE_SPECIAL)
    )

    val panelOptions = contentOptions + VideoRelateFilterOption(
        FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
        isMatchingEnhancement = true
    )
}

/** 弹窗草稿：只有确认保存才生成变化集；隐藏推荐理由区域不会清除关键词。 */
internal class VideoRelateFilterDraft(
    initialValues: Map<String, Boolean>,
    initialKeywords: String
) {
    private val supportedKeys = (
        VideoRelateFilterCatalog.panelOptions.map(VideoRelateFilterOption::preferenceKey) +
            FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED
        ).toSet()
    private val initial = supportedKeys.associateWith { initialValues[it] == true }
    private val current = initial.toMutableMap()
    private val initialKeywords = initialKeywords.take(MAX_KEYWORDS_LENGTH)

    var reasonKeywords: String = this.initialKeywords
        set(value) {
            field = value.take(MAX_KEYWORDS_LENGTH)
        }

    val reasonFilterVisible: Boolean
        get() = this[FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED]

    val keywordEditorVisible: Boolean
        get() = reasonFilterVisible &&
            this[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED]

    operator fun get(preferenceKey: String): Boolean = current[preferenceKey] == true

    operator fun set(preferenceKey: String, enabled: Boolean) {
        require(preferenceKey in current) { "Unknown video relate filter key: $preferenceKey" }
        current[preferenceKey] = enabled
    }

    fun selectedContentCount(): Int = VideoRelateFilterCatalog.contentOptions.count {
        current[it.preferenceKey] == true
    }

    fun selectAll() {
        VideoRelateFilterCatalog.panelOptions.forEach { current[it.preferenceKey] = true }
    }

    fun clear() {
        current.keys.forEach { current[it] = false }
    }

    fun changedValues(): Map<String, Boolean> = current.filter { (key, value) ->
        initial[key] != value
    }

    fun keywordsChanged(): Boolean = reasonKeywords != initialKeywords

    companion object {
        const val MAX_KEYWORDS_LENGTH = 4_096
    }
}
