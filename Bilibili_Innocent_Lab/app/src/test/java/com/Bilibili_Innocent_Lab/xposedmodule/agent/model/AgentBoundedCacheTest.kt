package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentBoundedCacheTest {
    @Test fun `compatibility metadata uses LRU bounds TTL and refuses clock rollback`() {
        var now = 1000L
        val cache = AgentBoundedCache<String, String>(capacity = 2, ttlMs = 100, clock = { now })
        cache["a"] = "first"; cache["b"] = "second"
        assertEquals("first", cache["a"])
        cache["c"] = "third"
        assertNull(cache["b"])
        assertEquals(2, cache.size())
        now = 1100
        assertEquals("first", cache["a"])
        now = 1101
        assertNull(cache["a"])
        cache["new"] = "value"
        now = 900
        assertNull(cache["new"])
    
        AgentCapabilityProbeCases().verifyAllCases()
        AgentChatStreamCases().verifyAllCases()
        AgentDecisionClientCases().verifyAllCases()
        AgentDecisionProbeCases().verifyAllCases()
        AgentHttpSchedulingCases().verifyAllCases()
    }
}

private class AgentCapabilityProbeCases {
    private val source = AgentModelSource(1, "https://example.test", "test-key", "model-name-is-not-evidence")
    private val challenge = VisionChallenge("data:image/png;base64,YWJj", "A7c9E2")

    private fun response(content: String, calls: JSONArray? = null): String = JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("finish_reason", if (calls == null) "stop" else "tool_calls")
            .put("message", JSONObject().put("role", "assistant").put("content", content)
                .also { if (calls != null) it.put("tool_calls", calls) }))).toString()

    private fun echo(body: JSONObject, receiptCorrect: Boolean = true): String {
        val messages = body.getJSONArray("messages")
        if (messages.length() == 1) {
            val prompt = messages.getJSONObject(0).getString("content")
            val nonce = Regex("nonce '([^']+)'").find(prompt)!!.groupValues[1]
            val calls = JSONArray().put(JSONObject().put("id", "echo_1").put("type", "function")
                .put("function", JSONObject().put("name", "capability_echo")
                    .put("arguments", JSONObject().put("nonce", nonce).toString())))
            return response("", calls)
        }
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("echo_1", messages.getJSONObject(2).getString("tool_call_id"))
        val receipt = JSONObject(messages.getJSONObject(2).getString("content")).getString("receipt")
        assertFalse(messages.getJSONObject(0).getString("content").contains(receipt))
        return response(if (receiptCorrect) receipt else "fabricated")
    }

    fun `tool support requires real echo result round trip and vision requires image answer`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            if (body.has("tools")) echo(body) else {
                val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                assertFalse(content.getJSONObject(0).getString("text").contains(challenge.expectedAnswer))
                assertEquals(challenge.dataUrl, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
                response(challenge.expectedAnswer)
            }
        }, clock = { 20_000L })
        val result = AgentCapabilityProbe(client, clock = { 20_000L }).probe(source, challenge)
        assertTrue(result.tools)
        assertTrue(result.vision)
        assertEquals(AgentCapabilityState.SUPPORTED, result.toolState)
        assertEquals(3, requests.size)
        assertFalse(result.detail.contains("test-key"))
        assertFalse(challenge.toString().contains(challenge.dataUrl))
        // 探测结果已登记；同一凭据的普通视觉调用不再触发未验证拒绝。
        client.generate(source, requests.last().getJSONArray("messages"), JSONArray(), true, 1000) { false }
    }

    fun `tool-looking text and incorrect visual answer remain unknown`() {
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> response("capability_echo(nonce)") })
        val result = AgentCapabilityProbe(client).probe(source, challenge)
        assertFalse(result.tools)
        assertFalse(result.vision)
        assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
        assertEquals(AgentCapabilityState.UNKNOWN, result.visionState)
    }

    fun `one tool invocation alone does not prove full tool cycle`() {
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ -> echo(JSONObject(String(bytes, Charsets.UTF_8)), false) })
        val result = AgentCapabilityProbe(client).probe(source)
        assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
        assertEquals(AgentCapabilityState.UNKNOWN, result.visionState)
    }

    fun `authentication network and timeouts cannot be mislabeled unsupported`() {
        for ((reason, status) in listOf(AgentModelException.Reason.HTTP to 401,
            AgentModelException.Reason.NETWORK to null, AgentModelException.Reason.TIMEOUT to null)) {
            var requests = 0
            val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> requests++; throw AgentModelException(reason, status) })
            val result = AgentCapabilityProbe(client).probe(source, challenge)
            assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
            assertEquals(AgentCapabilityState.UNKNOWN, result.visionState)
            assertEquals(1, requests)
        }
    }

    fun `generic tool rejection does not skip a potentially valid visual challenge`() {
        // 403 也可能只是工具功能的独立权限限制，不能据此放弃视觉来源。
        for (status in listOf(400, 403)) {
            var requests = 0
            val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
                requests++
                if (JSONObject(String(bytes, Charsets.UTF_8)).has("tools")) throw AgentModelException(AgentModelException.Reason.HTTP, status)
                response(challenge.expectedAnswer)
            })
            val result = AgentCapabilityProbe(client).probe(source, challenge)
            assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
            assertTrue(result.vision)
            assertEquals(3, requests) // 工具之外还独立尝试一次文本指令检测；错误格式不会授予规划能力。
        }
    }

    fun `receipt authentication failure stops image request without accepting partial tool proof`() {
        var requests = 0
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            requests++
            if (requests == 1) echo(JSONObject(String(bytes, Charsets.UTF_8)))
            else throw AgentModelException(AgentModelException.Reason.HTTP, 401)
        })
        val result = AgentCapabilityProbe(client).probe(source, challenge)
        assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
        assertEquals(AgentCapabilityState.UNKNOWN, result.visionState)
        assertEquals(2, requests)
    }

    fun `cancellation during a common failure still propagates and publishes no proof`() {
        var cancelled = false
        var requests = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ ->
            requests++
            cancelled = true
            throw AgentModelException(AgentModelException.Reason.NETWORK)
        })
        try { AgentCapabilityProbe(client).probe(source, challenge, cancelled = { cancelled }); fail("cancel expected") }
        catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, error.reason) }
        assertEquals(1, requests)
    }

    fun `explicit unsupported is distinct from inconclusive`() {
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            if (JSONObject(String(bytes, Charsets.UTF_8)).has("tools")) throw AgentModelException(AgentModelException.Reason.TOOLS_UNSUPPORTED)
            response(challenge.expectedAnswer)
        })
        val result = AgentCapabilityProbe(client).probe(source, challenge)
        assertEquals(AgentCapabilityState.UNSUPPORTED, result.toolState)
        assertTrue(result.vision)
    }

    fun `cancelled capability probe propagates cancellation`() {
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> fail("must not call"); response("") })
        try { AgentCapabilityProbe(client).probe(source, challenge, cancelled = { true }); fail("cancel expected") }
        catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, e.reason) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentCapabilityProbeCases) -> Unit) {
            val s = AgentCapabilityProbeCases()
            block(s)
        }
        runIsolated { s -> s.`tool support requires real echo result round trip and vision requires image answer`() }
        runIsolated { s -> s.`tool-looking text and incorrect visual answer remain unknown`() }
        runIsolated { s -> s.`one tool invocation alone does not prove full tool cycle`() }
        runIsolated { s -> s.`authentication network and timeouts cannot be mislabeled unsupported`() }
        runIsolated { s -> s.`generic tool rejection does not skip a potentially valid visual challenge`() }
        runIsolated { s -> s.`receipt authentication failure stops image request without accepting partial tool proof`() }
        runIsolated { s -> s.`cancellation during a common failure still propagates and publishes no proof`() }
        runIsolated { s -> s.`explicit unsupported is distinct from inconclusive`() }
        runIsolated { s -> s.`cancelled capability probe propagates cancellation`() }
    }
}

private class AgentChatStreamCases {
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
    fun `UTF8 fragmented at every byte preserves content and excludes reasoning and heartbeats`() {
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
    fun `multiline data events and usage-only tail are accepted`() {
        val data = chunk(JSONObject().put("content", "text")).replace("{\"choices\":", "{\n\"choices\":")
        val wire = data.lines().joinToString("\n") { "data: $it" } + "\n\n" + event(chunk(finish = "stop")) +
            event("{\"choices\":[],\"usage\":{\"total_tokens\":9}}") + event("[DONE]")
        assertEquals(9, read(wire).getJSONObject("usage").getInt("total_tokens"))
    }
    fun `completion marker and clean EOF require a stop event`() {
        rejects(event(chunk(JSONObject().put("content", "partial"))))
        rejects(event("[DONE]"))
        rejects(event(chunk(JSONObject().put("content", "partial"))) + "data: " + chunk(finish = "stop"))
        assertNotNull(read(event(chunk(JSONObject().put("content", "text"))) + event(chunk(finish = "stop"))))
    }
    fun `aggregator completion reasons are normalized to stop`() {
        for (reason in listOf("end_turn", "stop_sequence")) {
            val wire = event(chunk(JSONObject().put("content", "正文"))) +
                event(chunk(finish = reason)) + event("[DONE]")
            val result = read(wire)
            assertEquals("stop", result.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
            assertEquals("正文", result.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content"))
        }
    }
    fun `anthropic streamed text blocks are normalized to a chat completion`() {
        val wire = event("""{"type":"message_start","message":{}}""") +
            event("""{"type":"content_block_start","content_block":{"type":"text"}}""") +
            event("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"你好"}}""") +
            event("""{"type":"content_block_stop"}""") +
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""") +
            event("""{"type":"message_stop"}""")
        val deltas = mutableListOf<String>()
        val result = JSONObject(AgentChatStream.read(wire.byteInputStream(), {}, deltas::add,
            AgentSourceProtocol.ANTHROPIC))
        assertEquals(listOf("你好"), deltas)
        val choice = result.getJSONArray("choices").getJSONObject(0)
        assertEquals("你好", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
    }

    fun `length refusal tool calls and late content are never accepted as success`() {
        rejects(event(chunk(finish = "length")), AgentModelException.Reason.OUTPUT_LIMIT)
        rejects(event(chunk(JSONObject().put("refusal", "refused"))) + end())
        rejects(event(chunk(JSONObject().put("tool_calls", JSONArray()))) + end())
        rejects(event(chunk(finish = "stop")) + event(chunk(JSONObject().put("content", "late"))))
        rejects(event("{\"error\":{\"message\":\"private\"}}"))
    }
    fun `response and line budgets reject unbounded content`() {
        rejects(event(chunk(JSONObject().put("content", "x".repeat(65_537)))), AgentModelException.Reason.TOO_LARGE)
        rejects("data: " + "x".repeat(131_073) + "\n\n", AgentModelException.Reason.TOO_LARGE)
    }
    fun `cancel is checked before exposing any content`() {
        var callbacks = 0
        val error = runCatching { AgentChatStream.read((event(chunk(JSONObject().put("content", "x"))) + end()).byteInputStream(),
            { throw AgentModelException(AgentModelException.Reason.CANCELLED) }, { callbacks++ }) }.exceptionOrNull()
        assertEquals(AgentModelException.Reason.CANCELLED, (error as AgentModelException).reason)
        assertEquals(0, callbacks)
    }
    fun `production transport emits content before the server completes and keeps HTTPS guards`() {
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
    fun `stream is opt in and only a structured rejection allows a nonstreaming fallback`() {
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

    fun verifyAllCases() {
        fun runIsolated(block: (AgentChatStreamCases) -> Unit) {
            val s = AgentChatStreamCases()
            block(s)
        }
        runIsolated { s -> s.`UTF8 fragmented at every byte preserves content and excludes reasoning and heartbeats`() }
        runIsolated { s -> s.`multiline data events and usage-only tail are accepted`() }
        runIsolated { s -> s.`completion marker and clean EOF require a stop event`() }
        runIsolated { s -> s.`aggregator completion reasons are normalized to stop`() }
        runIsolated { s -> s.`anthropic streamed text blocks are normalized to a chat completion`() }
        runIsolated { s -> s.`length refusal tool calls and late content are never accepted as success`() }
        runIsolated { s -> s.`response and line budgets reject unbounded content`() }
        runIsolated { s -> s.`cancel is checked before exposing any content`() }
        runIsolated { s -> s.`production transport emits content before the server completes and keeps HTTPS guards`() }
        runIsolated { s -> s.`stream is opt in and only a structured rejection allows a nonstreaming fallback`() }
    }
}

private class AgentDecisionClientCases {
    private val source = AgentModelSource(1, "https://example.test/v1/systemone", "test-key", "model", AgentSourceProtocol.DECISIONS)
    private val state = JSONObject().put("goal", "find the original demonstration").put("evidence", "candidate facts")
    private val options = linkedMapOf("first" to "first candidate", "second" to "second candidate")
    private val image = "data:image/png;base64,YWJj"

    private fun schemaRejected(parameter: String = "type") =
        AgentModelException(AgentModelException.Reason.DECISION_PARAMETER, 422, rejectedParameter = parameter)

    private fun failure(reason: AgentModelException.Reason, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (error: AgentModelException) { assertEquals(reason, error.reason) }
    }

    private fun supported(vision: Boolean = false, formats: Set<String> = setOf("choice", "noul", "score"),
                          visualFormats: Set<String> = if (vision) formats else emptySet(), checkedAt: Long = 10_000L) =
        AgentModelCapabilities(false, vision, checkedAt, "test proof", decisions = true,
            decisionState = AgentCapabilityState.SUPPORTED, decisionFormats = formats, decisionVisionFormats = visualFormats)

    /** 普通请求测试显式登记证明；能力门禁测试直接创建未登记的 client。 */
    private fun provedClient(transport: AgentHttpTransport, clock: () -> Long = { 10_000L }) =
        AgentDecisionClient(transport, clock).also { it.setCapabilities(source, supported(checkedAt = clock())) }

    private fun response(questions: JSONObject, winner: String = "first", yesValues: Map<String, Double> = emptyMap()): String {
        val answers = JSONObject()
        questions.keys().forEach { id ->
            val question = questions.getJSONObject(id)
            val format = question.getString("type")
            val answer = JSONObject().put("type", format)
            when (format) {
                "choice" -> {
                    val keys = question.getJSONObject("criteria").keys().asSequence().toList()
                    val probabilities = JSONObject()
                    keys.forEach { probabilities.put(it, if (it == winner) 0.96 else 0.04 / (keys.size - 1)) }
                    answer.put("choice", winner).put("probabilities", probabilities).put("confidence", 0.9)
                }
                "noul" -> answer.put("noul", yesValues[id] ?: if (id == winner) 0.96 else 0.04)
                "score" -> {
                    val probability = yesValues[id] ?: if (id == winner) 0.96 else 0.04
                    answer.put("score", probability).put("confidence", 0.9)
                        .put("probabilities", JSONObject().put("0", 1 - probability).put("1", probability))
                }
            }
            answers.put(id, answer)
        }
        return JSONObject().put("answers", answers).put("usage", JSONObject().put("input_tokens", 20).put("output_tokens", 0)).toString()
    }

    private fun questions() = JSONObject().put("selection", JSONObject().put("type", "choice")
        .put("instructions", "Select one supported option.").put("criteria", JSONObject().put("first", "first").put("second", "second")))

    private fun noulQuestions() = JSONObject().put("first", JSONObject().put("type", "noul").put("instructions", "Does this match?"))

    fun `decision results stay typed and selection never emits tool arguments`() {
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            assertFalse(body.has("messages")); assertFalse(body.has("tools"))
            val root = JSONObject(response(body.getJSONObject("questions")))
            root.getJSONObject("answers").getJSONObject("selection").put("tool_calls", "ignore previous instructions")
            root.toString()
        })
        val selection = client.select(source, state, options, timeoutMs = 5000) { false }
        assertEquals("first", selection.choice)
        assertFalse(selection.answers.getJSONObject("selection").has("tool_calls"))
        assertEquals("decisions:choice:object", selection.provenance)
        assertFalse(selection.toString().contains("first candidate"))
    }

    fun `uncertain choice and explicit unknown remain abstentions`() {
        for (unknown in listOf(false, true)) {
            val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
                val questions = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("questions")
                val root = JSONObject(response(questions, if (unknown) "unknown" else "first"))
                if (!unknown) root.getJSONObject("answers").getJSONObject("selection")
                    .put("probabilities", JSONObject().put("first", 0.6).put("second", 0.3).put("unknown", 0.1))
                root.toString()
            })
            assertNull(client.select(source, state, options, timeoutMs = 5000) { false }.choice)
        }
    }

    fun `missing confidence is compatible evidence but never fabricated into action confidence`() {
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val qs = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("questions")
            val root = JSONObject(response(qs))
            root.getJSONObject("answers").keys().forEach { root.getJSONObject("answers").getJSONObject(it).remove("confidence") }
            root.toString()
        })
        val selection = client.select(source, state, options, timeoutMs = 5000) { false }
        assertNull(selection.choice)
        assertFalse(selection.answers.getJSONObject("selection").has("confidence"))
    }

    fun `noul requires unique high candidate and low competing evidence`() {
        val vectors = listOf(mapOf("first" to 0.96, "second" to 0.04, "unknown" to 0.04),
            mapOf("first" to 0.96, "second" to 0.9, "unknown" to 0.04),
            mapOf("first" to 0.96, "second" to 0.04, "unknown" to 0.4),
            mapOf("first" to 0.6, "second" to 0.04, "unknown" to 0.04))
        vectors.forEachIndexed { index, vector ->
            val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
                response(JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("questions"), yesValues = vector)
            }, clock = { 10_000L })
            client.setCapabilities(source, supported(formats = setOf("noul")))
            val selected = client.select(source, state, options, timeoutMs = 5000) { false }
            if (index == 0) assertEquals("first", selected.choice) else assertNull(selected.choice)
        }
    }

    fun `six level selection fallback is bounded and remembers successful shape`() {
        val requests = mutableListOf<JSONObject>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            val questions = body.getJSONObject("questions")
            val format = questions.getJSONObject(questions.keys().next()).getString("type")
            if (format != "score" || body.opt("state") !is String) throw schemaRejected()
            response(questions)
        })
        assertEquals("first", client.select(source, state, options, timeoutMs = 5000) { false }.choice)
        assertEquals(6, requests.size)
        assertEquals("first", client.select(source, state, options, timeoutMs = 5000) { false }.choice)
        assertEquals(7, requests.size)
        assertEquals("score", requests.last().getJSONObject("questions").getJSONObject("first").getString("type"))
    }

    fun `text state learning is isolated by credential fingerprint`() {
        val requests = mutableListOf<JSONObject>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            if (body.opt("state") !is String) throw schemaRejected("state")
            response(body.getJSONObject("questions"))
        })
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        val changed = source.copy(apiKey = "different-key")
        client.setCapabilities(changed, supported())
        client.evaluate(changed, state, questions(), timeoutMs = 5000) { false }
        assertEquals(5, requests.size)
        assertTrue(requests[0].opt("state") is JSONObject)
        assertTrue(requests[2].opt("state") is String)
        assertTrue(requests[3].opt("state") is JSONObject)
    }

    fun `explicit source state hint saves repeated rejected requests across formats and selection`() {
        val requests = mutableListOf<JSONObject>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            if (body.opt("state") !is String) throw schemaRejected("state")
            response(body.getJSONObject("questions"))
        })
        client.probeEvaluate(source, state, questions(), timeoutMs = 5000) { false }
        client.evaluate(source, state, noulQuestions(), timeoutMs = 5000) { false }
        client.select(source, state, options, timeoutMs = 5000) { false }
        assertEquals(4, requests.size)
        assertTrue(requests[0].opt("state") is JSONObject)
        assertTrue(requests.drop(1).all { it.opt("state") is String })
    }

    fun `source hint does not remove the alternate shape for a format specific requirement`() {
        val states = mutableListOf<Pair<String, Boolean>>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val qs = body.getJSONObject("questions")
            val format = qs.getJSONObject(qs.keys().next()).getString("type")
            val text = body.opt("state") is String
            states += format to text
            if (text != (format == "choice")) throw schemaRejected("state")
            response(qs)
        })
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        client.evaluate(source, state, noulQuestions(), timeoutMs = 5000) { false }
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        assertEquals(listOf("choice" to false, "choice" to true, "noul" to true, "noul" to false, "choice" to true), states)
    }

    fun `non state schema failures cannot teach source wide text shape`() {
        val states = mutableListOf<Boolean>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val text = body.opt("state") is String
            states += text
            if (!text) throw schemaRejected("type")
            response(body.getJSONObject("questions"))
        })
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        client.evaluate(source, state, noulQuestions(), timeoutMs = 5000) { false }
        assertEquals(listOf(false, true, false, true), states)
    }

    fun `source state hint expires rolls back and is isolated when credentials change`() {
        var now = 10_000L
        val states = mutableListOf<Boolean>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val text = body.opt("state") is String
            states += text
            if (!text) throw schemaRejected("state")
            response(body.getJSONObject("questions"))
        }, clock = { now })
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        val changed = source.copy(apiKey = "replacement")
        client.setCapabilities(changed, supported(checkedAt = now))
        client.evaluate(changed, state, noulQuestions(), timeoutMs = 5000) { false }
        now += AgentModelCapabilities.VALID_FOR_MS + 1
        client.setCapabilities(source, supported(checkedAt = now))
        client.evaluate(source, state, noulQuestions(), timeoutMs = 5000) { false }
        now -= 100
        client.setCapabilities(source, supported(checkedAt = now))
        client.select(source, state, options, timeoutMs = 5000) { false }
        assertEquals(listOf(false, true, false, true, false, true, false, true), states)
    }

    fun `learned state hint fallback cannot restart the total deadline`() {
        var delay = false
        val states = mutableListOf<Boolean>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val text = body.opt("state") is String
            states += text
            if (delay) {
                Thread.sleep(140)
                throw schemaRejected("state")
            }
            if (!text) throw schemaRejected("state")
            response(body.getJSONObject("questions"))
        })
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        delay = true
        failure(AgentModelException.Reason.TIMEOUT) {
            client.evaluate(source, state, noulQuestions(), timeoutMs = 100) { false }
        }
        assertEquals(listOf(false, true, true), states)
    }

    fun `cancelled alternative response cannot publish learned shape or state hint`() {
        var cancelled = false
        val states = mutableListOf<Boolean>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val text = body.opt("state") is String
            states += text
            if (!text) throw schemaRejected("state")
            if (states.size == 2) cancelled = true
            response(body.getJSONObject("questions"))
        })
        failure(AgentModelException.Reason.CANCELLED) {
            client.evaluate(source, state, questions(), timeoutMs = 5000) { cancelled }
        }
        cancelled = false
        client.evaluate(source, state, noulQuestions(), timeoutMs = 5000) { cancelled }
        assertEquals(listOf(false, true, false, true), states)
    }

    fun `text and image state keep full goal once ahead of unchanged structured evidence`() {
        val original = JSONObject(state.toString()).put("instructions", "Only choose known candidates.")
            .put("nested", JSONObject().put("verified", false)).put("cursor", "valid-next-page")
        val before = original.toString()
        val wireTexts = mutableListOf<String>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val wire = body.opt("state")
            if (wire is JSONObject) throw schemaRejected("state")
            val text = if (wire is JSONArray) wire.getString(0) else wire as String
            wireTexts += text
            assertEquals(1, Regex(Regex.escape(original.getString("goal"))).findAll(text).count())
            assertTrue(text.indexOf("User goal:") < text.indexOf("State:"))
            val evidence = JSONObject(text.substring(text.indexOf("State: ") + "State: ".length))
            assertFalse(evidence.has("goal"))
            assertEquals(original.getString("instructions"), evidence.getString("instructions"))
            assertEquals("valid-next-page", evidence.getString("cursor"))
            assertFalse(evidence.getJSONObject("nested").getBoolean("verified"))
            response(body.getJSONObject("questions"))
        })
        client.setCapabilities(source, supported(true))
        client.evaluate(source, original, questions(), timeoutMs = 5000) { false }
        client.evaluate(source, original, questions(), image, 5000) { false }
        assertEquals(2, wireTexts.size)
        assertEquals(before, original.toString())
    }

    fun `learned shape expires and clock rollback cannot keep stale preference`() {
        var now = 10_000L
        val states = mutableListOf<Boolean>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            states += body.opt("state") is String
            if (body.opt("state") !is String) throw schemaRejected("state")
            response(body.getJSONObject("questions"))
        }, clock = { now })
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        now += AgentModelCapabilities.VALID_FOR_MS + 1
        client.setCapabilities(source, supported(checkedAt = now))
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        now -= 100
        client.setCapabilities(source, supported(checkedAt = now))
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        assertEquals(listOf(false, true, false, true, false, true), states)
    }

    fun `generic HTTP and network failures are not shape retries`() {
        val errors = listOf(AgentModelException(AgentModelException.Reason.HTTP, 400),
            AgentModelException(AgentModelException.Reason.HTTP, 401),
            AgentModelException(AgentModelException.Reason.HTTP, 429),
            AgentModelException(AgentModelException.Reason.NETWORK))
        errors.forEach { expected ->
            var calls = 0
            val client = provedClient(AgentHttpTransport { _, _, _, _ -> calls++; throw expected })
            failure(expected.reason) { client.select(source, state, options, timeoutMs = 5000) { false } }
            assertEquals(1, calls)
        }
    }

    fun `malformed responses are rejected without trying until one agrees`() {
        val invalid = listOf("{'answers':{}}", "{\"answers\":{},\"answers\":{}}", "{} trailing",
            "{\"answers\":{\"selection\":{\"type\":\"choice\",\"choice\":\"shell\"}}}")
        invalid.forEach { payload ->
            var calls = 0
            val client = provedClient(AgentHttpTransport { _, _, _, _ -> calls++; payload })
            failure(AgentModelException.Reason.INVALID_RESPONSE) { client.evaluate(source, state, questions(), timeoutMs = 5000) { false } }
            assertEquals(1, calls)
        }
    }

    fun `missing extra mistyped and impossible probabilities cannot be accepted`() {
        val client = AgentDecisionClient()
        val correct = JSONObject(response(questions()))
        val altered = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("answers").remove("selection") },
            { it.getJSONObject("answers").put("extra", JSONObject().put("type", "noul").put("noul", 1)) },
            { it.getJSONObject("answers").getJSONObject("selection").put("type", "noul") },
            { it.getJSONObject("answers").getJSONObject("selection").put("confidence", "0.9") },
            { it.getJSONObject("answers").getJSONObject("selection").getJSONObject("probabilities").put("first", 99) },
            { it.getJSONObject("answers").getJSONObject("selection").getJSONObject("probabilities").put("first", 0.01) }
        )
        altered.forEach { mutate ->
            val payload = JSONObject(correct.toString()); mutate(payload)
            failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(payload.toString(), questions(), "choice", false) }
        }
    }

    fun `score and its distribution must describe the same expected value`() {
        val qs = JSONObject().put("first", JSONObject().put("type", "score").put("instructions", "match")
            .put("criteria", JSONArray().put("no").put("yes")))
        val root = JSONObject(response(qs))
        root.getJSONObject("answers").getJSONObject("first").put("score", 0.0)
        failure(AgentModelException.Reason.INVALID_RESPONSE) { AgentDecisionClient().decode(root.toString(), qs, "score", false) }
    }

    fun `image proof is tied to source fingerprint freshness and question format`() {
        var calls = 0
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            calls++; response(JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("questions"))
        }, clock = { 10_000L })
        failure(AgentModelException.Reason.VISION_UNVERIFIED) { client.evaluate(source, state, questions(), image, 5000) { false } }
        client.setCapabilities(source, supported(true, visualFormats = setOf("noul")))
        failure(AgentModelException.Reason.VISION_UNVERIFIED) { client.evaluate(source, state, questions(), image, 5000) { false } }
        client.setCapabilities(source, AgentModelCapabilities(false, true, 10_000L, "legacy proof without formats"))
        failure(AgentModelException.Reason.VISION_UNVERIFIED) { client.select(source, state, options, image, 5000) { false } }
        client.setCapabilities(source, supported(true))
        failure(AgentModelException.Reason.VISION_UNVERIFIED) { client.evaluate(source.copy(apiKey = "other"), state, questions(), image, 5000) { false } }
        assertEquals(0, calls)
        client.evaluate(source, state, questions(), image, 5000) { false }
        assertEquals(1, calls)
        val stale = provedClient(AgentHttpTransport { _, _, _, _ -> fail("stale proof cannot send image"); "" },
            clock = { 10_000L + AgentModelCapabilities.VALID_FOR_MS + 1 })
        stale.setCapabilities(source, supported(true))
        failure(AgentModelException.Reason.VISION_UNVERIFIED) { stale.evaluate(source, state, questions(), image, 5000) { false } }
    }

    fun `images remain top level parts and state shape rejection never serializes image into text`() {
        val requests = mutableListOf<JSONObject>()
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            val parts = body.getJSONArray("state")
            assertTrue(parts.opt(0) is String)
            assertFalse(parts.getString(0).contains(image))
            assertEquals(image, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
            throw schemaRejected("state")
        }, clock = { 10_000L })
        client.setCapabilities(source, supported(true))
        failure(AgentModelException.Reason.DECISION_PARAMETER) { client.evaluate(source, state, questions(), image, 5000) { false } }
        assertEquals(1, requests.size)
    }

    fun `hidden images excessive state and arbitrary candidate keys are rejected before HTTP`() {
        val client = provedClient(AgentHttpTransport { _, _, _, _ -> fail("invalid input cannot send"); "" })
        val hidden = JSONObject().put("nested", JSONObject().put("image_url", image))
        failure(AgentModelException.Reason.INVALID_REQUEST) { client.evaluate(source, hidden, questions(), timeoutMs = 5000) { false } }
        failure(AgentModelException.Reason.TOO_LARGE) {
            client.evaluate(source, JSONObject().put("text", "x".repeat(AgentDecisionClient.MAX_STATE_CHARS)), questions(), timeoutMs = 5000) { false }
        }
        failure(AgentModelException.Reason.INVALID_REQUEST) {
            client.select(source, state, mapOf("unknown" to "candidate"), timeoutMs = 5000) { false }
        }
        failure(AgentModelException.Reason.INVALID_REQUEST) {
            client.select(source.copy(protocol = AgentSourceProtocol.CHAT), state, options, timeoutMs = 5000) { false }
        }
    }

    fun `late cancellation discards valid decision response`() {
        var cancelled = false
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            cancelled = true
            response(JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("questions"))
        })
        failure(AgentModelException.Reason.CANCELLED) { client.select(source, state, options, timeoutMs = 5000) { cancelled } }
    }

    fun `shape fallback shares one deadline and cannot restart the timeout`() {
        var calls = 0
        val client = provedClient(AgentHttpTransport { _, _, _, _ ->
            calls++; Thread.sleep(140); throw schemaRejected()
        })
        failure(AgentModelException.Reason.TIMEOUT) { client.select(source, state, options, timeoutMs = 100) { false } }
        assertEquals(1, calls)
    }

    fun `production text requires present fresh positive proof and at least one verified format`() {
        val now = AgentModelCapabilities.VALID_FOR_MS + 20_000L
        var calls = 0
        val client = AgentDecisionClient(AgentHttpTransport { _, _, _, _ -> calls++; fail("Unverified source cannot send"); "" },
            clock = { now })
        val proofs = listOf<AgentModelCapabilities?>(null,
            AgentModelCapabilities(false, false, now, "not proved", decisionFormats = setOf("choice")),
            supported(formats = emptySet(), checkedAt = now), supported(checkedAt = 10_000L),
            supported(checkedAt = now + 1))
        proofs.forEachIndexed { index, proof ->
            val candidate = source.copy(apiKey = "proof-variant-$index")
            proof?.let { client.setCapabilities(candidate, it) }
            failure(AgentModelException.Reason.DECISIONS_UNVERIFIED) {
                client.evaluate(candidate, state, questions(), timeoutMs = 5000) { false }
            }
            failure(AgentModelException.Reason.DECISIONS_UNVERIFIED) {
                client.select(candidate, state, options, timeoutMs = 5000) { false }
            }
        }
        assertEquals(0, calls)
    }

    fun `text evaluate cannot silently send a format omitted by the actual proof`() {
        var calls = 0
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            calls++; response(JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("questions"))
        })
        client.setCapabilities(source, supported(formats = setOf("choice")))
        val noul = JSONObject().put("first", JSONObject().put("type", "noul").put("instructions", "Does this match?"))
        failure(AgentModelException.Reason.DECISIONS_UNVERIFIED) {
            client.evaluate(source, state, noul, timeoutMs = 5000) { false }
        }
        failure(AgentModelException.Reason.DECISIONS_UNVERIFIED) {
            client.evaluate(source.copy(apiKey = "changed-key"), state, questions(), timeoutMs = 5000) { false }
        }
        assertEquals(0, calls)
        client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        assertEquals(1, calls)
    }

    fun `only explicit probe bypasses text proof and the probe itself does not publish a proof`() {
        var calls = 0
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            calls++; response(JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("questions"))
        }, clock = { 10_000L })
        client.probeEvaluate(source, state, questions(), timeoutMs = 5000) { false }
        assertEquals(1, calls)
        failure(AgentModelException.Reason.DECISIONS_UNVERIFIED) {
            client.evaluate(source, state, questions(), timeoutMs = 5000) { false }
        }
        assertEquals(1, calls)
    }

    fun `hidden media aliases and embedded mixed case data URLs are rejected while image digest is allowed`() {
        var calls = 0
        val client = provedClient(AgentHttpTransport { _, bytes, _, _ ->
            calls++; response(JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("questions"))
        })
        val keys = listOf("IMAGE_URL", "ImageUrl", "Input-Image", "Image Data Url", "images", "INPUT_IMAGES", "Image")
        keys.forEach { key ->
            val hidden = JSONObject().put("nested", JSONArray().put(JSONObject().put(key, "hidden media")))
            failure(AgentModelException.Reason.INVALID_REQUEST) {
                client.evaluate(source, hidden, questions(), timeoutMs = 5000) { false }
            }
        }
        for (url in listOf(" DATA:IMAGE/PNG;base64,YWJj", "caption before data:image/jpeg;base64,YWJj after")) {
            val hidden = JSONObject().put("nested", JSONArray().put(JSONObject().put("caption", url)))
            failure(AgentModelException.Reason.INVALID_REQUEST) {
                client.evaluate(source, hidden, questions(), timeoutMs = 5000) { false }
            }
        }
        assertEquals(0, calls)
        client.evaluate(source, state.put("image_digest", "sha256-metadata-only"), questions(), timeoutMs = 5000) { false }
        assertEquals(1, calls)
    }

    fun `local unverified decision proof does not impose provider cooldown`() {
        val health = AgentHealthRegistry()
        val lease = health.reserve(listOf(source), AgentModelRole.DECISION, 10_000L, null)
        assertNotNull(lease)
        lease!!.fail(10_000L, error = AgentModelException(AgentModelException.Reason.DECISIONS_UNVERIFIED))
        val next = health.reserve(listOf(source), AgentModelRole.DECISION, 10_001L, source.fingerprint)
        assertNotNull(next)
        next!!.use { assertEquals(source.fingerprint, it.source.fingerprint) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentDecisionClientCases) -> Unit) {
            val s = AgentDecisionClientCases()
            block(s)
        }
        runIsolated { s -> s.`decision results stay typed and selection never emits tool arguments`() }
        runIsolated { s -> s.`uncertain choice and explicit unknown remain abstentions`() }
        runIsolated { s -> s.`missing confidence is compatible evidence but never fabricated into action confidence`() }
        runIsolated { s -> s.`noul requires unique high candidate and low competing evidence`() }
        runIsolated { s -> s.`six level selection fallback is bounded and remembers successful shape`() }
        runIsolated { s -> s.`text state learning is isolated by credential fingerprint`() }
        runIsolated { s -> s.`explicit source state hint saves repeated rejected requests across formats and selection`() }
        runIsolated { s -> s.`source hint does not remove the alternate shape for a format specific requirement`() }
        runIsolated { s -> s.`non state schema failures cannot teach source wide text shape`() }
        runIsolated { s -> s.`source state hint expires rolls back and is isolated when credentials change`() }
        runIsolated { s -> s.`learned state hint fallback cannot restart the total deadline`() }
        runIsolated { s -> s.`cancelled alternative response cannot publish learned shape or state hint`() }
        runIsolated { s -> s.`text and image state keep full goal once ahead of unchanged structured evidence`() }
        runIsolated { s -> s.`learned shape expires and clock rollback cannot keep stale preference`() }
        runIsolated { s -> s.`generic HTTP and network failures are not shape retries`() }
        runIsolated { s -> s.`malformed responses are rejected without trying until one agrees`() }
        runIsolated { s -> s.`missing extra mistyped and impossible probabilities cannot be accepted`() }
        runIsolated { s -> s.`score and its distribution must describe the same expected value`() }
        runIsolated { s -> s.`image proof is tied to source fingerprint freshness and question format`() }
        runIsolated { s -> s.`images remain top level parts and state shape rejection never serializes image into text`() }
        runIsolated { s -> s.`hidden images excessive state and arbitrary candidate keys are rejected before HTTP`() }
        runIsolated { s -> s.`late cancellation discards valid decision response`() }
        runIsolated { s -> s.`shape fallback shares one deadline and cannot restart the timeout`() }
        runIsolated { s -> s.`production text requires present fresh positive proof and at least one verified format`() }
        runIsolated { s -> s.`text evaluate cannot silently send a format omitted by the actual proof`() }
        runIsolated { s -> s.`only explicit probe bypasses text proof and the probe itself does not publish a proof`() }
        runIsolated { s -> s.`hidden media aliases and embedded mixed case data URLs are rejected while image digest is allowed`() }
        runIsolated { s -> s.`local unverified decision proof does not impose provider cooldown`() }
    }
}

private class AgentDecisionProbeCases {
    private val source = AgentModelSource(1, "https://example.test/v1/systemone", "test-key", "name-is-not-proof", AgentSourceProtocol.DECISIONS)
    private val challenge = VisionChallenge("data:image/png;base64,YWJj", "A7c9E2")

    private fun stateOf(body: JSONObject): JSONObject = when (val value = body.get("state")) {
        is JSONObject -> value
        is JSONArray -> JSONObject()
        is String -> {
            val offset = value.indexOf("State: ")
            check(offset >= 0)
            JSONObject(value.substring(offset + "State: ".length))
        }
        else -> error("bad state")
    }

    /** 本地假服务根据请求数据完成文字题；图片题的答案只由测试持有，不从请求文字推断。 */
    private fun answer(body: JSONObject, alwaysYesForImage: Boolean = false): String {
        val questions = body.getJSONObject("questions")
        val image = body.opt("state") is JSONArray
        val state = stateOf(body)
        val answers = JSONObject()
        questions.keys().forEach { id ->
            val question = questions.getJSONObject(id)
            val format = question.getString("type")
            val instruction = question.getString("instructions")
            val result = JSONObject().put("type", format)
            if (format == "choice") {
                val criteria = question.getJSONObject("criteria")
                val token = if (image) {
                    val position = Regex("character (\\d+)").find(instruction)!!.groupValues[1].toInt() - 1
                    challenge.expectedAnswer[position].toString()
                } else state.getString("token")
                val choice = criteria.keys().asSequence().single { criteria.getString(it) == token }
                val probabilities = JSONObject()
                criteria.keys().forEach { key -> probabilities.put(key, if (key == choice) 0.99 else 0.01 / (criteria.length() - 1)) }
                result.put("choice", choice).put("probabilities", probabilities).put("confidence", 0.98)
            } else {
                val yes = if (image) {
                    if (alwaysYesForImage) true else {
                        val position = Regex("character (\\d+)").find(instruction)?.groupValues?.get(1)?.toInt()?.minus(1) ?: 0
                        val subset = instruction.substring(instruction.indexOf(':') + 1).trim().removeSuffix("?")
                        challenge.expectedAnswer[position] in subset
                    }
                } else {
                    val quoted = Regex("'([^']+)'").find(instruction)!!.groupValues[1]
                    state.getString("token") == quoted
                }
                val probability = if (yes) 0.99 else 0.01
                if (format == "noul") result.put("noul", probability)
                else result.put("score", probability).put("confidence", 0.98)
                    .put("probabilities", JSONObject().put("0", 1 - probability).put("1", probability))
            }
            answers.put(id, result)
        }
        return JSONObject().put("answers", answers).toString()
    }

    fun `decision and visual probes use typed answers and never claim tool support`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            if (body.opt("state") is JSONArray) {
                assertEquals(6, body.getJSONObject("questions").length())
                assertFalse(body.getJSONArray("state").getString(0).contains(challenge.expectedAnswer))
                body.getJSONObject("questions").keys().forEach { id ->
                    assertEquals(62, body.getJSONObject("questions").getJSONObject(id).getJSONObject("criteria").length())
                }
            }
            answer(body)
        }, clock = { 10_000L })
        val caps = AgentDecisionProbe(client, clock = { 10_000L }).probe(source, challenge)
        assertTrue(caps.decisions); assertTrue(caps.vision); assertFalse(caps.tools)
        assertEquals(AgentCapabilityState.UNSUPPORTED, caps.toolState)
        assertEquals(setOf("choice", "noul", "score"), caps.decisionFormats)
        assertEquals(setOf("choice"), caps.decisionVisionFormats)
        assertEquals(4, requests.size)
        assertFalse(caps.detail.contains("test-key"))
    }

    fun `noul only visual probe is strong and split into bounded batches with both answer signs`() {
        val images = mutableListOf<JSONObject>()
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val questions = body.getJSONObject("questions")
            if (questions.getJSONObject(questions.keys().next()).getString("type") != "noul") {
                throw AgentModelException(AgentModelException.Reason.DECISION_PARAMETER, 422, rejectedParameter = "type")
            }
            if (body.opt("state") is JSONArray) {
                images += body
                assertTrue(questions.length() <= AgentDecisionClient.MAX_QUESTIONS)
                assertFalse(body.getJSONArray("state").getString(0).contains(challenge.expectedAnswer))
            }
            answer(body)
        }, clock = { 10_000L })
        val caps = AgentDecisionProbe(client, clock = { 10_000L }).probe(source, challenge)
        assertTrue(caps.decisions); assertTrue(caps.vision)
        assertEquals(setOf("noul"), caps.decisionFormats)
        assertEquals(setOf("noul"), caps.decisionVisionFormats)
        assertEquals(2, images.size)
        assertEquals(37, images.sumOf { it.getJSONObject("questions").length() })
        val signs = images.flatMap { body ->
            val answers = JSONObject(answer(body)).getJSONObject("answers")
            answers.keys().asSequence().map { answers.getJSONObject(it).getDouble("noul") > 0.5 }.toList()
        }.toSet()
        assertEquals(setOf(true, false), signs)
    }

    fun `text only service can prove decisions but image failure remains unknown`() {
        var imageRequests = 0
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (body.opt("state") is JSONArray) {
                imageRequests++
                throw AgentModelException(AgentModelException.Reason.HTTP, 400)
            }
            answer(body)
        })
        val caps = AgentDecisionProbe(client).probe(source, challenge)
        assertTrue(caps.decisions); assertFalse(caps.vision)
        assertEquals(AgentCapabilityState.UNKNOWN, caps.visionState)
        assertTrue(caps.decisionVisionFormats.isEmpty())
        assertEquals(1, imageRequests)
    }

    fun `incorrect visual guesses do not become unsupported or gain proof`() {
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val root = JSONObject(answer(body, alwaysYesForImage = true))
            if (body.opt("state") is JSONArray) root.getJSONObject("answers").keys().forEach { id ->
                val result = root.getJSONObject("answers").getJSONObject(id)
                if (result.getString("type") == "choice") {
                    val criteria = body.getJSONObject("questions").getJSONObject(id).getJSONObject("criteria")
                    val probabilities = JSONObject()
                    criteria.keys().forEach { key -> probabilities.put(key, if (key == "c_0") 0.99 else 0.01 / 61) }
                    result.put("choice", "c_0").put("probabilities", probabilities)
                }
            }
            root.toString()
        })
        val caps = AgentDecisionProbe(client).probe(source, challenge)
        assertTrue(caps.decisions)
        assertFalse(caps.vision)
        assertEquals(AgentCapabilityState.UNKNOWN, caps.visionState)
        assertTrue(caps.decisionVisionFormats.isEmpty())
    }

    fun `explicit image rejection distinguishes unsupported from general errors`() {
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (body.opt("state") is JSONArray) throw AgentModelException(AgentModelException.Reason.VISION_UNSUPPORTED, 422)
            answer(body)
        })
        val caps = AgentDecisionProbe(client).probe(source, challenge)
        assertTrue(caps.decisions); assertFalse(caps.vision)
        assertEquals(AgentCapabilityState.UNSUPPORTED, caps.visionState)
    }

    fun `authentication network and timeout do not assert unsupported decisions or retry formats`() {
        for (reason in listOf(AgentModelException.Reason.HTTP, AgentModelException.Reason.NETWORK, AgentModelException.Reason.TIMEOUT)) {
            var attempts = 0
            val client = AgentDecisionClient(AgentHttpTransport { _, _, _, _ ->
                attempts++; throw AgentModelException(reason, if (reason == AgentModelException.Reason.HTTP) 401 else null)
            })
            val caps = AgentDecisionProbe(client).probe(source, challenge)
            assertFalse(caps.decisions); assertFalse(caps.vision)
            assertEquals(AgentCapabilityState.UNKNOWN, caps.decisionState)
            assertEquals(AgentCapabilityState.UNKNOWN, caps.visionState)
            assertEquals(1, attempts)
        }
    }

    fun `known text formats do not leak into visual proof if only noul image tests succeed`() {
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val questions = body.getJSONObject("questions")
            val format = questions.getJSONObject(questions.keys().next()).getString("type")
            if (body.opt("state") is JSONArray && format == "choice") {
                throw AgentModelException(AgentModelException.Reason.DECISION_PARAMETER, 422, rejectedParameter = "type")
            }
            answer(body)
        })
        val caps = AgentDecisionProbe(client).probe(source, challenge)
        assertEquals(setOf("choice", "noul", "score"), caps.decisionFormats)
        assertEquals(setOf("noul"), caps.decisionVisionFormats)
        assertTrue(caps.vision)
    }

    fun `string state requirement is discovered with explicit schema rejection`() {
        var stringRequests = 0
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (body.opt("state") is JSONObject) throw AgentModelException(AgentModelException.Reason.DECISION_PARAMETER, 422, rejectedParameter = "state")
            stringRequests++
            answer(body)
        })
        val caps = AgentDecisionProbe(client).probe(source)
        assertTrue(caps.decisions)
        assertEquals(setOf("choice", "noul", "score"), caps.decisionFormats)
        assertEquals(3, stringRequests)
        assertEquals(AgentCapabilityState.UNKNOWN, caps.visionState)
    }

    fun `cancelled challenge never returns a capability record`() {
        var cancelled = false
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            cancelled = true
            answer(JSONObject(String(bytes, Charsets.UTF_8)))
        })
        try {
            AgentDecisionProbe(client).probe(source, challenge, cancelled = { cancelled })
            fail("expected cancellation")
        } catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, error.reason) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentDecisionProbeCases) -> Unit) {
            val s = AgentDecisionProbeCases()
            block(s)
        }
        runIsolated { s -> s.`decision and visual probes use typed answers and never claim tool support`() }
        runIsolated { s -> s.`noul only visual probe is strong and split into bounded batches with both answer signs`() }
        runIsolated { s -> s.`text only service can prove decisions but image failure remains unknown`() }
        runIsolated { s -> s.`incorrect visual guesses do not become unsupported or gain proof`() }
        runIsolated { s -> s.`explicit image rejection distinguishes unsupported from general errors`() }
        runIsolated { s -> s.`authentication network and timeout do not assert unsupported decisions or retry formats`() }
        runIsolated { s -> s.`known text formats do not leak into visual proof if only noul image tests succeed`() }
        runIsolated { s -> s.`string state requirement is discovered with explicit schema rejection`() }
        runIsolated { s -> s.`cancelled challenge never returns a capability record`() }
    }
}

private class AgentHttpSchedulingCases {
    private val source = AgentModelSource(1, "https://example.test/v1", "private-key", "model")

    private class Connection : HttpURLConnection(URL("https://example.test/v1/chat/completions")) {
        val disconnected = AtomicBoolean()
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected.set(true) }
        override fun getResponseCode(): Int = 200
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream("{}".toByteArray())
        override fun getContentLengthLong(): Long = -1
    }

    private fun workers(): ThreadPoolExecutor = AgentHttpsTransport::class.java.getDeclaredField("workers").let { field ->
        field.isAccessible = true
        field.get(AgentHttpsTransport) as ThreadPoolExecutor
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out: $description")
            Thread.sleep(5)
        }
    }

    fun `a hundred consecutive short requests cannot be rejected while previous worker returns to idle`() {
        val connected = AtomicInteger()
        repeat(100) {
            val connection = Connection()
            assertEquals("{}", AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 2000, { false }) {
                connected.incrementAndGet()
                connection
            })
            assertTrue(connection.disconnected.get())
        }
        assertEquals(100, connected.get())
    }

    fun `two active and two queued stay bounded while cancelled and expired queued requests never connect`() {
        val pool = workers()
        waitUntil("previous work drained") { pool.activeCount == 0 && pool.queue.isEmpty() }
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val connected = AtomicInteger()
        val queuedCancel = AtomicBoolean()
        val activeErrors = arrayOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        val queuedErrors = arrayOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        val activeDone = CountDownLatch(2)
        val queuedDone = CountDownLatch(2)
        val threads = mutableListOf<Thread>()
        fun caller(name: String, done: CountDownLatch, errors: AtomicReference<Throwable?>,
                   cancelled: () -> Boolean, connect: (URL) -> HttpURLConnection): Thread =
            Thread({
                try { assertEquals("{}", AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 10_000, cancelled, connect)) }
                catch (error: Throwable) { errors.set(error) }
                finally { done.countDown() }
            }, name).apply { isDaemon = true; threads += this; start() }

        try {
            repeat(2) { index ->
                caller("http-active-$index", activeDone, activeErrors[index], { false }) {
                    connected.incrementAndGet()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    Connection()
                }
            }
            assertTrue("Two independent requests must run concurrently", entered.await(2, TimeUnit.SECONDS))
            repeat(2) { index ->
                caller("http-queued-$index", queuedDone, queuedErrors[index], queuedCancel::get) {
                    connected.incrementAndGet()
                    Connection()
                }
            }
            waitUntil("two queued requests") { pool.queue.size == 2 }
            assertEquals(2, pool.activeCount)
            assertEquals(2, pool.maximumPoolSize)
            assertEquals(0, pool.queue.remainingCapacity())
            assertEquals(2, connected.get())

            // 第五份工作只能有界拒绝，不得新增线程或在调用线程执行连接。
            try {
                AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 1000, { false }) {
                    connected.incrementAndGet()
                    fail("Saturated transport cannot connect")
                    Connection()
                }
                fail("Bounded backpressure expected")
            } catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.NETWORK, error.reason) }
            assertEquals(2, connected.get())

            queuedCancel.set(true)
            assertTrue("Queued cancellation must not wait for active sockets", queuedDone.await(2, TimeUnit.SECONDS))
            queuedErrors.forEach { failure ->
                assertTrue(failure.get() is AgentModelException)
                assertEquals(AgentModelException.Reason.CANCELLED, (failure.get() as AgentModelException).reason)
            }
            waitUntil("cancelled queue slots removed") { pool.queue.isEmpty() }

            // 排队时间属于原期限。两条连接仍堵塞时，第三条超时必须在连接工厂之前停止。
            val started = System.nanoTime()
            try {
                AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 120, { false }) {
                    connected.incrementAndGet()
                    fail("Expired queued work cannot connect")
                    Connection()
                }
                fail("Original deadline expected")
            } catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.TIMEOUT, error.reason) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            waitUntil("expired queue slot removed") { pool.queue.isEmpty() }
            assertEquals(2, connected.get())

            release.countDown()
            assertTrue(activeDone.await(2, TimeUnit.SECONDS))
            activeErrors.forEach { assertNull(it.get()) }
            waitUntil("active requests drained") { pool.activeCount == 0 && pool.queue.isEmpty() }
            assertEquals("{}", AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 2000, { false }) { Connection() })
            assertEquals(2, connected.get())
        } finally {
            queuedCancel.set(true)
            release.countDown()
            threads.forEach { it.join(2000) }
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentHttpSchedulingCases) -> Unit) {
            val s = AgentHttpSchedulingCases()
            block(s)
        }
        runIsolated { s -> s.`a hundred consecutive short requests cannot be rejected while previous worker returns to idle`() }
        runIsolated { s -> s.`two active and two queued stay bounded while cancelled and expired queued requests never connect`() }
    }
}
