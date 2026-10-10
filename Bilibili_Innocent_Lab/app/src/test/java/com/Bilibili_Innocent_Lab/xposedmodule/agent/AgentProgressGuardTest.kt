package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentProgressGuardTest {
    @Test fun postActionSnapshotUsesRealUiFactsWithoutInventingProgressFromDispatch() {
        fun ui(label: String, after: Boolean) = JSONObject().put("ok", true).put("data", JSONObject().put("backend", "accessibility")
            .put("snapshot_id", "fresh").put("nodes", JSONArray().put(JSONObject().put("label", label)))
            .apply { if (after) put("action", "dispatched").put("observation_after_action", true).put("verification_required", false) })
        val guard = AgentProgressGuard(2)
        assertTrue(guard.observe(call("get_ui_state"), ui("same", false)))
        assertTrue(guard.observe(call("click_ui"), ui("same", true)))
        assertFalse(guard.observe(call("get_ui_state"), ui("same", false)))
        assertTrue(guard.observe(call("swipe_ui"), ui("new", true)))
    }
    @Test fun newSnapshotIdsOffsetsAndDispatchedClicksCannotFakeUiProgress() {
        val guard = AgentProgressGuard(3)
        fun same(id: String, offset: Int) = JSONObject().put("ok", true).put("data", JSONObject()
            .put("snapshot_id", id).put("offset", offset).put("next_offset", offset + 12)
            .put("nodes", JSONArray().put(JSONObject().put("label", "相同界面"))))
        assertTrue(guard.observe(call("get_ui_state"), same("one", 0)))
        assertTrue(guard.observe(call("get_ui_state"), same("two", 12)))
        assertTrue(guard.observe(call("click_ui"), JSONObject().put("ok", true).put("data", JSONObject().put("action", "dispatched"))))
        assertFalse(guard.observe(call("get_ui_state"), same("three", 24)))
    }
    private fun call(name: String = "get_host_state", args: JSONObject = JSONObject(), id: String = "fresh") =
        AgentModelToolCall(id, name, args)
    private fun state(time: Long = 1, page: String = "search") = JSONObject().put("ok", true).put("data", JSONObject()
        .put("page", page).put("query", "悟空").put("capture_elapsed", time).put("observed_at_elapsed", time)
        .put("cache_hit", time > 1).put("observations", time).put("source_index", time.toInt()))
    private fun candidates(vararg ids: String, query: String = "悟空", cursor: String = "page:1") = JSONObject().put("ok", true)
        .put("data", JSONObject().put("query", query).put("next_cursor", cursor).put("videos", JSONArray(ids.map { id ->
            JSONObject().put("video_id", id).put("title", "demo $id").put("official_source", "unknown")
        })))

    @Test fun `time counters provenance and cache flags do not fake progress`() {
        val guard = AgentProgressGuard()
        assertTrue(guard.observe(call(), state()))
        repeat(7) { assertTrue(guard.observe(call(id = "call_$it"), state(it + 2L))) }
        assertFalse(guard.observe(call(), state(10)))
    }

    @Test fun `alternating old reads and failures still stalls`() {
        val guard = AgentProgressGuard()
        val failed = JSONObject().put("ok", false).put("error", "same_failure")
        assertTrue(guard.observe(call(), state()))
        assertTrue(guard.observe(call("get_video_details", JSONObject().put("video_id", "av1")), failed))
        repeat(6) { index ->
            assertTrue(if (index % 2 == 0) guard.observe(call(), state(index + 2L)) else
                guard.observe(call("get_video_details", JSONObject().put("video_id", "av1")), failed))
        }
        assertFalse(guard.observe(call(), state(20)))
    }

    @Test fun `only actual candidates details and page identity reset stagnation`() {
        val guard = AgentProgressGuard(2)
        assertTrue(guard.observe(call(), state()))
        assertTrue(guard.observe(call(), state(2)))
        assertTrue(guard.observe(call(), state(3, "video")))
        assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "new query")), state()))
        assertFalse(guard.observe(call("search_videos", JSONObject().put("query", "another query")), state()))
        val candidates = JSONObject().put("ok", true).put("data", JSONObject().put("videos", JSONArray()
            .put(JSONObject().put("video_id", "av2").put("official_source", "unknown"))))
        assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "new query")), candidates))
        assertTrue(guard.observe(call("get_video_details", JSONObject().put("video_id", "av2")),
            JSONObject().put("ok", true).put("data", JSONObject().put("video_id", "av2").put("title", "new details"))))
    }

    @Test fun `stable digest handles field order nested timestamps and retained semantic facts`() {
        val first = JSONObject("{\"query\":\"悟空\",\"data\":{\"title\":\"demo\",\"observed_at_elapsed\":1}}")
        val second = JSONObject("{\"data\":{\"cache_hit\":true,\"observed_at_elapsed\":9,\"title\":\"demo\"},\"query\":\"悟空\"}")
        assertEquals(AgentProgressGuard.stableDigest(first), AgentProgressGuard.stableDigest(second))
        second.getJSONObject("data").put("title", "changed")
        assertNotEquals(AgentProgressGuard.stableDigest(first), AgentProgressGuard.stableDigest(second))
    }

    @Test fun `image digest and pagination remain evidence while raw image and model words are discarded`() {
        val value = JSONObject().put("image_data_url", "data:image/jpeg;base64,one")
            .put("image_digest", "same-image").put("visual_assessment", JSONObject().put("description", "search results"))
            .put("next_cursor", "page:1")
        val before = AgentProgressGuard.stableDigest(value)
        value.put("image_data_url", "data:image/jpeg;base64,two")
        assertEquals(before, AgentProgressGuard.stableDigest(value))
        value.put("next_cursor", "page:2")
        assertNotEquals(before, AgentProgressGuard.stableDigest(value))
        value.put("next_cursor", "page:1").getJSONObject("visual_assessment").put("description", "blocked")
        assertEquals(before, AgentProgressGuard.stableDigest(value))
        value.put("image_digest", "new-image")
        assertNotEquals(before, AgentProgressGuard.stableDigest(value))
    }

    @Test fun `same image with varying auxiliary model wording cannot fake task progress`() {
        val guard = AgentProgressGuard()
        fun response(index: Int) = state(index.toLong()).apply {
            getJSONObject("data").put("image_digest", "fixed-screen")
                .put("visual_assessment", JSONObject().put("description", "model wording $index").put("source_index", index))
                .put("visual_status", "status-$index")
                .put("decision_review", JSONObject().put("suggestion", "review wording $index"))
                .put("decision_review_status", "review-status-$index").put("decision_review_is_unverified", index % 2 == 0)
        }
        assertTrue(guard.observe(call("inspect_screen"), response(1)))
        repeat(7) { assertTrue(guard.observe(call("inspect_screen"), response(it + 2))) }
        assertFalse(guard.observe(call("inspect_screen"), response(10)))
        val newScreen = response(11).apply { getJSONObject("data").put("image_digest", "changed-screen") }
        assertTrue(guard.observe(call("inspect_screen"), newScreen))
    }

    @Test fun `guard remains bounded across many results and clears task evidence`() {
        val guard = AgentProgressGuard(2)
        repeat(1_000) { index -> assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q$index")), candidates("av${index + 1}"))) }
        assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q999")), candidates("av1000")))
        assertFalse(guard.observe(call("search_videos", JSONObject().put("query", "q999")), candidates("av1000")))
        guard.clear()
        assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q999")), candidates("av1000")))
    }

    @Test fun `different failed operations ids and queries all accumulate stagnation`() {
        val guard = AgentProgressGuard()
        repeat(7) { index -> assertTrue(guard.observe(call(if (index % 2 == 0) "search_videos" else "get_video_details",
            JSONObject().put("query", "different $index").put("video_id", "av$index")),
            JSONObject().put("ok", false).put("error", "failure_$index"))) }
        assertFalse(guard.observe(call("search_videos", JSONObject().put("query", "brand new")),
            JSONObject().put("ok", false).put("error", "new_failure")))
    }

    @Test fun `empty search results keep accumulating despite changing query and cursor`() {
        val guard = AgentProgressGuard()
        repeat(7) { index -> assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q$index")),
            candidates(query = "q$index", cursor = "page:$index"))) }
        assertFalse(guard.observe(call("search_videos", JSONObject().put("query", "new query")),
            candidates(query = "new query", cursor = "new cursor")))
    }

    @Test fun `same candidate set with different ordering queries and cursors is old evidence`() {
        val guard = AgentProgressGuard()
        assertTrue(guard.observe(call("search_videos"), candidates("av1", "av2")))
        repeat(7) { index -> assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q$index")),
            candidates("av2", "av1", query = "q$index", cursor = "page:$index"))) }
        assertFalse(guard.observe(call("search_videos"), candidates("av1", "av2", query = "fresh phrase")))
        assertTrue(guard.observe(call("search_videos"), candidates("av1", "av3")))
    }

    @Test fun `navigation requested is not a new observation`() {
        val guard = AgentProgressGuard(2)
        val requested = JSONObject().put("ok", true).put("data", JSONObject().put("video_id", "av1")
            .put("navigation", "requested").put("observed", false))
        assertTrue(guard.observe(call("open_video", JSONObject().put("video_id", "av1")), requested))
        assertFalse(guard.observe(call("open_video", JSONObject().put("video_id", "av2")), requested))
        assertTrue(guard.observe(call("get_host_state"), state(page = "video")))
    }
}
