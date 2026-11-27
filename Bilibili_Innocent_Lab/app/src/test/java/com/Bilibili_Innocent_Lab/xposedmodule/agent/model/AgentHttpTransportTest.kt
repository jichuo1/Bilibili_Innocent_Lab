package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentHttpTransportTest {
    private val source = AgentModelSource(1, "https://example.test/v1", "private-key", "model")
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "test"))
    private val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "echo").put("parameters", JSONObject().put("type", "object"))))
    private val success = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"

    private open class Connection(private val status: Int = 200, private val payload: String = "{}") :
        HttpURLConnection(URL("https://example.test/v1/chat/completions")) {
        val disconnected = AtomicBoolean()
        val output = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected.set(true) }
        override fun getResponseCode(): Int = status
        override fun getOutputStream() = output
        override fun getInputStream(): InputStream = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        override fun getContentLengthLong(): Long = -1
    }

    fun `request uses bearer HTTPS POST no redirects and bounded timeouts`() {
        val connection = Connection(payload = "{\"value\":1}")
        val result = AgentHttpsTransport.postWithConnection(source, "body".toByteArray(), 2000, { false }) { url ->
            assertEquals("https", url.protocol); connection
        }
        assertEquals("{\"value\":1}", result)
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.connectTimeout in 1..2000)
        assertTrue(connection.readTimeout in 1..2000)
        assertEquals("Bearer private-key", connection.getRequestProperty("Authorization"))
        assertEquals("body", connection.output.toString(Charsets.UTF_8.name()))
        assertFalse("2xx response closes stream cleanly to preserve HTTP Keep-Alive", connection.disconnected.get())
    }

    fun `redirect is refused before reading or forwarding any response`() {
        val connection = object : Connection(status = 302) {
            override fun getInputStream(): InputStream = error("must not follow redirect")
            override fun getHeaderField(name: String?): String = "https://different-host.test/steal"
        }
        try { AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 1000, { false }) { connection }; fail("redirect expected") }
        catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.REDIRECT, e.reason) }
        assertTrue(connection.disconnected.get())
    }

    fun `chunked response without content length still has a byte limit`() {
        val connection = Connection(payload = "x".repeat(AgentHttpsTransport.MAX_RESPONSE_BYTES + 1))
        try { AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 1000, { false }) { connection }; fail("size expected") }
        catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.TOO_LARGE, e.reason) }
        assertTrue(connection.disconnected.get())
    }

    fun `cancel while blocked disconnects and makes late response unusable`() {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : Connection() {
            override fun getResponseCode(): Int {
                entered.countDown()
                disconnected.await(2, TimeUnit.SECONDS)
                return 200
            }
            override fun disconnect() { super.disconnect(); disconnected.countDown() }
        }
        val start = System.nanoTime()
        try {
            AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 2000, { entered.count == 0L }) { connection }
            fail("cancel expected")
        } catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, e.reason) }
        assertTrue(connection.disconnected.get())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    }

    fun `absolute deadline also bounds response header blocking`() {
        val disconnected = CountDownLatch(1)
        val connection = object : Connection() {
            override fun getResponseCode(): Int { disconnected.await(2, TimeUnit.SECONDS); return 200 }
            override fun disconnect() { super.disconnect(); disconnected.countDown() }
        }
        try { AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 100, { false }) { connection }; fail("timeout expected") }
        catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.TIMEOUT, e.reason) }
        assertTrue(connection.disconnected.get())
    }

    fun `known providers use verified non streaming reasoning controls while unknown hosts stay plain`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { src, body, timeout, cancelled ->
            AgentHttpsTransport.postWithConnection(src, body, timeout, cancelled) {
                object : Connection(payload = success) {
                    override fun getResponseCode(): Int { requests += JSONObject(output.toString(Charsets.UTF_8.name())); return 200 }
                }
            }
        })
        val endpoints = listOf("https://dashscope.aliyuncs.com/compatible-mode/v1", "https://api.siliconflow.cn/v1",
            "https://api.deepseek.com", "https://open.bigmodel.cn/api/paas/v4", "https://api.z.ai/api/paas/v4",
            "https://ark.cn-beijing.volces.com/api/v3", "https://openrouter.ai/api/v1", "https://unknown.example/v1")
        endpoints.forEach { endpoint -> client.generate(source.copy(endpoint = endpoint), messages, tools, false, 5000) { false } }
        assertFalse(requests[0].getBoolean("enable_thinking"))
        assertFalse(requests[1].getBoolean("enable_thinking"))
        for (index in 2..5) assertEquals("disabled", requests[index].getJSONObject("thinking").getString("type"))
        assertEquals("none", requests[6].getJSONObject("reasoning").getString("effort"))
        assertFalse(requests.last().has("reasoning_effort"))
        assertFalse(requests.last().has("enable_thinking"))
        assertFalse(requests.last().has("thinking"))
        assertFalse(requests.last().has("reasoning"))
        requests.forEach { assertFalse(it.getBoolean("stream")); assertEquals(2048, it.getInt("max_tokens")); assertTrue(it.has("tools")) }
    }

    fun `only explicit optional parameter rejection removes that control with one bounded retry`() {
        val requests = mutableListOf<JSONObject>()
        val timeouts = mutableListOf<Int>()
        val qwen = source.copy(endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1")
        val client = AgentModelClient(AgentHttpTransport { src, body, timeout, cancelled ->
            timeouts += timeout
            val status = if (requests.isEmpty()) 400 else 200
            val payload = if (status == 400) "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"enable_thinking\",\"message\":\"secret\"}}" else success
            AgentHttpsTransport.postWithConnection(src, body, timeout, cancelled) {
                object : Connection(status, payload) {
                    override fun getResponseCode(): Int { requests += JSONObject(output.toString(Charsets.UTF_8.name())); return status }
                }
            }
        })
        assertEquals("ok", client.generate(qwen, messages, tools, false, 5000) { false }.text)
        assertEquals(2, requests.size)
        assertFalse(requests[0].getBoolean("enable_thinking"))
        assertFalse(requests[1].has("enable_thinking"))
        assertTrue(timeouts[1] <= timeouts[0])
        requests.forEach { assertEquals(tools.toString(), it.getJSONArray("tools").toString()); assertEquals(2048, it.getInt("max_tokens")) }
        client.generate(qwen, messages, tools, false, 5000) { false }
        assertEquals(3, requests.size)
        assertFalse(requests.last().has("enable_thinking"))
    }

    fun `unstructured errors tools rejection or rejection of an absent control never trigger stripping`() {
        val errors = listOf(
            "{\"error\":{\"message\":\"enable_thinking unsupported\"}}",
            "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"tools\"}}",
            "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"reasoning.effort\"}}"
        )
        for (error in errors) {
            var attempts = 0
            val client = AgentModelClient(AgentHttpTransport { src, body, timeout, cancelled ->
                attempts++
                AgentHttpsTransport.postWithConnection(src, body, timeout, cancelled) { Connection(400, error) }
            })
            try { client.generate(source.copy(endpoint = "https://dashscope.aliyuncs.com/v1"), messages, tools, false, 5000) { false }; fail("reject expected") }
            catch (_: AgentModelException) { assertEquals(1, attempts) }
        }
    }

    fun `reasoning and output budget compatibility use bounded retries without losing tools or budget`() {
        var attempts = 0
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { src, body, timeout, cancelled ->
            val request = JSONObject(String(body, Charsets.UTF_8))
            requests += request
            assertTrue(request.has("tools"))
            assertTrue(request.has("max_tokens") || request.has("max_completion_tokens"))
            if (attempts++ >= 2) return@AgentHttpTransport success
            val parameter = if (attempts == 1) "enable_thinking" else "max_tokens"
            val error = "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"$parameter\"}}"
            AgentHttpsTransport.postWithConnection(src, body, timeout, cancelled) { Connection(400, error) }
        })
        val qwen = source.copy(endpoint = "https://dashscope.aliyuncs.com/v1")
        assertEquals("ok", client.generate(qwen, messages, tools, false, 5000) { false }.text)
        assertEquals(3, attempts)
        assertFalse(requests.last().has("enable_thinking"))
        assertEquals(2048, requests.last().getInt("max_completion_tokens"))
        client.generate(qwen, messages, tools, false, 5000) { false }
        assertFalse(requests.last().has("enable_thinking"))
        assertEquals(2048, requests.last().getInt("max_completion_tokens"))
    }

    fun `decision shape classification only reports bounded field names and respects status`() {
        val shape = "{\"detail\":[{\"loc\":[\"body\",\"questions\",\"secret-user-question\",\"type\"],\"msg\":\"private-key\"}]}"
        val result = AgentHttpsTransport.httpFailure(422, shape, null)
        assertEquals(AgentModelException.Reason.DECISION_PARAMETER, result.reason)
        assertEquals("type", result.rejectedParameter)
        assertFalse(result.toString().contains("private-key"))
        assertFalse(result.rejectedParameter!!.contains("secret"))
        for (status in listOf(401, 403, 500)) {
            assertEquals(AgentModelException.Reason.HTTP, AgentHttpsTransport.httpFailure(status, shape, null).reason)
        }
        assertEquals("state", AgentHttpsTransport.httpFailure(400,
            "{\"error\":{\"param\":\"state\",\"message\":\"must be string\"}}", null).rejectedParameter)
        for (payload in listOf("{\"error\":{\"message\":\"unsupported model\"}}",
            "{\"error\":{\"param\":\"model\"}}", "{\"detail\":[{\"loc\":[\"body\",\"api_key\"]}]}")) {
            assertEquals(AgentModelException.Reason.HTTP, AgentHttpsTransport.httpFailure(400, payload, null).reason)
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentHttpTransportTest) -> Unit) {
            val s = AgentHttpTransportTest()
            block(s)
        }
        runIsolated { s -> s.`request uses bearer HTTPS POST no redirects and bounded timeouts`() }
        runIsolated { s -> s.`redirect is refused before reading or forwarding any response`() }
        runIsolated { s -> s.`chunked response without content length still has a byte limit`() }
        runIsolated { s -> s.`cancel while blocked disconnects and makes late response unusable`() }
        runIsolated { s -> s.`absolute deadline also bounds response header blocking`() }
        runIsolated { s -> s.`known providers use verified non streaming reasoning controls while unknown hosts stay plain`() }
        runIsolated { s -> s.`only explicit optional parameter rejection removes that control with one bounded retry`() }
        runIsolated { s -> s.`unstructured errors tools rejection or rejection of an absent control never trigger stripping`() }
        runIsolated { s -> s.`reasoning and output budget compatibility use bounded retries without losing tools or budget`() }
        runIsolated { s -> s.`decision shape classification only reports bounded field names and respects status`() }
    
        AgentModelClientCases().verifyAllCases()
        AgentModelTypesCases().verifyAllCases()
        AgentRoleRoutingCases().verifyAllCases()
        AgentSourceRouterCases().verifyAllCases()
        AgentStructuredOutputCases().verifyAllCases()
    }
}

private class AgentModelClientCases {
    private val source = AgentModelSource(1, "https://example.test/v1", "secret-api-key", "model")
    private val user = JSONArray().put(JSONObject().put("role", "user").put("content", "search"))
    private val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "search_videos").put("parameters", JSONObject().put("type", "object"))))

    private fun response(content: String? = "done", calls: JSONArray? = null, finish: String = if (calls == null) "stop" else "tool_calls"): String {
        val message = JSONObject().put("role", "assistant").put("content", content ?: JSONObject.NULL)
        calls?.let { message.put("tool_calls", it) }
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish).put("message", message))).toString()
    }

    private fun calls(arguments: String = "{\"keyword\":\"黑神话\"}", name: String = "search_videos", id: String = "call_1") =
        JSONArray().put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject()
            .put("name", name).put("arguments", arguments)))

    private fun failure(reason: AgentModelException.Reason, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: AgentModelException) { assertEquals(reason, e.reason) }
    }

    fun `verified River endpoint disables thinking without guessing for lookalike hosts`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            requests += JSONObject(String(bytes, Charsets.UTF_8)); response()
        })
        client.generate(source.copy(endpoint = "https://api.river.ai/v1"), user, JSONArray(), false, 5000) { false }
        assertEquals("disabled", requests.single().getJSONObject("thinking").getString("type"))
        assertFalse(requests.single().has("enable_thinking"))
        client.generate(source.copy(endpoint = "https://api.river.ai.example.test/v1"), user, JSONArray(), false, 5000) { false }
        assertFalse(requests.last().has("thinking"))
        assertFalse(requests.last().has("reasoning_effort"))
    }

    fun `insight client omits reasoning controls on aggregator hosts`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            requests += JSONObject(String(bytes, Charsets.UTF_8)); response()
        }, allowReasoningParams = false)
        client.generate(source.copy(endpoint = "https://openrouter.ai/api/v1"), user, JSONArray(), false, 5000) { false }
        // Claude Haiku 等非推理模型不接受 reasoning.effort=none；AI Insight 不应注入任何思考控制参数。
        assertFalse(requests.single().has("reasoning"))
        assertFalse(requests.single().has("reasoning_effort"))
        assertFalse(requests.single().has("thinking"))
    }

    fun `anthropic endpoints resolve to the messages path`() {
        assertEquals("https://api.anthropic.com/v1/messages",
            AgentModelSource(1, "https://api.anthropic.com", "key", "m",
                AgentSourceProtocol.ANTHROPIC).resolvedEndpoint)
        assertEquals("https://api.anthropic.com/v1/messages",
            AgentModelSource(1, "https://api.anthropic.com/v1", "key", "m",
                AgentSourceProtocol.ANTHROPIC).resolvedEndpoint)
        assertEquals("https://api.anthropic.com/v1/messages",
            AgentModelSource(1, "https://api.anthropic.com/v1/messages", "key", "m",
                AgentSourceProtocol.ANTHROPIC).resolvedEndpoint)
    }

    fun `anthropic messages keep system separate and decode content blocks`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            requests += JSONObject(String(bytes, Charsets.UTF_8))
            JSONObject().put("role", "assistant").put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "答案")))
                .put("stop_reason", "end_turn").put("usage", JSONObject()
                    .put("input_tokens", 10).put("output_tokens", 5)).toString()
        })
        val anthropic = source.copy(endpoint = "https://api.anthropic.com/v1",
            protocol = AgentSourceProtocol.ANTHROPIC)
        val withSystem = JSONArray().put(JSONObject().put("role", "system").put("content", "SYS"))
            .put(JSONObject().put("role", "user").put("content", "Q"))
        val turn = client.generate(anthropic, withSystem, JSONArray(), false, 5000) { false }
        assertEquals("答案", turn.text)
        assertEquals("SYS", requests.single().getString("system"))
        assertEquals(1, requests.single().getJSONArray("messages").length())
        assertEquals("user", requests.single().getJSONArray("messages").getJSONObject(0).getString("role"))
        assertFalse(requests.single().has("response_format"))
        assertEquals(5L, turn.usage?.outputTokens)
    }

    fun `explicit large input budget transmits complete text without relaxing output or default clients`() {
        val text = "完整字幕".repeat(20_000)
        var received = ""
        val transport = AgentHttpTransport { _, bytes, _, _ ->
            received = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("messages").getJSONObject(0).getString("content")
            response()
        }
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", text))
        val client = AgentModelClient(transport, inputTextLimit = 500_000)
        client.generate(source, messages, JSONArray(), false, 5000) { false }
        assertEquals(text, received)
        failure(AgentModelException.Reason.INVALID_REQUEST) {
            AgentModelClient(transport).generate(source, messages, JSONArray(), false, 5000) { false }
        }
        failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(response("x".repeat(65_537)), emptySet()) }
    }

    fun `plain text JSON never becomes an executable call`() {
        val client = AgentModelClient()
        val turn = client.decode(response("{\"name\":\"search_videos\",\"arguments\":{}}"), setOf("search_videos"))
        assertTrue(turn.toolCalls.isEmpty())
    }

    fun `typed text response content remains text and rejects non text parts`() {
        val client = AgentModelClient()
        val root = JSONObject(response())
        val message = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        message.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "first"))
            .put(JSONObject().put("type", "text").put("text", " second")))
        assertEquals("first second", client.decode(root.toString(), emptySet()).text)
        assertTrue(client.decode(root.toString(), emptySet()).toolCalls.isEmpty())
        message.getJSONArray("content").put(JSONObject().put("type", "image_url").put("image_url", "private"))
        failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(root.toString(), emptySet()) }
    }

    fun `switching planners strips source specific reasoning without changing tool pairing or original history`() {
        val history = JSONArray(user.toString()).put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", calls()).put("reasoning_content", "source-private-reasoning"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "verified result"))
        val same = AgentModelClient.historyForSource(history, source.fingerprint, source)
        assertTrue(same.getJSONObject(1).has("reasoning_content"))
        val nextSource = source.copy(apiKey = "replacement")
        val next = AgentModelClient.historyForSource(history, source.fingerprint, nextSource)
        assertFalse(next.getJSONObject(1).has("reasoning_content"))
        assertTrue(history.getJSONObject(1).has("reasoning_content"))
        assertEquals("call_1", next.getJSONObject(2).getString("tool_call_id"))
        assertEquals(calls().toString(), next.getJSONObject(1).getJSONArray("tool_calls").toString())
        val client = AgentModelClient(AgentHttpTransport { _, body, _, _ ->
            assertFalse(JSONObject(String(body, Charsets.UTF_8)).getJSONArray("messages").getJSONObject(1).has("reasoning_content"))
            response()
        })
        client.generate(nextSource, next, tools, false, 5000) { false }
    }

    fun `one shared client learns compatibility across tasks but changed credentials or TTL require fresh attempts`() {
        var now = 1_000_000L
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            val request = JSONObject(String(bytes, Charsets.UTF_8))
            requests += request
            if (request.has("max_tokens")) throw AgentModelException(AgentModelException.Reason.TOKEN_PARAMETER,
                rejectedParameter = "max_tokens")
            response()
        }, clock = { now })
        client.generate(source, user, tools, false, 5000) { false }
        assertEquals(2, requests.size)
        client.generate(source, JSONArray(user.toString()), tools, false, 5000) { false }
        assertEquals(3, requests.size)
        client.generate(source.copy(apiKey = "changed"), user, tools, false, 5000) { false }
        assertEquals(5, requests.size)
        now += AgentModelCapabilities.VALID_FOR_MS + 1
        client.generate(source, user, tools, false, 5000) { false }
        assertEquals(7, requests.size)
    }

    fun `decision protocol never enters chat transport`() {
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> fail("must not use chat protocol"); response() })
        failure(AgentModelException.Reason.INVALID_REQUEST) {
            client.generate(source.copy(protocol = AgentSourceProtocol.DECISIONS), user, tools, false, 5000) { false }
        }
    }

    fun `nullable compatibility fields remain non executable`() {
        val client = AgentModelClient()
        for (toolCalls in listOf(JSONObject.NULL, JSONArray())) {
            val root = JSONObject(response())
            root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .put("tool_calls", toolCalls).put("function_call", JSONObject.NULL).put("reasoning_content", JSONObject.NULL)
            assertTrue(client.decode(root.toString(), setOf("search_videos")).toolCalls.isEmpty())
        }
    }

    fun `tool calls preserve id arguments and reasoning for next round`() {
        val root = JSONObject(response(null, calls()))
        root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("reasoning_content", "private reasoning")
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            requests += JSONObject(String(bytes, Charsets.UTF_8))
            if (requests.size == 1) root.toString() else response()
        })
        val first = client.generate(source, user, tools, false, 5000) { false }
        assertEquals("call_1", first.toolCalls.single().id)
        assertEquals("黑神话", first.toolCalls.single().arguments.getString("keyword"))
        user.put(first.message).put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "result"))
        client.generate(source, user, tools, false, 5000) { false }
        assertEquals("private reasoning", requests.last().getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
        assertFalse(first.toString().contains("private reasoning"))
        assertFalse(first.toolCalls.single().toString().contains("黑神话"))
    }

    fun `unknown tools duplicate ids and reused history ids are rejected`() {
        val client = AgentModelClient()
        failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(response(null, calls(name = "shell")), setOf("search_videos")) }
        val duplicates = calls().put(calls().getJSONObject(0))
        failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(response(null, duplicates), setOf("search_videos")) }
        failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(response(null, calls()), setOf("search_videos"), setOf("call_1")) }
    }

    fun `invalid JSON dialect duplicate keys suffix and deep arguments cannot become calls`() {
        val client = AgentModelClient()
        val badArguments = listOf("{'keyword':'x'}", "{\"keyword\":\"a\",\"keyword\":\"b\"}",
            "{\"keyword\":1,\"\\u006beyword\":2}", "{} trailing", "[1]", "{\"a\":NaN}",
            "{\"a\":".repeat(30) + "0" + "}".repeat(30))
        badArguments.forEach { arguments ->
            failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(response(null, calls(arguments)), setOf("search_videos")) }
        }
        val valid = AgentJson.objectOf("{\"array\":[true,false,null,-1.5e+2,{\"x\":\"escaped\\nline\"}]}")
        assertEquals(5, valid.getJSONArray("array").length())
    }

    fun `truncated response is never partially executed`() {
        failure(AgentModelException.Reason.OUTPUT_LIMIT) {
            AgentModelClient().decode(response(null, calls(), "length"), setOf("search_videos"))
        }
    }

    fun `budget parameter fallback retains budget and is learned per credential fingerprint`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            if (body.has("max_tokens")) throw AgentModelException(AgentModelException.Reason.TOKEN_PARAMETER, 400)
            response()
        }, outputTokenBudget = 1024)
        client.generate(source, user, tools, false, 5000) { false }
        client.generate(source, user, tools, false, 5000) { false }
        assertEquals(3, requests.size)
        assertEquals(1024, requests[0].getInt("max_tokens"))
        assertEquals(1024, requests[1].getInt("max_completion_tokens"))
        assertEquals(1024, requests[2].getInt("max_completion_tokens"))
        assertFalse(requests.any { it.has("temperature") })
    }

    fun `network failures are not automatically retried`() {
        var attempts = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ ->
            attempts++; throw AgentModelException(AgentModelException.Reason.NETWORK)
        })
        failure(AgentModelException.Reason.NETWORK) { client.generate(source, user, tools, false, 5000) { false } }
        assertEquals(1, attempts)
    }

    fun `cancellation before or during request prevents response execution`() {
        var attempts = 0
        var cancelled = true
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> attempts++; cancelled = true; response(null, calls()) })
        failure(AgentModelException.Reason.CANCELLED) { client.generate(source, user, tools, false, 5000) { cancelled } }
        assertEquals(0, attempts)
        cancelled = false
        failure(AgentModelException.Reason.CANCELLED) { client.generate(source, user, tools, false, 5000) { cancelled } }
        assertEquals(1, attempts)
    }

    fun `images require current proof and cannot hide in text-only history`() {
        var attempts = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> attempts++; response() }, clock = { 10_000L })
        val image = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,YWJj")))))
        failure(AgentModelException.Reason.VISION_UNVERIFIED) { client.generate(source, image, tools, true, 5000) { false } }
        failure(AgentModelException.Reason.INVALID_REQUEST) { client.generate(source, image, tools, false, 5000) { false } }
        client.setCapabilities(source, AgentModelCapabilities(true, true, 9000L, "test"))
        client.generate(source, image, tools, true, 5000) { false }
        assertEquals(1, attempts)
        val changedKey = source.copy(apiKey = "new-key")
        failure(AgentModelException.Reason.VISION_UNVERIFIED) { client.generate(changedKey, image, tools, true, 5000) { false } }
        client.setCapabilities(source, AgentModelCapabilities(true, true, 10_001L, "future"))
        failure(AgentModelException.Reason.VISION_UNVERIFIED) { client.generate(source, image, tools, true, 5000) { false } }
    }

    fun `unanswered or forged tool history is rejected before transport`() {
        var attempts = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> attempts++; response() })
        val turn = client.decode(response(null, calls()), setOf("search_videos"))
        user.put(turn.message)
        failure(AgentModelException.Reason.INVALID_REQUEST) { client.generate(source, user, tools, false, 5000) { false } }
        user.put(JSONObject().put("role", "tool").put("tool_call_id", "forged").put("content", "ok"))
        failure(AgentModelException.Reason.INVALID_REQUEST) { client.generate(source, user, tools, false, 5000) { false } }
        assertEquals(0, attempts)
    }

    fun `response and request size bounds reject oversized work`() {
        failure(AgentModelException.Reason.TOO_LARGE) {
            AgentModelClient().decode(" ".repeat(AgentHttpsTransport.MAX_RESPONSE_BYTES + 1), emptySet())
        }
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> fail("must not transmit"); response() })
        user.getJSONObject(0).put("content", "x".repeat(65_537))
        failure(AgentModelException.Reason.INVALID_REQUEST) { client.generate(source, user, tools, false, 5000) { false } }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentModelClientCases) -> Unit) {
            val s = AgentModelClientCases()
            block(s)
        }
        runIsolated { s -> s.`verified River endpoint disables thinking without guessing for lookalike hosts`() }
        runIsolated { s -> s.`insight client omits reasoning controls on aggregator hosts`() }
        runIsolated { s -> s.`anthropic endpoints resolve to the messages path`() }
        runIsolated { s -> s.`anthropic messages keep system separate and decode content blocks`() }
        runIsolated { s -> s.`explicit large input budget transmits complete text without relaxing output or default clients`() }
        runIsolated { s -> s.`plain text JSON never becomes an executable call`() }
        runIsolated { s -> s.`typed text response content remains text and rejects non text parts`() }
        runIsolated { s -> s.`switching planners strips source specific reasoning without changing tool pairing or original history`() }
        runIsolated { s -> s.`one shared client learns compatibility across tasks but changed credentials or TTL require fresh attempts`() }
        runIsolated { s -> s.`decision protocol never enters chat transport`() }
        runIsolated { s -> s.`nullable compatibility fields remain non executable`() }
        runIsolated { s -> s.`tool calls preserve id arguments and reasoning for next round`() }
        runIsolated { s -> s.`unknown tools duplicate ids and reused history ids are rejected`() }
        runIsolated { s -> s.`invalid JSON dialect duplicate keys suffix and deep arguments cannot become calls`() }
        runIsolated { s -> s.`truncated response is never partially executed`() }
        runIsolated { s -> s.`budget parameter fallback retains budget and is learned per credential fingerprint`() }
        runIsolated { s -> s.`network failures are not automatically retried`() }
        runIsolated { s -> s.`cancellation before or during request prevents response execution`() }
        runIsolated { s -> s.`images require current proof and cannot hide in text-only history`() }
        runIsolated { s -> s.`unanswered or forged tool history is rejected before transport`() }
        runIsolated { s -> s.`response and request size bounds reject oversized work`() }
    }
}

private class AgentModelTypesCases {
    fun `source normalization preserves query and rejects credentials cleartext or fragments`() {
        assertEquals("https://example.test/v1/chat/completions", AgentModelSource(8, "https://example.test", "k", "m").resolvedEndpoint)
        assertEquals("https://example.test/custom/chat/completions?api-version=1", AgentModelSource(1,
            "https://example.test/custom?api-version=1", "k", "m").resolvedEndpoint)
        assertEquals("https://example.test/v1/chat/completions?api-version=1", AgentModelSource(1,
            "https://example.test/v1/chat/completions?api-version=1", "k", "m").resolvedEndpoint)
        for (endpoint in listOf("http://example.test", "https://user:secret@example.test", "https://example.test/#fragment", "file:///secret", "https://example.test:0")) {
            assertNull(endpoint, AgentModelSource.from(1, endpoint, "key", "model"))
        }
        assertNull(AgentModelSource.from(9, "https://example.test", "key", "model"))
        assertNull(AgentModelSource.from(1, "https://example.test", "key\r\nInjected: value", "model"))
    }

    fun `credential changes invalidate proof without exposing key in diagnostic strings`() {
        val source = AgentModelSource(1, "https://example.test/v1", "private-key", "private-model")
        assertNotEquals(source.fingerprint, source.copy(apiKey = "other-key").fingerprint)
        assertNotEquals(source.fingerprint, source.copy(model = "other-model").fingerprint)
        assertNotEquals(source.fingerprint, source.copy(endpoint = "https://other.test").fingerprint)
        assertEquals(source.fingerprint, source.copy(index = 8).fingerprint)
        assertEquals(64, source.fingerprint.length)
        assertFalse(source.toString().contains("private"))
        assertFalse(source.fingerprint.contains("private-key"))
    }

    fun `capability persistence preserves unknown unsupported and freshness`() {
        val original = AgentModelCapabilities(false, true, 1000, "checked", AgentCapabilityState.UNSUPPORTED, AgentCapabilityState.SUPPORTED)
        assertEquals(original, AgentModelCapabilities.fromJson(original.toJson()))
        assertFalse(original.fresh(999))
        assertTrue(original.fresh(1000))
        assertFalse(original.fresh(1001 + AgentModelCapabilities.VALID_FOR_MS))
        assertNull(AgentModelCapabilities.fromJson(original.toJson().put("tools", true)))
        assertNull(AgentModelCapabilities.fromJson(JSONObject()))
    }

    fun `HTTP failure classification is structural and does not disclose response contents`() {
        val body = "{\"error\":{\"message\":\"private-key image unsupported\"}}"
        val failure = AgentHttpsTransport.httpFailure(401, body, null)
        assertEquals(AgentModelException.Reason.HTTP, failure.reason)
        assertFalse(failure.toString().contains("private-key"))
        val unsupported = "{\"error\":{\"code\":\"unsupported_feature\",\"param\":\"image_url\"}}"
        assertEquals(AgentModelException.Reason.VISION_UNSUPPORTED, AgentHttpsTransport.httpFailure(400, unsupported, null).reason)
        assertEquals(AgentModelException.Reason.HTTP, AgentHttpsTransport.httpFailure(500, unsupported, null).reason)
        val tokens = "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"max_tokens\"}}"
        assertEquals(AgentModelException.Reason.TOKEN_PARAMETER, AgentHttpsTransport.httpFailure(400, tokens, null).reason)
    }

    fun `decisions endpoints use the actual protocol and never become chat paths`() {
        val native = AgentModelSource(1, "https://api.typesafe.ai/v1", "key", "jev-latest", AgentSourceProtocol.DECISIONS)
        assertEquals("https://api.typesafe.ai/v1/systemone", native.resolvedEndpoint)
        val router = native.copy(endpoint = "https://openrouter.ai/api/v1")
        assertEquals("https://openrouter.ai/api/alpha/decisions", router.resolvedEndpoint)
        assertEquals("https://proxy.example/v1/decision?version=2", native.copy(endpoint =
            "https://proxy.example/v1/decision?version=2").resolvedEndpoint)
        assertNotEquals(native.fingerprint, native.copy(protocol = AgentSourceProtocol.CHAT).fingerprint)
        for (endpoint in listOf("http://proxy.example/v1", "https://user:secret@proxy.example/v1/decision",
            "https://proxy.example/v1/decision#fragment")) {
            assertNull(AgentModelSource.from(1, endpoint, "key", "jev", AgentSourceProtocol.DECISIONS))
        }
    }

    fun `old capability documents remain readable while new decision evidence expires after one day`() {
        val old = AgentModelCapabilities(true, false, 1000, "checked").toJson()
        old.remove("decisions"); old.remove("decisionState"); old.remove("decisionFormats")
        assertFalse(AgentModelCapabilities.fromJson(old)!!.decisions)
        val current = AgentModelCapabilities(false, true, 1000, "checked", decisions = true,
            decisionFormats = setOf("choice", "noul"))
        assertEquals(current, AgentModelCapabilities.fromJson(current.toJson()))
        assertTrue(current.fresh(1000 + 24 * 60 * 60_000L))
        assertFalse(current.fresh(1001 + 24 * 60 * 60_000L))
        assertNull(AgentModelCapabilities.fromJson(current.toJson().put("decisionFormats", org.json.JSONArray().put("arbitrary"))))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentModelTypesCases) -> Unit) {
            val s = AgentModelTypesCases()
            block(s)
        }
        runIsolated { s -> s.`source normalization preserves query and rejects credentials cleartext or fragments`() }
        runIsolated { s -> s.`credential changes invalidate proof without exposing key in diagnostic strings`() }
        runIsolated { s -> s.`capability persistence preserves unknown unsupported and freshness`() }
        runIsolated { s -> s.`HTTP failure classification is structural and does not disclose response contents`() }
        runIsolated { s -> s.`decisions endpoints use the actual protocol and never become chat paths`() }
        runIsolated { s -> s.`old capability documents remain readable while new decision evidence expires after one day`() }
    }
}

private class AgentRoleRoutingCases {
    private val now = 1_000_000L
    private val planner = AgentModelSource(1, "https://planner.example/v1", "key", "planner")
    private val eye = AgentModelSource(2, "https://eye.example/v1", "key", "eye")
    private val judge = AgentModelSource(3, "https://judge.example/v1", "key", "jev", AgentSourceProtocol.DECISIONS)
    private val sources = listOf(planner, eye, judge)
    private val proof = mapOf(planner.fingerprint to AgentModelCapabilities(true, false, now, "tool round trip"),
        eye.fingerprint to AgentModelCapabilities(false, true, now, "image challenge"),
        judge.fingerprint to AgentModelCapabilities(false, true, now, "typed image challenge", decisions = true,
            decisionFormats = setOf("choice")))

    fun `fixed text planner receives eyes from authorized ordinary or decision models`() {
        val router = AgentSourceRouter(sources, proof)
        val route = AgentRoutePolicy(setOf(1, 2, 3), fixedIndex = 1)
        router.acquire(AgentModelRole.PLANNER, route, now)!!.use { assertEquals(1, it.source.index) }
        router.acquire(AgentModelRole.VISION, route, now, preferredFingerprint = judge.fingerprint)!!.use {
            assertEquals(3, it.source.index)
        }
        router.acquire(AgentModelRole.VISION, route, now, preferredFingerprint = eye.fingerprint)!!.use {
            assertEquals(2, it.source.index)
        }
        assertNull(router.acquire(AgentModelRole.VISION, AgentRoutePolicy(setOf(1), 1), now))
        assertNull(router.acquire(AgentModelRole.PLANNER, AgentRoutePolicy(setOf(3), 3), now))
        router.acquire(AgentModelRole.DECISION, route, now)!!.use { assertEquals(3, it.source.index) }
    }

    fun `shared health survives new task routers without cooling unrelated roles`() {
        val health = AgentHealthRegistry()
        val route = AgentRoutePolicy(setOf(3))
        AgentSourceRouter(sources, proof, health).acquire(AgentModelRole.VISION, route, now)!!
            .fail(now, 60_000, AgentModelException(AgentModelException.Reason.NETWORK))
        val next = AgentSourceRouter(sources, proof, health)
        assertNull(next.acquire(AgentModelRole.VISION, route, now + 59_999))
        next.acquire(AgentModelRole.DECISION, route, now)!!.use { assertEquals(3, it.source.index) }
        next.acquire(AgentModelRole.VISION, route, now + 60_000)!!.close()
        val changed = judge.copy(apiKey = "new-key")
        assertNull(AgentSourceRouter(listOf(changed), proof, health).acquire(AgentModelRole.DECISION, route, now))
    }

    fun `local cancellation and budget failures do not punish source health`() {
        val route = AgentRoutePolicy(setOf(1), 1)
        val router = AgentSourceRouter(sources, proof)
        for (reason in listOf(AgentModelException.Reason.CANCELLED, AgentModelException.Reason.INVALID_REQUEST,
            AgentModelException.Reason.TOO_LARGE)) {
            router.acquire(AgentModelRole.PLANNER, route, now)!!.fail(now, error = AgentModelException(reason))
            assertNotNull(router.acquire(AgentModelRole.PLANNER, route, now)?.also { it.close() })
        }
    }

    fun `planning stays on a successful source and explicit fallback remains bounded`() {
        val another = eye.copy(model = "tool-helper")
        val capabilities = proof + (another.fingerprint to AgentModelCapabilities(true, true, now, "verified"))
        val router = AgentSourceRouter(listOf(planner, another), capabilities)
        val route = AgentRoutePolicy(setOf(1, 2))
        router.acquire(AgentModelRole.PLANNER, route, now)!!.also { assertEquals(1, it.source.index) }.succeed(20_000)
        router.acquire(AgentModelRole.PLANNER, route, now)!!.use { assertEquals(1, it.source.index) }
        router.acquire(AgentModelRole.PLANNER, route.copy(fixedIndex = 1), now)!!.fail(now)
        assertNull(router.acquire(AgentModelRole.PLANNER, route.copy(fixedIndex = 1), now))
        router.acquire(AgentModelRole.PLANNER, route.copy(fixedIndex = 1, allowFallback = true), now)!!.use {
            assertEquals(2, it.source.index)
        }
    }

    fun `role health storage stays bounded across repeated configuration changes`() {
        val health = AgentHealthRegistry()
        repeat(300) { index ->
            val source = planner.copy(apiKey = "key-$index")
            val capabilities = mapOf(source.fingerprint to AgentModelCapabilities(true, false, now, "probe"))
            AgentSourceRouter(listOf(source), capabilities, health).acquire(AgentModelRole.PLANNER,
                AgentRoutePolicy(setOf(1)), now)!!.succeed(100)
        }
        assertTrue(health.size() <= AgentHealthRegistry.MAX_RECORDS)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentRoleRoutingCases) -> Unit) {
            val s = AgentRoleRoutingCases()
            block(s)
        }
        runIsolated { s -> s.`fixed text planner receives eyes from authorized ordinary or decision models`() }
        runIsolated { s -> s.`shared health survives new task routers without cooling unrelated roles`() }
        runIsolated { s -> s.`local cancellation and budget failures do not punish source health`() }
        runIsolated { s -> s.`planning stays on a successful source and explicit fallback remains bounded`() }
        runIsolated { s -> s.`role health storage stays bounded across repeated configuration changes`() }
    }
}

private class AgentSourceRouterCases {
    private val sources = (1..8).map { AgentModelSource(it, "https://source$it.example/v1", "key$it", "model") }
    private val now = 1_000_000L
    private val all = AgentRoutePolicy((1..8).toSet())
    private fun router(vision: Set<Int> = emptySet()) = AgentSourceRouter(sources,
        sources.associate { it.fingerprint to AgentModelCapabilities(true, it.index in vision, now, "probe") })

    fun `eight simultaneous requests spread across all eight verified sources`() {
        val router = router()
        val leases = (1..8).map { router.acquire(true, false, all, now)!! }
        assertEquals((1..8).toList(), leases.map { it.source.index })
        leases.forEach { it.close(); it.close() }
        val next = router.acquire(true, false, all, now)!!
        assertEquals(1, next.source.index)
        next.close()
    }

    fun `fixed route never sends content to another provider without explicit fallback`() {
        val router = router()
        val fixed = AgentRoutePolicy(setOf(1, 2), fixedIndex = 1)
        val first = router.acquire(true, false, fixed, now)!!
        assertEquals(1, first.source.index)
        first.fail(now)
        assertNull(router.acquire(true, false, fixed, now))
        val fallback = router.acquire(true, false, fixed.copy(allowFallback = true), now)!!
        assertEquals(2, fallback.source.index)
        fallback.close()
        assertNull(router.acquire(true, false, AgentRoutePolicy(setOf(1), 1, true), now))
    }

    fun `task scope snapshots cannot grow through a mutable caller set`() {
        val allowed = mutableSetOf(1)
        val policy = AgentRoutePolicy(allowed)
        allowed.add(8)
        assertNull(router(setOf(8)).acquire(true, true, policy, now))
    }

    fun `vision routing needs fresh proof and respects explicit scope`() {
        val router = router(setOf(8))
        assertNull(router.acquire(true, true, AgentRoutePolicy(setOf(1, 2)), now))
        val lease = router.acquire(true, true, all, now)!!
        assertEquals(8, lease.source.index)
        lease.close()
        router.updateCapabilities(sources[7], AgentModelCapabilities(true, false, now, "uncertain"))
        assertNull(router.acquire(true, true, all, now))
        router.updateCapabilities(sources[7], AgentModelCapabilities(true, true, now, "probe"))
        assertNull(router.acquire(true, true, all, now + AgentModelCapabilities.VALID_FOR_MS + 1))
    }

    fun `vision only source can describe images but cannot act as a tool planner`() {
        val visionOnly = sources[7]
        val router = AgentSourceRouter(listOf(visionOnly), mapOf(visionOnly.fingerprint to
            AgentModelCapabilities(false, true, now, "vision verified")))
        val selected = AgentRoutePolicy(setOf(8), fixedIndex = 8)
        assertNull(router.acquire(true, true, selected, now))
        assertNull(router.acquire(true, false, selected, now))
        val helper = router.acquire(false, true, selected, now)!!
        assertEquals(8, helper.source.index)
        helper.close()
        assertNull(router.acquire(false, true, AgentRoutePolicy(setOf(1), fixedIndex = 1), now))
    }

    fun `unverified models and changed credentials never inherit old proof`() {
        val source = sources.first()
        val changed = source.copy(apiKey = "changed-key")
        val router = AgentSourceRouter(listOf(changed), mapOf(source.fingerprint to AgentModelCapabilities(true, true, now, "old")))
        assertNull(router.acquire(true, false, all, now))
        assertNull(router.acquire(false, true, all, now))
        assertNull(AgentSourceRouter(sources).acquire(true, false, all, now))
    }

    fun `cooldown and exclusion prevent repeating a failed model request`() {
        val router = router()
        val lease = router.acquire(true, false, all, now)!!
        lease.fail(now, 60_000)
        lease.fail(now, 300_000) // 重复回调不得扩大冷却或减成负的在途数。
        lease.close()
        assertNull(router.acquire(true, false, AgentRoutePolicy(setOf(1)), now + 59_999))
        assertNotNull(router.acquire(true, false, AgentRoutePolicy(setOf(1)), now + 60_000)?.also { it.close() })
        val next = router.acquire(true, false, all, now + 60_000, setOf(sources[0].fingerprint))!!
        assertEquals(2, next.source.index)
        next.close()
    }

    fun `latency and in flight load affect selection`() {
        val router = router()
        router.acquire(true, false, AgentRoutePolicy(setOf(1)), now)!!.succeed(20_000)
        router.acquire(true, false, AgentRoutePolicy(setOf(2)), now)!!.succeed(100)
        val fast = router.acquire(true, false, AgentRoutePolicy(setOf(1, 2)), now)!!
        assertEquals(2, fast.source.index)
        fast.close()
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentSourceRouterCases) -> Unit) {
            val s = AgentSourceRouterCases()
            block(s)
        }
        runIsolated { s -> s.`eight simultaneous requests spread across all eight verified sources`() }
        runIsolated { s -> s.`fixed route never sends content to another provider without explicit fallback`() }
        runIsolated { s -> s.`task scope snapshots cannot grow through a mutable caller set`() }
        runIsolated { s -> s.`vision routing needs fresh proof and respects explicit scope`() }
        runIsolated { s -> s.`vision only source can describe images but cannot act as a tool planner`() }
        runIsolated { s -> s.`unverified models and changed credentials never inherit old proof`() }
        runIsolated { s -> s.`cooldown and exclusion prevent repeating a failed model request`() }
        runIsolated { s -> s.`latency and in flight load affect selection`() }
    }
}

private class AgentStructuredOutputCases {
    private val source = AgentModelSource(1, "https://example.test/v1", "secret", "model")
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "材料\n任务"))
    private val schema = JSONObject().put("name", "answer").put("strict", true).put("schema", JSONObject()
        .put("type", "object").put("additionalProperties", false).put("required", JSONArray().put("answer"))
        .put("properties", JSONObject().put("answer", JSONObject().put("type", "string"))))
    private fun response() = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", "{\"answer\":\"ok\"}")))).toString()

    fun `schema is carried by sampling parameters without repeated prompt boilerplate`() {
        var request = JSONObject()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ -> request = JSONObject(bytes.toString(Charsets.UTF_8)); response() })
        client.generateStructured(source, messages, schema, 5000) { false }
        assertEquals("json_schema", request.getJSONObject("response_format").getString("type"))
        assertEquals("材料\n任务", request.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(request.has("tools"))
    }
    fun `explicit schema rejection falls back once to JSON mode and remembers it`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            requests += JSONObject(bytes.toString(Charsets.UTF_8))
            if (requests.size == 1) throw AgentModelException(AgentModelException.Reason.RESPONSE_FORMAT_PARAMETER, 400)
            response()
        })
        client.generateStructured(source, messages, schema, 5000) { false }
        client.generateStructured(source, messages, schema, 5000) { false }
        assertEquals(listOf("json_schema", "json_object", "json_object"), requests.map { it.getJSONObject("response_format").getString("type") })
        assertTrue(requests[1].getJSONArray("messages").getJSONObject(0).getString("content").contains("JSON schema："))
        assertFalse(messages.getJSONObject(0).getString("content").contains("schema"))
    }
    fun `unknown HTTP errors never trigger format retries`() {
        var count = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> count++; throw AgentModelException(AgentModelException.Reason.HTTP, 400) })
        assertTrue(runCatching { client.generateStructured(source, messages, schema, 5000) { false } }.isFailure)
        assertEquals(1, count)
    }
    fun `JSON mode rejection stops without silently dropping the output contract`() {
        var count = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> count++; throw AgentModelException(AgentModelException.Reason.RESPONSE_FORMAT_PARAMETER, 400) })
        assertTrue(runCatching { client.generateStructured(source, messages, schema, 5000) { false } }.isFailure)
        assertEquals(2, count)
    }
    fun `only a structured unsupported format error enables compatibility fallback`() {
        assertEquals(AgentModelException.Reason.RESPONSE_FORMAT_PARAMETER, AgentHttpsTransport.httpFailure(400,
            """{"error":{"code":"unsupported_value","param":"response_format.type"}}""", null).reason)
        for (payload in listOf("""{"error":{"message":"response_format unsupported"}}""",
            """{"error":{"code":"invalid_request_error","param":"response_format.json_schema.schema"}}""")) {
            assertEquals(AgentModelException.Reason.HTTP, AgentHttpsTransport.httpFailure(400, payload, null).reason)
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentStructuredOutputCases) -> Unit) {
            val s = AgentStructuredOutputCases()
            block(s)
        }
        runIsolated { s -> s.`schema is carried by sampling parameters without repeated prompt boilerplate`() }
        runIsolated { s -> s.`explicit schema rejection falls back once to JSON mode and remembers it`() }
        runIsolated { s -> s.`unknown HTTP errors never trigger format retries`() }
        runIsolated { s -> s.`JSON mode rejection stops without silently dropping the output contract`() }
        runIsolated { s -> s.`only a structured unsupported format error enables compatibility fallback`() }
    }
}
