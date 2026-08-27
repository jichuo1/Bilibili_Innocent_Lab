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
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(bangumi = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeBangumi = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(course = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeCourses = true
            )
        )
    }

    @Test
    fun `filters exact short drama shopping and music signals independently`() {
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(shortDrama = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeShortDrama = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(shopping = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeShopping = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(music = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeMusic = true
            )
        )
        assertFalse(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(shortDrama = true, shopping = true, music = true),
                removeAds = false,
                removeLive = false,
                removeGames = false
            )
        )
    }

    @Test
    fun `filters only the enabled exact season subtype`() {
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_MOVIE
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeMovies = true
            )
        )
        assertFalse(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_DOCUMENTARY
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeMovies = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_DOCUMENTARY
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeDocumentaries = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_TV
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeTv = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_VARIETY
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeVariety = true
            )
        )
    }
}
