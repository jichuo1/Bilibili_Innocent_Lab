package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import org.junit.Assert.*
import org.junit.Test

class GuestVideoFollowInitialStateTest {
    open class Callback
    class OwnerCallback : Callback()
    class LegacyOwnerCallback : Callback()
    class OtherCallback : Callback()
    class Config(private val mid: Long, private val callback: Callback?, private val guest: Boolean?) {
        fun getMid(): Long = mid
        fun getCallback(): Callback? = callback
        fun isGuestAttention(): Boolean? = guest
    }
    class Helper(@JvmField var c: Long)
    class Button {
        var helper: Helper? = null
        var following = false
        var secondary: Boolean? = null
        var updates = 0
        fun bind(config: Config) { helper = Helper(config.getMid()) }
        fun getMFollowFlowHelper(): Helper? = helper
        fun updateUI(value: Boolean) { following = value; secondary = null; updates++ }
        fun updateUI(value: Boolean, guest: Boolean) { following = value; secondary = guest; updates++ }
    }
    class Source : GuestVideoFollowSource {
        val requests = mutableListOf<Pair<Long, (Boolean) -> Unit>>()
        override fun read(mid: Long, callback: (Boolean) -> Unit) { requests += mid to callback }
    }
    private inner class Harness {
        val registrar = PlayerPortTestRegistrar()
        val source = Source()
        var guest = true
        val button = Button()
        val statuses = mutableMapOf<String, String>()
        val loader = javaClass.classLoader!!
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })
        init {
            GuestVideoFollowInitialState(source, { guest }, GuestVideoFollowInitialState.Names(
                Button::class.java.name, Config::class.java.name, Helper::class.java.name, OwnerCallback::class.java.name, LegacyOwnerCallback::class.java.name
            )).install(env)
        }
        fun bind(config: Config) = registrar.invoke("guest.video.follow.initial", button, arrayOf(config)) { args ->
            button.bind(args[0] as Config); null
        }
    }

    @Test fun localFollowStateUpdatesTheOwnerButtonAndPreservesItsGuestFlag() {
        val h = Harness()
        h.bind(Config(42, OwnerCallback(), true))
        assertEquals(42L, h.source.requests.single().first)
        h.source.requests.single().second(true)
        assertTrue(h.button.following)
        assertEquals(true, h.button.secondary)
        assertEquals("applied", h.statuses["guest_video_follow_initial"])
    }

    @Test fun legacyOwnerCallbackReceivesTheLocalState() {
        val h = Harness()
        h.bind(Config(42, LegacyOwnerCallback(), false))
        h.source.requests.single().second(true)
        assertTrue(h.button.following)
        assertEquals(false, h.button.secondary)
    }

    @Test fun delayedReadsCannotChangeAReboundButton() {
        val h = Harness()
        h.bind(Config(42, OwnerCallback(), null))
        h.bind(Config(99, OwnerCallback(), null))
        h.source.requests[0].second(true)
        assertEquals(0, h.button.updates)
        h.source.requests[1].second(false)
        assertEquals(1, h.button.updates)
        assertFalse(h.button.following)
        assertNull(h.button.secondary)
    }

    @Test fun otherPagesAndLoginChangesDoNotReceiveGuestState() {
        val h = Harness()
        h.bind(Config(42, OtherCallback(), false))
        assertTrue(h.source.requests.isEmpty())
        h.bind(Config(0, OwnerCallback(), false))
        assertTrue(h.source.requests.isEmpty())
        h.bind(Config(42, OwnerCallback(), false))
        h.guest = false
        h.source.requests.single().second(true)
        assertEquals(0, h.button.updates)
        h.bind(Config(99, OwnerCallback(), false))
        assertEquals(1, h.source.requests.size)
    }
}
