package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCooperationTest {
    private fun nativeCall(name: String, args: JSONObject, id: String = "native_call"): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject()
            .put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject()
                .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString())))))))
        .put("usage", JSONObject().put("prompt_tokens", 12).put("completion_tokens", 4)).toString()

    @Test fun nativeNumericReadHasCanonicalHistoryAndNoAdditionalRequest() {
        val planner = source(1); var requests = 0
        val cooperation = models(listOf(planner), mapOf(planner.fingerprint to caps(tools = true)), AgentRoutePolicy(setOf(1)),
            AgentHttpTransport { _, _, _, _ -> requests++; nativeCall("get_ui_state", JSONObject().put("offset", 0)) })
        val turn = cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal))
        assertEquals(1, requests)
        assertEquals("0", turn.toolCalls.single().arguments.getString("offset"))
        val echo = JSONObject(turn.message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("0", echo.getString("offset")); assertEquals(12L, turn.usage!!.inputTokens)
    }
    @Test fun invalidNativePlanUsesOnlyPermittedFallbackAndRetainsItsTokenMetrics() {
        val primary = source(1); val backup = source(2); val requests = mutableListOf<Int>(); val updates = mutableListOf<AgentRequestUpdate>()
        val transport = AgentHttpTransport { selected, _, _, _ ->
            requests += selected.index
            if (selected.index == 1) nativeCall("swipe_ui", JSONObject().put("direction", "up"))
            else nativeCall("get_host_state", JSONObject(), "backup_read")
        }
        val cooperation = AgentCooperation(listOf(primary, backup), mapOf(primary.fingerprint to caps(tools = true), backup.fingerprint to caps(tools = true)),
            AgentRoutePolicy(setOf(1, 2), fixedIndex = 1, allowFallback = true), goal, false, { 5000 }, { false }, {}, { 10_000L },
            AgentModelClient(transport), AgentDecisionClient(transport), AgentHealthRegistry(), requestEvent = updates::add)
        val turn = cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal))
        assertEquals(listOf(1, 2), requests); assertEquals("get_host_state", turn.toolCalls.single().name)
        val invalid = updates.single { it.status == AgentRequestStatus.FALLBACK }
        assertEquals(AgentModelException.Reason.INVALID_RESPONSE, invalid.error)
        assertEquals(12L, invalid.usage!!.inputTokens)
    }
    @Test fun invalidFixedPlanNeverEscapesThroughUnapprovedFallbackOrMissingIdentity() {
        val primary = source(1); val backup = source(2); val requests = mutableListOf<Int>()
        val cooperation = models(listOf(primary, backup), mapOf(primary.fingerprint to caps(tools = true), backup.fingerprint to caps(tools = true)),
            AgentRoutePolicy(setOf(1, 2), fixedIndex = 1, allowFallback = false), AgentHttpTransport { selected, _, _, _ ->
                requests += selected.index; nativeCall("swipe_ui", JSONObject().put("direction", "up"))
            })
        try { cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal)); fail("Invalid plan must not be returned") }
        catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.INVALID_RESPONSE, error.reason) }
        assertEquals(listOf(1), requests)
    }
    @Test fun textOnlyPlannerReceivesOrdinaryDialogAndRealExecutionResults() {
        val planner = source(1)
        val proof = caps().copy(plainPlanning = true, plainState = AgentCapabilityState.SUPPORTED)
        var count = 0
        val transport = AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            assertFalse(body.has("tools")); assertFalse(body.has("tool_choice"))
            assertFalse(body.getJSONArray("messages").toString().contains("tool_call_id"))
            count++
            if (count == 1) chatResponse("{\"action\":\"get_host_state\",\"arguments\":{}}")
            else { assertTrue(body.toString().contains("REAL_FACT")); chatResponse("{\"answer\":\"根据真实结果完成核对\"}") }
        }
        val cooperation = models(listOf(planner), mapOf(planner.fingerprint to proof), AgentRoutePolicy(setOf(1), 1), transport)
        val history = AgentConversation(AgentToolCatalog.SYSTEM, goal)
        val first = cooperation.next(history)
        assertEquals("get_host_state", first.toolCalls.single().name)
        val result = JSONObject().put("ok", true).put("data", JSONObject().put("fact", "REAL_FACT"))
        history.append(first, result, planner.fingerprint); cooperation.record(first.toolCalls.single(), result)
        assertEquals("根据真实结果完成核对", cooperation.next(history).text)
        assertEquals(2, count)
    }

    @Test fun explicitToolRejectionProbesTextModeOnceWithoutExtendingVisionProof() {
        val planner = source(1)
        val original = caps(tools = true, vision = true).copy(checkedAtMs = System.currentTimeMillis() - 10_000)
        var probes = 0
        var tasks = 0
        val changed = mutableListOf<AgentModelCapabilities>()
        val transport = AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (body.has("tools")) throw AgentModelException(AgentModelException.Reason.TOOLS_UNSUPPORTED)
            val messages = body.getJSONArray("messages")
            val initial = messages.getJSONObject(0).getString("content")
            if (initial.contains("无副作用的文本指令检测")) {
                probes++
                if (messages.length() == 1) {
                    val nonce = Regex("nonce '([^']+)'").find(initial)!!.groupValues[1]
                    chatResponse(JSONObject().put("action", "capability_echo").put("arguments", JSONObject().put("nonce", nonce)).toString())
                } else {
                    val content = messages.getJSONObject(2).getString("content")
                    val receipt = JSONObject(content.substring(content.indexOf('{'))).getString("receipt")
                    chatResponse(JSONObject().put("answer", receipt).toString())
                }
            } else { tasks++; chatResponse("{\"action\":\"get_host_state\",\"arguments\":{}}") }
        }
        val cooperation = AgentCooperation(listOf(planner), mapOf(planner.fingerprint to original), AgentRoutePolicy(setOf(1), 1),
            goal, false, { 5000 }, { false }, {}, { 1000 }, AgentModelClient(transport), AgentDecisionClient(transport),
            capabilityChanged = { _, value -> changed += value })
        repeat(2) { assertEquals("get_host_state", cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal)).toolCalls.single().name) }
        assertEquals(2, probes); assertEquals(2, tasks); assertEquals(1, changed.size)
        assertTrue(changed.single().plainPlanning); assertFalse(changed.single().tools)
        assertTrue(changed.single().vision); assertEquals(original.checkedAtMs, changed.single().checkedAtMs)
    }
    @Test fun `model request metrics preserve planning when a log observer fails`() {
        val planner = source(1)
        val updates = mutableListOf<AgentRequestUpdate>()
        var tick = 1000L
        val transport = AgentHttpTransport { _, _, _, _ -> tick += 45; chatResponse("", call = true) }
        val models = AgentCooperation(listOf(planner), mapOf(planner.fingerprint to caps(tools = true)),
            AgentRoutePolicy(setOf(1)), goal, false, { 5000 }, { false }, {}, { tick },
            AgentModelClient(transport), AgentDecisionClient(transport), AgentHealthRegistry(), requestEvent = {
                updates += it
                if (it.status == AgentRequestStatus.STARTED) throw IllegalStateException("observer_failed")
            })
        val turn = models.next(AgentConversation(AgentToolCatalog.SYSTEM, goal))
        assertEquals("search_videos", turn.toolCalls.single().name)
        assertEquals(listOf(AgentRequestStatus.STARTED, AgentRequestStatus.SUCCEEDED), updates.map { it.status })
        assertEquals(45L, updates.last().durationMs)
        assertTrue(updates.all { it.role == AgentModelRole.PLANNER && it.source == 1 })
    }

    private val image = "data:image/jpeg;base64,YWJj"
    private val goal = "帮我找到黑神话悟空官方演示"
    private fun source(index: Int, decision: Boolean = false) = AgentModelSource(index,
        "https://example.test/v1/${if (decision) "systemone" else "chat/completions"}", "test-key-$index", "model-$index",
        if (decision) AgentSourceProtocol.DECISIONS else AgentSourceProtocol.CHAT)
    private fun caps(tools: Boolean = false, vision: Boolean = false, decision: Boolean = false) =
        AgentModelCapabilities(tools, vision, System.currentTimeMillis(), "test proof", decisions = decision,
            decisionState = if (decision) AgentCapabilityState.SUPPORTED else AgentCapabilityState.UNKNOWN,
            decisionFormats = if (decision) setOf("choice") else emptySet(),
            decisionVisionFormats = if (decision && vision) setOf("choice") else emptySet())

    private fun chatResponse(content: String, call: Boolean = false, reasoning: String? = null): String {
        val message = JSONObject().put("role", "assistant").put("content", content)
        if (call) message.put("tool_calls", JSONArray().put(JSONObject().put("id", "search_1").put("type", "function")
            .put("function", JSONObject().put("name", "search_videos")
                .put("arguments", JSONObject().put("query", "黑神话悟空 官方 演示").toString()))))
        reasoning?.let { message.put("reasoning_content", it) }
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", if (call) "tool_calls" else "stop")
            .put("message", message))).toString()
    }

    private fun decisionResponse(body: JSONObject, choice: String): String {
        val questions = body.getJSONObject("questions")
        val criteria = questions.getJSONObject("selection").getJSONObject("criteria")
        val probabilities = JSONObject()
        criteria.keys().forEach { probabilities.put(it, if (it == choice) 0.99 else 0.01 / (criteria.length() - 1)) }
        return JSONObject().put("answers", JSONObject().put("selection", JSONObject().put("type", "choice")
            .put("choice", choice).put("probabilities", probabilities).put("confidence", 0.98))).toString()
    }

    private fun models(sources: List<AgentModelSource>, proofs: Map<String, AgentModelCapabilities>, route: AgentRoutePolicy,
                       transport: AgentHttpTransport, health: AgentHealthRegistry = AgentHealthRegistry()) =
        AgentCooperation(sources, proofs, route, goal, true, { 5000 }, { false }, {}, { 10_000L },
            AgentModelClient(transport), AgentDecisionClient(transport), health)

    @Test fun `uncertain visual decision tries ordinary eyes without health punishment or caching unknown`() {
        val decision = source(1, true)
        val eyes = source(2)
        val sources = listOf(decision, eyes)
        val proofs = mapOf(decision.fingerprint to caps(vision = true, decision = true), eyes.fingerprint to caps(vision = true))
        val requests = mutableListOf<Int>()
        val health = AgentHealthRegistry()
        val route = AgentRoutePolicy(setOf(1, 2), fixedIndex = 1)
        val cooperation = models(sources, proofs, route, AgentHttpTransport { selected, bytes, _, _ ->
            requests += selected.index
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (selected.protocol == AgentSourceProtocol.DECISIONS) decisionResponse(body, "unknown")
            else chatResponse("EYES_MARKER:当前可见黑神话演示搜索页，官方身份尚待核实")
        }, health)
        val page = JSONObject().put("ok", true).put("data", JSONObject().put("page", "search"))
        val first = cooperation.inspect(image, page)
        assertEquals(2, first.getInt("source_index"))
        assertTrue(first.getBoolean("is_unverified"))
        assertTrue(first.getString("description").contains("EYES_MARKER"))
        assertEquals(listOf(1, 2), requests)
        val router = AgentSourceRouter(sources, proofs, health)
        val lease = router.acquire(AgentModelRole.VISION, route, System.currentTimeMillis(), preferredFingerprint = decision.fingerprint)
        assertNotNull("An honest abstention must not cool down the provider", lease)
        lease!!.use { assertEquals(1, it.source.index) }
        val next = cooperation.inspect(image, page)
        assertEquals(2, next.getInt("source_index"))
        assertEquals(listOf(1, 2), requests) // 复用真实通过的eyes，未知结果本身仍未缓存。
    }

    @Test fun `ordinary eyes produce bounded text evidence for a nonvisual decision source`() {
        val decision = source(1, true)
        val eyes = source(2)
        var seenImage = false
        var seenTextReview = false
        val cooperation = models(listOf(decision, eyes), mapOf(decision.fingerprint to caps(decision = true),
            eyes.fingerprint to caps(vision = true)), AgentRoutePolicy(setOf(1, 2), fixedIndex = 1),
            AgentHttpTransport { selected, bytes, _, _ ->
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                if (selected.index == 2) {
                    val messages = body.getJSONArray("messages")
                    val content = messages.getJSONObject(1).getJSONArray("content")
                    assertEquals(image, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
                    seenImage = true
                    chatResponse("EYES_MARKER:画面有候选标题及发布者，原始出处还缺详情证据")
                } else {
                    assertFalse(body.get("state") is JSONArray)
                    val state = body.getJSONObject("state")
                    assertTrue(state.getJSONObject("visual_assessment").getString("description").contains("EYES_MARKER"))
                    assertTrue(state.getJSONObject("visual_assessment").getBoolean("is_unverified"))
                    assertFalse(String(bytes, Charsets.UTF_8).contains("data:image/"))
                    seenTextReview = true
                    decisionResponse(body, "more_evidence")
                }
            })
        val response = JSONObject().put("ok", true).put("data", JSONObject().put("page", "search"))
        response.getJSONObject("data").put("visual_assessment", cooperation.inspect(image, response))
        cooperation.record(AgentModelToolCall("inspect_1", "inspect_screen", JSONObject()), response)
        cooperation.review(response)
        assertTrue(seenImage); assertTrue(seenTextReview)
        assertEquals("more_evidence", response.getJSONObject("data").getJSONObject("decision_review").getString("suggestion"))
        assertTrue(response.getJSONObject("data").getBoolean("decision_review_is_unverified"))
    }

    @Test fun `fixed ordinary planner can use another selected visual decision source`() {
        val planner = source(1)
        val visual = source(2, true)
        val requests = mutableListOf<Int>()
        val cooperation = models(listOf(planner, visual), mapOf(planner.fingerprint to caps(tools = true),
            visual.fingerprint to caps(vision = true, decision = true)), AgentRoutePolicy(setOf(1, 2), fixedIndex = 1),
            AgentHttpTransport { selected, bytes, _, _ ->
                requests += selected.index
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                if (selected.index == 1) {
                    assertFalse(String(bytes, Charsets.UTF_8).contains("data:image/"))
                    assertTrue(body.has("tools"))
                    chatResponse("将核实候选")
                } else {
                    assertEquals(image, body.getJSONArray("state").getJSONObject(1).getJSONObject("image_url").getString("url"))
                    decisionResponse(body, "relevant_search")
                }
            })
        cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal))
        val result = cooperation.inspect(image, JSONObject().put("ok", true).put("data", JSONObject().put("page", "search")))
        assertEquals("relevant_search", result.getString("page_assessment"))
        assertEquals(2, result.getInt("source_index"))
        assertEquals(listOf(1, 2), requests)
    }

    @Test fun `planner fallback keeps public tool history and strips another models private reasoning`() {
        val first = source(1)
        val second = source(2)
        var firstCalls = 0
        var switched = false
        val cooperation = models(listOf(first, second), mapOf(first.fingerprint to caps(tools = true),
            second.fingerprint to caps(tools = true)), AgentRoutePolicy(setOf(1, 2), fixedIndex = 1, allowFallback = true),
            AgentHttpTransport { selected, bytes, _, _ ->
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                if (selected.index == 1) {
                    firstCalls++
                    if (firstCalls == 1) chatResponse("", call = true, reasoning = "PRIVATE_REASONING_MARKER")
                    else throw AgentModelException(AgentModelException.Reason.NETWORK)
                } else {
                    val messages = body.getJSONArray("messages")
                    assertFalse(String(bytes, Charsets.UTF_8).contains("PRIVATE_REASONING_MARKER"))
                    assertTrue((0 until messages.length()).any { messages.getJSONObject(it).optString("role") == "tool" })
                    assertTrue(String(bytes, Charsets.UTF_8).contains("真实候选"))
                    switched = true
                    chatResponse("已取得真实候选，出处仍待核实")
                }
            })
        val history = AgentConversation(AgentToolCatalog.SYSTEM, goal)
        val turn = cooperation.next(history)
        assertEquals(1, turn.toolCalls.size)
        history.append(turn, JSONObject().put("ok", true).put("data", JSONObject().put("title", "真实候选")), first.fingerprint)
        cooperation.next(history)
        assertTrue(switched)
        assertEquals(second.fingerprint, cooperation.plannerFingerprint)
    }

    @Test fun `healthy fixed decision stays primary when ordinary fallback is permitted`() {
        val decision = source(1, true)
        val planner = source(2)
        val requests = mutableListOf<Int>()
        val cooperation = models(listOf(decision, planner), mapOf(decision.fingerprint to caps(decision = true),
            planner.fingerprint to caps(tools = true)), AgentRoutePolicy(setOf(1, 2), fixedIndex = 1, allowFallback = true),
            AgentHttpTransport { selected, bytes, _, _ ->
                requests += selected.index
                assertEquals(1, selected.index)
                decisionResponse(JSONObject(String(bytes, Charsets.UTF_8)), "search")
            })
        val turn = cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal))
        assertEquals("search_videos", turn.toolCalls.single().name)
        assertEquals(listOf(1), requests)
        assertEquals(decision.fingerprint, cooperation.plannerFingerprint)
    }

    @Test fun `failed fixed decision can hand planning to selected ordinary model with explicit fallback`() {
        val decision = source(1, true)
        val planner = source(2)
        val requests = mutableListOf<Int>()
        val cooperation = models(listOf(decision, planner), mapOf(decision.fingerprint to caps(decision = true),
            planner.fingerprint to caps(tools = true)), AgentRoutePolicy(setOf(1, 2), fixedIndex = 1, allowFallback = true),
            AgentHttpTransport { selected, _, _, _ ->
                requests += selected.index
                if (selected.index == 1) throw AgentModelException(AgentModelException.Reason.NETWORK)
                chatResponse("", call = true)
            })
        val turn = cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal))
        assertEquals("search_videos", turn.toolCalls.single().name)
        assertEquals(listOf(1, 2), requests)
        assertEquals(planner.fingerprint, cooperation.plannerFingerprint)
    }

    @Test fun `automatic exhausted ordinary planning falls back to bounded decision actions`() {
        val first = source(1)
        val second = source(2)
        val decision = source(3, true)
        val requests = mutableListOf<Int>()
        val cooperation = models(listOf(first, second, decision), mapOf(first.fingerprint to caps(tools = true),
            second.fingerprint to caps(tools = true), decision.fingerprint to caps(decision = true)), AgentRoutePolicy(setOf(1, 2, 3)),
            AgentHttpTransport { selected, bytes, _, _ ->
                requests += selected.index
                if (selected.protocol == AgentSourceProtocol.CHAT) throw AgentModelException(AgentModelException.Reason.NETWORK)
                decisionResponse(JSONObject(String(bytes, Charsets.UTF_8)), "search")
            })
        val turn = cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal))
        assertEquals("search_videos", turn.toolCalls.single().name)
        assertTrue(AgentToolCatalog.valid(turn.toolCalls.single().name, turn.toolCalls.single().arguments, false))
        assertEquals(listOf(1, 2, 3), requests)
        assertEquals(decision.fingerprint, cooperation.plannerFingerprint)
    }

    @Test fun `strict fixed ordinary planning failure cannot switch to selected decision model`() {
        val planner = source(1)
        val decision = source(2, true)
        val requests = mutableListOf<Int>()
        val cooperation = models(listOf(planner, decision), mapOf(planner.fingerprint to caps(tools = true),
            decision.fingerprint to caps(decision = true)), AgentRoutePolicy(setOf(1, 2), fixedIndex = 1, allowFallback = false),
            AgentHttpTransport { selected, _, _, _ ->
                requests += selected.index
                throw AgentModelException(AgentModelException.Reason.NETWORK)
            })
        try {
            cooperation.next(AgentConversation(AgentToolCatalog.SYSTEM, goal))
            fail("Expected the fixed source failure")
        } catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.NETWORK, error.reason) }
        assertEquals(listOf(1), requests)
    }
}
