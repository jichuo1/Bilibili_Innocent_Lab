package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.bapis.bilibili.app.view.v1.Relate
import com.bapis.bilibili.app.viewunite.common.RelateCard
import com.bapis.bilibili.app.viewunite.common.RelateGameCard
import com.bapis.bilibili.app.viewunite.common.Relates
import org.junit.Assert.*
import org.junit.Test

class VideoRelateTagCoverageTest {
    private val loader = requireNotNull(javaClass.classLoader)

    private fun game(tag: String) = object : RelateCard() {
        override fun getCardCase() = CardCase.GAME
        override fun getGame() = object : RelateGameCard() {
            override fun getTagName() = tag
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
                rawBlockedTags = rules, points = points
            ).install(HookEnvironment(
                "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { id, stage, count -> runtime += Triple(id, stage, count) }
            ))
        }
        fun filter(source: List<*>) = registrar.items("video.relate.response.0", Relates(), source)
    }

    @Test fun nestedAndLegacyTagPathsSurviveSerialization() {
        val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        val restored = VersionAdapter.VideoRelatePoints.fromJson(located.toJson())
        assertEquals(setOf(listOf("getTagName"), listOf("getGame", "getTagName")),
            restored.tagNameChains.map { it.steps.map { point -> point.methodName } }.toSet())
    }

    @Test fun nestedGameTagsMatchWholeNamesAndLeaveUntaggedCards() {
        val target = game("  策略  ")
        val partial = game("策略游戏")
        val normal = RelateCard()
        val h = Harness("策略")
        val source = listOf(target, partial, normal)
        assertEquals(listOf(partial, normal), h.filter(source))
        assertEquals(3, source.size)
        assertTrue(h.runtime.contains(Triple("video_related_tag_block", FeatureRuntimeStage.APPLIED, 1)))
        assertFalse(h.runtime.any { it.first in setOf("video_related_author_block", "video_related_game_removed") })
    }

    @Test fun caseFoldingAndMixedRulesDoNotTurnPrefixesIntoMatches() {
        val rpg = game("RPG")
        val strategy = game("策略")
        val longer = game("RPG Online")
        assertEquals(listOf(longer), Harness("rpg,策略,rpg").filter(listOf(rpg, strategy, longer)))
        val source = listOf(rpg, strategy)
        val h = Harness("rp,策")
        assertSame(source, h.filter(source))
        assertFalse(h.runtime.any { it.first == "video_related_tag_block" && it.second == FeatureRuntimeStage.APPLIED })
    }

    @Test fun missingAndUnreadableTagsRemainWithoutFallbackToReasons() {
        val throwing = object : RelateCard() {
            override fun getGame(): RelateGameCard = error("unreadable game")
        }
        val source = listOf(throwing, RelateCard(), game(""), game(" "))
        // The fixture's recommendation reason is 预约游戏; it is never a tag fallback.
        val h = Harness("预约游戏")
        assertSame(source, h.filter(source))
        assertFalse(h.runtime.any { it.second == FeatureRuntimeStage.ERROR })
    }

    @Test fun directLegacyTagsContinueToFilterAlongsideNestedCards() {
        val legacy = object : Relate() {
            override fun getTagName() = "策略"
        }
        val nested = game("策略")
        val other = Relate()
        assertEquals(listOf(other), Harness("策略").filter(listOf(legacy, nested, other)))
    }
}
