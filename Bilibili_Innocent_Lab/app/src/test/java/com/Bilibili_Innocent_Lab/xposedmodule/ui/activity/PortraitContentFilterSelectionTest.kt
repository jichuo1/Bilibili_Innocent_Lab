package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitContentFilterSelectionTest {

    @Test
    fun `catalog keeps thirteen unique existing preference keys`() {
        val options = PortraitContentFilterCatalog.options

        assertEquals(13, options.size)
        assertEquals(13, options.map { it.preferenceKey }.distinct().size)
        assertEquals(
            mapOf(
                PortraitContentFilterGroup.HOME to 1,
                PortraitContentFilterGroup.STORY to 7,
                PortraitContentFilterGroup.SERIES to 5
            ),
            options.groupingBy { it.group }.eachCount()
        )
    }

    @Test
    fun `all series covers children without erasing their draft values`() {
        val movieKey = FeaturePreferences.REMOVE_STORY_MOVIES
        val parentKey = FeaturePreferences.REMOVE_STORY_BANGUMI
        val movie = PortraitContentFilterCatalog.options.first { it.preferenceKey == movieKey }
        val draft = PortraitContentFilterDraft(mapOf(movieKey to true))

        draft[parentKey] = true
        assertTrue(draft.isCovered(movie))
        assertTrue(draft[movieKey])

        draft[parentKey] = false
        assertFalse(draft.isCovered(movie))
        assertTrue(draft[movieKey])
    }

    @Test
    fun `changed values contain only explicit draft differences`() {
        val adsKey = FeaturePreferences.REMOVE_STORY_ADS
        val liveKey = FeaturePreferences.REMOVE_STORY_LIVE
        val draft = PortraitContentFilterDraft(mapOf(adsKey to true, liveKey to false))

        draft[adsKey] = false
        draft[liveKey] = true

        assertEquals(mapOf(adsKey to false, liveKey to true), draft.changedValues())
        assertEquals(1, draft.selectedCount())
    }
}
