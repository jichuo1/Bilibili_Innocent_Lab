package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecommendPurifyFeatureInstallerTest {

    @Test
    fun `classifies ads pictures and game promotions from explicit signals`() {
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isAdvertisement(
                HomeRecommendPurifyFeatureInstaller.Signals(bizType = "AD")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isPicture(
                HomeRecommendPurifyFeatureInstaller.Signals(uri = "bilibili://opus/123")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isGamePromotion(
                HomeRecommendPurifyFeatureInstaller.Signals(goTo = "mini_game")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isGamePromotion(
                HomeRecommendPurifyFeatureInstaller.Signals(
                    title = "小游戏试玩",
                    hasAdInfo = true
                )
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isGamePromotion(
                HomeRecommendPurifyFeatureInstaller.Signals(title = "游戏开发纪录片")
            )
        )
    }
}
