package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Counts real client POSTs through an injected transport, never inferred tokenizer usage. */
class AgentTokenRoutingTest {
    private val goal = "帮我找到黑神话悟空的官方演示"
    private val image = "data:image/jpeg;base64,YWJj"

    private fun source(index: Int, decision: Boolean = false) = AgentModelSource(index,
        "https://example.test/v1/${if (decision) "systemone" else "chat/completions"}", "test-key-$index", "model-$index",
        if (decision) AgentSourceProtocol.DECISIONS else AgentSourceProtocol.CHAT)

    private fun caps(source: AgentModelSource, now: Long, vision: Boolean = true) = AgentModelCapabilities(
        false, vision, now, "test proof", decisions = source.protocol == AgentSourceProtocol.DECISIONS,
        decisionFormats = if (source.protocol == AgentSourceProtocol.DECISIONS) setOf("choice") else emptySet(),
        decisionVisionFormats = if (vision && source.protocol == AgentSourceProtocol.DECISIONS) setOf("choice") else emptySet())

    private fun chatResponse() = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", "可见搜索页与视频标题，官方出处未知。"))))
        .put("usage", JSONObject().put("prompt_tokens", 123).put("completion_tokens", 17).put("total_tokens", 140)).toString()

    private fun decisionResponse(body: JSONObject, choice: String): String {
        val criteria = body.getJSONObject("questions").getJSONObject("selection").getJSONObject("criteria")
        val probabilities = JSONObject()
        criteria.keys().forEach { probabilities.put(it, if (it == choice) 0.99 else 0.01 / (criteria.length() - 1)) }
        return JSONObject().put("answers", JSONObject().put("selection", JSONObject().put("type", "choice")
            .put("choice", choice).put("probabilities", probabilities).put("confidence", 0.98)))
            .put("usage", JSONObject().put("input_tokens", 31).put("output_tokens", 7)).toString()
    }

    private class Time(var elapsed: Long = 10_000, var wall: Long = 100_000)
    private fun models(sources: List<AgentModelSource>, time: Time, proofs: Map<String, AgentModelCapabilities> =
        sources.associate { it.fingerprint to caps(it, time.wall) }, route: AgentRoutePolicy = AgentRoutePolicy(sources.mapTo(linkedSetOf()) { it.index }),
        checkpoint: () -> Int = { 5000 }, cancelled: () -> Boolean = { false }, updates: MutableList<AgentRequestUpdate> = mutableListOf(),
        transport: AgentHttpTransport) = AgentCooperation(sources, proofs, route, goal, true, checkpoint, cancelled, {}, { time.elapsed },
        AgentModelClient(transport, clock = { time.wall }), AgentDecisionClient(transport, clock = { time.wall }), AgentHealthRegistry(),
        requestEvent = { updates += it }, wallClock = { time.wall })

    private fun page(name: String = "search") = JSONObject().put("ok", true).put("data", JSONObject().put("page", name))
    private fun call(name: String, arguments: JSONObject = JSONObject()) = AgentModelToolCall("call-$name", name, arguments)
    private fun search(official: Any = "unknown", title: String = "悟空官方演示") = page().apply {
        getJSONObject("data").put("query", "悟空 官方 演示").put("videos", JSONArray().put(JSONObject()
            .put("video_id", "av1").put("title", title).put("author", "发布者").put("author_uid", "42").put("official_source", official)))
    }
    private fun visual(time: Time, description: String = "可见标题，官方出处未知。") = page().apply {
        getJSONObject("data").put("capture_elapsed", time.elapsed).put("cache_hit", false).put("visual_assessment", JSONObject()
            .put("description", description).put("page_assessment", "needs_details").put("source_index", 2).put("is_unverified", true))
    }

    fun `equivalent visual evidence with newer capture metadata needs one review POST`() {
        val decision = source(1, true)
        val time = Time()
        var posts = 0
        val updates = mutableListOf<AgentRequestUpdate>()
        val models = models(listOf(decision), time, updates = updates, transport = AgentHttpTransport { _, bytes, _, _ ->
            posts++; decisionResponse(JSONObject(String(bytes, Charsets.UTF_8)), "more_evidence")
        })
        val first = visual(time)
        models.record(call("inspect_screen"), first); models.review(first)
        time.elapsed += 10
        val second = visual(time).apply { getJSONObject("data").put("cache_hit", true).put("observed_at_elapsed", time.elapsed) }
        models.record(call("inspect_screen"), second); models.review(second)
        assertEquals(1, posts)
        assertEquals(AgentRequestStatus.CACHE_HIT, updates.last().status)
        assertTrue(second.getJSONObject("data").getJSONObject("decision_review").getBoolean("cache_hit"))
    }

    fun `new visual text and changed unknown false null facts require new reviews`() {
        val decision = source(1, true)
        val time = Time()
        var posts = 0
        val models = models(listOf(decision), time, transport = AgentHttpTransport { _, bytes, _, _ ->
            posts++; decisionResponse(JSONObject(String(bytes, Charsets.UTF_8)), "more_evidence")
        })
        for (text in listOf("候选 A 缺出处", "候选 A 可能有新的出处文字")) {
            val response = visual(time, text); models.record(call("inspect_screen"), response); models.review(response)
        }
        for (official in listOf("unknown", false, JSONObject.NULL)) {
            val response = search(official); models.record(call("search_videos", JSONObject().put("query", "悟空")), response); models.review(response)
        }
        assertEquals(5, posts)
    }

    fun `ordinary eyes reuse unchanged image and page after unrelated candidates or details change`() {
        val eyes = source(1)
        val time = Time()
        var posts = 0
        val models = models(listOf(eyes), time, transport = AgentHttpTransport { _, _, _, _ -> posts++; chatResponse() })
        models.inspect(image, page())
        models.record(call("search_videos", JSONObject().put("query", "悟空")), search())
        assertTrue(models.inspect(image, page()).getBoolean("cache_hit"))
        models.record(call("get_video_details", JSONObject().put("video_id", "av1")), page().apply {
            getJSONObject("data").put("video_id", "av1").put("verification", JSONObject().put("status", "unknown"))
        })
        assertTrue(models.inspect(image, page()).getBoolean("cache_hit"))
        assertEquals(1, posts)
    }

    fun `positive ordinary eyes cache avoids repeating a prior uncertain visual decision`() {
        val decision = source(1, true)
        val eyes = source(2)
        val time = Time()
        val requests = mutableListOf<Int>()
        val models = models(listOf(decision, eyes), time, route = AgentRoutePolicy(setOf(1, 2), fixedIndex = 1),
            transport = AgentHttpTransport { selected, bytes, _, _ ->
                requests += selected.index
                if (selected == decision) decisionResponse(JSONObject(String(bytes, Charsets.UTF_8)), "unknown") else chatResponse()
            })
        assertEquals(2, models.inspect(image, page()).getInt("source_index"))
        assertEquals(2, models.inspect(image, page()).getInt("source_index"))
        assertEquals(listOf(1, 2), requests)
    }

    fun `changed image or page cannot reuse ordinary visual facts`() {
        val time = Time()
        var posts = 0
        val models = models(listOf(source(1)), time, transport = AgentHttpTransport { _, _, _, _ -> posts++; chatResponse() })
        models.inspect(image, page())
        models.inspect("data:image/jpeg;base64,ZGVm", page())
        models.inspect(image, page("video"))
        assertEquals(3, posts)
    }

    fun `visual decision reuses only matching actual state and excludes previous visual assessment`() {
        val time = Time()
        val decision = source(1, true)
        var posts = 0
        val updates = mutableListOf<AgentRequestUpdate>()
        val models = models(listOf(decision), time, updates = updates, transport = AgentHttpTransport { _, bytes, _, _ ->
            posts++
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val text = body.getJSONArray("state").getString(0)
            assertFalse(text.contains("visual_assessment"))
            decisionResponse(body, "needs_details")
        })
        models.record(call("get_host_state"), page())
        val first = models.inspect(image, page())
        models.record(call("inspect_screen"), visual(time).apply { getJSONObject("data").put("visual_assessment", first) })
        assertTrue(models.inspect(image, page()).getBoolean("cache_hit"))
        models.record(call("search_videos", JSONObject().put("query", "悟空")), search())
        models.inspect(image, page())
        assertEquals(2, posts)
        assertTrue(updates.filter { it.status == AgentRequestStatus.SUCCEEDED }.all {
            it.role == AgentModelRole.VISION && it.usage == AgentModelUsage(31, 7, 38)
        })
        assertNull(updates.single { it.status == AgentRequestStatus.CACHE_HIT }.usage)
    }

    fun `visual evidence expires at thirty seconds and on elapsed clock rollback`() {
        val time = Time()
        var posts = 0
        val models = models(listOf(source(1)), time, transport = AgentHttpTransport { _, _, _, _ -> posts++; chatResponse() })
        models.inspect(image, page())
        time.elapsed += AgentTaskCache.VISION_TTL_MS - 1
        assertTrue(models.inspect(image, page()).getBoolean("cache_hit"))
        time.elapsed++
        models.inspect(image, page())
        time.elapsed--
        models.inspect(image, page())
        assertEquals(3, posts)
    }

    fun `positive cache never bypasses expired role proof or selected source scope`() {
        val time = Time()
        val first = source(1)
        val selected = source(2)
        val requests = mutableListOf<Int>()
        val models = models(listOf(first, selected), time, route = AgentRoutePolicy(setOf(2)),
            transport = AgentHttpTransport { source, _, _, _ -> requests += source.index; chatResponse() })
        assertEquals(2, models.inspect(image, page()).getInt("source_index"))
        assertTrue(models.inspect(image, page()).getBoolean("cache_hit"))
        time.wall += AgentModelCapabilities.VALID_FOR_MS + 1
        try { models.inspect(image, page()); fail("expired proof cannot use a still-present task cache") }
        catch (error: IllegalStateException) { assertEquals("vision_route_unavailable", error.message) }
        assertEquals(listOf(2), requests)
    }

    fun `cancellation at checkpoint rejects a cache hit without emitting success`() {
        val time = Time()
        var cancelled = false
        var cancelAtCheckpoint = false
        var posts = 0
        val updates = mutableListOf<AgentRequestUpdate>()
        val models = models(listOf(source(1)), time, checkpoint = { cancelled = cancelAtCheckpoint; 5000 },
            cancelled = { cancelled }, updates = updates, transport = AgentHttpTransport { _, _, _, _ -> posts++; chatResponse() })
        models.inspect(image, page())
        updates.clear(); cancelAtCheckpoint = true
        try { models.inspect(image, page()); fail("cancelled cache hit") }
        catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, error.reason) }
        assertEquals(1, posts)
        assertTrue(updates.none { it.status == AgentRequestStatus.SUCCEEDED || it.status == AgentRequestStatus.CACHE_HIT })
    }

    fun `unknown review and visual answers are retried rather than cached as successful evidence`() {
        val time = Time()
        val decision = source(1, true)
        var posts = 0
        val models = models(listOf(decision), time, transport = AgentHttpTransport { _, bytes, _, _ ->
            posts++; decisionResponse(JSONObject(String(bytes, Charsets.UTF_8)), "unknown")
        })
        repeat(2) { assertEquals("unknown", models.inspect(image, page()).getString("page_assessment")) }
        repeat(2) { models.review(page()) }
        assertEquals(4, posts)
    }

    fun `failed auxiliary response is not cached and local failure does not punish source health`() {
        val time = Time()
        var posts = 0
        val decision = source(1, true)
        val models = models(listOf(decision), time, transport = AgentHttpTransport { _, bytes, _, _ ->
            posts++
            if (posts == 1) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
            decisionResponse(JSONObject(String(bytes, Charsets.UTF_8)), "more_evidence")
        })
        val first = page(); models.review(first)
        assertEquals("unavailable", first.getJSONObject("data").getString("decision_review_status"))
        val second = page(); models.review(second)
        assertEquals("more_evidence", second.getJSONObject("data").getJSONObject("decision_review").getString("suggestion"))
        assertEquals(2, posts)
    }

    fun `provider visual and decision usage reaches logs while cache hits contain no reused token counts`() {
        val time = Time()
        val decision = source(1, true)
        val eyes = source(2)
        val updates = mutableListOf<AgentRequestUpdate>()
        var posts = 0
        val proofs = mapOf(decision.fingerprint to caps(decision, time.wall, vision = false), eyes.fingerprint to caps(eyes, time.wall))
        val models = models(listOf(decision, eyes), time, proofs, updates = updates,
            transport = AgentHttpTransport { selected, bytes, _, _ ->
                posts++
                if (selected == decision) decisionResponse(JSONObject(String(bytes, Charsets.UTF_8)), "more_evidence") else chatResponse()
            })
        val response = page().apply { getJSONObject("data").put("visual_assessment", models.inspect(image, page())) }
        models.record(call("inspect_screen"), response); models.review(response)
        models.inspect(image, page()); models.review(response)
        val actual = updates.filter { it.status == AgentRequestStatus.SUCCEEDED }
        assertEquals(listOf(AgentModelRole.VISION, AgentModelRole.DECISION), actual.map { it.role })
        assertEquals(AgentModelUsage(123, 17, 140), actual[0].usage)
        assertEquals(AgentModelUsage(31, 7, 38), actual[1].usage)
        val hits = updates.filter { it.status == AgentRequestStatus.CACHE_HIT }
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.usage == null })
        assertFalse(response.toString().contains("input_tokens"))
        assertFalse(response.toString().contains("data:image/"))
        assertEquals(2, posts)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentTokenRoutingTest) -> Unit) {
            val s = AgentTokenRoutingTest()
            block(s)
        }
        runIsolated { s -> s.`equivalent visual evidence with newer capture metadata needs one review POST`() }
        runIsolated { s -> s.`new visual text and changed unknown false null facts require new reviews`() }
        runIsolated { s -> s.`ordinary eyes reuse unchanged image and page after unrelated candidates or details change`() }
        runIsolated { s -> s.`positive ordinary eyes cache avoids repeating a prior uncertain visual decision`() }
        runIsolated { s -> s.`changed image or page cannot reuse ordinary visual facts`() }
        runIsolated { s -> s.`visual decision reuses only matching actual state and excludes previous visual assessment`() }
        runIsolated { s -> s.`visual evidence expires at thirty seconds and on elapsed clock rollback`() }
        runIsolated { s -> s.`positive cache never bypasses expired role proof or selected source scope`() }
        runIsolated { s -> s.`cancellation at checkpoint rejects a cache hit without emitting success`() }
        runIsolated { s -> s.`unknown review and visual answers are retried rather than cached as successful evidence`() }
        runIsolated { s -> s.`failed auxiliary response is not cached and local failure does not punish source health`() }
        runIsolated { s -> s.`provider visual and decision usage reaches logs while cache hits contain no reused token counts`() }
    }
}
