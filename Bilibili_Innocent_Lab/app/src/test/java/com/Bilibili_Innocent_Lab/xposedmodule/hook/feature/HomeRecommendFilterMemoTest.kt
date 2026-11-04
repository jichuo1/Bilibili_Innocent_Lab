package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveHomeRules
import com.bilibili.pegasus.PegasusHolderData
import com.bilibili.pegasus.data.base.PegasusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 宿主对同一份响应反复调用 getItems()：过滤只做一次，交回的始终是同一个列表。 */
class HomeRecommendFilterMemoTest {
    private val loader = requireNotNull(javaClass.classLoader)

    private class Card(private val title: String) : PegasusHolderData() {
        var titleReads = 0
        override fun getTitle(): String {
            titleReads += 1
            return title
        }
    }

    private inner class Harness(liveRules: (() -> LiveHomeRules)? = null) {
        val registrar = ContentFilterTestRegistrar(loader)
        val evidence = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        init {
            val environment = HookEnvironment(
                "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { id, stage, count -> evidence += Triple(id, stage, count) }
            )
            val installed = HomeRecommendPurifyFeatureInstaller(
                removeAds = false, removeCmV2 = false, removeBanner = false,
                removePictures = false, removeGamePromotions = false,
                titleFilterEnabled = true, rawTitleKeywords = "blocked",
                removeLive = false, removeCourses = false, removeVertical = false, removeLarge = false,
                minDurationSeconds = 0, maxDurationSeconds = 0,
                points = requireNotNull(VersionAdapter.locateHomeRecommendFeed(loader)),
                liveRules = liveRules
            ).install(environment)
            assertTrue(installed is FeatureInstallResult.Installed)
        }

        fun read(response: Any, source: List<*>) = registrar.items("home.recommend.purify.0", response, source)

        val appliedRemovals get() = evidence.filter {
            it.first == HomeRecommendPurifyFeatureInstaller.ID && it.second == FeatureRuntimeStage.APPLIED
        }.sumOf { it.third }
    }

    @Test fun repeatedReadsOfOneResponseFilterOnceAndReturnTheSameList() {
        val h = Harness()
        val kept = Card("ordinary")
        val blocked = Card("blocked title")
        val source = listOf(kept, blocked)
        val response = PegasusResponse()
        val first = h.read(response, source)
        repeat(12) { assertSame(first, h.read(response, source)) }
        assertEquals(listOf(kept), first)
        assertEquals(1, kept.titleReads)
        assertEquals(1, blocked.titleReads)
        // 迁移前每次读取都会把同一张卡再计一次；现在一份响应只计一次。
        assertEquals(1, h.appliedRemovals)
    }

    @Test fun hostWritesIntoTheReturnedListSurviveLaterReads() {
        val h = Harness()
        val source = listOf(Card("ordinary"), Card("blocked"))
        val response = PegasusResponse()
        @Suppress("UNCHECKED_CAST")
        val first = h.read(response, source) as MutableList<Any?>
        val injected = Card("host injected")
        first.add(0, injected)
        assertSame(injected, h.read(response, source).first())
    }

    @Test fun aGrownSourceListIsFilteredAgain() {
        val h = Harness()
        val source = mutableListOf<Any?>(Card("ordinary"), Card("blocked"))
        val response = PegasusResponse()
        val first = h.read(response, source)
        val later = Card("blocked again")
        source += later
        val second = h.read(response, source)
        assertNotSame(first, second)
        assertEquals(1, second.size)
        assertEquals(1, later.titleReads)
    }

    @Test fun replacedLiveRulesAreAppliedToALaterReadOfTheSameResponse() {
        var rules = LiveHomeRules(setOf("alpha"), VideoDurationRange(0, 0), VideoPlayCountRange(0, 0))
        val h = Harness(liveRules = { rules })
        val beta = Card("beta")
        val source = listOf(Card("alpha"), beta)
        val response = PegasusResponse()
        assertEquals(listOf(beta), h.read(response, source))
        rules = rules.copy(titles = setOf("beta"))
        assertEquals(1, h.read(response, source).size)
        assertTrue(h.read(response, source).none { it === beta })
    }

    @Test fun anUnfilteredListIsStillTheHostsOwnInstance() {
        val h = Harness()
        val source = listOf(Card("ordinary"))
        val response = PegasusResponse()
        assertSame(source, h.read(response, source))
        assertSame(source, h.read(response, source))
    }
}
