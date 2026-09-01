package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRelateFilterSelectionTest {

    @Test
    fun `catalog keeps existing filters and matching enhancement last`() {
        assertEquals(5, VideoRelateFilterCatalog.contentOptions.size)
        assertEquals(6, VideoRelateFilterCatalog.panelOptions.size)
        assertEquals(
            6,
            VideoRelateFilterCatalog.panelOptions.map { it.preferenceKey }.distinct().size
        )
        assertEquals(
            FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
            VideoRelateFilterCatalog.panelOptions.last().preferenceKey
        )
        assertTrue(VideoRelateFilterCatalog.panelOptions.last().isMatchingEnhancement)
    }

    @Test
    fun `reason controls follow enhancement without losing keywords`() {
        val draft = VideoRelateFilterDraft(emptyMap(), "商业推广\n去小程序")

        assertFalse(draft.reasonFilterVisible)
        assertFalse(draft.strongModeVisible)
        draft[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED] = true
        assertFalse(draft.keywordEditorVisible)

        draft[FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED] = true
        assertTrue(draft.reasonFilterVisible)
        assertTrue(draft.strongModeVisible)
        assertTrue(draft.keywordEditorVisible)

        draft[FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED] = false
        assertEquals("商业推广\n去小程序", draft.reasonKeywords)
        assertFalse(draft.keywordEditorVisible)
    }

    @Test
    fun `strong mode requires explicit selection while clear disables it`() {
        val draft = VideoRelateFilterDraft(
            mapOf(FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED to true),
            ""
        )

        draft.selectAll()
        assertTrue(draft[FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED])
        assertTrue(draft[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED])

        val fresh = VideoRelateFilterDraft(emptyMap(), "")
        fresh.selectAll()
        assertFalse(fresh[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED])

        draft.clear()
        assertFalse(draft[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED])
    }

    @Test
    fun `changed values and keywords remain draft only`() {
        val commercial = FeaturePreferences.REMOVE_RELATE_COMMERCIAL
        val draft = VideoRelateFilterDraft(mapOf(commercial to true), "广告")

        draft[commercial] = false
        draft[FeaturePreferences.REMOVE_RELATE_GAME] = true
        draft.reasonKeywords = "推广"

        assertEquals(
            mapOf(
                commercial to false,
                FeaturePreferences.REMOVE_RELATE_GAME to true
            ),
            draft.changedValues()
        )
        assertEquals(1, draft.selectedContentCount())
        assertTrue(draft.keywordsChanged())
    }
}
