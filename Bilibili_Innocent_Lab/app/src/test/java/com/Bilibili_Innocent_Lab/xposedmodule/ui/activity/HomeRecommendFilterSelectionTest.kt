package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import org.junit.Assert.*
import org.junit.Test

class HomeRecommendFilterSelectionTest {
    @Test
    fun `catalog preserves the eight independent preference keys in display order`() {
        assertEquals(listOf(
            FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE
        ), HomeRecommendFilterCatalog.preferenceKeys)
        assertEquals(8, HomeRecommendFilterCatalog.preferenceKeys.distinct().size)
    }

    @Test
    fun `opening and canceling a draft leaves the initial settings untouched`() {
        val initial = mutableMapOf(
            FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS to true,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2 to true,
            FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED to true
        )
        val before = initial.toMap()
        val draft = HomeRecommendFilterDraft(initial)
        assertEquals(1, draft.selectedCount())
        assertTrue(draft.changedValues().isEmpty())
        draft.selectAll()
        draft.clear()
        assertEquals(before, initial)
        assertEquals(1, HomeRecommendFilterDraft(initial).selectedCount())
    }

    @Test
    fun `select all and clear produce only changes to panel keys`() {
        val ads = FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS
        val initial = mapOf(ads to true, FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL to true)
        val draft = HomeRecommendFilterDraft(initial)
        draft.selectAll()
        assertEquals(8, draft.selectedCount())
        assertEquals(HomeRecommendFilterCatalog.preferenceKeys.filterNot { it == ads }
            .associateWith { true }, draft.changedValues())
        draft.clear()
        assertEquals(0, draft.selectedCount())
        assertEquals(mapOf(ads to false), draft.changedValues())
        assertFalse(draft.changedValues().containsKey(FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL))
    }

    @Test
    fun `toggling back to the original value does not write a preference`() {
        val key = FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES
        val draft = HomeRecommendFilterDraft(emptyMap())
        draft[key] = true
        assertEquals(mapOf(key to true), draft.changedValues())
        draft[key] = false
        assertTrue(draft.changedValues().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the disguised ad switch cannot be changed through this panel`() {
        HomeRecommendFilterDraft(emptyMap())[FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2] = true
    }

    @Test
    fun `a saved change set is detached from further draft edits`() {
        val key = FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE
        val draft = HomeRecommendFilterDraft(emptyMap())
        draft[key] = true
        val saved = draft.changedValues()
        draft.clear()
        assertEquals(mapOf(key to true), saved)
        assertTrue(draft.changedValues().isEmpty())
    }
}
