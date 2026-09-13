package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class RecommendationSettingsPlacementTest {
    @Test fun feedbackAndRulesAreGroupedInEnhancementBrowsing() {
        val enhance = SettingsUiSource.function("enhanceBrowsingCategory")
        val purify = SettingsUiSource.function("purifyHomeCategory")
        val labels = listOf("home_recommend_section_pick", "home_recommend_blocked_tids",
            "home_recommend_blocked_authors", "recommendation_blocklist_manage")
        val positions = labels.map { label ->
            val position = enhance.indexOf("R.string.$label")
            assertTrue(label, position >= 0)
            assertFalse(label, purify.contains("R.string.$label"))
            position
        }
        assertEquals(positions.sorted(), positions)
        assertTrue(enhance.contains("showRecommendationBlocklistDialog(anchor = it)"))
    }

    @Test fun manualEditorsDoNotAutomaticallyReimportHostSelections() {
        val enhance = SettingsUiSource.function("enhanceBrowsingCategory")
        assertFalse(enhance.contains("prefilledBlocked"))
        assertTrue(enhance.contains("prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS"))
        assertTrue(enhance.contains("prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS"))
        assertFalse(Regex("putString\\(\\s*FeaturePreferences.HOME_RECOMMEND_BLOCKED_\\w+,\\s*recommendationRuleCount").containsMatchIn(enhance))
    }
}
