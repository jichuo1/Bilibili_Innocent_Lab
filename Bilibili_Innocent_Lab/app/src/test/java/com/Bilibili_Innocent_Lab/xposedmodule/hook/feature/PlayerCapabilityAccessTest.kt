package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerCapabilityAccess
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply
import com.bapis.bilibili.playershared.ArcConf
import com.bapis.bilibili.playershared.PlayArcConf
import com.bapis.bilibili.app.playurl.v1.ArcConf as LegacyArc
import com.bapis.bilibili.app.playurl.v1.PlayArcConf as LegacyConfig
import com.bapis.bilibili.app.playurl.v1.PlayViewReply
import org.junit.Assert.*
import org.junit.Test

class PlayerCapabilityAccessTest {
    private fun access(shared: Boolean, vararg enabled: PlayerCapability) = requireNotNull(
        PlayerCapabilityAccess.resolve(javaClass.classLoader!!,
            if (shared) PlayViewUniteReply::class.java.name else PlayViewReply::class.java.name,
            shared, enabled.toList())
    )

    @Test fun `unite copies only selected slots and preserves listener unknown flags and media`() {
        val background = ArcConf(true, false, "background-extra")
        val listener = ArcConf(true, false, "listener")
        val cast = ArcConf(true, false, "cast")
        val original = PlayArcConf(mapOf(9 to background, 36 to listener, 2 to cast, 999 to listener))
        val payload = Any()
        val reply = PlayViewUniteReply(original, videoPayload = payload)
        val plan = access(true, PlayerCapability.BACKGROUND)
        assertEquals(1, plan.apply(reply))
        val result = reply.getPlayArcConf()
        assertNotSame(original, result)
        assertEquals("keep-container", result.unrelated)
        assertEquals("background-extra", result.arcs[9]!!.unrelated)
        assertFalse(result.arcs[9]!!.getDisabled())
        assertTrue(result.arcs[9]!!.getIsSupport())
        assertSame(listener, result.arcs[36])
        assertSame(listener, result.arcs[999])
        assertSame(cast, result.arcs[2])
        assertTrue(background.getDisabled())
        assertFalse(background.getIsSupport())
        assertSame(payload, reply.videoPayload)
        assertEquals(0, plan.apply(reply))
        assertSame(result, reply.getPlayArcConf())
    }

    @Test fun `default protobuf instances are never modified even when missing slots are filled`() {
        val defaultArc = ArcConf.getDefaultInstance()
        val defaultConfig = PlayArcConf.getDefaultInstance()
        val defaultReply = PlayViewUniteReply.getDefaultInstance()
        val plan = access(true, *PlayerCapability.entries.toTypedArray())
        assertEquals(0, plan.apply(defaultReply))
        val reply = PlayViewUniteReply()
        assertEquals(3, plan.apply(reply))
        assertFalse(defaultArc.getIsSupport())
        assertTrue(defaultConfig.arcs.isEmpty())
        assertSame(defaultConfig, defaultReply.getPlayArcConf())
        assertEquals(setOf(2, 9, 23), reply.getPlayArcConf().arcs.keys)
    }

    @Test fun `video-less and unrelated responses remain untouched`() {
        val config = PlayArcConf(mapOf(9 to ArcConf()))
        val reply = PlayViewUniteReply(config, video = false)
        val plan = access(true, PlayerCapability.BACKGROUND)
        assertEquals(0, plan.apply(reply))
        assertSame(config, reply.getPlayArcConf())
        assertEquals(0, plan.apply(Any()))
    }

    @Test fun `legacy copy retains unselected configuration and default identities`() {
        val background = LegacyArc(true, false, "unchanged")
        val listener = LegacyArc(true, false, "listener")
        val config = LegacyConfig(mapOf(9 to background, 36 to listener))
        val reply = PlayViewReply(config)
        val plan = access(false, PlayerCapability.SMALL_WINDOW, PlayerCapability.CAST)
        assertEquals(2, plan.apply(reply))
        val updated = reply.getPlayArc()
        assertSame(background, updated.arcs[9])
        assertSame(listener, updated.arcs[36])
        assertEquals("keep-container", updated.unrelated)
        assertTrue(updated.getSmallWindowConf().getIsSupport())
        assertFalse(updated.getCastConf().getDisabled())
        assertFalse(LegacyArc.getDefaultInstance().getIsSupport())
        assertTrue(LegacyConfig.getDefaultInstance().arcs.isEmpty())
        assertEquals(0, plan.apply(PlayViewReply.getDefaultInstance()))
        assertEquals(0, plan.apply(reply))
    }

    @Test fun `empty capability selection and unavailable reply produce no access plan`() {
        assertNull(PlayerCapabilityAccess.resolve(javaClass.classLoader!!, PlayViewUniteReply::class.java.name, true, emptyList()))
        assertNull(PlayerCapabilityAccess.resolve(javaClass.classLoader!!, "missing.Reply", true, listOf(PlayerCapability.CAST)))
        assertEquals(listOf(PlayerCapability.BACKGROUND, PlayerCapability.SMALL_WINDOW, PlayerCapability.CAST),
            PlayerCapabilityOptions(true, true, true).enabled)
        assertFalse(PlayerCapability.entries.any { it.wireId == 36 })
    }
}
