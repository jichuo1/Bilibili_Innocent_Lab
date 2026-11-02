package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.bapis.bilibili.app.viewunite.common.CardBasicInfo
import com.bapis.bilibili.app.viewunite.common.BadgeInfo
import com.bapis.bilibili.app.viewunite.common.RelateAVCard
import com.bapis.bilibili.app.viewunite.common.RelateCard
import com.bapis.bilibili.app.viewunite.common.RelateThreePoint
import com.bapis.bilibili.app.viewunite.common.Relates
import org.junit.Assert.*
import org.junit.Test

class VideoRelateCommercialCoverageTest {
    private val loader = requireNotNull(javaClass.classLoader)

    private class Card(
        private val stock: Boolean = false,
        private val feedback: Boolean = false,
        private val failStock: Boolean = false,
        private val case: RelateCard.CardCase = RelateCard.CardCase.AV,
        private val reason: String = "3万点赞"
    ) : RelateCard() {
        override fun getCardCase() = case
        override fun getAv(): RelateAVCard = object : RelateAVCard() {
            override fun getRcmdReason(): BadgeInfo = object : BadgeInfo() {
                override fun getText() = reason
            }
        }
        override fun getBasicInfo(): CardBasicInfo = object : CardBasicInfo() {
            override fun getFromSourceType() = 0L
        }
        override fun hasCmStock(): Boolean {
            check(!failStock)
            return stock
        }
        override fun getThreePoint(): RelateThreePoint = object : RelateThreePoint() {
            override fun hasFeedback() = feedback
        }
    }

    private inner class Harness(
        hidden: Set<String>,
        enhancement: Boolean = false,
        strong: Boolean = false,
        typeReaders: Boolean = true,
        missingStockReader: Boolean = false,
        minDuration: Int = 0
    ) {
        val registrar = ContentFilterTestRegistrar(loader)
        val statuses = mutableListOf<String>()
        val runtime = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        private val located = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        private val points = located.copy(
            responseItemGetters = located.responseItemGetters.filter {
                it.className == Relates::class.java.name
            },
            detailRelateService = null,
            // 本夹具是 AV；其余卡型的默认样本带推广理由，不属于这条反馈证据测试的输入。
            reasonChains = located.reasonChains.filter { it.steps.first().methodName == "getAv" },
            commercialEvidenceChains = located.commercialEvidenceChains.map { chain ->
                if (missingStockReader && chain.steps.singleOrNull()?.methodName == "hasCmStock") {
                    chain.copy(steps = listOf(chain.steps.single().copy(className = "missing.host.RelateCard")))
                } else chain
            },
            cardCaseGetters = if (typeReaders) located.cardCaseGetters else emptyList(),
            gotoGetters = if (typeReaders) located.gotoGetters else emptyList(),
            cardTypeGetters = if (typeReaders) located.cardTypeGetters else emptyList(),
            relateCardTypeGetters = if (typeReaders) located.relateCardTypeGetters else emptyList(),
            fromSourceTypeGetters = if (typeReaders) located.fromSourceTypeGetters else emptyList(),
            fromSourceTypeChains = if (typeReaders) located.fromSourceTypeChains else emptyList(),
            relateCardTypeValueGetters = if (typeReaders) located.relateCardTypeValueGetters else emptyList()
        )
        val result = VideoRelateFilterFeatureInstaller(
            hiddenTypes = hidden, minDurationSeconds = minDuration, maxDurationSeconds = 0,
            matchingEnhancementEnabled = enhancement, strongModeEnabled = strong, points = points
        ).install(HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, status -> statuses += status },
            runtimeEvidence = { id, stage, count -> runtime += Triple(id, stage, count) }
        ))

        fun filter(source: List<*>, receiver: Any = Relates()) = registrar.items("video.relate.response.0", receiver, source)
        fun count(id: String, stage: FeatureRuntimeStage = FeatureRuntimeStage.APPLIED) =
            runtime.filter { it.first == id && it.second == stage }.sumOf { it.third }
    }

    @Test fun explicitCommercialPayloadWorksWithoutEnhancementOrStrongMode() {
        for (type in listOf("CM", "SPECIAL")) {
            val h = Harness(setOf(type))
            val normal = Card()
            val source = listOf(Card(stock = true), normal)
            assertEquals(type, listOf(normal), h.filter(source))
            assertEquals(2, source.size)
        }
    }

    @Test fun normalFeedbackIsNotPromotedToCommercialEvidenceOutsideStrongMode() {
        for (enhancement in listOf(false, true)) {
            val h = Harness(setOf("CM"), enhancement = enhancement)
            val source = listOf(Card(feedback = true))
            assertSame(source, h.filter(source))
        }
    }

    @Test fun strongModeDoesNotTreatOrdinaryFeedbackMenusAsCommercialPayloads() {
        val h = Harness(emptySet(), enhancement = true, strong = true)
        val coinReason = Card(feedback = true, reason = "10万投币")
        val likeReason = Card(feedback = true, reason = "5万点赞")
        val unknownReason = Card(feedback = true, reason = "")
        val namedLikeReason = Card(feedback = true, reason = "百万点赞")
        assertEquals(listOf(coinReason), h.filter(listOf(coinReason, likeReason, unknownReason, namedLikeReason)))
        assertEquals(3, h.count("video_related_strong_mode_enabled"))
        assertEquals(0, h.count("video_related_commercial_removed"))
    }

    @Test fun gameOnlyDoesNotAcquireCommercialRemovalFromAnotherDimension() {
        val h = Harness(setOf("GAME"))
        val source = listOf(Card(stock = true))
        assertSame(source, h.filter(source))
    }

    @Test fun payloadFallbackContinuesWhenTypeReadersAreMissing() {
        val h = Harness(setOf("CM"), typeReaders = false)
        assertTrue(h.result is FeatureInstallResult.Installed)
        val normal = Card()
        assertEquals(listOf(normal), h.filter(listOf(Card(stock = true), normal)))
    }

    @Test fun failingPayloadReaderDoesNotRemoveUnknownVideo() {
        val h = Harness(setOf("CM"))
        val source = listOf(Card(failStock = true))
        assertSame(source, h.filter(source))
    }

    @Test fun missingPayloadReaderReportsPartialButTypedCommercialFilteringContinues() {
        val h = Harness(setOf("CM"), missingStockReader = true)
        assertTrue(h.statuses.single().contains("missing-commercial-payload-accessor"))
        val normal = Card()
        assertEquals(listOf(normal), h.filter(listOf(Card(case = RelateCard.CardCase.CM), normal)))
    }

    @Test fun mixedTypedRemovalsReportOnlyTheirActualCauses() {
        val h = Harness(linkedSetOf("CM", "GAME", "COURSE"))
        val normal = Card()
        assertEquals(listOf(normal), h.filter(listOf(Card(case = RelateCard.CardCase.CM),
            Card(case = RelateCard.CardCase.GAME), normal)))
        assertEquals(1, h.count("video_related_commercial_removed"))
        assertEquals(1, h.count("video_related_game_removed"))
        assertEquals(0, h.count("video_related_course_removed"))
        assertEquals(1, h.count("video_related_commercial_removed", FeatureRuntimeStage.OBSERVED))
    }

    @Test fun unmatchedEnabledTypesRemainWithoutRuntimeEvidence() {
        val h = Harness(setOf("CM", "GAME"))
        val source = listOf(Card())
        assertSame(source, h.filter(source))
        assertFalse(h.runtime.any { it.first.startsWith("video_related_") })
    }

    @Test fun commercialPayloadUsesTheEnabledRemovalDimension() {
        for ((type, capability) in listOf("CM" to "video_related_commercial_removed",
            "SPECIAL" to "video_related_special_removed")) {
            val h = Harness(setOf(type))
            assertTrue(h.filter(listOf(Card(stock = true))).isEmpty())
            assertEquals(1, h.count(capability))
            assertEquals(1, h.runtime.filter { it.first.startsWith("video_related_") &&
                it.second == FeatureRuntimeStage.APPLIED }.sumOf { it.third })
        }
    }

    @Test fun strongOnlyPayloadDoesNotClaimCommercialOrMatchingHits() {
        val h = Harness(emptySet(), enhancement = true, strong = true)
        assertTrue(h.filter(listOf(Card(stock = true))).isEmpty())
        assertEquals(1, h.count("video_related_strong_mode_enabled"))
        assertEquals(0, h.count("video_related_commercial_removed"))
        assertEquals(0, h.count("video_related_matching_enhancement_enabled"))
    }

    @Test fun numericRemovalDoesNotClaimAnEnabledType() {
        val h = Harness(setOf("CM"), minDuration = 10_000)
        assertTrue(h.filter(listOf(Card())).isEmpty())
        assertEquals(1, h.count("video_related_duration_filter"))
        assertEquals(0, h.count("video_related_commercial_removed"))
    }

    @Test fun failedWritebackMarksOnlyMatchedCausesAndPreservesGetterFiltering() {
        val h = Harness(setOf("CM", "GAME"))
        val normal = Card()
        repeat(2) {
            assertEquals(listOf(normal), h.filter(listOf(Card(case = RelateCard.CardCase.CM), normal), Any()))
        }
        assertEquals(2, h.count("video_related_commercial_removed", FeatureRuntimeStage.ERROR))
        assertEquals(2, h.count("video_related_commercial_removed"))
        assertEquals(0, h.count("video_related_game_removed", FeatureRuntimeStage.ERROR))
        assertEquals(0, h.count("video_related_game_removed"))
    }
}
