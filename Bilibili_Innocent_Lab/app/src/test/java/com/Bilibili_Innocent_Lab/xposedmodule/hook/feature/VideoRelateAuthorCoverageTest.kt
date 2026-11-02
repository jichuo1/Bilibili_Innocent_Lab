package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.bapis.bilibili.app.archive.v1.Author
import com.bapis.bilibili.app.view.v1.Relate
import com.bapis.bilibili.app.viewunite.common.CardBasicInfo
import com.bapis.bilibili.app.viewunite.common.Owner
import com.bapis.bilibili.app.viewunite.common.RelateCard
import com.bapis.bilibili.app.viewunite.common.Relates
import org.junit.Assert.*
import org.junit.Test

class VideoRelateAuthorCoverageTest {
    private val loader = requireNotNull(javaClass.classLoader)

    private fun card(name: String = "", mid: Long = 0L, unreadable: Boolean = false) = object : RelateCard() {
        override fun getBasicInfo() = object : CardBasicInfo() {
            override fun getAuthor() = object : Owner("", null) {
                override fun getTitle() = name
                override fun getMid(): Long {
                    check(!unreadable)
                    return mid
                }
            }
        }
    }

    private inner class Harness(rules: String) {
        private val registrar = ContentFilterTestRegistrar(loader)
        val runtime = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        private val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        private val points = located.copy(
            responseItemGetters = located.responseItemGetters.filter { it.className == Relates::class.java.name },
            detailRelateService = null
        )
        init {
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
                rawBlockedAuthors = rules, points = points
            ).install(HookEnvironment(
                "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { id, stage, count -> runtime += Triple(id, stage, count) }
            ))
        }
        fun filter(source: List<*>) = registrar.items("video.relate.response.0", Relates(), source)
    }

    @Test fun currentAndLegacyAuthorPathsSurviveSerialization() {
        val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        val restored = VersionAdapter.VideoRelatePoints.fromJson(located.toJson())
        assertEquals(setOf(listOf("getAuthor", "getName"), listOf("getBasicInfo", "getAuthor", "getTitle")),
            restored.authorNameChains.map { it.steps.map { point -> point.methodName } }.toSet())
        assertEquals(setOf(listOf("getAuthor", "getMid"), listOf("getBasicInfo", "getAuthor", "getMid")),
            restored.authorMidChains.map { it.steps.map { point -> point.methodName } }.toSet())
    }

    @Test fun nestedOwnerMidRemovesOnlyTheExactPublicUid() {
        val target = card("public author", 208259L)
        val prefix = card("another author", 20825L)
        val suffix = card("another author", 1208259L)
        val h = Harness("208259")
        val source = listOf(target, prefix, suffix)
        assertEquals(listOf(prefix, suffix), h.filter(source))
        assertEquals(3, source.size)
        assertTrue(h.runtime.contains(Triple("video_related_author_block", FeatureRuntimeStage.OBSERVED, 1)))
        assertTrue(h.runtime.contains(Triple("video_related_author_block", FeatureRuntimeStage.APPLIED, 1)))
        assertFalse(h.runtime.any { it.first == "video_related_tag_block" || it.second == FeatureRuntimeStage.ERROR })
    }

    @Test fun nestedOwnerDisplayNameMatchesExactlyWithoutUsingCardTitles() {
        val target = card("  PublicAuthor  ", 208259L)
        val prefix = card("PublicAuthorExtra", 10L)
        val suffix = card("OtherPublicAuthor", 20L)
        assertEquals(listOf(prefix, suffix), Harness("publicauthor").filter(listOf(target, prefix, suffix)))
        val source = listOf(target)
        val h = Harness("20825")
        assertSame(source, h.filter(source))
        assertFalse(h.runtime.any { it.first == "video_related_author_block" && it.second == FeatureRuntimeStage.APPLIED })
    }

    @Test fun absentAndUnreadableOwnersRemainWhileReadableNamesCanStillMatch() {
        val noOwner = RelateCard()
        val unreadableOwner = object : RelateCard() {
            override fun getBasicInfo(): CardBasicInfo = error("unreadable basic info")
        }
        val unavailableMid = card(unreadable = true)
        val zeroMid = card(mid = 0L)
        val negativeMid = card(mid = -1L)
        val source = listOf(noOwner, unreadableOwner, unavailableMid, zeroMid, negativeMid)
        assertSame(source, Harness("208259,0,-1").filter(source))
        assertEquals(emptyList<Any>(), Harness("publicauthor").filter(listOf(card("PublicAuthor", unreadable = true))))
    }

    @Test fun directLegacyAuthorNameAndMidContinueToFilter() {
        val target = object : Relate() {
            override fun getAuthor() = Author(208259L, "LegacyAuthor")
        }
        val other = Relate()
        for (rule in listOf("legacyauthor", "208259")) {
            assertEquals(listOf(other), Harness(rule).filter(listOf(target, other)))
        }
    }
}
