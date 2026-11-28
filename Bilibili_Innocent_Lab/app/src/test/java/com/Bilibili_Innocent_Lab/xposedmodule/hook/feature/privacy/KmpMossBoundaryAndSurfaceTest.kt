package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bapis.bilibili.metadata.FakeBuilderBase
import com.bapis.bilibili.metadata.FakeMessageBase
import com.bapis.bilibili.metadata.Metadata
import com.bapis.bilibili.metadata.device.Device
import com.bilibili.lib.ighttp.IgHttpRequest
import java.io.IOException
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.MossInterceptor
import kmpfixture.modern.Service
import kntr.base.neuron.KNeuron
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import org.junit.Assert.*
import org.junit.Test

class KmpMossBoundaryAndSurfaceTest {
    private val loader = javaClass.classLoader!!

    private val continuation = object : Continuation<Any?> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) {}
    }

    private class Events : KmpMossInterceptor.Events {
        val replies = mutableListOf<Pair<Boolean, String?>>()
        val passed = mutableListOf<String>()
        var sanitized: Set<String>? = null
        val failures = mutableListOf<Throwable>()
        override fun replied(local: Boolean, rpc: String?) { replies += local to rpc }
        override fun sanitized(names: Set<String>) { sanitized = names }
        override fun passed(rpc: String) { passed += rpc }
        override fun failed(reason: Throwable) { failures += reason }
    }

    private class ModernChain(override val request: MossInterceptor.Request) : MossInterceptor.Chain {
        var proceeded: MossInterceptor.Request? = null
        override fun a(request: MossInterceptor.Request, c: Continuation<Any?>): Any? {
            proceeded = request
            return "downstream"
        }
    }

    private class LegacyChain(override val request: kmpfixture.legacy.MossInterceptor.Request) :
        kmpfixture.legacy.MossInterceptor.Chain {
        var proceeded: kmpfixture.legacy.MossInterceptor.Request? = null
        override fun a(request: kmpfixture.legacy.MossInterceptor.Request, c: Continuation<Any?>): Any? {
            proceeded = request
            return "downstream"
        }
    }

    private object DummyStrategy : SerializationStrategy<Any>

    private class StrippedDescriptor(pkg: String, svc: String, mtd: String) :
        Descriptor(pkg, svc, mtd, Any::class, DummyStrategy) {
        override fun toString(): String = "kmp.Descriptor@${Integer.toHexString(System.identityHashCode(this))}"
    }

    private class GetterOnlyStrippedDescriptor(
        private val pkg: String,
        private val svc: String,
        private val mtd: String
    ) {
        @Suppress("unused") internal val reqClass = Any::class
        @Suppress("unused") internal val reqSerialization: SerializationStrategy<*> = DummyStrategy
        fun getPackageName(): String = pkg
        fun getServiceName(): String = svc
        fun getMethodName(): String = mtd
        override fun toString(): String = "ObfDesc@${Integer.toHexString(System.identityHashCode(this))}"
    }

    private class OtherToStringDescriptor(
        val packageName: String,
        val serviceName: String,
        val methodName: String
    ) {
        override fun toString(): String = "SomethingElse()"
    }

    private data class ComponentDesc(
        val a: String = "bilibili.pgc.v1",
        val b: String = "Pgc",
        val c: String = "Play",
        val d: KClass<*> = String::class,
        val e: DeserializationStrategy<String> = object : DeserializationStrategy<String> {}
    ) {
        override fun toString() = "a.b.c@1234abcd"
    }

    private class ThreeStringFieldDesc {
        private val p = "bilibili.app.interface.v1"
        private val s = "Search"
        private val m = "DefaultWords"
        private val k: KClass<*> = String::class
        private val st: SerializationStrategy<String> = object : SerializationStrategy<String> {}
        override fun toString() = "x.y.z@deadbeef"
    }

    private class BadPkgThreeStringDesc {
        private val p = "nodot"
        private val s = "Search"
        private val m = "DefaultWords"
        private val k: KClass<*> = String::class
        private val st: SerializationStrategy<String> = object : SerializationStrategy<String> {}
        override fun toString() = "x.y.Bad@01"
    }

    class DeviceWithoutSetFts : FakeMessageBase<DeviceWithoutSetFts, DeviceWithoutSetFts.Builder>(arrayOf("", "")) {
        override fun newBuilderInstance() = Builder()
        class Builder : FakeBuilderBase<DeviceWithoutSetFts, Builder>(2) {
            fun setBuvid(v: String) = apply { fields[0] = v }
            fun setFp(v: String) = apply { fields[1] = v }
            fun setFpLocal(v: String) = this
            fun setFpRemote(v: String) = this
            fun setGuestId(v: String) = this
            override fun create(fields: Array<String>) = DeviceWithoutSetFts()
        }
        companion object {
            @JvmStatic fun parseFrom(bytes: ByteArray) = DeviceWithoutSetFts()
            @JvmStatic fun newBuilder() = Builder()
        }
    }

    private class AdaptedRegistrar(val port: PlayerPortTestRegistrar = PlayerPortTestRegistrar()) : HookRegistrar by port {
        val policies = linkedMapOf<String, HookExceptionPolicy>()
        val hooks get() = port.hooks
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            policies[id] = exceptionPolicy
            val member = Class.forName(point.className).declaredMethods.single { it.name == point.methodName }
            port.hooks[id] = PlayerPortTestRegistrar.Entry(member, ModernMemberHookCreator(member).apply(block))
        }
        fun invoke(id: String, receiver: Any? = null, args: Array<Any?> = emptyArray(), original: (Array<Any?>) -> Any? = { null }) =
            port.invoke(id, receiver, args, original)
    }

    private fun names(pkg: String, exception: String = "KMossException") = KmpMossSurface.Names(
        engine = "kmpfixture.$pkg.GrpcEngine",
        interceptor = "kmpfixture.$pkg.MossInterceptor",
        exception = "kmpfixture.$pkg.$exception"
    )

    private fun resolvedSurface(pkg: String) =
        (KmpMossSurface.resolve(loader, names(pkg)) as KmpMossSurface.Result.Resolved).surface

    private fun modernRequest(service: String, method: String) = kmpfixture.modern.RequestImpl(
        Service("host"),
        Descriptor("bilibili.app.test.v1", service, method, String::class, object : SerializationStrategy<String> {}),
        byteArrayOf(7)
    ).also {
        it.headers.ascii["authorization"] = "identify_v1 secret"
        it.headers.binary["x-bili-metadata-bin"] = byteArrayOf(1)
    }

    private fun legacyRequest(service: String, method: String) = kmpfixture.legacy.RequestImpl(
        kmpfixture.legacy.Service("host"),
        kmpfixture.legacy.Descriptor("bilibili.app.test.v1", service, method, String::class, object : SerializationStrategy<String> {}),
        byteArrayOf(7)
    ).also {
        it.headers.ascii["authorization"] = "identify_v1 secret"
        it.headers.binary["x-bili-metadata-bin"] = byteArrayOf(1)
    }

    private fun incognito() = IncognitoSession(run { var n = 0; { "id-${n++}" } }).also { it.enter() }

    private fun modernInterceptor(
        session: IncognitoSession?,
        events: Events,
        ready: () -> Boolean = { true },
        sanitizer: KmpHeaderSanitizer? = KmpHeaderSanitizer({ b, _ -> b + 1 }, { b, _ -> b }),
        reply: ((String, ByteArray) -> ByteArray?)? = null
    ) = KmpMossInterceptor(
        resolvedSurface("modern"), loader, session, ready, sanitizer, guest = { true }, localReply = { reply }, events = events
    ).proxy as MossInterceptor

    private fun legacyInterceptor(
        reply: ((String, ByteArray) -> ByteArray?)? = null,
        session: IncognitoSession? = null
    ): kmpfixture.legacy.MossInterceptor {
        val sanitizer = KmpHeaderSanitizer({ b, _ -> b + 1 }, { b, _ -> b })
        return KmpMossInterceptor(
            resolvedSurface("legacy"), loader, session, { true }, sanitizer, guest = { true }, localReply = { reply }, events = Events()
        ).proxy as kmpfixture.legacy.MossInterceptor
    }

    @Test
    fun verifiesMossSurfaceIdentityAndInterceptorBehavior() {
        // KmpMossBoundary identity sanitizer & Device.setFts gate.
        run {
            val identity = IncognitoSession.Identity(1, "session-buvid", "session-id")
            val sanitizer = KmpMossBoundary.identitySanitizer(loader)!!
            assertNotNull(sanitizer)

            val realMeta = Metadata.newBuilder().setBuvid("real-buvid").setAccessKey("real-secret").build().toByteArray()
            val metaMaps = listOf(LinkedHashMap<Any?, Any?>(), LinkedHashMap<Any?, Any?>(mapOf("x-bili-metadata-bin" to realMeta)))
            sanitizer.sanitize(metaMaps, identity)
            val metaBytes = metaMaps[1]["x-bili-metadata-bin"] as ByteArray
            val sealedMeta = Metadata.parseFrom(metaBytes)
            assertEquals("session-buvid", sealedMeta.buvid)
            assertEquals("", sealedMeta.accessKey)
            assertFalse(String(metaBytes).contains("real"))

            val realDevice = Device.newBuilder().setBuvid("real-buvid").setFp("real-fp").setFpLocal("real-local")
                .setFpRemote("real-remote").setGuestId("real-guest").setFts(1234L).build().toByteArray()
            val deviceMaps = listOf(LinkedHashMap<Any?, Any?>(), LinkedHashMap<Any?, Any?>(mapOf("x-bili-device-bin" to realDevice)))
            sanitizer.sanitize(deviceMaps, identity)
            val sealedDevice = Device.parseFrom(deviceMaps[1]["x-bili-device-bin"] as ByteArray)
            assertEquals((0..4).map { "session-buvid" }, (0..4).map { sealedDevice.field(it) })
            assertEquals("0", sealedDevice.field(5))

            val noFtsLoader = object : ClassLoader(loader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> =
                    if (name == "com.bapis.bilibili.metadata.device.Device") DeviceWithoutSetFts::class.java
                    else super.loadClass(name, resolve)
            }
            assertNull(KmpMossBoundary.identitySanitizer(noFtsLoader))
        }

        // KmpMossSurface modern, legacy, absent, unresolved, and cachedEngine resolution.
        run {
            val modern = resolvedSurface("modern")
            assertFalse(modern.responseHasRequest)
            assertEquals(3, modern.responseCtor.parameterCount)
            assertEquals("ALL", (modern.protocolAll as Enum<*>).name)
            assertEquals(2, modern.headerMapGetters.size)
            assertEquals("interceptors", modern.engineList.name)
            assertEquals("a", modern.engineEntry.name)
            assertEquals(1, modern.entryDescriptorIndex)
            assertEquals(3, modern.entryHandlerIndex)

            val legacy = resolvedSurface("legacy")
            assertTrue(legacy.responseHasRequest)
            assertEquals(4, legacy.responseCtor.parameterCount)
            val req = kmpfixture.legacy.RequestImpl(
                kmpfixture.legacy.Service("h"),
                kmpfixture.legacy.Descriptor("p", "S", "M", String::class, object : SerializationStrategy<String> {}),
                byteArrayOf(1, 2)
            )
            assertEquals(listOf<Byte>(1, 2), (legacy.requestPayload.get(req) as ByteArray).toList())
            assertTrue(legacy.requestDescriptor.get(req).toString().startsWith("KMethodDescriptor(packageName=p"))
            assertEquals("d", legacy.requestHeaders.name)
            assertEquals(1, legacy.entryDescriptorIndex)
            assertEquals(-1, legacy.entryHandlerIndex)

            assertEquals(KmpMossSurface.Result.Absent, KmpMossSurface.resolve(loader, names("none")))
            val missingEx = KmpMossSurface.resolve(loader, names("modern", exception = "Nope"))
            assertTrue(missingEx is KmpMossSurface.Result.Unresolved)
            assertEquals("exception", (missingEx as KmpMossSurface.Result.Unresolved).reason)

            val noEngineLoader = TestRenamingClassLoader(parent = loader, hiddenNames = setOf(GrpcEngine::class.java.name))
            assertEquals(KmpMossSurface.Result.Unresolved("engine"), KmpMossSurface.resolve(noEngineLoader, names("modern")))
            val wirePoint = KmpGrpcWireAdapter.point(
                GrpcEngine::class.java.getDeclaredMethod("a", Service::class.java, Descriptor::class.java, ByteArray::class.java, Handler::class.java, Any::class.java)
            )
            assertTrue(KmpMossSurface.resolve(loader, names("modern"), cachedEngine = wirePoint) is KmpMossSurface.Result.Resolved)
        }

        // KmpMossInterceptor modern & legacy behavior + fullMethodName structural fallbacks.
        run {
            val proxy = modernInterceptor(null, Events())
            assertEquals(MossInterceptor.Protocol.ALL, proxy.a())
            assertEquals(Int.MAX_VALUE, proxy.priority)
            assertEquals(KmpMossInterceptor.NAME, proxy.name)

            val guestEvents = Events()
            val guestChain = ModernChain(modernRequest("History", "Other"))
            assertEquals("downstream", modernInterceptor(null, guestEvents, reply = { _, _ -> null }).b(guestChain, continuation))
            assertSame(guestChain.request, guestChain.proceeded)
            assertEquals("identify_v1 secret", (guestChain.request as kmpfixture.modern.RequestImpl).headers.ascii["authorization"])
            assertTrue(guestEvents.replies.isEmpty() && guestEvents.sanitized == null)

            val localEvents = Events()
            val localChain = ModernChain(modernRequest("History", "CursorV2"))
            val localResp = modernInterceptor(null, localEvents, reply = { rpc, payload ->
                assertEquals("bilibili.app.test.v1.History/CursorV2", rpc)
                payload + 9
            }).b(localChain, continuation) as MossInterceptor.Response
            assertNull(localChain.proceeded)
            assertNull(localResp.error)
            assertArrayEquals(byteArrayOf(7, 9), localResp.payload)
            assertEquals(listOf(true to "bilibili.app.test.v1.History/CursorV2"), localEvents.replies)

            val failEvents = Events()
            val failChain = ModernChain(modernRequest("History", "CursorV2"))
            val failResp = modernInterceptor(null, failEvents, reply = { _, _ -> error("disk") }).b(failChain, continuation) as MossInterceptor.Response
            assertNull(failChain.proceeded)
            assertTrue(failResp.error is KMossException)
            assertEquals(1, failEvents.failures.size)

            val blockEvents = Events()
            val blockChain = ModernChain(modernRequest("Report", "Heartbeat"))
            val blockResp = modernInterceptor(incognito(), blockEvents).b(blockChain, continuation) as MossInterceptor.Response
            assertNull(blockChain.proceeded)
            assertNull(blockResp.error)
            assertEquals(0, blockResp.payload!!.size)
            assertEquals(listOf(false to "bilibili.app.test.v1.Report/Heartbeat"), blockEvents.replies)

            val passEvents = Events()
            val passChain = ModernChain(modernRequest("Feed", "Index"))
            assertEquals("downstream", modernInterceptor(incognito(), passEvents).b(passChain, continuation))
            val headers = (passChain.proceeded as kmpfixture.modern.RequestImpl).headers
            assertFalse(headers.ascii.containsKey("authorization"))
            assertArrayEquals(byteArrayOf(1, 1), headers.binary["x-bili-metadata-bin"])
            assertTrue("authorization" in passEvents.sanitized!!)
            assertEquals(listOf("bilibili.app.test.v1.Feed/Index"), passEvents.passed)

            val notReadyChain = ModernChain(modernRequest("Feed", "Index"))
            val notReadyResp = modernInterceptor(incognito(), Events(), ready = { false }).b(notReadyChain, continuation) as MossInterceptor.Response
            assertNull(notReadyChain.proceeded)
            assertEquals(0, notReadyResp.payload!!.size)
            assertEquals("identify_v1 secret", (notReadyChain.request as kmpfixture.modern.RequestImpl).headers.ascii["authorization"])

            val sanFailEvents = Events()
            val sanFailChain = ModernChain(modernRequest("Feed", "Index"))
            val sanFailResp = modernInterceptor(
                incognito(), sanFailEvents, sanitizer = KmpHeaderSanitizer({ _, _ -> error("parse") }, { b, _ -> b })
            ).b(sanFailChain, continuation) as MossInterceptor.Response
            assertNull(sanFailChain.proceeded)
            assertTrue(sanFailResp.error is KMossException)
            assertEquals(1, sanFailEvents.failures.size)

            val nullSanChain = ModernChain(modernRequest("Feed", "Index"))
            val nullSanResp = modernInterceptor(incognito(), Events(), sanitizer = null).b(nullSanChain, continuation) as MossInterceptor.Response
            assertNull(nullSanChain.proceeded)
            assertTrue(nullSanResp.error is KMossException)

            val ended = incognito().also { it.exit() }
            val endedChain = ModernChain(modernRequest("Feed", "Index"))
            val endedResp = modernInterceptor(ended, Events()).b(endedChain, continuation) as MossInterceptor.Response
            assertNull(endedChain.proceeded)
            assertEquals(0, endedResp.payload!!.size)

            val strategy = object : SerializationStrategy<String> {}
            fun unrecognised() = kmpfixture.modern.RequestImpl(
                Service("h"),
                object : Descriptor("p", "S", "M", String::class, strategy) { override fun toString() = "SomethingElse()" },
                byteArrayOf(7)
            )
            val blockedUnrec = ModernChain(unrecognised())
            val blockedUnrecResp = modernInterceptor(incognito(), Events()).b(blockedUnrec, continuation) as MossInterceptor.Response
            assertNull(blockedUnrec.proceeded)
            assertEquals(0, blockedUnrecResp.payload!!.size)
            val openUnrec = ModernChain(unrecognised())
            assertEquals("downstream", modernInterceptor(null, Events(), reply = { _, _ -> error("not asked") }).b(openUnrec, continuation))

            val ic = KmpMossInterceptor(
                resolvedSurface("modern"), loader, incognito(), { true },
                KmpHeaderSanitizer({ b, _ -> b + 1 }, { b, _ -> b }), { true }, { null }, Events()
            )
            assertEquals("bilibili.pgc.v1.Pgc/Play", ic.fullMethodName(ComponentDesc()))
            assertEquals("bilibili.app.interface.v1.Search/DefaultWords", ic.fullMethodName(ThreeStringFieldDesc()))
            assertNull(ic.fullMethodName(BadPkgThreeStringDesc()))
            assertEquals("pkg.Service/View", ic.fullMethodName(StrippedDescriptor("pkg", "Service", "View")))
            assertEquals("pkg.Service/View", ic.fullMethodName(GetterOnlyStrippedDescriptor("pkg", "Service", "View")))
            assertNull(ic.fullMethodName(OtherToStringDescriptor("pkg", "Service", "View")))

            // Legacy 4-argument Response paths.
            val legLocalChain = LegacyChain(legacyRequest("History", "CursorV2"))
            val legLocalResp = legacyInterceptor(reply = { _, payload -> payload + 5 }).b(legLocalChain, continuation) as kmpfixture.legacy.MossInterceptor.Response
            assertNull(legLocalChain.proceeded)
            assertSame(legLocalChain.request, legLocalResp.request)
            assertNull(legLocalResp.error)
            assertArrayEquals(byteArrayOf(7, 5), legLocalResp.payload)
            assertFalse(legLocalResp.headers === (legLocalChain.request as kmpfixture.legacy.RequestImpl).headers)

            val legPassChain = LegacyChain(legacyRequest("Feed", "Index"))
            assertEquals("downstream", legacyInterceptor(session = incognito()).b(legPassChain, continuation))
            val legHeaders = (legPassChain.proceeded as kmpfixture.legacy.RequestImpl).headers
            assertFalse(legHeaders.ascii.containsKey("authorization"))
            assertArrayEquals(byteArrayOf(1, 1), legHeaders.binary["x-bili-metadata-bin"])

            val legBlocked = LegacyChain(legacyRequest("Report", "Heartbeat"))
            val legBlockedResp = legacyInterceptor(session = incognito()).b(legBlocked, continuation) as kmpfixture.legacy.MossInterceptor.Response
            assertNull(legBlocked.proceeded)
            assertEquals(0, legBlockedResp.payload!!.size)

            val legFail = LegacyChain(legacyRequest("History", "CursorV2"))
            val legFailResp = legacyInterceptor(reply = { _, _ -> error("disk") }).b(legFail, continuation) as kmpfixture.legacy.MossInterceptor.Response
            assertNull(legFail.proceeded)
            assertTrue(legFailResp.error is kmpfixture.legacy.KMossException)
        }
    }

    @Test
    fun verifiesSideChannelBoundariesIgnetAndChannelAdapters() {
        fun env(customLoader: ClassLoader = loader, registrar: HookRegistrar = PlayerPortTestRegistrar(), statuses: MutableMap<String, String> = mutableMapOf()) =
            HookEnvironment("tv.danmaku.bili", customLoader, HookPointRegistry(customLoader), registrar, { _, _ -> }, { _, _ -> }, { k, v -> statuses[k] = v })

        class IgnetHarness {
            val session = IncognitoSession().apply { enter() }
            val registrar = AdaptedRegistrar()
            val statuses = mutableMapOf<String, String>()
            var ready = true
            val env = env(registrar = registrar, statuses = statuses)
            init {
                assertEquals(2, requireNotNull(IgnetHttpBoundary(session) { ready }.prepare(env)).invoke())
            }
            fun request(url: String, method: String = "GET", vararg headers: Pair<String, String>): IgHttpRequest {
                val builder = IgHttpRequest.Builder().url(url).method(method, null)
                headers.forEach { (k, v) -> builder.addHeaders(k, v) }
                return registrar.invoke("incognito.ignet.http.build", builder) { builder.build() } as IgHttpRequest
            }
            fun execute(request: IgHttpRequest) =
                runCatching { registrar.invoke("incognito.ignet.http.execute", request) { request.execute() } }
        }

        // Ignet sealing, telemetry/game-tracker/ad-measurement withholding, unsealed/early rejection.
        run {
            val h = IgnetHarness()
            val sealed = h.request(
                "https://app.bilibili.com/x/resource/show/tab/v2?access_key=real&buvid=dev&a=1",
                "GET", "Authorization" to "identify_v1 real", "x-bili-mid" to "42", "Buvid" to "dev", "User-Agent" to "ua"
            )
            val id = h.session.identity()!!
            assertEquals("https://app.bilibili.com/x/resource/show/tab/v2?buvid=${id.buvid}&a=1", sealed.url())
            assertEquals(mapOf("Buvid" to id.buvid, "User-Agent" to "ua"), sealed.headers().toMap())
            assertTrue(h.execute(sealed).isSuccess)
            assertEquals(1, sealed.executed)
        }
        run {
            val h = IgnetHarness()
            assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, h.registrar.policies["incognito.ignet.http.execute"])
            for ((url, method) in listOf("https://dataflow.biliapi.com/log/pbmobile/realtime" to "POST",
                "https://api.bilibili.com/x/report/click/now" to "POST")) {
                val request = h.request(url, method)
                assertTrue(url, h.execute(request).exceptionOrNull() is IOException)
                assertEquals(0, request.executed)
                assertTrue(request.closed)
            }
            assertEquals("withheld", h.statuses["incognito_transport_ignet_http"])
        }
        run {
            val h = IgnetHarness()
            val config = h.request("https://line1-log.biligame.net/collector/admin/config", "POST")
            assertTrue(h.execute(config).isSuccess)
            assertEquals(1, config.executed)
            val report = h.request("https://line1-log.biligame.net/collector/api/report", "POST")
            assertTrue(h.execute(report).exceptionOrNull() is IOException)
            assertEquals(0, report.executed)
            assertTrue(report.closed)
            assertEquals("withheld-ignet", h.statuses["incognito_game_tracker_report"])
        }
        run {
            val h = IgnetHarness()
            val request = h.request("https://g.cn.miaozhen.com/x/k=sample&m1a=example-device-hash&m11=example-id")
            assertTrue(h.execute(request).exceptionOrNull() is IOException)
            assertEquals(0, request.executed)
            assertTrue(request.closed)
            assertEquals("withheld", h.statuses["incognito_ad_measurement_ignet"])
        }
        run {
            val h = IgnetHarness()
            val unsealed = IgHttpRequest.Builder().url("https://api.bilibili.com/x/v2/view?aid=1").build()
            assertTrue(h.execute(unsealed).exceptionOrNull() is IOException)
            assertEquals(0, unsealed.executed)
            h.ready = false
            val early = h.request("https://api.bilibili.com/x/v2/view?aid=1")
            assertTrue(h.execute(early).exceptionOrNull() is IOException)
            assertEquals(0, early.executed)
        }
        run {
            val session = IncognitoSession().apply { enter() }
            val registrar = AdaptedRegistrar()
            requireNotNull(IncognitoTelemetryHooks(session).prepare(env(registrar = registrar))).invoke()
            val produced = registrar.hooks.filterKeys { it.startsWith("incognito.kneuron.") }.values.map { it.member.name }.toSet()
            assertEquals(setOf("reportClick", "trackT", "simpleTrackT"), produced)
            var original = 0
            var condition = 0
            val id = registrar.hooks.entries.single { it.key.startsWith("incognito.kneuron.trackT") }.key
            registrar.invoke(id, KNeuron(), arrayOf(false, "event", emptyMap<String, String>(), { condition++; true })) { original++; null }
            assertEquals(0, original)
            assertEquals(0, condition)
        }

        // Fail-closed privacy boundaries & PrivacyChannelAdapter validation.
        run {
            val session = IncognitoSession().apply { enter() }
            val brokenIgnetLoader = object : ClassLoader(loader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                    PrivacyChannelAdapter.IGNET_ENGINE -> Any::class.java
                    PrivacyChannelAdapter.IGNET_REQUEST -> throw ClassNotFoundException(name)
                    PrivacyChannelAdapter.IGNET_STREAM_PROVIDER -> IgHttpRequest.StreamDataProvider::class.java
                    else -> super.loadClass(name, resolve)
                }
            }
            assertNull(IgnetHttpBoundary(session) { true }.prepare(env(customLoader = brokenIgnetLoader)))

            val noIgnetReqLoader = TestRenamingClassLoader(parent = loader, hiddenNames = setOf(PrivacyChannelAdapter.IGNET_REQUEST))
            assertNull(IgnetHttpBoundary(session) { true }.prepare(env(customLoader = noIgnetReqLoader)))

            val noKNeuronLoader = object : ClassLoader(loader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                    IncognitoTelemetryHooks.KMP_NEURON -> throw ClassNotFoundException(name)
                    "kntr.base.config.KConfig" -> Service::class.java
                    else -> super.loadClass(name, resolve)
                }
            }
            assertNull(IncognitoTelemetryHooks(session).prepare(env(customLoader = noKNeuronLoader)))

            val statuses = linkedMapOf<String, String>()
            assertFalse(GuestHostFavoriteDialog { false }.install(env(statuses = statuses), loader))
            assertEquals("skipped-no-local-boundary", statuses["guest_video_favorite_host_dialog"])

            assertNotNull(PrivacyChannelAdapter.directIgnet(loader))
            assertNotNull(PrivacyChannelAdapter.directKNeuron(loader))
            assertNull(PrivacyChannelAdapter.resolveIgHttpRequest(noIgnetReqLoader, VersionAdapter.HookPoint("com.evil.Spy", "execute", emptyList())))
            assertNull(PrivacyChannelAdapter.resolveIgHttpRequest(noIgnetReqLoader, VersionAdapter.HookPoint(PrivacyChannelAdapter.IGNET_REQUEST, "execute", listOf("int"))))
            assertNull(PrivacyChannelAdapter.resolveKNeuron(noKNeuronLoader, "com.bilibili.lib.neuron.api.Neurons"))
            assertTrue(PrivacyChannelAdapter.allowedIgnetOwnerName("com.bilibili.lib.ighttp.RelocatedReq"))
            assertFalse(PrivacyChannelAdapter.allowedIgnetOwnerName("com.evil.RelocatedReq"))
            assertTrue(PrivacyChannelAdapter.allowedKNeuronOwnerName("kntr.base.neuron.Relocated"))
            assertFalse(PrivacyChannelAdapter.allowedKNeuronOwnerName("com.bilibili.lib.neuron.api.Neurons"))
        }
    }
}
