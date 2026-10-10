package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentDecisionClient
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentDecisionActionsTest {
    @Test fun observedPostActionUiRefreshesMenusWithoutAnExtraModelReadRound() {
        val actions = AgentDecisionActions("搜索")
        val data = JSONObject().put("backend", "accessibility").put("snapshot_id", "12345678-1234-1234-1234-123456789abc")
            .put("observation_after_action", true).put("nodes", JSONArray().put(JSONObject().put("node_id", "0.1")
                .put("label", "新页面按钮").put("clickable", true).put("enabled", true)))
        actions.record(call("click_ui"), JSONObject().put("ok", true).put("data", data))
        assertTrue(actions.menu(false).containsKey("finish"))
        assertTrue(actions.menu(false).values.any { it.first == "click_ui" && it.second.optString("node_id") == "0.1" })
    }
    @Test fun uiMenusAreBoundedAndCannotFinishBeforeAnActionIsObserved() {
        val actions = AgentDecisionActions("搜索悟空")
        val id = "12345678-1234-1234-1234-123456789abc"
        val nodes = JSONArray((0 until 64).map { index -> JSONObject().put("node_id", "0.$index")
            .put("label", "普通按钮$index").put("clickable", true).put("enabled", true).put("protected", index == 0) })
        val response = JSONObject().put("ok", true).put("data", JSONObject().put("backend", "accessibility")
            .put("snapshot_id", id).put("nodes", nodes).put("next_offset", 12))
        actions.record(call("get_ui_state"), response)
        val menu = actions.menu(true)
        assertTrue(menu.size <= AgentDecisionClient.MAX_OPTIONS)
        assertFalse(menu.values.any { it.second.optString("node_id") == "0.0" })
        assertTrue(menu.values.any { it.first == "get_ui_state" && it.second.optString("offset") == "12" })
        menu.values.filter { it.first != "finish" }.forEach { assertTrue(AgentToolCatalog.valid(it.first, it.second, true)) }
        actions.record(call("click_ui"), JSONObject().put("ok", true).put("data", JSONObject().put("action", "dispatched")))
        assertFalse(actions.menu(true).containsKey("finish"))
        assertFalse(actions.menu(true).values.any { it.first == "click_ui" })
        actions.record(call("get_ui_state"), response)
        assertTrue(actions.menu(true).containsKey("finish"))
        assertFalse(actions.report().contains("视频候选"))
    }
    private fun call(name: String, arguments: JSONObject = JSONObject()) = AgentModelToolCall("test_call", name, arguments)
    private fun video(id: Int, title: String = "演示标题_$id", author: String = "发布者_$id") = JSONObject()
        .put("video_id", "av$id").put("title", title).put("author", author).put("author_uid", id.toString())

    private fun search(videos: List<JSONObject>, cursor: String? = null) = JSONObject().put("ok", true)
        .put("data", JSONObject().put("videos", JSONArray(videos)).put("query", "黑神话 官方 演示")
            .put("source", "host_rpc").put("visible_results_verified", false)
            .put("next_cursor", cursor ?: JSONObject.NULL))

    private fun detail(id: Int) = JSONObject().put("ok", true).put("data", video(id)
        .put("source", "host_rpc").put("official_source", "unknown"))

    @Test fun `before observations no finish choice or completed observation claim is available`() {
        val actions = AgentDecisionActions("帮我找到黑神话官方演示")
        assertFalse(actions.menu(false).containsKey("finish"))
        assertFalse(actions.report().startsWith("已读取宿主"))
        assertFalse(actions.report().contains("video_id"))
    }

    @Test fun `failed search cannot create candidates pagination or finish permission`() {
        val actions = AgentDecisionActions("帮我找到黑神话官方演示")
        actions.record(call("search_videos"), JSONObject().put("ok", false).put("data", search(listOf(video(1))).getJSONObject("data")))
        val menu = actions.menu(true)
        assertTrue(menu.containsKey("search"))
        assertFalse(menu.containsKey("finish"))
        assertFalse(menu.containsKey("next_page"))
        assertFalse(menu.values.any { it.second.has("video_id") })
    }

    @Test fun `closed actions use valid catalog arguments and never invent open or arbitrary network operations`() {
        val actions = AgentDecisionActions("帮我找到有关于黑神话悟空的官方演示视频")
        val initial = actions.menu(true)
        assertEquals("黑神话悟空的官方演示视频", initial.getValue("search").second.getString("query"))
        actions.record(call("search_videos"), search((1..20).map { video(it) }, "opaque_cursor"))
        actions.menu(true).values.forEach { action ->
            assertFalse(action.first in setOf("open_video", "click", "shell", "http"))
            if (action.first != "finish") assertTrue(AgentToolCatalog.valid(action.first, action.second, true))
        }
        assertTrue(actions.menu(true).size <= AgentDecisionClient.MAX_OPTIONS)
    }

    @Test fun `multiline goals produce valid bounded search arguments`() {
        val actions = AgentDecisionActions("帮我找到\n有关于黑神话悟空\t的官方演示视频\u0000")
        val arguments = actions.menu(false).getValue("search").second
        assertTrue(AgentToolCatalog.valid("search_videos", arguments, false))
        assertTrue(arguments.getString("query").length <= 200)
        assertFalse(arguments.getString("query").any(Char::isISOControl))
    }

    @Test fun `details only follow observed IDs and completion removes their menu entries`() {
        val actions = AgentDecisionActions("找到官方演示")
        actions.record(call("search_videos"), search(listOf(video(1), video(2))))
        assertEquals(setOf("av1", "av2"), actions.menu(false).values.filter { it.first == "get_video_details" }
            .map { it.second.getString("video_id") }.toSet())
        actions.record(call("get_video_details", JSONObject().put("video_id", "av1")), detail(1))
        assertEquals(setOf("av2"), actions.menu(false).values.filter { it.first == "get_video_details" }
            .map { it.second.getString("video_id") }.toSet())
        assertFalse(actions.report().contains("已确认官方"))
    }

    @Test fun `pending detail choices retain title evidence after the first candidate window was reviewed`() {
        val actions = AgentDecisionActions("找到官方演示")
        actions.record(call("search_videos"), search((1..20).map { video(it) }))
        (1..8).forEach { id -> actions.record(call("get_video_details", JSONObject().put("video_id", "av$id")), detail(id)) }
        val menu = actions.menu(false)
        val descriptions = actions.options(menu)
        val evidence = actions.state().toString()
        menu.forEach { (key, action) ->
            if (action.first == "get_video_details") {
                val id = action.second.getString("video_id").removePrefix("av")
                assertTrue("Each selectable candidate needs its title, not just an opaque ID",
                    descriptions.getValue(key).contains("演示标题_$id") || evidence.contains("演示标题_$id"))
            }
        }
    }

    @Test fun `escaped source text cannot overflow the decision context budget`() {
        val text = "\"\\\n".repeat(110)
        val actions = AgentDecisionActions("\"\\".repeat(500))
        actions.record(call("search_videos"), search((1..20).map { video(it, text.take(300), text.take(100)) }))
        (1..3).forEach { id ->
            val response = detail(id)
            response.getJSONObject("data").put("description", text.repeat(6).take(2000))
            actions.record(call("get_video_details", JSONObject().put("video_id", "av$id")), response)
        }
        val state = actions.state()
        assertTrue("Budget must count serialized escapes", state.toString().length <= AgentDecisionClient.MAX_STATE_CHARS)
        assertTrue(state.has("goal"))
    }

    @Test fun `visual eyes remain visible to the next text decision even behind lengthy page metadata`() {
        val actions = AgentDecisionActions("找到官方演示")
        val data = JSONObject().put("metadata", "x".repeat(2000))
            .put("visual_assessment", JSONObject().put("description", "EYES_MARKER:可见演示标题和发布者")
                .put("source_index", 2).put("is_unverified", true))
        actions.record(call("inspect_screen"), JSONObject().put("ok", true).put("data", data))
        assertTrue(actions.state().toString().contains("EYES_MARKER"))
    }

    @Test fun `full goal and escaped visual evidence still fit when only one candidate remains`() {
        val actions = AgentDecisionActions("\"".repeat(2000))
        actions.record(call("search_videos"), search(listOf(video(1, "\"".repeat(120), "\"".repeat(100)))))
        val data = JSONObject().put("visual_assessment", JSONObject().put("description", "\"".repeat(900))
            .put("is_unverified", true).put("source_index", 2))
        actions.record(call("inspect_screen"), JSONObject().put("ok", true).put("data", data))
        assertTrue("The final candidate and vision fields cannot escape the total state budget",
            actions.state().toString().length <= AgentDecisionClient.MAX_STATE_CHARS)
    }

    @Test fun `fresh visual eyes survive later detail queries with their original observation timestamp`() {
        var now = 1000L
        val actions = AgentDecisionActions("找到官方演示", elapsed = { now })
        actions.record(call("search_videos"), search(listOf(video(1))))
        val data = JSONObject().put("capture_elapsed", now).put("visual_assessment", JSONObject()
            .put("description", "EYES_MARKER:画面可见演示标题").put("source_index", 2).put("is_unverified", true))
        actions.record(call("inspect_screen"), JSONObject().put("ok", true).put("data", data))
        now = 15_000L
        actions.record(call("get_video_details", JSONObject().put("video_id", "av1")), detail(1))
        val eyes = actions.state().getJSONObject("visual_assessment")
        assertTrue(eyes.getString("description").contains("EYES_MARKER"))
        assertEquals(1000L, eyes.getLong("observed_at_elapsed"))
        assertTrue(eyes.getBoolean("historical")); assertTrue(eyes.getBoolean("is_unverified"))
    }

    @Test fun `visual eyes expire after thirty seconds and detail reads do not renew them`() {
        var now = 1000L
        val actions = AgentDecisionActions("找到官方演示", elapsed = { now })
        val data = JSONObject().put("capture_elapsed", now).put("visual_assessment", JSONObject()
            .put("description", "EYES_MARKER").put("source_index", 2).put("is_unverified", true))
        actions.record(call("inspect_screen"), JSONObject().put("ok", true).put("data", data))
        now = 32_000L
        actions.record(call("get_video_details", JSONObject().put("video_id", "av1")), detail(1))
        assertFalse(actions.state().has("visual_assessment"))
        assertFalse(actions.state().toString().contains("EYES_MARKER"))
    }

    @Test fun `successful search navigation clears previous page visual eyes immediately`() {
        var now = 1000L
        val actions = AgentDecisionActions("找到官方演示", elapsed = { now })
        val data = JSONObject().put("capture_elapsed", now).put("visual_assessment", JSONObject()
            .put("description", "OLD_PAGE_EYES").put("source_index", 2).put("is_unverified", true))
        actions.record(call("inspect_screen"), JSONObject().put("ok", true).put("data", data))
        val next = search(listOf(video(2)))
        next.getJSONObject("data").put("navigation", "requested")
        now += 1
        actions.record(call("search_videos", JSONObject().put("query", "新关键词")), next)
        assertFalse(actions.state().has("visual_assessment"))
        assertFalse(actions.state().toString().contains("OLD_PAGE_EYES"))
    }

    @Test fun `closed call copies validated arguments and does not retain mutable menu parameters`() {
        val actions = AgentDecisionActions("搜索演示")
        val arguments = JSONObject().put("query", "演示")
        val chosen = actions.call("search_videos" to arguments)
        arguments.put("query", "changed").put("endpoint", "https://untrusted.test")
        assertEquals("演示", chosen.arguments.getString("query"))
        assertFalse(chosen.arguments.has("endpoint"))
    }

    @Test fun `rolling results stay bounded and expose only current retained candidate identifiers`() {
        val actions = AgentDecisionActions("找到官方演示")
        (0..5).forEach { page -> actions.record(call("search_videos"), search((1..20).map { video(page * 20 + it) })) }
        val ids = actions.menu(false).values.filter { it.first == "get_video_details" }.map { it.second.getString("video_id") }
        assertTrue(ids.size <= 18)
        assertTrue(ids.all { it.removePrefix("av").toInt() in 57..120 })
    }
    @Test fun `candidate examined outside the first eight appears in the final report`() {
        val actions = AgentDecisionActions("找到官方演示")
        actions.record(call("search_videos"), search((1..20).map { video(it) }))
        actions.record(call("get_video_details", JSONObject().put("video_id", "av19")), detail(19))
        val report = actions.report()
        assertTrue(report.contains("演示标题_19"))
        assertTrue(report.contains("视频：av19"))
        assertTrue(report.indexOf("演示标题_19") < report.indexOf("演示标题_1\n"))
        assertFalse(report.contains("已确认官方"))
    }
}
