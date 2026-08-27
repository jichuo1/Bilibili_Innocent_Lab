package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryPurifyFeatureInstallerTest {

    @Test
    fun `filters only explicitly enabled exact story types`() {
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(ad = true),
                removeAds = true,
                removeLive = false,
                removeGames = false
            )
        )
        assertFalse(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(game = true),
                removeAds = true,
                removeLive = false,
                removeGames = false
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(live = true),
                removeAds = false,
                removeLive = true,
                removeGames = false
            )
        )
    }
}
