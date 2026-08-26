package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DynamicTabsFeatureInstallerTest {

    @Test
    fun `classifies city and school by title without touching regular tabs`() {
        assertEquals(
            DynamicTabsFeatureInstaller.Companion.HiddenKind.CITY,
            DynamicTabsFeatureInstaller.hiddenKind("同城", "unknown", true, true)
        )
        assertEquals(
            DynamicTabsFeatureInstaller.Companion.HiddenKind.SCHOOL,
            DynamicTabsFeatureInstaller.hiddenKind("校园", null, true, true)
        )
        assertNull(DynamicTabsFeatureInstaller.hiddenKind("视频", "video", true, true))
    }

    @Test
    fun `respects independent switches and stable name fallbacks`() {
        assertNull(DynamicTabsFeatureInstaller.hiddenKind("同城", "city", false, true))
        assertEquals(
            DynamicTabsFeatureInstaller.Companion.HiddenKind.CITY,
            DynamicTabsFeatureInstaller.hiddenKind(null, "same_city", true, false)
        )
        assertEquals(
            DynamicTabsFeatureInstaller.Companion.HiddenKind.SCHOOL,
            DynamicTabsFeatureInstaller.hiddenKind(null, "campus", false, true)
        )
    }

    @Test
    fun `matches only the exact video tab label`() {
        assertEquals(true, DynamicTabsFeatureInstaller.isVideoTab("视频", null))
        assertEquals(true, DynamicTabsFeatureInstaller.isVideoTab(" 视频 ", null))
        assertEquals(true, DynamicTabsFeatureInstaller.isVideoTab(null, "VIDEO"))
        assertEquals(false, DynamicTabsFeatureInstaller.isVideoTab("视频号", null))
        assertEquals(false, DynamicTabsFeatureInstaller.isVideoTab(null, null))
    }

    @Test
    fun `prefers only video after adapter confirms the tab exists`() {
        assertEquals(
            true,
            DynamicTabsFeatureInstaller.selectedForVideoPreference(
                "视频", videoAvailable = true, preferVideo = true, originalSelected = false
            )
        )
        assertEquals(
            false,
            DynamicTabsFeatureInstaller.selectedForVideoPreference(
                "综合", videoAvailable = true, preferVideo = true, originalSelected = true
            )
        )
        assertEquals(
            true,
            DynamicTabsFeatureInstaller.selectedForVideoPreference(
                "综合", videoAvailable = false, preferVideo = true, originalSelected = true
            )
        )
    }
}
