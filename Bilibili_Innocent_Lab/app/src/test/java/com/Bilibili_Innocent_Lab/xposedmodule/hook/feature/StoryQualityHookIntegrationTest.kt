package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.app.comm.restrict.RestrictedMode
import com.bilibili.lib.accounts.BiliAccounts
import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.player.tangram.basic.StoryPreferenceFixture
import com.bilibili.teenagersmode.TeenagersMode
import com.bilibili.video.story.player.StoryPlayer
import com.bilibili.video.story.player.datasource.PlayableProvider
import com.bilibili.video.story.player.datasource.FixtureResolveListener
import com.bilibili.video.story.player.quality.FixtureCore
import com.bilibili.video.story.player.quality.StoryQualityFacade
import com.bilibili.video.story.player.quality.StoryQualityService
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import tv.danmaku.biliplayerv2.service.FixturePlayableParams
import tv.danmaku.biliplayerv2.service.Video
import tv.danmaku.biliplayerv2.service.resolve.AbsMediaResourceResolveTask
import tv.danmaku.videoplayer.coreV2.VideoBizType
import tv.danmaku.videoplayer.coreV2.MediaItem

/** 实际 HostAccess 解析和实际 Modern DSL 回调；不反射构造安装器的私有访问器。 */
class StoryQualityHookIntegrationTest {
    private val loader = javaClass.classLoader!!
    private val continuation = object : Continuation<Any?> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) = Unit
    }

    @Before fun resetAdmission() {
        BiliAccounts.instance.loggedIn = true
        BiliAccounts.fail = false
        TeenagersMode.getInstance().enabled = false
        RestrictedMode.enabled = false
    }
    @After fun clearAdmission() = resetAdmission()

    private fun point(method: Method) = VersionAdapter.HookPoint(method.declaringClass.name, method.name,
        method.parameterTypes.map(Class<*>::getName))
    private fun parameters(aid: Long = 123, cid: Long = 456, from: String = "vupload") =
        FixturePlayableParams(Video.DisplayParams(aid, cid, from))

    private inner class Harness(failId: String? = null, private val useClaims: Boolean = false) {
        val port = PlayerPortTestRegistrar(failId)
        val registry = HookPointRegistry(loader)
        private fun claimed(id: String, member: Executable, install: () -> Unit) {
            if (!useClaims) { install(); return }
            if (!registry.claim(id, member)) return
            try {
                install()
                registry.markInstalled(id, member)
            } catch (failure: Throwable) {
                registry.markFailed(id, member, failure)
                throw failure
            }
        }
        private val registrar = object : HookRegistrar by port {
            override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>,
                               block: ModernMemberHookCreator.() -> Unit) {
                val method = requireNotNull(registry.resolveExact(id, owner, methodName, *parameterTypes))
                claimed(id, method) { port.exact(id, method.declaringClass, method.name, *method.parameterTypes, block = block) }
            }
            override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) =
                claimed(id, constructor) { port.constructor(id, constructor, block) }
            override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                                 block: ModernMemberHookCreator.() -> Unit) {
                val method = requireNotNull(registry.resolveAdapted(id, point.className, point.methodName, point.paramClassNames))
                claimed(id, method) { port.exact(id, method.declaringClass, method.name, *method.parameterTypes, block = block) }
            }
        }
        val stages = arrayListOf<FeatureRuntimeStage>()
        val statuses = arrayListOf<String>()
        val owner = StoryPlayer().also { it.source.values += listOf(parameters(), parameters(cid = 789)) }
        val storyPoints = requireNotNull(StoryQualityLocator.direct(loader)).copy(
            integerDefaultMethod = point(StoryQualityFacade::class.java.getDeclaredMethod("integerDefault")),
            policyMethod = point(StoryQualityFacade::class.java.getDeclaredMethod("policy")),
            resourceSelectionMethod = point(StoryQualityFacade::class.java.getDeclaredMethod("resource", MediaResource::class.java)),
            matchPreferenceMethod = point(StoryQualityService::class.java.getDeclaredMethod("matchPreference", Continuation::class.java)))
        val access = requireNotNull(StoryQualityHostAccess.resolve(loader,
            requireNotNull(StoryQualityLocator.resolve(loader, storyPoints))))
        val installer = PlayerQualityFeatureInstaller(80, VersionAdapter.PlayerQualityPoints(
            VersionAdapter.HookPoint("gh6.h", "c", emptyList()), story = storyPoints))
        val environment = HookEnvironment("tv.danmaku.bili", loader, registry, registrar,
            logInfo = { _, _ -> }, logError = { _, _ -> }, reportStatus = { _, status -> statuses += status },
            runtimeEvidence = { _, stage, _ -> stages += stage })
        fun install() = installer.install(environment)
        fun create() = port.invoke("player.story.create", owner, arrayOf(null)) { owner.onCreate(null); Unit }
        fun select(index: Int = 0): Any? = port.invoke("player.story.select", owner.controller, arrayOf(index)) {
            owner.controller.select(it[0] as Int)
        }
        fun start() {
            val installed = install() as FeatureInstallResult.Installed
            assertTrue(installed.complete)
            create(); assertEquals(0, select())
        }
        fun global(): Any? = port.invoke("player.default_quality", gh6.h()) { 32 }
        fun wifi(): Any? = port.invoke("player.story.wifi", null,
            arrayOf(StoryQualityLocator.WIFI_KEY, 32)) { 32 }
        fun policy(original: Any = StoryPreferenceFixture.Auto): Any? = port.invoke("player.story.policy") { original }
        fun request(parameters: Video.PlayableParams = owner.getCurrentPlayableParam()!!,
                    requestedQn: Int? = 32,
                    listener: FixtureResolveListener? = null,
                    original: (Array<Any?>) -> Any? = { Unit }): Any? =
            port.invoke("player.story.request", owner.source,
                arrayOf(requestedQn, false, "fixture", 0, false, parameters, listener), original)
        val core: FixtureCore get() = owner.quality.core as FixtureCore
        fun match(original: (Array<Any?>) -> Any? = { Unit }) =
            port.invoke("player.story.match", owner.quality, arrayOf(continuation), original)
        fun resource(value: MediaResource, original: (Array<Any?>) -> Any? = { Unit }) =
            port.invoke("player.story.resource", null, arrayOf(value), original)
        fun consume(parameters: Video.PlayableParams, resource: MediaResource, position: Long?,
                    original: (Array<Any?>) -> Any?): Any? = port.invoke("player.story.consume_reparse",
            owner.source.consumer, arrayOf(parameters, resource, null, position), original)
        fun coroutine(task: AbsMediaResourceResolveTask = AbsMediaResourceResolveTask(),
                      parameters: Video.PlayableParams = owner.getCurrentPlayableParam()!!) =
            PlayableProvider.resolve.`1`(task, null, parameters, null, owner.source, continuation)
        fun capture(coroutine: PlayableProvider.resolve.`1`) = port.invoke("player.story.resolve_capture", coroutine,
            arrayOf(coroutine.task, null, coroutine.parameters, null, coroutine.provider, continuation)) { Unit }
        fun clone(parent: PlayableProvider.resolve.`1`): PlayableProvider.resolve.`1` =
            port.invoke("player.story.resolve_clone", parent, arrayOf(Unit, continuation)) {
                val child = parent.create(Unit, continuation) as PlayableProvider.resolve.`1`
                capture(child)
                child
            } as PlayableProvider.resolve.`1`
        fun resolve(coroutine: PlayableProvider.resolve.`1`, original: (Array<Any?>) -> Any? = { Unit }) =
            port.invoke("player.story.resolve", coroutine, arrayOf(Unit), original)
        fun manual(value: StoryPreferenceFixture, original: (Array<Any?>) -> Any? = {
            owner.quality.manual(it[0] as StoryPreferenceFixture, continuation)
        }) = port.invoke("player.story.manual", owner.quality, arrayOf(value, continuation), original)
        fun pending(task: AbsMediaResourceResolveTask = AbsMediaResourceResolveTask()): PlayableProvider.resolve.`1` {
            val coroutine = coroutine(task)
            request { args ->
                assertEquals(80, args[0]); assertEquals(false, args[1]); assertEquals("fixture", args[2])
                assertEquals(0, args[3]); assertEquals(false, args[4]); assertSame(coroutine.parameters, args[5])
                assertNull(args[6]); capture(coroutine); Unit
            }
            return clone(coroutine)
        }
    }

    private inner class Replay(val h: Harness) {
        val parameters = h.owner.getCurrentPlayableParam()!!
        var wrapped: FixtureResolveListener? = null
        var delegated = 0
        var errors = 0
        val positions = arrayListOf<Long>()
        var beforeConsume: () -> Unit = {}
        private val requestedPosition = h.core.position.toLong()
        val original = object : FixtureResolveListener {
            override fun success(resource: MediaResource) {
                delegated++
                beforeConsume()
                h.consume(parameters, resource, requestedPosition) { args ->
                    val position = args.last() as Long
                    positions += position
                    h.core.resource = resource
                    h.core.position = position.toInt()
                    h.core.playState = 4
                    MediaItem()
                }
            }
            override fun onError() { errors++ }
        }
        init {
            h.owner.source.onReload = { call ->
                h.request(requestedQn = call.qn, listener = original) { args ->
                    assertEquals(80, args[0])
                    assertNotSame(original, args.last())
                    assertTrue(Proxy.isProxyClass(args.last()!!.javaClass))
                    wrapped = args.last() as FixtureResolveListener
                    Unit
                }
            }
        }
        fun begin() {
            val privateResource = MediaResource(16).also { it.language = "ja-JP"; it.productionType = 9 }
            h.core.resource = privateResource
            val coroutine = h.pending()
            h.resolve(coroutine) { h.resource(privateResource); Unit }
            h.match()
            assertNotNull(wrapped)
        }
    }

    @Test fun `actual host graph requires display bridge and resolves account and resource contracts`() {
        assertTrue(java.lang.reflect.Modifier.isAbstract(
            Video.PlayableParams::class.java.getDeclaredMethod("getDisplayParams").modifiers))
        val h = Harness()
        assertFalse(Video.DisplayParams::class.java.isAssignableFrom(Video.PlayableParams::class.java))
        assertFalse(Video.PlayableParams::class.java.declaredMethods.any { it.name in setOf("getAvid", "getCid") })
        val value = h.owner.getCurrentPlayableParam()
        assertEquals(PlayerQualitySessions.ItemKey(123, 456), h.access.item(value))
        assertEquals(PlayerQualitySessions.Admission.ALLOW, h.access.admission(h.owner, value))
        assertSame(h.owner.source, h.access.provider(h.owner))
        assertNotNull(h.access.reload)
        assertTrue(h.access.reparseReady)
        assertEquals("com.bilibili.video.story.player.StoryVideoPlayHandler\$mPlayableConsumer\$1",
            requireNotNull(h.access.consumerMethod).declaringClass.name)
        assertEquals(MediaItem::class.java, h.access.consumerMethod!!.returnType)
        assertEquals(321L, h.access.currentPosition(h.owner))
        assertTrue(h.access.isPlaying(h.owner))
        h.core.playState = 5
        assertFalse(h.access.isPlaying(h.owner))
    }

    @Test fun `lifecycle selection request capture and suspended resume share the same target`() {
        val h = Harness(); h.start()
        val coroutine = h.pending()
        val result = h.resolve(coroutine) {
            assertEquals(80, h.wifi())
            assertEquals(80, h.global())
            assertEquals(80, (h.policy() as StoryPreferenceFixture.Fixed).qn)
            COROUTINE_SUSPENDED
        }
        assertSame(COROUTINE_SUSPENDED, result)
        assertEquals(32, h.wifi())
        assertEquals(80, h.global())
        h.resolve(coroutine) { assertEquals(80, h.wifi()); Unit }
        assertEquals(32, h.wifi())
    }

    @Test fun `manual fixed and auto revoke a captured request before asynchronous preference writes`() {
        for (choice in listOf(StoryPreferenceFixture.Fixed(16), StoryPreferenceFixture.Auto)) {
            val h = Harness(); h.start()
            val coroutine = h.pending()
            h.manual(choice) { args ->
                assertSame(choice, args[0])
                assertEquals(32, h.wifi())
                assertEquals(32, h.global())
                h.owner.quality.manual(choice, continuation)
            }
            assertSame(choice, h.owner.quality.selected)
            h.resolve(coroutine) {
                assertEquals(32, h.wifi()); assertEquals(32, h.global())
                assertSame(choice, h.policy(choice)); Unit
            }
            assertEquals(0, h.select(1))
            h.request { assertEquals(80, it[0]); Unit }
        }
    }

    @Test fun `failed selection preserves binding so the next successful item can intervene`() {
        val h = Harness(); h.start()
        h.owner.controller.selectionResult = -1
        assertEquals(-1, h.select(1))
        h.owner.controller.selectionResult = 0
        assertEquals(0, h.select(1))
        h.request { assertEquals(80, it[0]); Unit }
    }

    @Test fun `missing pointer or unknown admission never exposes a target`() {
        val h = Harness(); h.start()
        h.owner.controller.pointer = null
        assertEquals(-1, h.select(1))
        h.request { assertEquals(32, it[0]); assertEquals(32, h.global()); Unit }
        h.owner.controller.pointer = tv.danmaku.biliplayerv2.service.CurrentVideoPointer()
        assertEquals(0, h.select(1))
        BiliAccounts.fail = true
        h.request { assertEquals(32, it[0]); assertEquals(32, h.global()); Unit }
        assertEquals(80, h.global())
    }

    @Test fun `login teenager lesson and non ugc restrictions preserve host quality`() {
        for (deny in listOf<(Harness) -> Unit>(
            { BiliAccounts.instance.loggedIn = false },
            { TeenagersMode.getInstance().enabled = true },
            { RestrictedMode.enabled = true },
            { it.owner.getCurrentPlayableParam()!!.kind = VideoBizType.PGC })) {
            resetAdmission()
            val h = Harness(); h.start(); deny(h)
            h.request { assertEquals(32, it[0]); assertEquals(32, h.wifi()); assertEquals(32, h.global()); Unit }
            assertEquals(80, h.global())
        }
    }

    @Test fun `registration failure can retry without duplicating already installed hooks`() {
        val h = Harness("player.story.policy")
        val partial = h.install() as FeatureInstallResult.Installed
        assertFalse(partial.complete)
        assertEquals("partial:1/2", h.statuses.last())
        h.port.failId = null
        assertTrue((h.install() as FeatureInstallResult.Installed).complete)
        val successful = h.port.registrationAttempts.groupingBy { it.first }.eachCount()
        assertEquals(2, successful["player.story.policy"])
        assertTrue(successful.filterKeys { it != "player.story.policy" }.values.all { it == 1 })
        h.create(); assertEquals(0, h.select())
        h.request { assertEquals(80, it[0]); Unit }
    }

    @Test fun `real claim releases failed manual registration and preserves successful hook registrations`() {
        val h = Harness("player.story.manual", useClaims = true)
        val first = h.install() as FeatureInstallResult.Installed
        assertFalse(first.complete)
        assertTrue(h.installer.requiresAdaptationRetry)
        assertEquals(HookPointRegistry.State.FAILED,
            h.registry.snapshot().single { it.id == "player.story.manual" }.state)
        assertFalse(h.port.hooks.containsKey("player.story.manual"))
        h.port.failId = null
        assertTrue((h.install() as FeatureInstallResult.Installed).complete)
        assertFalse(h.installer.requiresAdaptationRetry)
        val counts = h.port.registrationAttempts.groupingBy { it.first }.eachCount()
        assertEquals(2, counts["player.story.manual"])
        assertTrue(counts.filterKeys { it != "player.story.manual" }.values.all { it == 1 })
        h.create(); assertEquals(0, h.select())
        h.manual(StoryPreferenceFixture.Auto)
        h.request { assertEquals(32, it[0]); Unit }
    }

    @Test fun `real claim retries a failed capture constructor without duplicating successful methods`() {
        val h = Harness("player.story.resolve_capture", useClaims = true)
        assertFalse((h.install() as FeatureInstallResult.Installed).complete)
        assertTrue(h.installer.requiresAdaptationRetry)
        assertFalse(h.port.hooks.containsKey("player.story.resolve_capture"))
        h.port.failId = null
        assertTrue((h.install() as FeatureInstallResult.Installed).complete)
        val counts = h.port.registrationAttempts.groupingBy { it.first }.eachCount()
        assertEquals(2, counts["player.story.resolve_capture"])
        assertTrue(counts.filterKeys { it != "player.story.resolve_capture" }.values.all { it == 1 })
        h.create(); assertEquals(0, h.select())
        val coroutine = h.pending()
        h.resolve(coroutine) { assertEquals(80, h.wifi()); COROUTINE_SUSPENDED }
    }

    @Test fun `real installed claims cause no duplicate native registrations on repeated installation`() {
        val h = Harness(useClaims = true); h.start()
        repeat(3) { assertTrue((h.install() as FeatureInstallResult.Installed).complete) }
        assertTrue(h.port.registrationAttempts.groupingBy { it.first }.eachCount().values.all { it == 1 })
        assertFalse(h.installer.requiresAdaptationRetry)
    }

    @Test fun `inflight claim from the same registry cannot report Story ready before native installation`() {
        val h = Harness(useClaims = true)
        assertTrue(h.registry.claim("player.story.manual", h.access.roles.manual))
        assertFalse((h.install() as FeatureInstallResult.Installed).complete)
        assertTrue(h.installer.requiresAdaptationRetry)
        assertFalse(h.port.hooks.containsKey("player.story.manual"))
        h.registry.markFailed("player.story.manual", h.access.roles.manual, IllegalStateException("abandoned inflight fixture"))
        assertTrue((h.install() as FeatureInstallResult.Installed).complete)
        assertEquals(1, h.port.registrationAttempts.count { it.first == "player.story.manual" })
    }

    @Test fun `release and recreate reject an old coroutine while allowing a new generation`() {
        val h = Harness(); h.start()
        val old = h.pending()
        h.port.invoke("player.story.release", h.owner) { h.owner.release(); Unit }
        h.resolve(old) { assertEquals(32, h.wifi()); Unit }
        h.create(); assertEquals(0, h.select())
        val fresh = h.pending()
        h.resolve(old) { assertEquals(32, h.wifi()); Unit }
        h.resolve(fresh) { assertEquals(80, h.wifi()); COROUTINE_SUSPENDED }
        assertEquals(80, h.global())
    }

    @Test fun `request exception revokes a coroutine captured before the failure`() {
        val h = Harness(); h.start()
        val coroutine = h.coroutine()
        val failure = IllegalStateException("original request failure")
        try {
            h.request { h.capture(coroutine); throw failure }
            fail("expected original request exception")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        h.resolve(coroutine) { assertEquals(32, h.wifi()); Unit }
        assertEquals(80, h.global())
    }

    @Test fun `resolve exception revokes the failed ticket and always leaves its frame`() {
        val h = Harness(); h.start()
        val coroutine = h.pending()
        val failure = IllegalStateException("original resolver failure")
        try {
            h.resolve(coroutine) { throw failure }
            fail("expected original resolve exception")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        h.resolve(coroutine) { assertEquals(32, h.wifi()); Unit }
        assertEquals(80, h.global())
    }

    @Test fun `manual invalidation does not depend on reading a potentially failing current getter`() {
        val h = Harness(); h.start()
        val coroutine = h.pending()
        h.owner.currentFailure = IllegalStateException("current fixture failure")
        h.manual(StoryPreferenceFixture.Auto) { assertEquals(32, h.global()); Unit }
        h.owner.currentFailure = null
        h.resolve(coroutine) { assertEquals(32, h.wifi()); Unit }
        assertEquals(80, h.global())
    }

    @Test fun `task reuse preserves one generation but cannot attach an old coroutine to a new item`() {
        val h = Harness(); h.start()
        val task = AbsMediaResourceResolveTask()
        val first = h.pending(task)
        val same = h.clone(first)
        h.resolve(same) { assertEquals(80, h.wifi()); COROUTINE_SUSPENDED }
        assertEquals(0, h.select(1))
        val next = h.pending(task)
        h.resolve(first) { assertEquals(32, h.wifi()); Unit }
        h.resolve(h.clone(first)) { assertEquals(32, h.wifi()); Unit }
        h.resolve(next) { assertEquals(80, h.wifi()); COROUTINE_SUSPENDED }
    }

    @Test fun `null captured params in the modern resolver cannot impersonate a legacy coroutine`() {
        val h = Harness(); h.start()
        val coroutine = h.pending()
        requireNotNull(h.access.resolveParams).set(coroutine, null)
        h.resolve(coroutine) { assertEquals(32, h.wifi()); Unit }
        assertEquals(80, h.global())
    }

    @Test fun `unselected current item mismatch permanently revokes old ticket after switching back`() {
        val h = Harness(); h.start()
        val old = h.pending()
        h.owner.source.currentIndex = 1
        h.resolve(old) { assertEquals(32, h.wifi()); Unit }
        h.owner.source.currentIndex = 0
        h.resolve(old) { assertEquals(32, h.wifi()); Unit }
        h.request { assertEquals(32, it[0]); Unit }
        assertEquals(0, h.select(1))
        val fresh = h.pending()
        h.resolve(fresh) { assertEquals(80, h.wifi()); COROUTINE_SUSPENDED }
    }

    @Test fun `old resolver exception cannot revoke a fresh generation of the same media`() {
        val h = Harness(); h.start()
        val old = h.pending()
        assertEquals(0, h.select(1))
        assertEquals(0, h.select(0))
        val fresh = h.pending()
        val failure = IllegalStateException("stale resolver failure")
        try {
            h.resolve(old) { assertEquals(32, h.wifi()); throw failure }
            fail("expected original stale resolver exception")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
        h.resolve(fresh) { assertEquals(80, h.wifi()); COROUTINE_SUSPENDED }
        assertEquals(80, h.global())
    }

    @Test fun `private flash resource selects only after original body and settles after accepted match`() {
        val h = Harness(); h.start()
        val value = MediaResource(16, 80, 120)
        h.core.resource = value
        val coroutine = h.pending()
        h.resolve(coroutine) {
            h.resource(value) {
                assertEquals(0, value.writes)
                assertEquals(32, h.wifi())
                assertEquals(32, h.global())
                Unit
            }
            Unit
        }
        assertEquals(1, value.resolved)
        assertEquals(1, value.writes)
        h.match { assertEquals(80, h.wifi()); Unit }
        h.match { assertEquals(32, h.wifi()); assertEquals(32, h.global()); Unit }
        assertEquals(0, h.owner.source.reloads)
        assertEquals(80, h.global())
    }

    @Test fun `shared resource outside private resolve remains unchanged`() {
        val h = Harness(); h.start()
        val shared = MediaResource(16, 80)
        h.resource(shared) { assertEquals(32, h.global()); Unit }
        assertEquals(0, shared.writes)
        assertEquals(0, shared.resolved)
        assertEquals(80, h.global())
    }

    @Test fun `host normal request keeps the exact listener identity`() {
        val h = Harness(); h.start()
        val replay = Replay(h)
        h.request(listener = replay.original) {
            assertSame(replay.original, it.last())
            assertFalse(Proxy.isProxyClass(it.last()!!.javaClass))
            Unit
        }
        assertEquals(0, h.owner.source.reloads)
    }

    @Test fun `reparse preserves language production and latest position then accepts normal result`() {
        val h = Harness(); h.start()
        val replay = Replay(h); replay.begin()
        val call = h.owner.source.reloadCalls.single()
        assertEquals(80, call.qn)
        assertEquals(321L, call.position)
        assertEquals("ja-JP", call.language)
        assertTrue(call.reload)
        assertEquals(9, call.productionType)
        h.match()
        assertEquals(1, h.owner.source.reloads)
        h.core.position = 789
        val normal = MediaResource(80).also { it.from = MediaResource.SOURCE_FROM_NORMAL }
        requireNotNull(replay.wrapped).success(normal)
        assertEquals(1, replay.delegated)
        assertEquals(listOf(789L), replay.positions)
        assertEquals(789, h.core.position)
        assertSame(normal, h.core.resource)
        assertEquals(0, normal.writes)
        requireNotNull(replay.wrapped).success(normal)
        assertEquals(1, replay.delegated)
        h.match()
        h.match { assertEquals(32, h.wifi()); Unit }
    }

    @Test fun `pause before module replay response cannot resume playback`() {
        val h = Harness(); h.start()
        val replay = Replay(h); replay.begin()
        val old = h.core.resource
        h.core.playState = 5
        requireNotNull(replay.wrapped).success(MediaResource(80))
        assertEquals(0, replay.delegated)
        assertEquals(5, h.core.playState)
        assertSame(old, h.core.resource)
        assertEquals(80, h.global())
    }

    @Test fun `manual action or new selection before replay response drops only the module result`() {
        for (newItem in listOf(false, true)) {
            val h = Harness(); h.start()
            val replay = Replay(h); replay.begin()
            val old = h.core.resource
            if (newItem) assertEquals(0, h.select(1)) else h.manual(StoryPreferenceFixture.Auto)
            requireNotNull(replay.wrapped).success(MediaResource(80))
            assertEquals(0, replay.delegated)
            assertSame(old, h.core.resource)
            if (newItem) h.request { assertEquals(80, it[0]); Unit }
            assertEquals(80, h.global())
        }
    }

    @Test fun `replay error delegates once revokes the module lease and clears synchronous scope`() {
        val h = Harness(); h.start()
        val replay = Replay(h); replay.begin()
        val listener = requireNotNull(replay.wrapped)
        listener.onError()
        listener.onError()
        listener.success(MediaResource(80))
        assertEquals(1, replay.errors)
        assertEquals(0, replay.delegated)
        h.match { assertEquals(32, h.wifi()); Unit }
        assertEquals(80, h.global())
    }

    @Test fun `stale replay error cannot alter manual choice or next generation`() {
        for (newItem in listOf(false, true)) {
            val h = Harness(); h.start()
            val replay = Replay(h); replay.begin()
            if (newItem) assertEquals(0, h.select(1)) else h.manual(StoryPreferenceFixture.Auto)
            requireNotNull(replay.wrapped).onError()
            assertEquals(0, replay.errors)
            assertEquals(0, replay.delegated)
            if (newItem) h.request { assertEquals(80, it[0]); Unit }
            else h.match { assertEquals(32, h.wifi()); Unit }
            assertEquals(80, h.global())
        }
    }

    @Test fun `consumer rechecks seek after delegate has entered instead of replay snapshot`() {
        val h = Harness(); h.start()
        val replay = Replay(h); replay.begin()
        h.core.position = 654
        replay.beforeConsume = { h.core.position = 987 }
        requireNotNull(replay.wrapped).success(MediaResource(80).also { it.from = MediaResource.SOURCE_FROM_NORMAL })
        assertEquals(1, replay.delegated)
        assertEquals(listOf(987L), replay.positions)
        assertEquals(987, h.core.position)
    }

    @Test fun `consumer drops replay if pause manual or new generation happens inside delegate`() {
        for (change in listOf<(Harness) -> Unit>(
            { it.core.playState = 5 },
            { it.manual(StoryPreferenceFixture.Auto) },
            { assertEquals(0, it.select(1)) })) {
            val h = Harness(); h.start()
            val replay = Replay(h); replay.begin()
            val oldResource = h.core.resource
            val oldPosition = h.core.position
            replay.beforeConsume = { change(h) }
            requireNotNull(replay.wrapped).success(MediaResource(80))
            assertEquals(1, replay.delegated)
            assertTrue(replay.positions.isEmpty())
            assertSame(oldResource, h.core.resource)
            assertEquals(oldPosition, h.core.position)
            assertEquals(80, h.global())
        }
    }

    @Test fun `resource permission errors and special formats preserve host and never reparse`() {
        for (protect in listOf<(MediaResource) -> Unit>(
            { it.mVodIndex.mVodList[0].mNeedVip = true },
            { it.mVodIndex.mVodList[0].mPlayError = Any() },
            { it.hdr = true }, { it.dolby = true }, { it.vivid = true }, { it.drm = true },
            { it.from = 99 })) {
            val h = Harness(); h.start()
            val resource = MediaResource(16, 80).also(protect)
            h.core.resource = resource
            val coroutine = h.pending()
            h.resolve(coroutine) { h.resource(resource); Unit }
            h.match()
            assertEquals(0, resource.resolved)
            assertEquals(0, resource.writes)
            assertEquals(0, h.owner.source.reloads)
            assertSame(resource, h.core.resource)
        }
    }

    @Test fun `normal resource retains legal fallback and settles without selecting vip entry`() {
        val h = Harness(); h.start()
        val resource = MediaResource(16, 80).also {
            it.from = MediaResource.SOURCE_FROM_NORMAL
            it.mVodIndex.mVodList[1].mNeedVip = true
        }
        h.core.resource = resource
        val coroutine = h.pending()
        h.resolve(coroutine) { h.resource(resource); Unit }
        assertEquals(0, resource.resolved)
        assertEquals(0, resource.writes)
        h.match { assertEquals(80, h.wifi()); Unit }
        h.match { assertEquals(32, h.wifi()); assertEquals(32, h.global()); Unit }
        assertEquals(0, h.owner.source.reloads)
        assertSame(resource, h.core.resource)
    }
}
