package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.bilibili.app.gemini.ui.RunningUIComponent
import com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.ModernRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.r2
import org.junit.Assert.*
import org.junit.Test

class VideoRelateServiceReasonCoverageTest {
    private val loader = requireNotNull(javaClass.classLoader)
    private val factory = requireNotNull(VersionAdapter.selectDetailRelateComponentFactory(ModernRelateService::class.java))
    private val getter = requireNotNull(VersionAdapter.selectDetailRelateServiceReasonGetter(factory))
    private val service = VersionAdapter.DetailRelateServicePoint(
        VersionAdapter.HookPoint(factory.declaringClass.name, factory.name,
            paramClassNames = factory.parameterTypes.map { it.name }),
        typeField = "a", typeGetter = VersionAdapter.HookPoint(r2::class.java.name, "y")
    )
    private val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
    private val points = located.copy(responseItemGetters = emptyList(), detailRelateService = service,
        reasonChains = located.reasonChains + VersionAdapter.ReasonMethodChain(listOf(
            VersionAdapter.HookPoint(getter.declaringClass.name, getter.name))))

    private inner class Harness(strong: Boolean = true, keywords: String = "", hidden: Set<String> = emptySet()) {
        private val registrar = PlayerPortTestRegistrar()
        val runtime = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        init {
            val result = VideoRelateFilterFeatureInstaller(hidden, 0, 0,
                matchingEnhancementEnabled = true, strongModeEnabled = strong,
                reasonFilterEnabled = keywords.isNotEmpty(), rawReasonKeywords = keywords, points = points)
                .install(HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                    { _, _ -> }, { _, _ -> }, { _, _ -> },
                    runtimeEvidence = { id, stage, count -> runtime += Triple(id, stage, count) }))
            assertTrue(result is FeatureInstallResult.Installed)
        }
        var called = false
        val component = RunningUIComponent()
        fun run(item: r2) = registrar.invoke("video.relate.service", ModernRelateService(), arrayOf(item)) {
            called = true
            component
        }
    }

    @Test fun exactDtoReasonGetterSurvivesSerializationAndRejectsLegacyGuessing() {
        assertEquals("H", getter.name)
        val legacy = requireNotNull(VersionAdapter.selectDetailRelateComponentFactory(DetailRelateService::class.java))
        assertNull(VersionAdapter.selectDetailRelateServiceReasonGetter(legacy))
        val restored = VersionAdapter.VideoRelatePoints.fromJson(points.toJson())
        assertTrue(restored.reasonChains.any { it.steps.singleOrNull()?.className == r2::class.java.name &&
            it.steps.single().methodName == "H" })
    }

    @Test fun strongServiceKeepsReadableCoinReasonsInsteadOfTreatingThemAsUnknown() {
        val h = Harness()
        assertSame(h.component, h.run(r2("10万投币")))
        assertTrue(h.called)
        assertFalse(h.runtime.any { it.second == FeatureRuntimeStage.APPLIED || it.second == FeatureRuntimeStage.ERROR })
    }

    @Test fun strongServiceStillRemovesLikeCountsAndUnknownReasonsBeforeOriginal() {
        for (reason in listOf("5万点赞", "百万点赞", "")) {
            val h = Harness()
            assertNull(h.run(r2(reason)))
            assertFalse(h.called)
            assertTrue(h.runtime.contains(Triple("video_related_strong_mode_enabled", FeatureRuntimeStage.APPLIED, 1)))
            assertFalse(h.runtime.any { it.first == "video_related_matching_enhancement_enabled" })
        }
    }

    @Test fun customServiceRulesUseRealReasonsAndPreserveUnmatchedCards() {
        val matching = Harness(strong = false, keywords = "10")
        assertNull(matching.run(r2("10万投币")))
        assertFalse(matching.called)
        assertTrue(matching.runtime.contains(Triple("video_related_reason_filter_enabled", FeatureRuntimeStage.APPLIED, 1)))
        val unmatched = Harness(strong = false, keywords = "1000000000")
        assertSame(unmatched.component, unmatched.run(r2("10万投币")))
        assertTrue(unmatched.called)
    }

    @Test fun promotionMatchingUsesDtoReasonsWithoutAttributingThemToCommercialType() {
        val h = Harness(strong = false, hidden = setOf("CM"))
        assertNull(h.run(r2("创作推广")))
        assertTrue(h.runtime.contains(Triple("video_related_matching_enhancement_enabled", FeatureRuntimeStage.APPLIED, 1)))
        assertFalse(h.runtime.any { it.first == "video_related_commercial_removed" })
    }

    @Test fun unreadableReasonsStaySafeInNormalModeAndRemainUnknownInStrongMode() {
        val item = object : r2() { override fun H(): String = error("unreadable reason") }
        val normal = Harness(strong = false, keywords = "10")
        assertSame(normal.component, normal.run(item))
        val strong = Harness()
        assertNull(strong.run(item))
        assertFalse(strong.called)
    }
}
