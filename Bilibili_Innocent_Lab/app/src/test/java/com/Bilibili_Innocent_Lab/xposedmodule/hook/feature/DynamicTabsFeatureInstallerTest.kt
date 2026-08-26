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
}
