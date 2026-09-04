package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInteractiveOverlayPolicyTest {

    @Test
    fun `viewunite whitelist never includes chapter points`() {
        assertFalse(
            PlayerInteractiveOverlayPolicy.PRESERVED_VIDEO_POINT_CLEAR in
                PlayerInteractiveOverlayPolicy.viewUniteGuideClears
        )
        assertTrue(
            PlayerInteractiveOverlayPolicy.viewUniteGuideClears.containsAll(
                listOf("clearContractCard", "clearMaterial", "clearRightMaterial")
            )
        )
    }

    @Test
    fun `view v1 whitelist covers vote follow contract and command cards`() {
        assertTrue(
            PlayerInteractiveOverlayPolicy.viewV1GuideClears.containsAll(
                listOf(
                    "clearAttention",
                    "clearCommandDms",
                    "clearContractCard",
                    "clearOperationCard",
                    "clearOperationCardNew",
                    "clearCardsSecond"
                )
            )
        )
        assertFalse(
            PlayerInteractiveOverlayPolicy.PRESERVED_VIDEO_POINT_CLEAR in
                PlayerInteractiveOverlayPolicy.viewV1GuideClears
        )
    }

    @Test
    fun `accepted clears drop chapter points`() {
        assertEquals(
            listOf("clearContractCard"),
            PlayerInteractiveOverlayPolicy.acceptedClears(
                listOf("clearContractCard", "clearVideoPoint", "getVideoGuide")
            )
        )
        assertTrue(PlayerInteractiveOverlayPolicy.acceptedClears(emptyList()).isEmpty())
    }

    @Test
    fun `applyClears invokes zero-arg methods and ignores chapter points`() {
        val target = ClearProbe()
        val methods = listOf(
            ClearProbe::class.java.getDeclaredMethod("clearContractCard"),
            ClearProbe::class.java.getDeclaredMethod("clearVideoPoint"),
            ClearProbe::class.java.getDeclaredMethod("clearWithArg", String::class.java)
        )
        assertEquals(1, PlayerInteractiveOverlayPolicy.applyClears(target, methods))
        assertEquals(1, target.contract)
        assertEquals(0, target.point)
    }

    class ClearProbe {
        var contract = 0
        var point = 0

        fun clearContractCard() {
            contract += 1
        }

        fun clearVideoPoint() {
            point += 1
        }

        fun clearWithArg(value: String) {
            contract += 10
        }
    }
}
