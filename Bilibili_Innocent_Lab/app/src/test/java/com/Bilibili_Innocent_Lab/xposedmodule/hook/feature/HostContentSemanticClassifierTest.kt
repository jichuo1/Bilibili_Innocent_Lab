package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostContentSemanticClassifierTest {
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
