package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import kmpfixture.modern.Descriptor
import kmpfixture.modern.KMossException
import kmpfixture.modern.MossInterceptor
import kmpfixture.modern.RequestImpl
import kmpfixture.modern.Service
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlin.reflect.KClass
import org.junit.Test

class KmpMossInterceptorTest {
    private val loader = javaClass.classLoader!!
    private val names = KmpMossSurface.Names("kmpfixture.modern.GrpcEngine", "kmpfixture.modern.MossInterceptor",
        "kmpfixture.modern.KMossException")
    private val surface = (KmpMossSurface.resolve(loader, names) as KmpMossSurface.Result.Resolved).surface
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

    private class Chain(override val request: MossInterceptor.Request) : MossInterceptor.Chain {
        var proceeded: MossInterceptor.Request? = null
        override fun a(request: MossInterceptor.Request, c: Continuation<Any?>): Any? {
            proceeded = request
            return "downstream"
        }
    }

    private fun request(service: String, method: String): RequestImpl = RequestImpl(Service("host"),
        Descriptor("bilibili.app.test.v1", service, method, String::class, object : kotlinx.serialization.SerializationStrategy<String> {}),
        byteArrayOf(7)).also {
        it.headers.ascii["authorization"] = "identify_v1 secret"
        it.headers.binary["x-bili-metadata-bin"] = byteArrayOf(1)
    }

    private fun interceptor(
        session: IncognitoSession?,
        events: Events,
        ready: () -> Boolean = { true },
        sanitizer: KmpHeaderSanitizer? = KmpHeaderSanitizer({ b, _ -> b + 1 }, { b, _ -> b }),
        reply: ((String, ByteArray) -> ByteArray?)? = null
    ): MossInterceptor = KmpMossInterceptor(surface, loader, session, ready, sanitizer, guest = { true },
        localReply = { reply }, events = events).proxy as MossInterceptor

    private fun incognito() = IncognitoSession(run { var n = 0; { "id-${n++}" } }).also { it.enter() }

    fun proxyAdvertisesAllProtocolsAndSortsLast() {
        val proxy = interceptor(null, Events())
        assertEquals(MossInterceptor.Protocol.ALL, proxy.a())
        assertEquals(Int.MAX_VALUE, proxy.priority)
        assertEquals(KmpMossInterceptor.NAME, proxy.name)
    }

    fun guestOnlySessionLeavesUnservedRequestsUntouched() {
        val events = Events()
        val chain = Chain(request("History", "Other"))
        val result = interceptor(null, events, reply = { _, _ -> null }).b(chain, continuation)
        assertEquals("downstream", result)
        assertSame(chain.request, chain.proceeded)
        // Nothing was sealed or blocked: the credential header is exactly as the host set it.
        assertEquals("identify_v1 secret", (chain.request as RequestImpl).headers.ascii["authorization"])
        assertTrue(events.replies.isEmpty() && events.sanitized == null)
    }

    fun localRpcIsAnsweredFromTheLibraryWithoutTouchingTheNetwork() {
        val events = Events()
        val chain = Chain(request("History", "CursorV2"))
        val response = interceptor(null, events, reply = { rpc, payload ->
            assertEquals("bilibili.app.test.v1.History/CursorV2", rpc)
            payload + 9
        }).b(chain, continuation) as MossInterceptor.Response
        assertNull(chain.proceeded)
        assertNull(response.error)
        assertArrayEquals(byteArrayOf(7, 9), response.payload)
        assertEquals(listOf(true to "bilibili.app.test.v1.History/CursorV2"), events.replies)
    }

    fun libraryFailureIsAnErrorNotAFallbackToTheCloud() {
        val events = Events()
        val chain = Chain(request("History", "CursorV2"))
        val response = interceptor(null, events, reply = { _, _ -> error("disk") }).b(chain, continuation) as MossInterceptor.Response
        assertNull(chain.proceeded)
        assertTrue(response.error is KMossException)
        assertEquals(1, events.failures.size)
    }

    fun incognitoBlocksPolicyForbiddenRpcsWithAnEmptySuccess() {
        val events = Events()
        val chain = Chain(request("Report", "Heartbeat"))
        val response = interceptor(incognito(), events).b(chain, continuation) as MossInterceptor.Response
        assertNull(chain.proceeded)
        assertNull(response.error)
        assertEquals(0, response.payload!!.size)
        assertEquals(listOf(false to "bilibili.app.test.v1.Report/Heartbeat"), events.replies)
    }

    fun incognitoSealsIdentityThenProceedsOnAllowedRpcs() {
        val events = Events()
        val chain = Chain(request("Feed", "Index"))
        val result = interceptor(incognito(), events).b(chain, continuation)
        assertEquals("downstream", result)
        val headers = (chain.proceeded as RequestImpl).headers
        assertFalse(headers.ascii.containsKey("authorization"))
        assertArrayEquals(byteArrayOf(1, 1), headers.binary["x-bili-metadata-bin"])
        assertNotNull(events.sanitized)
        assertTrue("authorization" in events.sanitized!!)
        assertEquals(listOf("bilibili.app.test.v1.Feed/Index"), events.passed)
    }

    fun incognitoThatIsNotReadyBlocksEverything() {
        val chain = Chain(request("Feed", "Index"))
        val response = interceptor(incognito(), Events(), ready = { false }).b(chain, continuation) as MossInterceptor.Response
        assertNull(chain.proceeded)
        assertEquals(0, response.payload!!.size)
        assertEquals("identify_v1 secret", (chain.request as RequestImpl).headers.ascii["authorization"])
    }

    fun sanitizerFailureFailsTheCallAndNeverSendsTheOriginal() {
        val events = Events()
        val failing = KmpHeaderSanitizer({ _, _ -> error("parse") }, { b, _ -> b })
        val chain = Chain(request("Feed", "Index"))
        val response = interceptor(incognito(), events, sanitizer = failing).b(chain, continuation) as MossInterceptor.Response
        assertNull(chain.proceeded)
        assertTrue(response.error is KMossException)
        assertEquals(1, events.failures.size)
    }

    fun incognitoWithoutASanitizerCannotProceed() {
        val chain = Chain(request("Feed", "Index"))
        val response = interceptor(incognito(), Events(), sanitizer = null).b(chain, continuation) as MossInterceptor.Response
        assertNull(chain.proceeded)
        assertTrue(response.error is KMossException)
    }

    fun incognitoSessionThatEndedBlocksInsteadOfSendingAnonymousLooking() {
        val session = incognito().also { it.exit() }
        val chain = Chain(request("Feed", "Index"))
        val response = interceptor(session, Events()).b(chain, continuation) as MossInterceptor.Response
        assertNull(chain.proceeded)
        assertEquals(0, response.payload!!.size)
    }

    fun unrecognisedDescriptorIsBlockedInIncognitoAndPassesThroughForGuestOnly() {
        val strategy = object : kotlinx.serialization.SerializationStrategy<String> {}
        fun unrecognised() = RequestImpl(Service("h"), object : Descriptor("p", "S", "M", String::class, strategy) {
            override fun toString() = "SomethingElse()"
        }, byteArrayOf(7))
        // Incognito cannot tell what this RPC is, so nothing may be sent.
        val blocked = Chain(unrecognised())
        val response = interceptor(incognito(), Events()).b(blocked, continuation) as MossInterceptor.Response
        assertNull(blocked.proceeded)
        assertEquals(0, response.payload!!.size)
        // A guest-only session does not filter traffic, so an unnamed RPC simply goes through.
        val open = Chain(unrecognised())
        assertEquals("downstream", interceptor(null, Events(), reply = { _, _ -> error("not asked") }).b(open, continuation))
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

    fun structuralFallbackResolvesObfuscatedComponentAndThreeStringDescriptorsAndFailsClosed() {
        val ic = KmpMossInterceptor(
            surface = surface,
            loader = loader,
            session = incognito(),
            isolationReady = { true },
            sanitizer = KmpHeaderSanitizer({ b, _ -> b + 1 }, { b, _ -> b }),
            guest = { true },
            localReply = { null },
            events = Events()
        )
        assertEquals("bilibili.pgc.v1.Pgc/Play", ic.fullMethodName(ComponentDesc()))
        assertEquals("bilibili.app.interface.v1.Search/DefaultWords", ic.fullMethodName(ThreeStringFieldDesc()))
        assertNull(ic.fullMethodName(BadPkgThreeStringDesc()))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (KmpMossInterceptorTest) -> Unit) {
            val s = KmpMossInterceptorTest()
            block(s)
        }
        runIsolated { s -> s.proxyAdvertisesAllProtocolsAndSortsLast() }
        runIsolated { s -> s.guestOnlySessionLeavesUnservedRequestsUntouched() }
        runIsolated { s -> s.localRpcIsAnsweredFromTheLibraryWithoutTouchingTheNetwork() }
        runIsolated { s -> s.libraryFailureIsAnErrorNotAFallbackToTheCloud() }
        runIsolated { s -> s.incognitoBlocksPolicyForbiddenRpcsWithAnEmptySuccess() }
        runIsolated { s -> s.incognitoSealsIdentityThenProceedsOnAllowedRpcs() }
        runIsolated { s -> s.incognitoThatIsNotReadyBlocksEverything() }
        runIsolated { s -> s.sanitizerFailureFailsTheCallAndNeverSendsTheOriginal() }
        runIsolated { s -> s.incognitoWithoutASanitizerCannotProceed() }
        runIsolated { s -> s.incognitoSessionThatEndedBlocksInsteadOfSendingAnonymousLooking() }
        runIsolated { s -> s.unrecognisedDescriptorIsBlockedInIncognitoAndPassesThroughForGuestOnly() }
        runIsolated { s -> s.structuralFallbackResolvesObfuscatedComponentAndThreeStringDescriptorsAndFailsClosed() }
    }
}
