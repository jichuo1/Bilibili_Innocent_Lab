package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentToolCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentTextPlannerTest {
    private val source = AgentModelSource(1, "https://example.test", "test-key", "text-only")
    private val proof = AgentModelCapabilities(false, false, 10_000, "plain loop", plainPlanning = true)
    private fun response(text: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", text)))).toString()
    private fun history() = JSONArray().put(JSONObject().put("role", "system").put("content", AgentToolCatalog.SYSTEM))
        .put(JSONObject().put("role", "user").put("content", "搜索官方演示"))
    private fun invalid(text: String) {
        try { AgentTextPlanner.decode(text, false); fail("must not execute: $text") }
        catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.INVALID_RESPONSE, error.reason) }
    }

    fun ordinaryTextPacketsProduceExactlyOneValidatedActionOrAnswer() {
        val turn = AgentTextPlanner.decode("```json\n{\"action\":\"get_ui_state\",\"arguments\":{}}\n```", false)
        assertEquals("get_ui_state", turn.toolCalls.single().name)
        assertTrue(turn.toolCalls.single().id.startsWith("text_"))
        assertEquals("已核对结果", AgentTextPlanner.decode("{\"answer\":\"已核对结果\"}", false).text)
        assertEquals("12", AgentTextPlanner.decode("```JSON\r\n{\"action\":\"get_ui_state\",\"arguments\":{\"offset\":12}}\r\n```", false)
            .toolCalls.single().arguments.getString("offset"))
        invalid("{\"action\":\"get_ui_state\",\"arguments\":{\"offset\":12.5}}")
        invalid("{\"action\":\"get_ui_state\",\"arguments\":{\"offset\":1000}}")
    }
    fun proseDuplicatesMultipleActionsAndUnknownParametersNeverBecomeCommands() {
        invalid("我建议点击按钮 {\"action\":\"get_ui_state\",\"arguments\":{}}")
        invalid("{\"action\":\"get_ui_state\",\"action\":\"press_back\",\"arguments\":{}}")
        invalid("{\"actions\":[{\"action\":\"get_ui_state\"}]}")
        invalid("{\"action\":\"shell\",\"arguments\":{\"command\":\"whoami\"}}")
        invalid("{\"action\":\"get_ui_state\",\"arguments\":{\"url\":\"https://example.test\"}}")
        invalid("{\"action\":\"inspect_screen\",\"arguments\":{}}")
    }
    fun requestsHaveNoNativeToolsAndReturnExecutionFactsAsOrdinaryUserMessages() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            assertFalse(body.has("tools")); assertFalse(body.has("tool_choice")); assertFalse(body.has("response_format"))
            for (i in 0 until body.getJSONArray("messages").length()) {
                val message = body.getJSONArray("messages").getJSONObject(i)
                assertNotEquals("tool", message.getString("role")); assertFalse(message.has("tool_calls"))
            }
            response(if (requests.size == 1) "{\"action\":\"get_ui_state\",\"arguments\":{}}" else "{\"answer\":\"读取到真实界面\"}")
        }, clock = { 10_000 })
        val planner = AgentTextPlanner(client) { 10_000 }
        val first = planner.generate(source, proof, history(), false, 5000) { false }
        val following = history().put(first.message).put(JSONObject().put("role", "tool").put("tool_call_id", first.toolCalls.single().id)
            .put("content", "{\"ok\":true,\"data\":{\"label\":\"REAL_OBSERVATION\"}}"))
        assertEquals("读取到真实界面", planner.generate(source, proof, following, false, 5000) { false }.text)
        assertTrue(requests.last().getJSONArray("messages").toString().contains("REAL_OBSERVATION"))
    }
    fun formatRepairIsBoundedAndDoesNotExecuteTheInvalidFirstResponse() {
        var count = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ ->
            count++; response(if (count == 1) "点击全部按钮" else "{\"action\":\"get_host_state\",\"arguments\":{}}")
        })
        assertEquals("get_host_state", AgentTextPlanner(client) { 10_000 }.generate(source, proof, history(), false, 5000) { false }.toolCalls.single().name)
        assertEquals(2, count)
    }
    fun noncompliantSecondReplyStopsWithoutInventingAnAction() {
        var count = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> count++; response("我会操作") })
        try { AgentTextPlanner(client) { 10_000 }.generate(source, proof, history(), false, 5000) { false }; fail("invalid expected") }
        catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.INVALID_RESPONSE, error.reason) }
        assertEquals(2, count)
    }
    fun oldOrExpiredProofCannotGrantTextPlanning() {
        val old = proof.toJson().apply { remove("plainPlanning"); remove("plainState") }
        assertFalse(AgentModelCapabilities.fromJson(old)!!.plainPlanning)
        assertEquals(proof, AgentModelCapabilities.fromJson(proof.toJson()))
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> fail("no network"); response("") })
        try { AgentTextPlanner(client) { 10_000 + AgentModelCapabilities.VALID_FOR_MS + 1 }.generate(source, proof, history(), false, 1000) { false }; fail("expired") }
        catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.PLAIN_UNVERIFIED, error.reason) }
    }
    fun realTextEchoReceiptProvesRoundTripAndEnablesPlannerRouting() {
        var count = 0
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (body.has("tools")) throw AgentModelException(AgentModelException.Reason.TOOLS_UNSUPPORTED)
            count++
            val messages = body.getJSONArray("messages")
            val last = messages.getJSONObject(messages.length() - 1).getString("content")
            if (count == 1) {
                val nonce = Regex("nonce '([^']+)'").find(last)!!.groupValues[1]
                response(JSONObject().put("action", "capability_echo").put("arguments", JSONObject().put("nonce", nonce)).toString())
            } else {
                val receipt = JSONObject(last.substring(last.indexOf('{'))).getString("receipt")
                response(JSONObject().put("answer", receipt).toString())
            }
        }, clock = { 10_000 })
        val actual = AgentCapabilityProbe(client, clock = { 10_000 }).probe(source)
        assertFalse(actual.tools); assertTrue(actual.plainPlanning); assertEquals(2, count)
        val router = AgentSourceRouter(listOf(source), mapOf(source.fingerprint to actual))
        router.acquire(AgentModelRole.PLANNER, AgentRoutePolicy(setOf(1)), 10_000)!!.use { assertEquals(source, it.source) }
        assertNull(router.acquire(true, false, AgentRoutePolicy(setOf(1)), 10_000))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentTextPlannerTest) -> Unit) {
            val s = AgentTextPlannerTest()
            block(s)
        }
        runIsolated { s -> s.ordinaryTextPacketsProduceExactlyOneValidatedActionOrAnswer() }
        runIsolated { s -> s.proseDuplicatesMultipleActionsAndUnknownParametersNeverBecomeCommands() }
        runIsolated { s -> s.requestsHaveNoNativeToolsAndReturnExecutionFactsAsOrdinaryUserMessages() }
        runIsolated { s -> s.formatRepairIsBoundedAndDoesNotExecuteTheInvalidFirstResponse() }
        runIsolated { s -> s.noncompliantSecondReplyStopsWithoutInventingAnAction() }
        runIsolated { s -> s.oldOrExpiredProofCannotGrantTextPlanning() }
        runIsolated { s -> s.realTextEchoReceiptProvesRoundTripAndEnablesPlannerRouting() }
    }
}
