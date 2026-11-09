package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentStructuredOutputTest {
    private val source = AgentModelSource(1, "https://example.test/v1", "secret", "model")
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "材料\n任务"))
    private val schema = JSONObject().put("name", "answer").put("strict", true).put("schema", JSONObject()
        .put("type", "object").put("additionalProperties", false).put("required", JSONArray().put("answer"))
        .put("properties", JSONObject().put("answer", JSONObject().put("type", "string"))))
    private fun response() = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", "{\"answer\":\"ok\"}")))).toString()

    @Test fun `schema is carried by sampling parameters without repeated prompt boilerplate`() {
        var request = JSONObject()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ -> request = JSONObject(bytes.toString(Charsets.UTF_8)); response() })
        client.generateStructured(source, messages, schema, 5000) { false }
        assertEquals("json_schema", request.getJSONObject("response_format").getString("type"))
        assertEquals("材料\n任务", request.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(request.has("tools"))
    }
    @Test fun `explicit schema rejection falls back once to JSON mode and remembers it`() {
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
    @Test fun `unknown HTTP errors never trigger format retries`() {
        var count = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> count++; throw AgentModelException(AgentModelException.Reason.HTTP, 400) })
        assertTrue(runCatching { client.generateStructured(source, messages, schema, 5000) { false } }.isFailure)
        assertEquals(1, count)
    }
    @Test fun `JSON mode rejection stops without silently dropping the output contract`() {
        var count = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> count++; throw AgentModelException(AgentModelException.Reason.RESPONSE_FORMAT_PARAMETER, 400) })
        assertTrue(runCatching { client.generateStructured(source, messages, schema, 5000) { false } }.isFailure)
        assertEquals(2, count)
    }
    @Test fun `only a structured unsupported format error enables compatibility fallback`() {
        assertEquals(AgentModelException.Reason.RESPONSE_FORMAT_PARAMETER, AgentHttpsTransport.httpFailure(400,
            """{"error":{"code":"unsupported_value","param":"response_format.type"}}""", null).reason)
        for (payload in listOf("""{"error":{"message":"response_format unsupported"}}""",
            """{"error":{"code":"invalid_request_error","param":"response_format.json_schema.schema"}}""")) {
            assertEquals(AgentModelException.Reason.HTTP, AgentHttpsTransport.httpFailure(400, payload, null).reason)
        }
    }
}
