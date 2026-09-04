package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecommendPurifyFeatureInstallerTest {

    private fun environment(statuses: MutableList<Pair<String, String>>) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    private fun installer(
        minSeconds: Int,
        maxSeconds: Int,
        removeAds: Boolean = false,
        removeCmV2: Boolean = false,
        removeBanner: Boolean = false,
        points: VersionAdapter.HomeRecommendFeedPoints? =
            VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))
    ) = HomeRecommendPurifyFeatureInstaller(
        removeAds = removeAds,
        removeCmV2 = removeCmV2,
        removeBanner = removeBanner,
        removePictures = false,
        removeGamePromotions = false,
        titleFilterEnabled = false,
        rawTitleKeywords = "",
        removeLive = false,
        removeCourses = false,
        removeVertical = false,
        removeLarge = false,
        minDurationSeconds = minSeconds,
        maxDurationSeconds = maxSeconds,
        points = points
    )

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

    @Test
    fun `classifies home banner only from exact structured token`() {
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isHomeBanner(
                HomeRecommendPurifyFeatureInstaller.Signals(holderType = "banner_v8")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isHomeBanner(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "CARD_TYPE_BANNER_V8")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isHomeBanner(
                HomeRecommendPurifyFeatureInstaller.Signals(holderType = "large_cover_v9")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isHomeBanner(
                HomeRecommendPurifyFeatureInstaller.Signals(title = "首页 Banner 推荐")
            )
        )
    }

    @Test
    fun `classifies cm_v2 feed ads only from exact card type`() {
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "cm_v2")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "CARD_TYPE_CM_V2")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "small_cover_v2")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "ogv_small_cover")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "banner_v8")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardGoto = "ad_web_s")
            )
        )
    }

    @Test
    fun `cm_v2 switch installs alone and stays independent from ads switch`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
            installer(minSeconds = 0, maxSeconds = 0, removeCmV2 = true, points = points)
                .install(environment(statuses))
        )
        assertEquals(listOf("home_recommend_purify_status" to "success"), statuses)
    }

    @Test
    fun `duration-only configuration installs while an empty range stays hook free`() {
        val durationStatuses = mutableListOf<Pair<String, String>>()
        val bannerStatuses = mutableListOf<Pair<String, String>>()
        val disabledStatuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
            installer(minSeconds = 30, maxSeconds = 0, points = points)
                .install(environment(durationStatuses))
        )
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            installer(minSeconds = 0, maxSeconds = 0, points = null)
                .install(environment(disabledStatuses))
        )
        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
            installer(
                minSeconds = 0,
                maxSeconds = 0,
                removeBanner = true,
                points = points
            ).install(environment(bannerStatuses))
        )
        assertEquals(listOf("home_recommend_purify_status" to "success"), durationStatuses)
        assertEquals(listOf("home_recommend_purify_status" to "success"), bannerStatuses)
        assertEquals(listOf("home_recommend_purify_status" to "disabled"), disabledStatuses)
    }

    @Test
    fun `missing duration accessor does not disable an existing content filter`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))
        ).copy(playerArgsGetter = null, playerArgsDurationField = null)

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
            installer(
                minSeconds = 30,
                maxSeconds = 0,
                removeAds = true,
                points = points
            ).install(environment(statuses))
        )
        assertEquals(
            listOf(
                "home_recommend_purify_status" to
                    "partial:missing-duration-accessor"
            ),
            statuses
        )
    }
}
