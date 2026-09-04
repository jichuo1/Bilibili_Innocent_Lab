package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单断言全部打在 [VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES] 上——那是运行时
 * 真正读的那一份。策略层不再自带副本，避免"测试锁一份、Hook 用另一份"。
 */
class PlayerInteractiveOverlayPolicyTest {

    private val familyByGuide = VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES
        .associateBy { it.guideClassName }

    @Test
    fun `policy reuses the adapter whitelist instead of copying it`() {
        assertEquals(
            VersionAdapter.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR,
            PlayerInteractiveOverlayPolicy.PRESERVED_VIDEO_POINT_CLEAR
        )
    }

    @Test
    fun `no family whitelist ever includes chapter points`() {
        assertTrue(VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES.isNotEmpty())
        VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES.forEach { family ->
            assertFalse(
                "family ${family.guideClassName} must keep chapter points",
                VersionAdapter.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR in family.clearNames
            )
            assertTrue(family.clearNames.isNotEmpty())
            assertTrue(family.clearNames.all { it.startsWith("clear") })
            assertEquals(family.clearNames.distinct(), family.clearNames)
        }
    }

    @Test
    fun `viewunite whitelist covers contract and material cards only`() {
        val family = requireNotNull(
            familyByGuide["com.bapis.bilibili.app.viewunite.v1.VideoGuide"]
        )
        assertEquals(
            listOf("clearContractCard", "clearMaterial", "clearRightMaterial"),
            family.clearNames
        )
    }

    @Test
    fun `view v1 whitelist covers vote follow contract and command cards`() {
        val family = requireNotNull(
            familyByGuide["com.bapis.bilibili.app.view.v1.VideoGuide"]
        )
        assertEquals(
            listOf(
                "clearAttention",
                "clearCommandDms",
                "clearContractCard",
                "clearOperationCard",
                "clearOperationCardNew",
                "clearCardsSecond"
            ),
            family.clearNames
        )
    }

    @Test
    fun `only viewunite carries the second DmResource whitelist`() {
        val unite = requireNotNull(familyByGuide["com.bapis.bilibili.app.viewunite.v1.VideoGuide"])
        val viewV1 = requireNotNull(familyByGuide["com.bapis.bilibili.app.view.v1.VideoGuide"])
        assertEquals("com.bapis.bilibili.app.viewunite.v1.DmResource", unite.dmClassName)
        assertEquals(listOf("clearAttention", "clearCards", "clearCommandDms"), unite.dmClearNames)
        assertEquals(null, viewV1.dmClassName)
        assertTrue(viewV1.dmClearNames.isEmpty())
        VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES.forEach { family ->
            assertFalse(
                VersionAdapter.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR in family.dmClearNames
            )
        }
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

    @Test
    fun `applyClears counts nothing when the method list is empty`() {
        assertEquals(0, PlayerInteractiveOverlayPolicy.applyClears(ClearProbe(), emptyList()))
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
