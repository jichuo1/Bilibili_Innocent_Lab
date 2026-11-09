package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import kmpfixture.legacy.Descriptor
import kmpfixture.legacy.KMossException
import kmpfixture.legacy.MossInterceptor
import kmpfixture.legacy.RequestImpl
import kmpfixture.legacy.Service
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 8.84-8.96 host builds a four-argument Response and reaches the request through accessors. */
class KmpMossInterceptorLegacyTest {
    private val loader = javaClass.classLoader!!
    private val names = KmpMossSurface.Names("kmpfixture.legacy.GrpcEngine", "kmpfixture.legacy.MossInterceptor",
        "kmpfixture.legacy.KMossException")
    private val surface = (KmpMossSurface.resolve(loader, names) as KmpMossSurface.Result.Resolved).surface
    private val continuation = object : Continuation<Any?> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) {}
    }

    private class Chain(override val request: MossInterceptor.Request) : MossInterceptor.Chain {
        var proceeded: MossInterceptor.Request? = null
        override fun a(request: MossInterceptor.Request, c: Continuation<Any?>): Any? {
            proceeded = request
            return "downstream"
        }
    }

    private val events = object : KmpMossInterceptor.Events {
        override fun replied(local: Boolean, rpc: String?) {}
        override fun sanitized(names: Set<String>) {}
        override fun passed(rpc: String) {}
        override fun failed(reason: Throwable) {}
    }

    private fun request(service: String, method: String) = RequestImpl(Service("host"),
        Descriptor("bilibili.app.test.v1", service, method, String::class, object : kotlinx.serialization.SerializationStrategy<String> {}),
        byteArrayOf(7)).also {
        it.headers.ascii["authorization"] = "identify_v1 secret"
        it.headers.binary["x-bili-metadata-bin"] = byteArrayOf(1)
    }

    private fun interceptor(reply: ((String, ByteArray) -> ByteArray?)? = null, session: IncognitoSession? = null): MossInterceptor {
        val sanitizer = KmpHeaderSanitizer({ b, _ -> b + 1 }, { b, _ -> b })
        return KmpMossInterceptor(surface, loader, session, { true }, sanitizer, guest = { true },
            localReply = { reply }, events = events).proxy as MossInterceptor
    }

    private fun incognito() = IncognitoSession(run { var n = 0; { "id-${n++}" } }).also { it.enter() }

    @Test fun localReplyCarriesTheRequestInTheFourArgResponse() {
        val chain = Chain(request("History", "CursorV2"))
        val response = interceptor(reply = { _, payload -> payload + 5 }).b(chain, continuation) as MossInterceptor.Response
        assertNull(chain.proceeded)
        assertSame(chain.request, response.request)
        assertNull(response.error)
        assertArrayEquals(byteArrayOf(7, 5), response.payload)
        // The response owns a fresh header container rather than aliasing the request's.
        assertFalse(response.headers === (chain.request as RequestImpl).headers)
    }

    @Test fun incognitoSealsThroughTheAccessorsAndProceeds() {
        val chain = Chain(request("Feed", "Index"))
        assertEquals("downstream", interceptor(session = incognito()).b(chain, continuation))
        val headers = (chain.proceeded as RequestImpl).headers
        assertFalse(headers.ascii.containsKey("authorization"))
        assertArrayEquals(byteArrayOf(1, 1), headers.binary["x-bili-metadata-bin"])
    }

    @Test fun incognitoBlockedRpcAndFailureBothAnswerWithoutProceeding() {
        val blocked = Chain(request("Report", "Heartbeat"))
        val empty = interceptor(session = incognito()).b(blocked, continuation) as MossInterceptor.Response
        assertNull(blocked.proceeded)
        assertEquals(0, empty.payload!!.size)

        val failing = Chain(request("History", "CursorV2"))
        val error = interceptor(reply = { _, _ -> error("disk") }).b(failing, continuation) as MossInterceptor.Response
        assertNull(failing.proceeded)
        assertTrue(error.error is KMossException)
    }
}
