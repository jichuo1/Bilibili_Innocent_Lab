package com.bilab.core.agent.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LlmHttpTransportTest {

    private open class FakeConnection(
        private val status: Int = 200,
        private val payload: String = "{\"ok\":true}",
        private val contentTypeHeader: String? = "application/json",
        private val retryAfterHeader: String? = null
    ) : HttpURLConnection(URL("https://example.test/v1/chat/completions")) {
        val disconnected = AtomicBoolean()
        val output = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected.set(true) }
        override fun getResponseCode(): Int = status
        override fun getContentType(): String? = contentTypeHeader
        override fun getOutputStream() = output
        override fun getInputStream(): InputStream = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        override fun getContentLengthLong(): Long = -1
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Retry-After", ignoreCase = true)) retryAfterHeader else null
    }

    @Test
    fun sharedPoolAllowsCoreThreadTimeoutAndIsBounded() {
        assertTrue(LlmHttpTransport.sharedWorkers.allowsCoreThreadTimeOut())
        assertTrue(LlmHttpTransport.sharedWorkers.corePoolSize in 0..2)
        assertEquals(6, LlmHttpTransport.sharedWorkers.maximumPoolSize)
        assertEquals(16, LlmHttpTransport.sharedWorkers.queue.remainingCapacity() + LlmHttpTransport.sharedWorkers.queue.size)
    }

    @Test
    fun postSendsHeadersDisablesRedirectsAndRedactsToString() {
        val streamClosed = AtomicBoolean()
        val conn = object : FakeConnection() {
            override fun getInputStream(): InputStream = object : ByteArrayInputStream("{\"ok\":true}".toByteArray(Charsets.UTF_8)) {
                override fun close() {
                    streamClosed.set(true)
                    super.close()
                }
            }
        }
        val request = LlmHttpRequest(
            url = "https://example.test/v1/chat/completions",
            body = "{\"ping\":1}".toByteArray(Charsets.UTF_8),
            headers = mapOf("Authorization" to "Bearer top-secret-key"),
            timeoutMs = 180_000
        )
        assertFalse(request.toString().contains("top-secret-key"))
        assertFalse(request.toString().contains("ping"))

        val result = LlmHttpTransport.postWithConnection(request, { false }) { conn }
        assertEquals(200, result.status)
        assertEquals("{\"ok\":true}", result.body)
        assertFalse(conn.instanceFollowRedirects)
        assertFalse(conn.useCaches)
        assertEquals("POST", conn.requestMethod)
        assertEquals("Bearer top-secret-key", conn.getRequestProperty("Authorization"))
        assertTrue(conn.connectTimeout in 1..180_000)
        assertTrue(conn.readTimeout in 1..60_000)
        assertTrue(streamClosed.get())
        assertFalse(conn.disconnected.get())
    }

    @Test
    fun redirectAndNon2xxStatusThrowBoundedExceptions() {
        val redirectConn = object : FakeConnection(status = 302) {
            override fun getInputStream(): InputStream = error("redirect must not read body")
        }
        val req = LlmHttpRequest("https://example.test/v1", byteArrayOf(1), timeoutMs = 1000)
        try {
            LlmHttpTransport.postWithConnection(req, { false }) { redirectConn }
            fail("expected redirect rejection")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.REDIRECT, e.reason)
            assertEquals(302, e.status)
        }
        assertTrue(redirectConn.disconnected.get())

        val rateLimited = FakeConnection(status = 429, payload = "{\"error\":\"secret\"}", retryAfterHeader = "12")
        try {
            LlmHttpTransport.postWithConnection(req, { false }) { rateLimited }
            fail("expected http status failure")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.HTTP_STATUS, e.reason)
            assertEquals(429, e.status)
            assertEquals(12_000L, e.retryAfterMs)
            assertFalse(e.toString().contains("secret"))
        }
    }

    @Test
    fun readBoundedEnforcesByteLimitDeadlineAndCancellation() {
        val futureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        assertEquals(
            "hello",
            LlmHttpTransport.readBounded(ByteArrayInputStream("hello".toByteArray()), 5, futureDeadline) { false }
        )

        try {
            LlmHttpTransport.readBounded(ByteArrayInputStream("hello!".toByteArray()), 5, futureDeadline) { false }
            fail("expected TOO_LARGE")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.TOO_LARGE, e.reason)
        }

        try {
            LlmHttpTransport.readBounded(ByteArrayInputStream("hello".toByteArray()), 10, System.nanoTime() - 1) { false }
            fail("expected TIMEOUT")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.TIMEOUT, e.reason)
        }

        try {
            LlmHttpTransport.readBounded(ByteArrayInputStream("hello".toByteArray()), 10, futureDeadline) { true }
            fail("expected CANCELLED")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.CANCELLED, e.reason)
        }
    }

    @Test
    fun blockedRequestCancelsAndMapsSocketTimeout() {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val blockedConn = object : FakeConnection() {
            override fun getResponseCode(): Int {
                entered.countDown()
                disconnected.await(2, TimeUnit.SECONDS)
                return 200
            }

            override fun disconnect() {
                super.disconnect()
                disconnected.countDown()
            }
        }
        val req = LlmHttpRequest("https://example.test/v1", byteArrayOf(), timeoutMs = 2000)
        try {
            LlmHttpTransport.postWithConnection(req, { entered.count == 0L }) { blockedConn }
            fail("expected cancellation")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.CANCELLED, e.reason)
        }
        assertTrue(blockedConn.disconnected.get())

        val timeoutConn = object : FakeConnection() {
            override fun getResponseCode(): Int = throw SocketTimeoutException("read timed out")
        }
        try {
            LlmHttpTransport.postWithConnection(req, { false }) { timeoutConn }
            fail("expected socket timeout mapping")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.TIMEOUT, e.reason)
        }
    }

    @Test
    fun sseReaderParsesBoundedEventsAndRejectsTruncatedStream() {
        val sse = "\uFEFFdata: {\"delta\":\"a\"}\n\ndata: [DONE]\n\n"
        val received = mutableListOf<String>()
        LlmHttpTransport.readSseEvents(
            input = ByteArrayInputStream(sse.toByteArray(Charsets.UTF_8)),
            checkAlive = {},
            onData = { data ->
                received += data
                data != "[DONE]"
            }
        )
        assertEquals(listOf("{\"delta\":\"a\"}", "[DONE]"), received)

        val broken = "data: {\"delta\":\"half\"}\n"
        try {
            LlmHttpTransport.readSseEvents(
                input = ByteArrayInputStream(broken.toByteArray(Charsets.UTF_8)),
                checkAlive = {},
                onData = { true }
            )
            fail("expected incomplete SSE error")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.NETWORK, e.reason)
        }
    }

    @Test
    fun overloadRejectionMapsToExplicitOverloadErrorAndNon2xxDisconnects() {
        val errorConn = FakeConnection(status = 500, payload = "{}")
        val req = LlmHttpRequest("https://example.test/v1", byteArrayOf(1), timeoutMs = 1000)
        try {
            LlmHttpTransport.postWithConnection(req, { false }) { errorConn }
            fail("expected HTTP_STATUS")
        } catch (e: LlmHttpException) {
            assertEquals(LlmHttpException.Reason.HTTP_STATUS, e.reason)
            assertFalse(e.overloaded)
        }
        assertTrue(errorConn.disconnected.get())

        val release = CountDownLatch(1)
        val started = CountDownLatch(6)
        val blockers = ArrayList<java.util.concurrent.Future<*>>(22)
        try {
            repeat(6 + 16) {
                blockers += LlmHttpTransport.sharedWorkers.submit {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            try {
                LlmHttpTransport.postWithConnection(req, { false }) { FakeConnection() }
                fail("expected overload rejection")
            } catch (e: LlmHttpException) {
                assertTrue(e.overloaded)
                assertEquals(LlmHttpTransport.ERROR_OVERLOADED, e.message)
                assertEquals(LlmHttpException.Reason.NETWORK, e.reason)
            }
        } finally {
            release.countDown()
            blockers.forEach { it.cancel(true) }
        }
    }
}
