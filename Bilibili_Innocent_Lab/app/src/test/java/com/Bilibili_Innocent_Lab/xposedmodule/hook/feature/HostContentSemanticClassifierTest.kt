package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostContentSemanticClassifierTest {
    @Test
    fun `accumulation preserves independent evidence order and existing destination kinds`() {
        val kinds = linkedSetOf(HostContentKind.PICTURE)
        HostContentSemanticClassifier.classifyInto(HostContentSignals(fromSourceType = 2L), kinds)
        HostContentSemanticClassifier.classifyInto(HostContentSignals(relateCardTypeValue = 4), kinds)
        HostContentSemanticClassifier.classifyInto(HostContentSignals(cardType = "LIVE"), kinds)
        HostContentSemanticClassifier.classifyInto(HostContentSignals(cardType = "UNKNOWN"), kinds)
        assertEquals(listOf(HostContentKind.PICTURE, HostContentKind.ADVERTISEMENT,
            HostContentKind.SPECIAL, HostContentKind.GAME, HostContentKind.LIVE), kinds.toList())
        // 后续调用不得污染之前返回的分类快照。
        val snapshot = HostContentSemanticClassifier.classify(HostContentSignals(cardType = "LIVE"))
        HostContentSemanticClassifier.classifyInto(HostContentSignals(cardType = "AD"), kinds)
        assertEquals(setOf(HostContentKind.LIVE), snapshot)
    }

    @Test
    fun `banner still checks all five allowed evidence slots with exact normalization`() {
        val factories = listOf<(String?) -> HostContentSignals>(
            { HostContentSignals(holderType = it) }, { HostContentSignals(bizType = it) },
            { HostContentSignals(cardType = it) }, { HostContentSignals(cardGoto = it) },
            { HostContentSignals(goTo = it) }
        )
        for (factory in factories) {
            assertTrue(HostContentSemanticClassifier.isHomeBanner(factory(" card_type_banner_v8 ")))
            assertFalse(HostContentSemanticClassifier.isHomeBanner(factory("BANNER_V8_EXTRA")))
            assertFalse(HostContentSemanticClassifier.isHomeBanner(factory(null)))
        }
        assertFalse(HostContentSemanticClassifier.isHomeBanner(
            HostContentSignals(cardCase = "BANNER_V8", relateCardType = "BANNER_V8")))
    }
    @Test
    fun `combines exact enum route and commercial evidence`() {
        val kinds = HostContentSemanticClassifier.classify(
            HostContentSignals(
                cardType = "CARD_TYPE_CM_V2",
                goTo = "vertical_av",
                uri = "bilibili://game_center/detail?id=1",
                hasCommercialPayload = true
            )
        )

        assertTrue(HostContentKind.ADVERTISEMENT in kinds)
        assertTrue(HostContentKind.VERTICAL in kinds)
        assertTrue(HostContentKind.GAME in kinds)
    }

    @Test
    fun `ordinary title text alone does not become live or game content`() {
        val kinds = HostContentSemanticClassifier.classify(
            HostContentSignals(
                cardType = "AV",
                title = "直播技术与游戏开发纪录片"
            )
        )

        assertFalse(HostContentKind.LIVE in kinds)
        assertFalse(HostContentKind.GAME in kinds)
    }

    @Test
    fun `cm_v2 feed ads match exact card type only`() {
        assertTrue(
            HostContentSemanticClassifier.isCmV2(
                HostContentSignals(cardType = "cm_v2")
            )
        )
        assertTrue(
            HostContentSemanticClassifier.isCmV2(
                HostContentSignals(cardType = "CARD_TYPE_CM_V2")
            )
        )
        assertTrue(
            HostContentKind.ADVERTISEMENT in HostContentSemanticClassifier.classify(
                HostContentSignals(cardType = "cm_v2")
            )
        )
        assertFalse(
            HostContentSemanticClassifier.isCmV2(
                HostContentSignals(cardType = "small_cover_v2", cardGoto = "ad_web_s")
            )
        )
        assertFalse(
            HostContentSemanticClassifier.isCmV2(
                HostContentSignals(cardType = "ogv_small_cover", hasAdInfo = true)
            )
        )
        assertFalse(
            HostContentSemanticClassifier.isCmV2(
                HostContentSignals(cardType = "banner_v8")
            )
        )
    }

    @Test
    fun `unknown signals remain fail open`() {
        assertEquals(emptySet<HostContentKind>(), HostContentSemanticClassifier.classify(
            HostContentSignals(cardType = "FUTURE_CARD_V99")
        ))
    }

    @Test
    fun `related promotions keep specific kinds and also map to special fallback`() {
        val sourcePromotion = HostContentSemanticClassifier.classify(
            HostContentSignals(fromSourceType = 2L)
        )
        val resourcePromotion = HostContentSemanticClassifier.classify(
            HostContentSignals(relateCardType = "RELATE_CARD_TYPE_RESOURCE")
        )
        val gamePromotion = HostContentSemanticClassifier.classify(
            HostContentSignals(relateCardTypeValue = 4)
        )

        assertTrue(HostContentKind.ADVERTISEMENT in sourcePromotion)
        assertTrue(HostContentKind.SPECIAL in sourcePromotion)
        assertTrue(HostContentKind.ADVERTISEMENT in resourcePromotion)
        assertTrue(HostContentKind.SPECIAL in resourcePromotion)
        assertTrue(HostContentKind.GAME in gamePromotion)
        assertTrue(HostContentKind.SPECIAL in gamePromotion)
        assertTrue(
            HostContentKind.SPECIAL in HostContentSemanticClassifier.classify(
                HostContentSignals(relateCardTypeValue = 3)
            )
        )
        assertTrue(
            HostContentKind.SPECIAL in HostContentSemanticClassifier.classify(
                HostContentSignals(relateCardTypeValue = 5)
            )
        )
        assertTrue(
            HostContentKind.SPECIAL in HostContentSemanticClassifier.classify(
                HostContentSignals(relateCardTypeValue = 10)
            )
        )
        setOf("RESOURCE", "CM", "GAME", "SPECIAL").forEach { type ->
            assertTrue(
                HostContentKind.SPECIAL in HostContentSemanticClassifier.classify(
                    HostContentSignals(relateCardType = type)
                )
            )
        }
        assertEquals(
            emptySet<HostContentKind>(),
            HostContentSemanticClassifier.classify(
                HostContentSignals(fromSourceType = 99L, relateCardTypeValue = 99)
            )
        )
    }

    @Test
    fun `related card checks every explicit type before semantic fallback`() {
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesAnyType(
                types = linkedSetOf("AV", "CM"),
                hiddenTypes = setOf("CM")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesAnyType(
                types = setOf("RELATE_CARD_TYPE_VERTICAL_AV"),
                hiddenTypes = setOf("VERTICAL")
            )
        )
    }
}
