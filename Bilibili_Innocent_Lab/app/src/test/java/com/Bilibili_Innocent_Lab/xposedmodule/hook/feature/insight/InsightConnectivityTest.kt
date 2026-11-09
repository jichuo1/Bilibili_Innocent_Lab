package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentHttpTransport
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelException
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentSourceProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightConnectivityTest {
    private val chat = AgentModelSource(1, "https://example.test/v1", "secret", "model")
    private val anthropic = AgentModelSource(1, "https://example.test", "secret", "claude",
        AgentSourceProtocol.ANTHROPIC)

    private fun okResponse(): String = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", "{\"ok\":true}")))).toString()

    @Test fun `successful probe reports ok and uses the real structured streaming path`() {
        val bodies = mutableListOf<JSONObject>()
        val transport = AgentHttpTransport { _, bytes, _, _ ->
            bodies += JSONObject(String(bytes, Charsets.UTF_8)); okResponse()
        }
        val result = InsightConnectivity.probe(chat, transport)
        assertTrue(result.ok)
        assertEquals("ok", result.outcome)
        assertEquals("OpenAI", result.variant)
        val body = bodies.single()
        assertTrue("must negotiate streaming like a real AI Insight request", body.optBoolean("stream"))
        assertTrue("must carry json_schema like a real AI Insight request", body.has("response_format"))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertFalse("AI Insight never injects reasoning controls", body.has("reasoning"))
        assertFalse(body.has("thinking"))
    }

    @Test fun `anthropic probe keeps schema in system and reports the anthropic variant`() {
        val bodies = mutableListOf<JSONObject>()
        val transport = AgentHttpTransport { _, bytes, _, _ ->
            bodies += JSONObject(String(bytes, Charsets.UTF_8)); okResponse()
        }
        val result = InsightConnectivity.probe(anthropic, transport)
        assertTrue(result.ok)
        assertEquals("Anthropic", result.variant)
        val body = bodies.single()
        assertFalse("Anthropic Messages has no response_format", body.has("response_format"))
        assertTrue(body.getString("system").contains("schema"))
    }

    @Test fun `http failures are classified as http status outcomes`() {
        val transport = AgentHttpTransport { _, _, _, _ ->
            throw AgentModelException(AgentModelException.Reason.HTTP, 401)
        }
        val result = InsightConnectivity.probe(chat, transport)
        assertFalse(result.ok)
        assertEquals("http-401", result.outcome)
    }

    @Test fun `network timeout and malformed replies are classified`() {
        fun outcome(reason: AgentModelException.Reason): String = InsightConnectivity.probe(
            chat, AgentHttpTransport { _, _, _, _ -> throw AgentModelException(reason) }).outcome
        assertEquals("network", outcome(AgentModelException.Reason.NETWORK))
        assertEquals("deadline", outcome(AgentModelException.Reason.TIMEOUT))
        assertEquals("parse", outcome(AgentModelException.Reason.INVALID_RESPONSE))
    }

    @Test fun `unexpected exception is reported as a network failure`() {
        val result = InsightConnectivity.probe(chat, AgentHttpTransport { _, _, _, _ -> error("boom") })
        assertFalse(result.ok)
        assertEquals("network", result.outcome)
    }
}
