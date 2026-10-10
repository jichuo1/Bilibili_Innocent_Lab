package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelTurn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentConversationTest {
    @Test fun oldUiNodesAreCompactedWhileTheNewestSnapshotAndCallPairRemain() {
        val history = AgentConversation("system", "goal")
        fun response(id: String) = JSONObject().put("ok", true).put("data", JSONObject().put("backend", "accessibility")
            .put("snapshot_id", id).put("complete", true).put("nodes", JSONArray((0..63).map { index ->
                JSONObject().put("node_id", "0.$index").put("label", "真实页面文字$id-$index".repeat(4)).put("bounds", JSONArray(listOf(1,2,3,4)))
            })))
        history.append(turn(1, "get_ui_state", JSONObject()), response("old"))
        history.append(turn(2, "get_ui_state", JSONObject()), response("new"))
        val messages = history.messages()
        val previous = JSONObject(messages.getJSONObject(3).getString("content")).getJSONObject("data")
        val newest = JSONObject(messages.getJSONObject(5).getString("content")).getJSONObject("data")
        assertTrue(previous.getBoolean("historical"))
        assertFalse(previous.has("snapshot_id")); assertFalse(previous.has("nodes")); assertFalse(previous.has("bounds"))
        assertEquals(64, previous.getJSONArray("visible_labels").length())
        assertEquals("真实页面文字old-63".repeat(4), previous.getJSONArray("visible_labels").getString(63))
        assertEquals("new", newest.getString("snapshot_id")); assertEquals(64, newest.getJSONArray("nodes").length())
        assertTrue(messages.toString().length < response("old").toString().length + response("new").toString().length)
    }
    @Test fun currentUiKeepsLaterControlsWithoutImagesAndWithinTheResultBudget() {
        val conversation = AgentConversation("system", "click control")
        val nodes = JSONArray((0 until 64).map { index -> JSONObject().put("node_id", "0.$index")
            .put("label", "\\\"".repeat(40)).put("clickable", true).put("bounds", JSONArray(listOf(1, 2, 300, 400))) })
        conversation.append(turn(1, "get_ui_state", JSONObject()), JSONObject().put("ok", true).put("data", JSONObject()
            .put("snapshot_id", "12345678-1234-1234-1234-123456789abc").put("nodes", nodes).put("image_data_url", "data:image/jpeg;base64,secret")))
        val tool = conversation.messages().getJSONObject(3).getString("content")
        assertTrue(tool.contains("0.63"))
        assertFalse(tool.contains("image_data_url"))
        assertTrue(tool.length <= AgentConversation.MAX_RESULT_CHARS)
    }
    private fun turn(index: Int, reasoning: String = ""): AgentModelTurn {
        val arguments = JSONObject().put("query", "悟空 $index")
        return turn(index, "search_videos", arguments, reasoning)
    }

    private fun turn(index: Int, name: String, arguments: JSONObject, reasoning: String = ""): AgentModelTurn {
        val calls = JSONArray().put(JSONObject().put("id", "call_$index").put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments.toString())))
        val message = JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls)
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning)
        return AgentModelTurn(message, listOf(AgentModelToolCall("call_$index", name, arguments)), "")
    }

    private fun result(index: Int) = JSONObject().put("ok", true).put("data", JSONObject()
        .put("videos", JSONArray().put(JSONObject().put("video_id", "av$index").put("title", "demo $index")
            .put("official_source", "unknown"))).put("observed_at_elapsed", index))

    @Test fun `hundreds of rounds retain goal and complete tool pairs with bounded history`() {
        val conversation = AgentConversation("system rules", "find official demonstrations")
        repeat(400) { conversation.append(turn(it + 1), result(it + 1), "source1") }
        val messages = conversation.forSource("source1")
        assertEquals("system rules", messages.getJSONObject(0).getString("content"))
        assertEquals("find official demonstrations", messages.getJSONObject(1).getString("content"))
        assertTrue(messages.length() <= 3 + AgentConversation.MAX_ROUNDS * 2)
        assertTrue(messages.toString().length <= AgentConversation.MAX_CONTEXT_CHARS)
        val history = messages.getJSONObject(2).getString("content")
        assertTrue(history.contains("历史数据"))
        assertTrue(history.contains("unknown"))
        assertTrue(history.contains("observed_at_elapsed"))
        for (index in 3 until messages.length() step 2) {
            val assistant = messages.getJSONObject(index)
            val tool = messages.getJSONObject(index + 1)
            assertEquals("assistant", assistant.getString("role"))
            assertEquals("tool", tool.getString("role"))
            assertEquals(assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"), tool.getString("tool_call_id"))
        }
    }

    @Test fun `source specific reasoning is not forwarded to another model`() {
        val conversation = AgentConversation("system", "goal")
        conversation.append(turn(1, "provider private reasoning"), result(1), "source1")
        assertTrue(conversation.forSource("source1").getJSONObject(2).has("reasoning_content"))
        assertFalse(conversation.forSource("source2").getJSONObject(2).has("reasoning_content"))
        assertFalse(conversation.messages().toString().contains("provider private reasoning"))
    }

    @Test fun `images never enter current or archived planner history`() {
        val conversation = AgentConversation("system", "goal")
        repeat(30) { index -> conversation.append(turn(index), JSONObject().put("ok", true)
            .put("data", JSONObject().put("image_data_url", "data:image/jpeg;base64,raw")
                .put("nested", JSONArray().put("data:image/png;base64,raw"))), "source1") }
        assertFalse(conversation.messages().toString().contains("data:image/"))
        assertFalse(conversation.messages().toString().contains("image_data_url"))
    }

    @Test fun `reasoning and large results remain bounded even for the original model`() {
        val conversation = AgentConversation("system", "goal")
        repeat(40) { index -> conversation.append(turn(index, "r".repeat(16_384)),
            JSONObject().put("ok", true).put("data", JSONObject().put("description", "d".repeat(60_000))), "source1") }
        assertTrue(conversation.forSource("source1").toString().length <= AgentConversation.MAX_CONTEXT_CHARS)
    }

    @Test fun `recent call id replay prevention is bounded and cleared at task end`() {
        val conversation = AgentConversation("system", "goal")
        repeat(1_000) { assertTrue(conversation.acceptCallId("call_$it")) }
        assertFalse(conversation.acceptCallId("call_999"))
        assertFalse(conversation.acceptCallId("bad id"))
        assertTrue(conversation.acceptCallId("call_0"))
        conversation.clear()
        assertTrue(conversation.acceptCallId("call_999"))
        assertEquals(2, conversation.messages().length())
    }

    @Test fun `oversized single tool result keeps valid bounded JSON and recent identity`() {
        val conversation = AgentConversation("system", "goal")
        val data = JSONObject().put("video_id", "av42").put("official_source", "unknown")
        repeat(40) { data.put("extra_$it", "x".repeat(4_000)) }
        conversation.append(turn(1), JSONObject().put("ok", true).put("data", data), "source1")
        val messages = conversation.messages()
        assertEquals(4, messages.length())
        val body = messages.getJSONObject(3).getString("content")
        assertTrue(body.length <= AgentConversation.MAX_RESULT_CHARS)
        val decoded = JSONObject(body)
        assertTrue(decoded.getBoolean("truncated"))
        assertEquals("av42", decoded.getJSONObject("data").getString("video_id"))
    }

    private fun history(messages: JSONArray): JSONArray {
        val content = messages.getJSONObject(2).getString("content")
        assertTrue(content.startsWith(AgentContextBudget.HISTORY_NOTICE))
        return JSONArray(content.substring(content.indexOf('\n') + 1))
    }

    private fun page(): JSONObject = JSONObject().put("ok", true).put("data", JSONObject()
        .put("query", "黑神话悟空 官方演示").put("next_cursor", "task-bound-cursor")
        .put("source", "host_rpc").put("navigation", "requested").put("visible_results_verified", false)
        .put("observed_at_elapsed", 100L).put("cache_hit", false)
        .put("candidate_window", 256).put("cursor_window", 16)
        .put("videos", JSONArray((1..20).map { index -> JSONObject()
            .put("video_id", "av$index").put("title", "演示片段 $index").put("author", "发布者 $index")
            .put("author_uid", "$index").put("official_source", "unknown")
            .put("verification", JSONObject().put("status", "reported").put("type", 1)
                .put("description", "认证描述仅是账号资料，不是官方出处")) })))

    @Test fun `archived search keeps every candidate identity and verification without creating navigation proof`() {
        val conversation = AgentConversation("system", "goal")
        conversation.append(turn(1, "search_videos", JSONObject().put("query", "黑神话悟空 官方演示")), page(), "private-source-hash")
        repeat(6) { index -> conversation.append(turn(index + 2), result(index + 2), "private-source-hash") }
        val note = history(conversation.messages()).getJSONObject(0)
        assertTrue(note.getBoolean("historical"))
        assertTrue(note.getBoolean("compacted"))
        assertFalse(note.has("source_fingerprint"))
        assertFalse(note.has("arguments")) // 同值 query 仍完整保留在 result.data。
        val data = note.getJSONObject("result").getJSONObject("data")
        assertEquals("黑神话悟空 官方演示", data.getString("query"))
        assertEquals("task-bound-cursor", data.getString("next_cursor"))
        assertEquals("requested", data.getString("navigation"))
        assertFalse(data.getBoolean("visible_results_verified"))
        assertEquals(100L, data.getLong("observed_at_elapsed"))
        assertFalse(data.getBoolean("cache_hit"))
        assertFalse(data.has("candidate_window")); assertFalse(data.has("cursor_window"))
        val candidates = data.getJSONArray("videos")
        assertEquals(20, candidates.length())
        for (index in 0 until candidates.length()) {
            val candidate = candidates.getJSONObject(index)
            assertEquals("av${index + 1}", candidate.getString("video_id"))
            assertEquals("演示片段 ${index + 1}", candidate.getString("title"))
            assertEquals("发布者 ${index + 1}", candidate.getString("author"))
            assertEquals("${index + 1}", candidate.getString("author_uid"))
            assertEquals("unknown", candidate.getString("official_source"))
            val verification = candidate.getJSONObject("verification")
            assertEquals("reported", verification.getString("status"))
            assertEquals(1, verification.getInt("type"))
            assertEquals("认证描述仅是账号资料，不是官方出处", verification.getString("description"))
        }
    }

    @Test fun `duplicate historical facts keep latest observation without collapsing failure or uncertainty changes`() {
        val conversation = AgentConversation("system", "goal")
        repeat(20) { index ->
            val result = JSONObject().put("ok", true).put("data", JSONObject()
                .put("video_id", "av1").put("title", "演示").put("official_source", "unknown")
                .put("observed_at_elapsed", index).put("cache_hit", index % 2 == 0))
            conversation.append(turn(index, "get_video_details", JSONObject().put("video_id", "av1")), result, "source1")
        }
        var notes = history(conversation.messages())
        assertEquals(1, notes.length())
        val latest = notes.getJSONObject(0).getJSONObject("result").getJSONObject("data")
        assertEquals(13, latest.getInt("observed_at_elapsed"))
        assertFalse(latest.getBoolean("cache_hit"))
        conversation.append(turn(20, "get_video_details", JSONObject().put("video_id", "av1")),
            JSONObject().put("ok", false).put("error", "video_details_unavailable"), "source1")
        repeat(6) { index -> conversation.append(turn(index + 21), result(index + 21), "source1") }
        notes = history(conversation.messages())
        val failure = (0 until notes.length()).map { notes.getJSONObject(it) }
            .single { !it.getJSONObject("result").getBoolean("ok") }
        assertEquals("video_details_unavailable", failure.getJSONObject("result").getString("error"))
        assertEquals("av1", failure.getJSONObject("arguments").getString("video_id"))
        assertTrue((0 until notes.length()).any { notes.getJSONObject(it).getJSONObject("result").optBoolean("ok") })
    }

    @Test fun `different pagination arguments null errors and unverified assessments stay distinct`() {
        val first = page().apply {
            getJSONObject("data").put("visual_status", "unavailable").put("visual_assessment", JSONObject().put("description", "标题可见")
                .put("page_assessment", "needs_details").put("is_unverified", true))
            put("error", JSONObject.NULL)
        }
        val call = AgentModelToolCall("id", "search_videos", JSONObject().put("query", "黑神话悟空 官方演示").put("cursor", "previous-cursor"))
        val projected = AgentContextBudget.evidence(call, first)
        assertEquals("previous-cursor", projected.getJSONObject("arguments").getString("cursor"))
        assertTrue(projected.getJSONObject("result").isNull("error"))
        assertEquals("unavailable", projected.getJSONObject("result").getJSONObject("data").getString("visual_status"))
        val assessment = projected.getJSONObject("result").getJSONObject("data").getJSONObject("visual_assessment")
        assertTrue(assessment.getBoolean("is_unverified"))
        val second = JSONObject(first.toString())
        second.getJSONObject("data").getJSONObject("visual_assessment").put("is_unverified", false)
        assertNotEquals(AgentContextBudget.key(projected), AgentContextBudget.key(AgentContextBudget.evidence(call, second)))
    }

    @Test fun `soft budget keeps original goal latest pair and accepted same source reasoning whole`() {
        val system = "system rules ".repeat(1_000)
        val goal = "原始目标".repeat(400)
        val conversation = AgentConversation(system, goal)
        repeat(12) { index -> conversation.append(turn(index), result(index), "source1") }
        val last = page()
        val candidates = last.getJSONObject("data").getJSONArray("videos")
        for (index in 0 until candidates.length()) candidates.getJSONObject(index)
            .put("title", "t".repeat(300)).put("author", "a".repeat(100))
            .getJSONObject("verification").put("description", "v".repeat(300))
        last.getJSONObject("data").put("description", "d".repeat(4_000))
        val reasoning = "r".repeat(16_384)
        val latestTurn = turn(100, "search_videos", JSONObject().put("query", "黑神话悟空 官方演示"), reasoning)
        conversation.append(latestTurn, last, "source1")
        val messages = conversation.forSource("source1")
        assertEquals(system, messages.getJSONObject(0).getString("content"))
        assertEquals(goal, messages.getJSONObject(1).getString("content"))
        assertEquals(4, messages.length())
        assertEquals(latestTurn.message.toString(), messages.getJSONObject(2).toString())
        assertEquals("call_100", messages.getJSONObject(3).getString("tool_call_id"))
        assertEquals(reasoning, messages.getJSONObject(2).getString("reasoning_content"))
        val data = JSONObject(messages.getJSONObject(3).getString("content")).getJSONObject("data")
        assertEquals(20, data.getJSONArray("videos").length())
        assertEquals("unknown", data.getJSONArray("videos").getJSONObject(19).getString("official_source"))
        assertEquals("task-bound-cursor", data.getString("next_cursor"))
        assertTrue(messages.toString().length > AgentContextBudget.TARGET_CHARS)
        assertTrue(messages.toString().length <= AgentConversation.MAX_CONTEXT_CHARS)
        assertFalse(conversation.forSource("source2").getJSONObject(2).has("reasoning_content"))
    }

    @Test fun `original overlarge reasoning policy remains unchanged and normal long tasks fit the soft target`() {
        val conversation = AgentConversation("system", "goal")
        conversation.append(turn(1, "r".repeat(16_385)), result(1), "source1")
        assertFalse(conversation.forSource("source1").getJSONObject(2).has("reasoning_content"))
        repeat(1_000) { index ->
            conversation.append(turn(index + 2, "r".repeat(1_000)), page().apply {
                getJSONObject("data").put("query", "query $index").put("observed_at_elapsed", index)
            }, "source1")
            val messages = conversation.forSource("source1")
            assertTrue(messages.toString().length <= AgentContextBudget.TARGET_CHARS)
            assertEquals("call_${index + 2}", messages.getJSONObject(messages.length() - 1).getString("tool_call_id"))
        }
    }

    @Test fun `project bounds encoded hostile text while keeping all identities failure and confidence fields`() {
        val input = page()
        val text = "\"\\\n\t\u0001".repeat(10_000)
        val data = input.getJSONObject("data")
        data.put("description", text).put("visual_status", "unavailable")
            .put("visual_assessment", JSONObject().put("description", text).put("page_assessment", "needs_details")
                .put("is_unverified", true).put("source_index", 2))
        val videos = data.getJSONArray("videos")
        for (index in 0 until videos.length()) videos.getJSONObject(index)
            .put("title", text).put("author", text)
            .getJSONObject("verification").put("description", text)
        val projected = AgentContextBudget.project(input)
        assertTrue(projected.toString().length <= AgentConversation.MAX_RESULT_CHARS)
        assertTrue(projected.getBoolean("truncated"))
        assertTrue(projected.getBoolean("ok"))
        val result = projected.getJSONObject("data")
        assertEquals("task-bound-cursor", result.getString("next_cursor"))
        assertEquals("requested", result.getString("navigation"))
        assertFalse(result.getBoolean("visible_results_verified"))
        assertEquals("unavailable", result.getString("visual_status"))
        assertTrue(result.getJSONObject("visual_assessment").getBoolean("is_unverified"))
        assertEquals(20, result.getJSONArray("videos").length())
        for (index in 0 until videos.length()) {
            val video = result.getJSONArray("videos").getJSONObject(index)
            assertEquals("av${index + 1}", video.getString("video_id"))
            assertEquals("${index + 1}", video.getString("author_uid"))
            assertEquals("unknown", video.getString("official_source"))
            assertFalse(video.getString("title").isEmpty())
            assertFalse(video.getString("author").isEmpty())
            assertEquals("reported", video.getJSONObject("verification").getString("status"))
            assertEquals(1, video.getJSONObject("verification").getInt("type"))
            assertFalse(video.getJSONObject("verification").getString("description").isEmpty())
        }
        assertTrue(input.getJSONObject("data").getString("description").length > 30_000)
        val failed = AgentContextBudget.project(JSONObject().put("ok", false).put("error", "video_identity_mismatch")
            .put("data", data))
        assertFalse(failed.getBoolean("ok"))
        assertEquals("video_identity_mismatch", failed.getString("error"))
    }

    @Test fun `historical limits preserve real host descriptions and visual description has its own budget`() {
        val input = page()
        input.getJSONObject("data").put("description", "d".repeat(2_000)).put("visual_assessment", JSONObject()
            .put("description", "v".repeat(4_000)).put("is_unverified", true).put("provenance", "chat:image"))
        val data = AgentContextBudget.project(input).getJSONObject("data")
        assertEquals("d".repeat(2_000), data.getString("description"))
        assertEquals("v".repeat(4_000), data.getJSONObject("visual_assessment").getString("description"))
        assertTrue(data.getJSONObject("visual_assessment").getBoolean("is_unverified"))
        assertEquals("chat:image", data.getJSONObject("visual_assessment").getString("provenance"))
    }
}
