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

    @Test
    fun `classifies home feed types only from explicit route signals`() {
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isLive(
                HomeRecommendPurifyFeatureInstaller.Signals(goTo = "live")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isCourse(
                HomeRecommendPurifyFeatureInstaller.Signals(uri = "https://www.bilibili.com/cheese/play/ep1")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isVertical(
                HomeRecommendPurifyFeatureInstaller.Signals(cardGoto = "vertical_av")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isLarge(
                HomeRecommendPurifyFeatureInstaller.Signals(holderType = "large_cover_v9")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isLive(
                HomeRecommendPurifyFeatureInstaller.Signals(title = "直播录像")
            )
        )
    }
}
