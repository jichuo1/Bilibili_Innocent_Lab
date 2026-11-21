package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Admission
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ItemKey
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ManualChoice
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Operation
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Outcome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedFeatureInstaller.DefaultSpeedResult
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDefaultSpeedTest {
    class Cell(var value: Any?)
    class Manager {
        @JvmField val x = Cell(1f)
        @JvmField val y = Cell(null)
        var staleReadback = false
        fun base(): Float = if (staleReadback) 1f else x.value as Float
        fun composed(): Float = y.value as? Float ?: base()
    }
    private fun point(twoGetters: Boolean = true) = PlayerSpeedLocator.DefaultSpeedPoint(
        Manager::class.java.getDeclaredConstructor(),
        listOf(Manager::class.java.getDeclaredField("x"), Manager::class.java.getDeclaredField("y")),
        Cell::class.java.getDeclaredMethod("getValue"),
        Cell::class.java.getDeclaredMethod("setValue", Any::class.java),
        (if (twoGetters) listOf("base", "composed") else listOf("base")).map { Manager::class.java.getDeclaredMethod(it) }
    )
    private fun apply(target: Manager, value: Float, twoGetters: Boolean = true) =
        PlayerSpeedFeatureInstaller.applyDefaultSpeed(target, point(twoGetters), value)

    fun `old and new getter shapes initialize only the unique base slot`() {
        listOf(true, false).forEach {
            val manager = Manager()
            assertEquals(DefaultSpeedResult.APPLIED, apply(manager, 1.25f, it))
            assertEquals(1.25f, manager.x.value)
            assertNull(manager.y.value)
            assertEquals(1.25f, manager.composed(), 0f)
        }
    }
    fun `temporary playback still overrides base and release restores selected base`() {
        val manager = Manager()
        apply(manager, 1.5f)
        manager.y.value = 3f
        assertEquals(3f, manager.composed(), 0f)
        manager.y.value = null
        assertEquals(1.5f, manager.composed(), 0f)
        manager.x.value = 2f
        assertEquals(2f, manager.composed(), 0f)
    }
    fun `unexpected or previously modified state fails open`() {
        listOf(2f to null, 1f to 3f, 1f to 1f, null to null).forEach { (base, temporary) ->
            val manager = Manager().apply { x.value = base; y.value = temporary }
            assertNotEquals(DefaultSpeedResult.APPLIED, apply(manager, 2.5f))
            assertEquals(base, manager.x.value)
            assertEquals(temporary, manager.y.value)
        }
    }
    fun `failed readback rolls back before reporting failure`() {
        val manager = Manager().apply { staleReadback = true }
        assertEquals(DefaultSpeedResult.FAILED, apply(manager, 1.5f))
        assertEquals(1f, manager.x.value)
        assertNull(manager.y.value)
    }
    fun `one times is unchanged and second invocation never overwrites a different value`() {
        val manager = Manager()
        assertEquals(DefaultSpeedResult.UNCHANGED, apply(manager, 1f))
        assertEquals(DefaultSpeedResult.APPLIED, apply(manager, 1.5f))
        assertEquals(DefaultSpeedResult.UNEXPECTED_STATE, apply(manager, 2f))
        assertEquals(1.5f, manager.x.value)
    }
    fun `range endpoints reach the flow`() {
        listOf(PlayerSpeedConfig.MIN_MULTIPLIER, PlayerSpeedConfig.MAX_MULTIPLIER).forEach {
            val manager = Manager()
            assertEquals(DefaultSpeedResult.APPLIED, apply(manager, it))
            assertEquals(it, manager.x.value)
        }
    }

    fun `invalid runtime multipliers cannot reach the flow`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 0.09f, 8.01f).forEach {
            val manager = Manager()
            assertEquals(DefaultSpeedResult.UNEXPECTED_STATE, apply(manager, it))
            assertEquals(1f, manager.x.value)
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerDefaultSpeedTest) -> Unit) {
            val s = PlayerDefaultSpeedTest()
            block(s)
        }
        runIsolated { s -> s.`old and new getter shapes initialize only the unique base slot`() }
        runIsolated { s -> s.`temporary playback still overrides base and release restores selected base`() }
        runIsolated { s -> s.`unexpected or previously modified state fails open`() }
        runIsolated { s -> s.`failed readback rolls back before reporting failure`() }
        runIsolated { s -> s.`one times is unchanged and second invocation never overwrites a different value`() }
        runIsolated { s -> s.`range endpoints reach the flow`() }
        runIsolated { s -> s.`invalid runtime multipliers cannot reach the flow`() }
    
        PlayerInteractiveOverlayPolicyCases().verifyAllCases()
        PlayerQualitySessionsCases().verifyAllCases()
        PlayerSpeedConfigCases().verifyAllCases()
        PlayerSubtitleStyleCases().verifyAllCases()
        ProtobufListRetentionCases().verifyAllCases()
        ReplyTopologyLocateRouteCases().verifyAllCases()
        ReplyTopologyPageCallCases().verifyAllCases()
    }
}

private class PlayerInteractiveOverlayPolicyCases {

    private val familyByGuide = VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES
        .associateBy { it.guideClassName }

    fun `policy reuses the adapter whitelist instead of copying it`() {
        assertEquals(
            VersionAdapter.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR,
            PlayerInteractiveOverlayPolicy.PRESERVED_VIDEO_POINT_CLEAR
        )
    }

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

    fun `viewunite whitelist covers contract and material cards only`() {
        val family = requireNotNull(
            familyByGuide["com.bapis.bilibili.app.viewunite.v1.VideoGuide"]
        )
        assertEquals(
            listOf("clearContractCard", "clearMaterial", "clearRightMaterial"),
            family.clearNames
        )
    }

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

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerInteractiveOverlayPolicyCases) -> Unit) {
            val s = PlayerInteractiveOverlayPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`policy reuses the adapter whitelist instead of copying it`() }
        runIsolated { s -> s.`no family whitelist ever includes chapter points`() }
        runIsolated { s -> s.`viewunite whitelist covers contract and material cards only`() }
        runIsolated { s -> s.`view v1 whitelist covers vote follow contract and command cards`() }
        runIsolated { s -> s.`only viewunite carries the second DmResource whitelist`() }
        runIsolated { s -> s.`applyClears invokes zero-arg methods and ignores chapter points`() }
        runIsolated { s -> s.`applyClears counts nothing when the method list is empty`() }
    }
}

private class PlayerQualitySessionsCases {
    private val item = ItemKey(123L, 456L)

    fun `follow host and unknown quality never create intervention`() {
        for (qn in listOf(0, -1, 999)) {
            val sessions = PlayerQualitySessions(qn)
            val owner = Any()
            assertNull(sessions.start(owner, item))
            assertNull(sessions.target(owner, item, Admission.ALLOW))
            assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        }
    }

    fun `pipeline shares target after request and resource operations complete`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        for (operation in listOf(Operation.REQUEST_TARGET, Operation.RESOURCE_SELECT, Operation.REPARSE)) {
            repeat(3) { assertEquals(80, sessions.target(owner, item, Admission.ALLOW)) }
            val token = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, operation))
            assertEquals(80, token.qn)
            assertTrue(sessions.valid(token, owner, item, Admission.ALLOW))
            assertTrue(sessions.finish(token, owner, item, Admission.ALLOW, Outcome.COMPLETED))
            assertFalse(sessions.valid(token, owner, item, Admission.ALLOW))
            assertNull(sessions.reserve(owner, item, Admission.ALLOW, operation))
            assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        }
        val render = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.PLAYBACK_SETTLE))
        assertTrue(sessions.settle(render, owner, item, Admission.ALLOW))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
        assertFalse(sessions.settle(render, owner, item, Admission.ALLOW))
    }

    fun `owner identity ignores host equals and hash collisions`() {
        class EqualOwner {
            override fun equals(other: Any?) = other is EqualOwner
            override fun hashCode() = 1
        }
        val first = EqualOwner()
        val second = EqualOwner()
        val sessions = PlayerQualitySessions(80)
        sessions.start(first, item)
        sessions.start(second, item)
        val firstToken = requireNotNull(sessions.reserve(first, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        val secondToken = requireNotNull(sessions.reserve(second, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertFalse(sessions.valid(firstToken, second, item, Admission.ALLOW))
        assertTrue(sessions.valid(secondToken, second, item, Admission.ALLOW))
        assertTrue(sessions.manual(first, item, ManualChoice.FIXED))
        assertNull(sessions.target(first, item, Admission.ALLOW))
        assertEquals(80, sessions.target(second, item, Admission.ALLOW))
        assertTrue(sessions.valid(secondToken, second, item, Admission.ALLOW))
    }

    fun `same item new playback revokes old generation without ending new one`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        val first = requireNotNull(sessions.start(owner, item))
        val old = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        val second = requireNotNull(sessions.start(owner, item))
        val current = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertNotEquals(first, second)
        assertFalse(sessions.finish(old, owner, item, Admission.UNKNOWN, Outcome.COMPLETED))
        assertFalse(sessions.settle(old, owner, item, Admission.ALLOW))
        assertTrue(sessions.valid(current, owner, item, Admission.ALLOW))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
    }

    fun `manual fixed and auto synchronously revoke pending asynchronous work`() {
        for (choice in ManualChoice.entries) {
            val sessions = PlayerQualitySessions(80)
            val owner = Any()
            sessions.start(owner, item)
            val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
            assertTrue(sessions.manual(owner, item, choice))
            assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
            assertFalse(sessions.settle(pending, owner, item, Admission.ALLOW))
            assertFalse(sessions.finish(pending, owner, item, Admission.ALLOW, Outcome.COMPLETED))
            assertNull(sessions.target(owner, item, Admission.ALLOW))
            assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
            sessions.start(owner, item)
            assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        }
    }

    fun `changing item after reservation cannot revive old work on return`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        assertFalse(sessions.valid(pending, owner, ItemKey(123L, 789L), Admission.ALLOW))
        assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
        sessions.start(owner, item)
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
    }

    fun `unknown or denied admission revokes token permanently without refunding budget`() {
        for (blocked in listOf(Admission.UNKNOWN, Admission.DENY)) {
            val sessions = PlayerQualitySessions(80)
            val owner = Any()
            sessions.start(owner, item)
            val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
            assertFalse(sessions.valid(pending, owner, item, blocked))
            assertNull(sessions.target(owner, item, blocked))
            assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
            assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
            val fresh = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
            assertNotEquals(pending.revision, fresh.revision)
            assertFalse(sessions.finish(pending, owner, item, Admission.UNKNOWN, Outcome.COMPLETED))
            assertTrue(sessions.valid(fresh, owner, item, Admission.ALLOW))
        }
    }

    fun `blocked default read invalidates previously reserved work`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertNull(sessions.target(owner, item, Admission.DENY))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
        assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
    }

    fun `old request cannot settle a new resource revision after denied admission`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val request = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertNull(sessions.target(owner, item, Admission.DENY))
        val resource = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        assertNotEquals(request.revision, resource.revision)
        assertFalse(sessions.settle(request, owner, item, Admission.ALLOW))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        assertTrue(sessions.valid(resource, owner, item, Admission.ALLOW))
        assertTrue(sessions.settle(resource, owner, item, Admission.ALLOW))
    }

    fun `old resource cannot settle or alter admission of newer pending revision`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val resource = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        assertFalse(sessions.valid(resource, owner, item, Admission.UNKNOWN))
        val reparse = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertFalse(sessions.settle(resource, owner, item, Admission.DENY))
        assertTrue(sessions.valid(reparse, owner, item, Admission.ALLOW))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
    }

    fun `request success and finished resource cannot replace fresh render lease`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val request = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertFalse(sessions.settle(request, owner, item, Admission.ALLOW))
        assertTrue(sessions.finish(request, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertFalse(sessions.settle(request, owner, item, Admission.ALLOW))
        val resource = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        assertTrue(sessions.finish(resource, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertFalse(sessions.settle(resource, owner, item, Admission.ALLOW))
        val render = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.PLAYBACK_SETTLE))
        assertTrue(sessions.settle(render, owner, item, Admission.ALLOW))
        assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.PLAYBACK_SETTLE))
    }

    fun `failed operation remains spent and does not consume another pipeline operation`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val failed = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertTrue(sessions.finish(failed, owner, item, Admission.ALLOW, Outcome.FAILED))
        assertFalse(sessions.finish(failed, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertEquals(80, sessions.target(owner, item, Admission.ALLOW))
        assertTrue(sessions.reserve(owner, item, Admission.ALLOW, Operation.RESOURCE_SELECT) != null)
    }

    fun `close revokes work and target reads never silently create a new session`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val old = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        sessions.close(owner)
        assertFalse(sessions.valid(old, owner, item, Admission.ALLOW))
        assertFalse(sessions.finish(old, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
        assertNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        sessions.start(owner, item)
        val current = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertFalse(sessions.valid(old, owner, item, Admission.ALLOW))
        assertTrue(sessions.valid(current, owner, item, Admission.ALLOW))
    }

    fun `unknown item invalidates active session and invalid start preserves no old target`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val old = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        assertNull(sessions.target(owner, null, Admission.ALLOW))
        assertFalse(sessions.valid(old, owner, item, Admission.ALLOW))
        sessions.start(owner, item)
        assertNull(sessions.start(owner, ItemKey(0L, 456L)))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
    }

    fun `sixteen owner bound evicts oldest session and revokes its token`() {
        val sessions = PlayerQualitySessions(80)
        val owners = List(17) { Any() }
        sessions.start(owners.first(), item)
        val oldest = requireNotNull(sessions.reserve(owners.first(), item, Admission.ALLOW, Operation.REQUEST_TARGET))
        owners.drop(1).forEach { sessions.start(it, item) }
        assertFalse(sessions.valid(oldest, owners.first(), item, Admission.ALLOW))
        assertNull(sessions.target(owners.first(), item, Admission.ALLOW))
        owners.drop(1).forEach { assertEquals(80, sessions.target(it, item, Admission.ALLOW)) }
    }

    fun `restarting an existing owner counts once and refreshes eviction order`() {
        val sessions = PlayerQualitySessions(80)
        val owners = List(17) { Any() }
        owners.take(16).forEach { sessions.start(it, item) }
        sessions.start(owners.first(), item)
        sessions.start(owners.last(), item)
        assertEquals(80, sessions.target(owners.first(), item, Admission.ALLOW))
        assertNull(sessions.target(owners[1], item, Admission.ALLOW))
        assertEquals(80, sessions.target(owners.last(), item, Admission.ALLOW))
    }

    fun `settled playback revokes pending operation and preserves user control`() {
        val sessions = PlayerQualitySessions(80)
        val owner = Any()
        sessions.start(owner, item)
        val pending = requireNotNull(sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertTrue(sessions.settle(pending, owner, item, Admission.ALLOW))
        assertFalse(sessions.valid(pending, owner, item, Admission.ALLOW))
        assertFalse(sessions.finish(pending, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
        assertTrue(sessions.manual(owner, item, ManualChoice.AUTO))
        assertNull(sessions.target(owner, item, Admission.ALLOW))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerQualitySessionsCases) -> Unit) {
            val s = PlayerQualitySessionsCases()
            block(s)
        }
        runIsolated { s -> s.`follow host and unknown quality never create intervention`() }
        runIsolated { s -> s.`pipeline shares target after request and resource operations complete`() }
        runIsolated { s -> s.`owner identity ignores host equals and hash collisions`() }
        runIsolated { s -> s.`same item new playback revokes old generation without ending new one`() }
        runIsolated { s -> s.`manual fixed and auto synchronously revoke pending asynchronous work`() }
        runIsolated { s -> s.`changing item after reservation cannot revive old work on return`() }
        runIsolated { s -> s.`unknown or denied admission revokes token permanently without refunding budget`() }
        runIsolated { s -> s.`blocked default read invalidates previously reserved work`() }
        runIsolated { s -> s.`old request cannot settle a new resource revision after denied admission`() }
        runIsolated { s -> s.`old resource cannot settle or alter admission of newer pending revision`() }
        runIsolated { s -> s.`request success and finished resource cannot replace fresh render lease`() }
        runIsolated { s -> s.`failed operation remains spent and does not consume another pipeline operation`() }
        runIsolated { s -> s.`close revokes work and target reads never silently create a new session`() }
        runIsolated { s -> s.`unknown item invalidates active session and invalid start preserves no old target`() }
        runIsolated { s -> s.`sixteen owner bound evicts oldest session and revokes its token`() }
        runIsolated { s -> s.`restarting an existing owner counts once and refreshes eviction order`() }
        runIsolated { s -> s.`settled playback revokes pending operation and preserves user control`() }
    }
}

private class PlayerSpeedConfigCases {
    fun `range is bounded with follow host sentinel`() {
        assertNull(PlayerSpeedConfig.multiplier(0))
        assertEquals(0.1f, PlayerSpeedConfig.multiplier(10))
        assertEquals(0.25f, PlayerSpeedConfig.multiplier(25))
        assertEquals(4f, PlayerSpeedConfig.multiplier(400))
        assertEquals(8f, PlayerSpeedConfig.multiplier(800))
        listOf(-1, 1, 9, 801, Int.MAX_VALUE).forEach {
            assertEquals(0, PlayerSpeedConfig.normalize(it))
            assertNull(PlayerSpeedConfig.multiplier(it))
        }
    }
    fun `decimal parsing never rounds invalid input into range`() {
        listOf("NaN", "Infinity", "", " ", "1.001", "0.099", "0.09", "8.001", "8.01", "-1", "1,25").forEach {
            assertNull(it, PlayerSpeedConfig.parseMultiplier(it))
        }
        assertEquals(125, PlayerSpeedConfig.parseMultiplier(" 1.25 "))
        assertEquals(10, PlayerSpeedConfig.parseMultiplier("0.1"))
        assertEquals(10, PlayerSpeedConfig.parseMultiplier(".10"))
        assertEquals(25, PlayerSpeedConfig.parseMultiplier("0.25"))
        assertEquals(400, PlayerSpeedConfig.parseMultiplier("4.00"))
        assertEquals(475, PlayerSpeedConfig.parseMultiplier("4.75"))
        assertEquals(800, PlayerSpeedConfig.parseMultiplier("8"))
        assertEquals("0.1", PlayerSpeedConfig.formatMultiplier(10))
        assertEquals("8", PlayerSpeedConfig.formatMultiplier(800))
    }
    fun `all supported values round trip without floating point drift`() {
        PlayerSpeedConfig.supportedPercents.forEach {
            assertEquals(it, PlayerSpeedConfig.parseMultiplier(PlayerSpeedConfig.formatMultiplier(it)))
        }
    }
    fun `disable wins without changing saved custom speed`() {
        assertEquals(0, PlayerSpeedConfig.effectiveLongPressPercent(true, 275))
        assertEquals(275, PlayerSpeedConfig.effectiveLongPressPercent(false, 275))
    }
    fun `menu multipliers parse valid 0_1 to 8 values and sort descending with deduplication`() {
        assertEquals(
            listOf(800, 300, 200, 150, 125, 100, 75, 50, 10),
            PlayerSpeedConfig.parseMenuMultipliers("0.5, 0.75, 1.0, 1.25x, 1.5X，2.0；3倍 8 0.10 1.5")
        )
        listOf("", "   ", "0", "0, 1.0", "0.09, 1.0", "1.0, 8.01", "1.0, 1.234", "1.0, abc").forEach {
            assertNull(it, PlayerSpeedConfig.parseMenuMultipliers(it))
        }
        val tooMany = (1..13).joinToString(", ") { "1.$it" }
        assertNull(PlayerSpeedConfig.parseMenuMultipliers(tooMany))
    }
    fun `stored menu spec round trips and rejects malformed or out of range strings`() {
        val percents = listOf(300, 200, 150, 125, 100, 75, 50, 10)
        val encoded = PlayerSpeedConfig.encodeMenuPercents(percents)
        assertEquals("300,200,150,125,100,75,50,10", encoded)
        assertEquals(percents, PlayerSpeedConfig.parseStoredMenuPercents(encoded))
        assertEquals("3, 2, 1.5, 1.25, 1, 0.75, 0.5, 0.1", PlayerSpeedConfig.formatMenuInput(percents))
        assertEquals("3x / 2x / 1.5x / 1.25x / 1x / 0.75x / 0.5x / 0.1x", PlayerSpeedConfig.formatMenuSummary(percents))
        listOf(null, "", "  ", "0", "9,100", "801,100", "100,bad", "1".repeat(200),
            "1,100", "1.25,100", "100,,50", ",100", "100,", "+100").forEach {
            assertTrue(PlayerSpeedConfig.parseStoredMenuPercents(it).isEmpty())
        }
    }
    fun `every supported menu speed round trips with the same precision as single speed`() {
        for (percent in PlayerSpeedConfig.MIN_PERCENT..PlayerSpeedConfig.MAX_PERCENT) {
            assertEquals(listOf(percent), PlayerSpeedConfig.parseMenuMultipliers(PlayerSpeedConfig.formatMultiplier(percent)))
            assertEquals(listOf(percent), PlayerSpeedConfig.parseStoredMenuPercents(PlayerSpeedConfig.encodeMenuPercents(listOf(percent))))
        }
        val twelve = (100..111).toList()
        assertEquals(twelve.reversed(), PlayerSpeedConfig.parseMenuMultipliers(PlayerSpeedConfig.formatMenuInput(twelve)))
        assertNull(PlayerSpeedConfig.parseMenuMultipliers("1,".repeat(100)))
        assertTrue(PlayerSpeedConfig.parseStoredMenuPercents((100..112).joinToString(",")).isEmpty())
    }
    fun `menu customization is mutually exclusive with default speed but independent of long press`() {
        val menuSpec = "300,200,150,100,50"
        assertEquals(listOf(300, 200, 150, 100, 50), PlayerSpeedConfig.effectiveMenuPercents(0, menuSpec))
        assertEquals(listOf(3f, 2f, 1.5f, 1f, 0.5f), PlayerSpeedConfig.menuMultipliers(0, menuSpec))
        assertFalse(PlayerSpeedConfig.conflictsWithDefaultSpeed(0, menuSpec))

        // Default speed active -> menu customization suppressed
        assertTrue(PlayerSpeedConfig.conflictsWithDefaultSpeed(150, menuSpec))
        assertTrue(PlayerSpeedConfig.effectiveMenuPercents(150, menuSpec).isEmpty())
        assertTrue(PlayerSpeedConfig.menuMultipliers(150, menuSpec).isEmpty())

        // Long-press speed remains independent when menu customization is active
        assertEquals(300, PlayerSpeedConfig.effectiveLongPressPercent(false, 300))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerSpeedConfigCases) -> Unit) {
            val s = PlayerSpeedConfigCases()
            block(s)
        }
        runIsolated { s -> s.`range is bounded with follow host sentinel`() }
        runIsolated { s -> s.`decimal parsing never rounds invalid input into range`() }
        runIsolated { s -> s.`all supported values round trip without floating point drift`() }
        runIsolated { s -> s.`disable wins without changing saved custom speed`() }
        runIsolated { s -> s.`menu multipliers parse valid 0_1 to 8 values and sort descending with deduplication`() }
        runIsolated { s -> s.`stored menu spec round trips and rejects malformed or out of range strings`() }
        runIsolated { s -> s.`every supported menu speed round trips with the same precision as single speed`() }
        runIsolated { s -> s.`menu customization is mutually exclusive with default speed but independent of long press`() }
    }
}

private class PlayerSubtitleStyleCases {
    fun `style preserves independent alpha channels and font choices`() {
        for (font in PlayerSubtitleStyle.FONTS) {
            val style = PlayerSubtitleStyle(sizePercent = 250, font = font, bold = true,
                textColor = 0x123456, textOpacity = 17, backgroundOpacity = 83,
                outlineOpacity = 42, outlineWidth = 60)
            assertEquals(style, PlayerSubtitleStyle.decode(style.encode()))
        }
        assertEquals(0x00123456, PlayerSubtitleStyle.argb(0x123456, 0))
        assertEquals(0xFF123456.toInt(), PlayerSubtitleStyle.argb(0x123456, 100))
        assertEquals(0x80123456.toInt(), PlayerSubtitleStyle.argb(0x123456, 50))
    }

    fun `corrupt or future settings preserve official rendering`() {
        val valid = PlayerSubtitleStyle().encode()
        for (raw in listOf(null, "", "{}", "[1]", "x".repeat(1025),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"size\":100", "\"size\":999"),
            valid.replace("\"size\":100", "\"size\":100.5"),
            valid.replace("\"size\":100", "\"size\":\"100\""),
            valid.replace("\"font\":\"sans-serif\"", "\"font\":\"/tmp/font.ttf\""),
            valid.replace("\"textOpacity\":100", "\"textOpacity\":-1"))) {
            assertNull(raw, PlayerSubtitleStyle.decode(raw))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerSubtitleStyleCases) -> Unit) {
            val s = PlayerSubtitleStyleCases()
            block(s)
        }
        runIsolated { s -> s.`style preserves independent alpha channels and font choices`() }
        runIsolated { s -> s.`corrupt or future settings preserve official rendering`() }
    }
}

private class ProtobufListRetentionCases {

    fun `retainOrNull signals no rewrite by returning null`() {
        val source = listOf("a", "b")
        assertNull(ProtobufListRetention.retainOrNull(source) { true })
    }

    fun `retainOrNull keeps order and drops nulls`() {
        val source = listOf("keep", null, "drop", "keep-2")
        val retained = ProtobufListRetention.retainOrNull(source) { it != "drop" }
        assertEquals(listOf("keep", "keep-2"), retained)
        assertEquals(listOf("keep", null, "drop", "keep-2"), source)
    }

    fun `filterOrSame returns the same instance when nothing matches`() {
        val source = listOf("a", "b")
        assertSame(source, ProtobufListRetention.filterOrSame(source) { false })
    }

    fun `filterOrSame returns an immutable copy that preserves nulls`() {
        val source = listOf("keep", null, "drop")
        val filtered = ProtobufListRetention.filterOrSame(source) { it == "drop" }

        assertNotSame(source, filtered)
        assertEquals(listOf("keep", null), filtered)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (filtered as MutableList<Any?>).add("x")
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ProtobufListRetentionCases) -> Unit) {
            val s = ProtobufListRetentionCases()
            block(s)
        }
        runIsolated { s -> s.`retainOrNull signals no rewrite by returning null`() }
        runIsolated { s -> s.`retainOrNull keeps order and drops nulls`() }
        runIsolated { s -> s.`filterOrSame returns the same instance when nothing matches`() }
        runIsolated { s -> s.`filterOrSame returns an immutable copy that preserves nulls`() }
    }
}

private class ReplyTopologyLocateRouteCases {

    private val key = ReplyTopologyThreadKey(
        oid = 456L,
        type = 1L,
        rootRpid = 789L
    )

    fun childRouteUsesPublicDetailOrderAndAnchor() {
        assertEquals(
            "bilibili://comment/detail/1/456/789?anchor=987",
            buildReplyTopologyLocateRoute(key, 987L)
        )
    }

    fun rootRouteOmitsAnchor() {
        assertEquals(
            "bilibili://comment/detail/1/456/789",
            buildReplyTopologyLocateRoute(key, 789L)
        )
    }

    fun invalidIdentityDoesNotProduceARoute() {
        assertNull(buildReplyTopologyLocateRoute(key, 0L))
        assertNull(
            buildReplyTopologyLocateRoute(
                ReplyTopologyThreadKey(oid = 0L, type = 1L, rootRpid = 789L),
                987L
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyLocateRouteCases) -> Unit) {
            val s = ReplyTopologyLocateRouteCases()
            block(s)
        }
        runIsolated { s -> s.childRouteUsesPublicDetailOrderAndAnchor() }
        runIsolated { s -> s.rootRouteOmitsAnchor() }
        runIsolated { s -> s.invalidIdentityDoesNotProduceARoute() }
    }
}

private class ReplyTopologyPageCallCases {

    fun cancelClearsResponseAndSuppressesLateCompletion() {
        var callbackCount = 0
        val call = ReplyTopologyPageCall { callbackCount++ }
        call.retainClient(Any())
        call.retainResponse(Any())

        call.cancel()
        call.retainResponse(Any())
        call.complete(Result.success(emptyPage()))

        assertNull(call.responseOrNull())
        assertEquals(0, callbackCount)
    }

    fun completionInvokesCallbackAtMostOnce() {
        var callbackCount = 0
        var deliveredPage: ReplyTopologyHostPage? = null
        val expected = emptyPage()
        val call = ReplyTopologyPageCall { result ->
            callbackCount++
            deliveredPage = result.getOrNull()
        }

        call.complete(Result.success(expected))
        call.complete(Result.failure(IllegalStateException("late error")))
        call.cancel()

        assertEquals(1, callbackCount)
        assertEquals(expected, deliveredPage)
        assertNull(call.responseOrNull())
    }

    fun responseThreadIdentityMustMatchTheRequestedThreadExactly() {
        val expected = ReplyTopologyThreadKey(oid = 100L, type = 1L, rootRpid = 200L)

        requireMatchingReplyTopologyThread(expected, expected.copy())
        assertThrows(IllegalStateException::class.java) {
            requireMatchingReplyTopologyThread(expected, expected.copy(oid = 101L))
        }
        assertThrows(IllegalStateException::class.java) {
            requireMatchingReplyTopologyThread(expected, expected.copy(type = 2L))
        }
        assertThrows(IllegalStateException::class.java) {
            requireMatchingReplyTopologyThread(expected, expected.copy(rootRpid = 201L))
        }
    }

    fun proxyFallbackReturnsBoxableZeroForEveryPrimitiveType() {
        assertFalse(defaultReplyTopologyProxyValue(java.lang.Boolean.TYPE) as Boolean)
        assertEquals(0.toByte(), defaultReplyTopologyProxyValue(java.lang.Byte.TYPE))
        assertEquals('\u0000', defaultReplyTopologyProxyValue(java.lang.Character.TYPE))
        assertEquals(0.toShort(), defaultReplyTopologyProxyValue(java.lang.Short.TYPE))
        assertEquals(0, defaultReplyTopologyProxyValue(java.lang.Integer.TYPE))
        assertEquals(0L, defaultReplyTopologyProxyValue(java.lang.Long.TYPE))
        assertEquals(0f, defaultReplyTopologyProxyValue(java.lang.Float.TYPE))
        assertEquals(0.0, defaultReplyTopologyProxyValue(java.lang.Double.TYPE))
        assertNull(defaultReplyTopologyProxyValue(java.lang.Void.TYPE))
        assertNull(defaultReplyTopologyProxyValue(String::class.java))
    }

    private fun emptyPage() = ReplyTopologyHostPage(
        nodes = emptyList(),
        nextOffset = null,
        expectedReplyCount = 0
    )

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyPageCallCases) -> Unit) {
            val s = ReplyTopologyPageCallCases()
            block(s)
        }
        runIsolated { s -> s.cancelClearsResponseAndSuppressesLateCompletion() }
        runIsolated { s -> s.completionInvokesCallbackAtMostOnce() }
        runIsolated { s -> s.responseThreadIdentityMustMatchTheRequestedThreadExactly() }
        runIsolated { s -> s.proxyFallbackReturnsBoxableZeroForEveryPrimitiveType() }
    }
}
