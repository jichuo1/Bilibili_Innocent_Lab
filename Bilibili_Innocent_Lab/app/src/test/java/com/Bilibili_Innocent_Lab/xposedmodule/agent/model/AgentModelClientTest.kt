package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentModelClientTest {
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

    @Test fun `verified River endpoint disables thinking without guessing for lookalike hosts`() {
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

    @Test fun `explicit large input budget transmits complete text without relaxing output or default clients`() {
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

    @Test fun `plain text JSON never becomes an executable call`() {
        val client = AgentModelClient()
        val turn = client.decode(response("{\"name\":\"search_videos\",\"arguments\":{}}"), setOf("search_videos"))
        assertTrue(turn.toolCalls.isEmpty())
    }

    @Test fun `typed text response content remains text and rejects non text parts`() {
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

    @Test fun `switching planners strips source specific reasoning without changing tool pairing or original history`() {
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

    @Test fun `one shared client learns compatibility across tasks but changed credentials or TTL require fresh attempts`() {
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

    @Test fun `decision protocol never enters chat transport`() {
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> fail("must not use chat protocol"); response() })
        failure(AgentModelException.Reason.INVALID_REQUEST) {
            client.generate(source.copy(protocol = AgentSourceProtocol.DECISIONS), user, tools, false, 5000) { false }
        }
    }

    @Test fun `nullable compatibility fields remain non executable`() {
        val client = AgentModelClient()
        for (toolCalls in listOf(JSONObject.NULL, JSONArray())) {
            val root = JSONObject(response())
            root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .put("tool_calls", toolCalls).put("function_call", JSONObject.NULL).put("reasoning_content", JSONObject.NULL)
            assertTrue(client.decode(root.toString(), setOf("search_videos")).toolCalls.isEmpty())
        }
    }

    @Test fun `tool calls preserve id arguments and reasoning for next round`() {
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

    @Test fun `unknown tools duplicate ids and reused history ids are rejected`() {
        val client = AgentModelClient()
        failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(response(null, calls(name = "shell")), setOf("search_videos")) }
        val duplicates = calls().put(calls().getJSONObject(0))
        failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(response(null, duplicates), setOf("search_videos")) }
        failure(AgentModelException.Reason.INVALID_RESPONSE) { client.decode(response(null, calls()), setOf("search_videos"), setOf("call_1")) }
    }

    @Test fun `invalid JSON dialect duplicate keys suffix and deep arguments cannot become calls`() {
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

    @Test fun `truncated response is never partially executed`() {
        failure(AgentModelException.Reason.OUTPUT_LIMIT) {
            AgentModelClient().decode(response(null, calls(), "length"), setOf("search_videos"))
        }
    }

    @Test fun `budget parameter fallback retains budget and is learned per credential fingerprint`() {
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

    @Test fun `network failures are not automatically retried`() {
        var attempts = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ ->
            attempts++; throw AgentModelException(AgentModelException.Reason.NETWORK)
        })
        failure(AgentModelException.Reason.NETWORK) { client.generate(source, user, tools, false, 5000) { false } }
        assertEquals(1, attempts)
    }

    @Test fun `cancellation before or during request prevents response execution`() {
        var attempts = 0
        var cancelled = true
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> attempts++; cancelled = true; response(null, calls()) })
        failure(AgentModelException.Reason.CANCELLED) { client.generate(source, user, tools, false, 5000) { cancelled } }
        assertEquals(0, attempts)
        cancelled = false
        failure(AgentModelException.Reason.CANCELLED) { client.generate(source, user, tools, false, 5000) { cancelled } }
        assertEquals(1, attempts)
    }

    @Test fun `images require current proof and cannot hide in text-only history`() {
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

    @Test fun `unanswered or forged tool history is rejected before transport`() {
        var attempts = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> attempts++; response() })
        val turn = client.decode(response(null, calls()), setOf("search_videos"))
        user.put(turn.message)
        failure(AgentModelException.Reason.INVALID_REQUEST) { client.generate(source, user, tools, false, 5000) { false } }
        user.put(JSONObject().put("role", "tool").put("tool_call_id", "forged").put("content", "ok"))
        failure(AgentModelException.Reason.INVALID_REQUEST) { client.generate(source, user, tools, false, 5000) { false } }
        assertEquals(0, attempts)
    }

    @Test fun `response and request size bounds reject oversized work`() {
        failure(AgentModelException.Reason.TOO_LARGE) {
            AgentModelClient().decode(" ".repeat(AgentHttpsTransport.MAX_RESPONSE_BYTES + 1), emptySet())
        }
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> fail("must not transmit"); response() })
        user.getJSONObject(0).put("content", "x".repeat(65_537))
        failure(AgentModelException.Reason.INVALID_REQUEST) { client.generate(source, user, tools, false, 5000) { false } }
    }
}
