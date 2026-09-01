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
