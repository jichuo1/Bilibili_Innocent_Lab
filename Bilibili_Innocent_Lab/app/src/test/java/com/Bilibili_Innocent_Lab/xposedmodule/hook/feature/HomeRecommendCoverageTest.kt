@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.bilibili.pegasus.PegasusHolderData
import com.bilibili.pegasus.data.base.PegasusResponse
import org.junit.Assert.*
import org.junit.Test

class HomeRecommendCoverageTest {
    private val loader = requireNotNull(javaClass.classLoader)
    private val points get() = requireNotNull(VersionAdapter.locateHomeRecommendFeed(loader))

    private class Card(
        private val holder: String = "large_cover_v9",
        private val type: String = "av",
        private val goTo: String = "av",
        private val uri: String = "bilibili://video/1",
        private val ad: Object? = null,
        private val failAd: Boolean = false,
        private val cardGoto: String = "av",
        private val title: String = "ordinary video",
        private val failRoute: Boolean = false
    ) : PegasusHolderData() {
        var titleReads = 0
        override fun getHolderType() = holder
        override fun getCardType() = type
        override fun getCardGoto(): String {
            check(!failRoute)
            return cardGoto
        }
        override fun getGoTo(): String {
            check(!failRoute)
            return goTo
        }
        override fun getUri(): String {
            check(!failRoute)
            return uri
        }
        override fun getTitle(): String {
            titleReads += 1
            return title
        }
        override fun getAdInfo(): Object? {
            check(!failAd) { "private payload must not reach diagnostics" }
            return ad
        }
    }

    private inner class Harness(
        ads: Boolean = false,
        cmV2: Boolean = false,
        hidden: String = "",
        comic: Boolean = false,
        special: Boolean = false,
        adapted: VersionAdapter.HomeRecommendFeedPoints = points
    ) {
        val registrar = ContentFilterTestRegistrar(loader)
        val statuses = mutableListOf<String>()
        val capabilities = linkedMapOf<String, FeatureInstallResult>()
        val evidence = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        val logs = mutableListOf<String>()
        val environment = HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, text -> logs += text }, { _, text -> logs += text },
            { _, status -> statuses += status },
            runtimeEvidence = { id, stage, count -> evidence += Triple(id, stage, count) },
            capabilityEvidence = { id, result -> capabilities[id] = result }
        )
        val result = HomeRecommendPurifyFeatureInstaller(
            removeAds = ads, removeCmV2 = cmV2, removeBanner = false,
            removePictures = hidden == "PICTURE", removeGamePromotions = hidden == "GAME",
            titleFilterEnabled = false, rawTitleKeywords = "",
            removeLive = hidden == "LIVE", removeCourses = hidden == "COURSE",
            removeVertical = hidden == "VERTICAL", removeLarge = false,
            removeComic = comic, removeSpecialCards = special,
            minDurationSeconds = 0, maxDurationSeconds = 0, points = adapted
        ).install(environment)

        fun filter(source: List<*>) = registrar.items("home.recommend.purify.0", PegasusResponse(), source)
    }

    fun comicAloneUsesExactBusinessTokensAndRoutesWithoutReadingTitles() {
        val h = Harness(comic = true)
        val ordinary = Card(title = "漫画解说：comic 动画与漫画的区别")
        val source = listOf(ordinary, Card(goTo = "comic"), Card(cardGoto = "COMIC"), Card(type = "comic"),
            Card(uri = "bilibili://comic/detail?comic_id=123"),
            Card(uri = "https://manga.bilibili.com/m/detail/mc123"))
        assertEquals(listOf(ordinary), h.filter(source))
        assertEquals(6, source.size)
        assertEquals(0, ordinary.titleReads)
        assertTrue(h.evidence.contains(Triple("home_recommend_comic_removed", FeatureRuntimeStage.APPLIED, 5)))
        assertFalse(h.evidence.any { it.first == "home_recommend_special_cards_removed" })
    }

    fun comicToggleOffPreservesComicCardsWhenAnIndependentFilterIsEnabled() {
        val h = Harness(ads = true)
        val source = listOf(Card(type = "comic", goTo = "comic"))
        assertSame(source, h.filter(source))
        assertFalse(h.capabilities.containsKey("home_recommend_comic_removed"))
        assertFalse(h.evidence.any { it.first == "home_recommend_comic_removed" })
    }

    fun comicAndSpecialFlagsCountTheirOwnMatchesWithoutChangingSpecialSemantics() {
        val comic = Card(goTo = "comic")
        val specialComic = Card(type = "special", uri = "bilibili://comic/detail?comic_id=123")
        val plainSpecial = Card(type = "special")
        val ordinary = Card()
        val source = listOf(comic, specialComic, plainSpecial, ordinary)
        assertEquals(listOf(plainSpecial, ordinary), Harness(comic = true).filter(source))
        assertEquals(listOf(comic, ordinary), Harness(special = true).filter(source))
        val h = Harness(comic = true, special = true)
        assertEquals(listOf(ordinary), h.filter(source))
        assertTrue(h.evidence.contains(Triple("home_recommend_comic_removed", FeatureRuntimeStage.APPLIED, 2)))
        assertTrue(h.evidence.any { it.first == "home_recommend_special_cards_removed" && it.second == FeatureRuntimeStage.APPLIED })
        assertTrue(h.evidence.contains(Triple(HomeRecommendPurifyFeatureInstaller.ID, FeatureRuntimeStage.APPLIED, 3)))
    }

    fun missingComicReadersArePartialAndOtherFiltersContinue() {
        val h = Harness(comic = true, hidden = "PICTURE", adapted = points.copy(
            cardTypeGetter = null, cardGotoGetter = null, goToGetter = null, uriGetter = null))
        assertFalse((h.result as FeatureInstallResult.Installed).complete)
        assertTrue(h.capabilities.getValue("home_recommend_comic_removed") is FeatureInstallResult.Skipped)
        val ordinary = Card()
        assertEquals(listOf(ordinary), h.filter(listOf(Card(holder = "picture"), ordinary)))
        assertTrue(h.statuses.single().contains("missing-comic-readers"))
    }

    fun comicRouteRemainsUsableWhenOptionalTypeReadersAreMissing() {
        val h = Harness(comic = true, adapted = points.copy(cardTypeGetter = null, cardGotoGetter = null, goToGetter = null))
        val ordinary = Card()
        assertEquals(listOf(ordinary), h.filter(listOf(Card(uri = "bilibili://comic/reader?ep_id=456"), ordinary)))
        assertTrue((h.capabilities.getValue("home_recommend_comic_removed") as FeatureInstallResult.Installed).complete)
    }

    fun comicFilterPreservesUnknownAndThrowingCardsWithoutReplacingNoMatchLists() {
        val h = Harness(comic = true)
        val source = listOf(Card(failRoute = true), Any(), Card(uri = "bilibili://comic/detail_future"),
            Card(uri = "https://manga.bilibili.com.evil.test/m/detail/mc123"))
        assertSame(source, h.filter(source))
        assertFalse(h.evidence.any { it.first == "home_recommend_comic_removed" && it.second == FeatureRuntimeStage.APPLIED })
    }

    fun adsAloneUseGotoAndUriWithoutAnotherFilterOrBanner() {
        val h = Harness(ads = true)
        val normal = Card()
        val source = listOf(normal, Card(goTo = "ad_av"), Card(uri = "bilibili://ad/1"))
        assertEquals(listOf(normal), h.filter(source))
        assertEquals(3, source.size)
        assertTrue(h.evidence.contains(Triple("home_recommend_ads_removed", FeatureRuntimeStage.APPLIED, 2)))
    }

    fun videoShapedAdPayloadIsRemovedWithoutRemovingOrdinaryLargeVideo() {
        val h = Harness(ads = true)
        val normal = Card()
        assertEquals(listOf(normal), h.filter(listOf(Card(ad = Object()), normal)))
        assertTrue(h.evidence.contains(Triple("home_recommend_ads_removed", FeatureRuntimeStage.APPLIED, 1)))
    }

    fun cmV2AloneRetainsIndependentVideoShapedPromotionSemantics() {
        val h = Harness(cmV2 = true)
        val video = Card(ad = Object())
        assertEquals(listOf(video), h.filter(listOf(Card(type = "cm_v2"), video)))
        assertFalse(h.evidence.any { it.first == "home_recommend_ads_removed" })
    }

    fun bothAdSwitchesReportTheirOwnAppliedEvidenceForTheSameRemovedCard() {
        val h = Harness(ads = true, cmV2 = true)
        assertTrue(h.filter(listOf(Card(type = "cm_v2", ad = Object()))).isEmpty())
        for (id in listOf("home_recommend_ads_removed", "home_recommend_cm_v2_removed")) {
            assertTrue(h.evidence.contains(Triple(id, FeatureRuntimeStage.APPLIED, 1)))
        }
    }

    fun ordinaryUrisContainingAnAdLinkInQueryOrLookalikeHostRemain() {
        val h = Harness(ads = true)
        val source = listOf(
            Card(uri = "bilibili://video/1?redirect=https://cm.bilibili.com/a"),
            Card(uri = "https://notcm.bilibili.com/a"),
            Card(uri = "https://cm.bilibili.com.evil.example/a"),
            Card(uri = "https://example.com/cm.bilibili.com/a")
        )
        assertSame(source, h.filter(source))
    }

    fun eachTypeFilterUsesHolderFallbackWhenOptionalTypeReadersAreMissing() {
        val adapted = points.copy(cardTypeGetter = null, cardGotoGetter = null, goToGetter = null)
        for (kind in listOf("PICTURE", "GAME", "LIVE", "COURSE", "VERTICAL")) {
            val h = Harness(hidden = kind, adapted = adapted)
            val normal = Card()
            assertEquals(kind, listOf(normal), h.filter(listOf(Card(holder = kind), normal)))
        }
    }

    fun missingAdPayloadReaderIsPartialWhileOtherFiltersContinue() {
        val h = Harness(ads = true, hidden = "PICTURE", adapted = points.copy(adInfoGetter = null))
        assertFalse((h.result as FeatureInstallResult.Installed).complete)
        assertFalse((h.capabilities.getValue("home_recommend_ads_removed") as FeatureInstallResult.Installed).complete)
        assertTrue((h.capabilities.getValue("home_recommend_pictures_removed") as FeatureInstallResult.Installed).complete)
        assertTrue(h.statuses.single().contains("missing-ad-info-accessor"))
        val normal = Card()
        assertEquals(listOf(normal), h.filter(listOf(Card(type = "picture"), Card(goTo = "ad_av"), normal)))
    }

    fun throwingAdReaderPreservesUnknownCardAndReportsBoundedErrorWithoutPayload() {
        val h = Harness(ads = true)
        val source = listOf(Card(failAd = true))
        assertSame(source, h.filter(source))
        assertTrue(h.evidence.any { it.first == "home_recommend_ads_removed" && it.second == FeatureRuntimeStage.ERROR })
        assertFalse(h.evidence.any { it.second == FeatureRuntimeStage.APPLIED })
        assertFalse(h.logs.any { it.contains("private payload") })
    }

    fun incompatibleFutureCardIsPreservedWithAnUnreadableAdCarrierSignal() {
        val h = Harness(ads = true)
        val source = listOf(Any())
        assertSame(source, h.filter(source))
        assertTrue(h.evidence.any { it.first == "home_recommend_ads_removed" && it.second == FeatureRuntimeStage.ERROR })
        assertFalse(h.evidence.any { it.second == FeatureRuntimeStage.APPLIED })
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HomeRecommendCoverageTest) -> Unit) {
            val s = HomeRecommendCoverageTest()
            block(s)
        }
        runIsolated { s -> s.comicAloneUsesExactBusinessTokensAndRoutesWithoutReadingTitles() }
        runIsolated { s -> s.comicToggleOffPreservesComicCardsWhenAnIndependentFilterIsEnabled() }
        runIsolated { s -> s.comicAndSpecialFlagsCountTheirOwnMatchesWithoutChangingSpecialSemantics() }
        runIsolated { s -> s.missingComicReadersArePartialAndOtherFiltersContinue() }
        runIsolated { s -> s.comicRouteRemainsUsableWhenOptionalTypeReadersAreMissing() }
        runIsolated { s -> s.comicFilterPreservesUnknownAndThrowingCardsWithoutReplacingNoMatchLists() }
        runIsolated { s -> s.adsAloneUseGotoAndUriWithoutAnotherFilterOrBanner() }
        runIsolated { s -> s.videoShapedAdPayloadIsRemovedWithoutRemovingOrdinaryLargeVideo() }
        runIsolated { s -> s.cmV2AloneRetainsIndependentVideoShapedPromotionSemantics() }
        runIsolated { s -> s.bothAdSwitchesReportTheirOwnAppliedEvidenceForTheSameRemovedCard() }
        runIsolated { s -> s.ordinaryUrisContainingAnAdLinkInQueryOrLookalikeHostRemain() }
        runIsolated { s -> s.eachTypeFilterUsesHolderFallbackWhenOptionalTypeReadersAreMissing() }
        runIsolated { s -> s.missingAdPayloadReaderIsPartialWhileOtherFiltersContinue() }
        runIsolated { s -> s.throwingAdReaderPreservesUnknownCardAndReportsBoundedErrorWithoutPayload() }
        runIsolated { s -> s.incompatibleFutureCardIsPreservedWithAnUnreadableAdCarrierSignal() }
    }
}
