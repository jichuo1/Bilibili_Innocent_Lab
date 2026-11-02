package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentChatStreamTest {
    private val source = AgentModelSource(1, "https://example.test/v1", "secret", "model")
    private fun chunk(delta: JSONObject = JSONObject(), finish: String? = null): String =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", finish ?: JSONObject.NULL))).toString()
    private fun event(text: String) = "data: $text\r\n\r\n"
    private fun end() = event(chunk(finish = "stop")) + event("[DONE]")
    private fun read(wire: String, deltas: MutableList<String> = mutableListOf()): JSONObject =
        JSONObject(AgentChatStream.read(wire.byteInputStream(), {}, deltas::add))
    private fun rejects(wire: String, reason: AgentModelException.Reason = AgentModelException.Reason.INVALID_RESPONSE) {
        val error = runCatching { read(wire) }.exceptionOrNull() as? AgentModelException
        assertEquals(reason, error?.reason)
    }
    @Test fun `UTF8 fragmented at every byte preserves content and excludes reasoning and heartbeats`() {
        val wire = "\uFEFF: keepalive\r\n\r\n" + event(chunk(JSONObject().put("role", "assistant"))) +
            event(chunk(JSONObject().put("reasoning_content", "PRIVATE"))) +
            event(chunk(JSONObject().put("content", "你好🌙"))) + end()
        val input = object : ByteArrayInputStream(wire.toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(1, length))
        }
        val deltas = mutableListOf<String>()
        val result = JSONObject(AgentChatStream.read(input, {}, deltas::add))
        assertEquals(listOf("你好🌙"), deltas)
        assertEquals("你好🌙", result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertFalse(result.toString().contains("PRIVATE"))
    }
    @Test fun `multiline data events and usage-only tail are accepted`() {
        val data = chunk(JSONObject().put("content", "text")).replace("{\"choices\":", "{\n\"choices\":")
        val wire = data.lines().joinToString("\n") { "data: $it" } + "\n\n" + event(chunk(finish = "stop")) +
            event("{\"choices\":[],\"usage\":{\"total_tokens\":9}}") + event("[DONE]")
        assertEquals(9, read(wire).getJSONObject("usage").getInt("total_tokens"))
    }
    @Test fun `completion marker and clean EOF require a stop event`() {
        rejects(event(chunk(JSONObject().put("content", "partial"))))
        rejects(event("[DONE]"))
        rejects(event(chunk(JSONObject().put("content", "partial"))) + "data: " + chunk(finish = "stop"))
        assertNotNull(read(event(chunk(JSONObject().put("content", "text"))) + event(chunk(finish = "stop"))))
    }
    @Test fun `length refusal tool calls and late content are never accepted as success`() {
        rejects(event(chunk(finish = "length")), AgentModelException.Reason.OUTPUT_LIMIT)
        rejects(event(chunk(JSONObject().put("refusal", "refused"))) + end())
        rejects(event(chunk(JSONObject().put("tool_calls", JSONArray()))) + end())
        rejects(event(chunk(finish = "stop")) + event(chunk(JSONObject().put("content", "late"))))
        rejects(event("{\"error\":{\"message\":\"private\"}}"))
    }
    @Test fun `response and line budgets reject unbounded content`() {
        rejects(event(chunk(JSONObject().put("content", "x".repeat(65_537)))), AgentModelException.Reason.TOO_LARGE)
        rejects("data: " + "x".repeat(131_073) + "\n\n", AgentModelException.Reason.TOO_LARGE)
    }
    @Test fun `cancel is checked before exposing any content`() {
        var callbacks = 0
        val error = runCatching { AgentChatStream.read((event(chunk(JSONObject().put("content", "x"))) + end()).byteInputStream(),
            { throw AgentModelException(AgentModelException.Reason.CANCELLED) }, { callbacks++ }) }.exceptionOrNull()
        assertEquals(AgentModelException.Reason.CANCELLED, (error as AgentModelException).reason)
        assertEquals(0, callbacks)
    }
    @Test fun `production transport emits content before the server completes and keeps HTTPS guards`() {
        val first = event(chunk(JSONObject().put("content", "first"))).byteInputStream()
        val last = end().byteInputStream()
        val seen = CountDownLatch(1); val finish = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val connection = object : HttpURLConnection(URL(source.resolvedEndpoint)) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { finish.countDown() }
            override fun getResponseCode() = 200
            override fun getContentType() = "text/event-stream; charset=utf-8"
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int {
                    val byte = first.read()
                    if (byte >= 0) return byte
                    assertTrue(finish.await(2, TimeUnit.SECONDS)); return last.read()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (first.available() > 0) return first.read(buffer, offset, length)
                    assertTrue(finish.await(2, TimeUnit.SECONDS)); return last.read(buffer, offset, length)
                }
            }
        }
        try {
            val future = pool.submit<String> { AgentHttpsTransport.postStreamingWithConnection(source, byteArrayOf(), 3000, { false },
                { assertEquals("first", it); seen.countDown() }) { connection } }
            assertTrue(seen.await(2, TimeUnit.SECONDS)); assertFalse(future.isDone)
            finish.countDown(); assertTrue(future.get(2, TimeUnit.SECONDS).contains("first"))
            assertFalse(connection.instanceFollowRedirects)
            assertEquals("text/event-stream, application/json", connection.getRequestProperty("Accept"))
        } finally { finish.countDown(); pool.shutdownNow() }
    }
    @Test fun `stream is opt in and only a structured rejection allows a nonstreaming fallback`() {
        val streams = mutableListOf<Boolean>(); var callbacks = 0
        val transport = object : AgentHttpTransport {
            override fun post(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean): String {
                streams += JSONObject(body.toString(Charsets.UTF_8)).getBoolean("stream")
                return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}"
            }
            override fun postStreaming(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
                onDelta: (String) -> Unit): String {
                streams += JSONObject(body.toString(Charsets.UTF_8)).getBoolean("stream")
                throw AgentHttpsTransport.httpFailure(400, "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"stream\"}}", null)
            }
        }
        val client = AgentModelClient(transport)
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "test"))
        val schema = JSONObject().put("schema", JSONObject().put("type", "object"))
        client.generateStructuredStreaming(source, messages, schema, 2000, { false }) { callbacks++ }
        client.generateStructuredStreaming(source, messages, schema, 2000, { false }) { callbacks++ }
        assertEquals(listOf(true, false, false), streams); assertEquals(0, callbacks)
    }
}
