package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class SemanticHttpResponseReaderTest {
    @Test fun `successful UTF8 body reads through EOF and closes its stream`() {
        val stream = Body("正常响应".toByteArray(Charsets.UTF_8))
        assertEquals("正常响应", SemanticHttpResponseReader.read(Connection(stream, null), 200))
        assertTrue(stream.closed)
    }

    @Test fun `a body exactly at the byte limit is accepted with or without a length header`() {
        val limit = SemanticHttpResponseReader.MAX_BODY_BYTES
        for (declared in listOf(-1L, limit.toLong())) {
            val stream = Body(ByteArray(limit) { 'a'.code.toByte() })
            val text = SemanticHttpResponseReader.read(Connection(stream, null, declared), 200)
            assertEquals(limit, text.length)
            assertEquals(limit, stream.consumed)
            assertTrue(stream.closed)
        }
    }

    @Test fun `chunked overflow is rejected despite a missing or understated length header`() {
        val limit = SemanticHttpResponseReader.MAX_BODY_BYTES
        for (declared in listOf(-1L, 1L)) {
            val stream = Body(ByteArray(limit + 200), chunk = 4093)
            expectTooLarge { SemanticHttpResponseReader.read(Connection(stream, null, declared), 200) }
            assertEquals(limit + 1, stream.consumed)
            assertTrue(stream.closed)
        }
    }

    @Test fun `oversized length preflight rejects before consuming the response body`() {
        val stream = Body("small body".toByteArray())
        expectTooLarge {
            SemanticHttpResponseReader.read(Connection(stream, null,
                SemanticHttpResponseReader.MAX_BODY_BYTES.toLong() + 1L), 200)
        }
        assertEquals(0, stream.consumed)
        assertTrue(stream.closed)
    }

    @Test fun `error streams use the same limit and are never returned as truncated JSON`() {
        val stream = Body(ByteArray(SemanticHttpResponseReader.MAX_BODY_BYTES + 1), chunk = 4093)
        expectTooLarge { SemanticHttpResponseReader.read(Connection(null, stream, 5L), 429) }
        assertTrue(stream.closed)
        assertEquals("", SemanticHttpResponseReader.read(Connection(null, null), 500))
    }

    private fun expectTooLarge(action: () -> Unit) {
        try {
            action()
            fail("oversized bodies must fail before parsing")
        } catch (expected: IOException) {
            assertEquals("semantic response body too large", expected.message)
        }
    }

    private class Body(bytes: ByteArray, private val chunk: Int = Int.MAX_VALUE) : ByteArrayInputStream(bytes) {
        var closed = false
        val consumed: Int get() = pos
        override fun read(target: ByteArray, offset: Int, length: Int): Int =
            super.read(target, offset, minOf(chunk, length))
        override fun close() { closed = true; super.close() }
    }

    private class Connection(
        private val success: InputStream?,
        private val error: InputStream?,
        private val declaredLength: Long = -1L
    ) : HttpURLConnection(URL("https://example.invalid/")) {
        override fun getInputStream(): InputStream = checkNotNull(success) { "wrong response stream" }
        override fun getErrorStream(): InputStream? = error
        override fun getContentLengthLong(): Long = declaredLength
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }
}
