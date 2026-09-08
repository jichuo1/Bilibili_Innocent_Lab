package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedSessionsTest {
    class Cell(var value: Any?)
    class Manager {
        @JvmField val base = Cell(1f)
        @JvmField val temporary = Cell(null)
        fun speed() = base.value as Float
    }
    class Index(@JvmField var mFrom: String = "vod")
    class Resource {
        var reads = 0
        var throwAt = -1
        fun getPlayIndex(): Index { check(++reads != throwAt); return Index(source) }
        var source = "vod"
    }
    class Item(val id: String)
    interface CoreApi {
        fun getMediaResource(): Resource
        fun getCurrentMediaItem(): Item
        fun getPlaySpeed(actual: Boolean): Float
        fun setPlaySpeed(value: Float)
    }
    class Core : CoreApi {
        var item = Item("a")
        val resource = Resource()
        var current = 1f
        override fun getMediaResource() = resource
        override fun getCurrentMediaItem() = item
        override fun getPlaySpeed(actual: Boolean) = current
        override fun setPlaySpeed(value: Float) { current = value }
    }
    interface Prepared { fun onPrepared(player: Any) }
    class Listener(@JvmField val core: Core) : Prepared { override fun onPrepared(player: Any) = Unit }
    class Context { @Suppress("UNUSED_PARAMETER") fun setOnPreparedListener(listener: Prepared) = Unit }
    class Params(val avid: Long, val cid: Long)
    class Playable(val params: Params)
    class Owner {
        @JvmField val manager = Manager()
        var active: Playable? = null
        @Suppress("UNUSED_PARAMETER") fun run(value: Playable?, continuation: Any?): Any = Any()
    }
    private fun speed() = PlayerSpeedLocator.DefaultSpeedPoint(Manager::class.java.getConstructor(),
        listOf("base","temporary").map { Manager::class.java.getField(it) },
        Cell::class.java.getMethod("getValue"), Cell::class.java.getMethod("setValue", Any::class.java),
        listOf(Manager::class.java.getMethod("speed")))
    private fun prepared() = PlayerSpeedSessionLocator.Prepared(
        Context::class.java.getMethod("setOnPreparedListener", Prepared::class.java), CoreApi::class.java,
        Prepared::class.java, Any::class.java, CoreApi::class.java.getMethod("getMediaResource"),
        CoreApi::class.java.getMethod("getCurrentMediaItem"), Item::class.java.getMethod("getId"),
        Resource::class.java.getMethod("getPlayIndex"), Index::class.java.getField("mFrom"),
        CoreApi::class.java.getMethod("getPlaySpeed", Boolean::class.javaPrimitiveType), setOf("vod","bangumi"))

    private inner class Harness(val requested: Float = 2f) {
        val registrar = PlayerPortTestRegistrar()
        val session = PlayerSpeedSessions(requested)
        val events = mutableListOf<FeatureRuntimeStage>()
        val env = HookEnvironment("tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
            registrar, { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { _, stage, _ -> events += stage })
        init { session.installPrepared(env, prepared(), speed()) }
        fun attach(core: Core, context: Context = Context()): Pair<Listener, Context> {
            val listener = Listener(core)
            registrar.invoke("player.speed.prepared_registration", context, arrayOf(listener))
            return listener to context
        }
        fun set(core: Core, value: Float) {
            registrar.invoke("player.speed.prepared_value." + Core::class.java.name, core, arrayOf(value)) {
                core.setPlaySpeed(it[0] as Float)
            }
        }
        fun ready(listener: Listener, action: () -> Unit = { set(listener.core, listener.core.current) }) {
            registrar.invoke("player.speed.prepared_scope." + Listener::class.java.name, listener, arrayOf(Any())) { action() }
        }
    }

    @Test fun mediaChangesResetOnlyBaseAndSameVideoPreservesManualSelection() {
        val manager = Manager()
        val session = PlayerSpeedSessions(2f)
        session.capture(manager, speed())
        assertTrue(session.applyForMedia(manager, "1:1", speed()))
        manager.base.value = 1.5f
        manager.temporary.value = 3f
        assertFalse(session.applyForMedia(manager, "1:1", speed()))
        assertEquals(1.5f, manager.base.value)
        assertEquals(3f, manager.temporary.value)
        assertTrue(session.applyForMedia(manager, "1:2", speed()))
        assertEquals(2f, manager.base.value)
        assertEquals(3f, manager.temporary.value)
        manager.temporary.value = null
        assertEquals(2f, manager.speed(), 0f)
    }

    @Test fun oneTimesExplicitlyResetsNewVideosAndPlayersDoNotShareState() {
        val session = PlayerSpeedSessions(1f)
        val first = Manager(); val second = Manager()
        session.capture(first, speed()); session.capture(second, speed())
        first.base.value = 2f; second.base.value = 3f
        assertTrue(session.applyForMedia(first, "1:1", speed()))
        assertEquals(3f, second.base.value)
        assertTrue(session.applyForMedia(second, "1:1", speed()))
        assertEquals(1f, second.base.value)
    }

    @Test fun unregisteredOrAmbiguousBaseIsNeverGuessed() {
        val session = PlayerSpeedSessions(2f); val manager = Manager()
        assertFalse(session.applyForMedia(manager, "a", speed()))
        manager.temporary.value = 1f
        session.capture(manager, speed())
        assertFalse(session.applyForMedia(manager, "a", speed()))
        assertEquals(1f, manager.base.value)
    }

    @Test fun legacyNewMediaGetsDefaultButManualAndRepeatedPrepareStayUnchanged() {
        val h = Harness(); val core = Core(); val (listener, context) = h.attach(core)
        h.ready(listener); assertEquals(2f, core.current, 0f)
        h.set(core, 1.5f)
        h.ready(listener); assertEquals(1.5f, core.current, 0f)
        core.item = Item("b")
        h.ready(listener); assertEquals(2f, core.current, 0f)
        assertNotNull(context)
    }

    @Test fun followupCoreSharingTheSameContextDoesNotResetCurrentVideo() {
        val h = Harness(); val context = Context(); val first = Core()
        val (listener, _) = h.attach(first, context); h.ready(listener)
        val next = Core().apply { current = 1.5f }
        val (nextListener, _) = h.attach(next, context)
        h.ready(nextListener)
        assertEquals(1.5f, next.current, 0f)
    }

    @Test fun modernPreparedRestorationUsesCurrentTemporaryOrManuallySelectedBase() {
        val h = Harness(); val core = Core(); val (listener, context) = h.attach(core)
        val manager = Manager()
        h.session.capture(manager, speed()); h.session.bind(core, manager)
        h.session.applyForMedia(manager, "1:1", speed())
        h.ready(listener); assertEquals(2f, core.current, 0f)
        manager.temporary.value = 3f
        h.ready(listener); assertEquals(3f, core.current, 0f)
        manager.temporary.value = null; manager.base.value = 1.5f
        h.ready(listener); assertEquals(1.5f, core.current, 0f)
        assertNotNull(context)
    }

    @Test fun exceptionsAlwaysClearScopeAndFailedWritesCanRetry() {
        val h = Harness(); val core = Core(); val (listener, context) = h.attach(core)
        val failure = IllegalStateException("host")
        assertSame(failure, assertThrows(IllegalStateException::class.java) { h.ready(listener) {
            h.registrar.invoke("player.speed.prepared_value." + Core::class.java.name, core, arrayOf(1f)) { throw failure }
        } })
        h.set(core, 1.25f); assertEquals(1.25f, core.current, 0f)
        h.ready(listener); assertEquals(2f, core.current, 0f)
        assertNotNull(context)
    }

    @Test fun nestedCallbacksAndOtherPlayersCannotConsumeOuterScope() {
        val h = Harness(); val one = Core(); val two = Core()
        val (a, c1) = h.attach(one); val (b, c2) = h.attach(two)
        h.ready(a) {
            h.set(two, 1.25f); assertEquals(1.25f, two.current, 0f)
            h.ready(b); assertEquals(2f, two.current, 0f)
            h.set(one, 1f)
        }
        assertEquals(2f, one.current, 0f)
        h.set(one, 1.75f); assertEquals(1.75f, one.current, 0f)
        assertNotNull(c1); assertNotNull(c2)
    }

    @Test fun liveUnknownSourcesAndMissingMediaIdentityAreUntouched() {
        val h = Harness(); val core = Core(); val (listener, context) = h.attach(core)
        for (source in listOf("live","music","unknown","")) {
            core.resource.source = source; core.current = 1.25f
            h.ready(listener); assertEquals(1.25f, core.current, 0f)
        }
        core.resource.source = "vod"; core.item = Item("")
        h.ready(listener); assertEquals(1.25f, core.current, 0f)
        assertFalse(FeatureRuntimeStage.APPLIED in h.events)
        assertNotNull(context)
    }

    @Test fun actualModernCallbackUsesBoundMediaNotQueuedArgumentsAndRejectsInvalidIdentity() {
        val h = Harness(); val owner = Owner()
        val point = PlayerSpeedSessionLocator.Modern(
            Owner::class.java.getMethod("run", Playable::class.java, Any::class.java),
            Owner::class.java.getMethod("getActive"), Playable::class.java.getMethod("getParams"),
            Params::class.java.getMethod("getAvid"), Params::class.java.getMethod("getCid"),
            Owner::class.java.getField("manager"), null)
        h.session.capture(owner.manager, speed())
        h.session.installModern(h.env, point, speed())
        val first = Playable(Params(1, 1)); val queued = Playable(Params(1, 2))
        owner.active = first
        fun invoke(argument: Playable?) = h.registrar.invoke("player.speed.media_session", owner, arrayOf(argument, null)) { Any() }
        invoke(first); assertEquals(2f, owner.manager.base.value)
        owner.manager.base.value = 1.5f
        invoke(queued); assertEquals(1.5f, owner.manager.base.value)
        owner.active = queued
        invoke(null); assertEquals(2f, owner.manager.base.value)
        owner.manager.base.value = 1.25f
        owner.active = Playable(Params(1, 0))
        invoke(null); assertEquals(1.25f, owner.manager.base.value)
        val failure = IllegalStateException("host coroutine")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            h.registrar.invoke("player.speed.media_session", owner, arrayOf(queued, null)) { throw failure }
        })
    }

    @Test fun invalidSpeedsNeverCreateRuntimePolicy() {
        for (value in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 4.01f)) {
            assertThrows(IllegalArgumentException::class.java) { PlayerSpeedSessions(value) }
        }
    }

    @Test fun sharedPlayerOrEarlyUserChoiceSurvivesFirstBindingButNotNextVideo() {
        val session = PlayerSpeedSessions(2f); val manager = Manager()
        session.capture(manager, speed())
        PlayerSpeedFeatureInstaller.applyDefaultSpeed(manager, speed(), 2f)
        session.initialized(manager)
        manager.base.value = 1.5f
        assertFalse(session.applyForMedia(manager, "1:1", speed()))
        assertEquals(1.5f, manager.base.value)
        assertTrue(session.applyForMedia(manager, "1:2", speed()))
        assertEquals(2f, manager.base.value)
    }

    @Test fun failingMediaReadInsideSetterDoesNotCrashOrChangeTheHostCall() {
        val h = Harness(); val core = Core(); val (listener, context) = h.attach(core)
        core.resource.throwAt = 2
        h.ready(listener)
        assertEquals(1f, core.current, 0f)
        assertFalse(FeatureRuntimeStage.APPLIED in h.events)
        h.set(core, 1.5f)
        assertEquals(1.5f, core.current, 0f)
        assertNotNull(context)
    }

    @Test fun failedInitialVerificationCannotBeReusedAsAnApprovedBase() {
        val session = PlayerSpeedSessions(2f); val manager = Manager()
        session.capture(manager, speed())
        session.discardCapture(manager)
        assertFalse(session.applyForMedia(manager, "1:1", speed()))
        assertEquals(1f, manager.base.value)
    }
}
