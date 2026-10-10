package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelTurn
import org.json.JSONArray
import org.json.JSONObject

/** 仅裁剪完整工具轮；原始目标不变，历史事实是有界的不可信数据，不承担导航授权。 */
internal class AgentConversation(private val system: String, private val goal: String) {
    private data class Round(val assistant: JSONObject, val tool: JSONObject, val evidence: JSONObject, val source: String,
                             val encodedChars: Int)
    private data class Evidence(val value: JSONObject, val key: String, val encodedChars: Int)
    private val rounds = ArrayDeque<Round>()
    private val evidence = ArrayDeque<Evidence>()
    private val callIds = linkedSetOf<String>()
    private val baseChars = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", goal)).toString().length
    private val historyShellChars = JSONObject().put("role", "user")
        .put("content", AgentContextBudget.HISTORY_NOTICE + "\n[]").toString().length
    private var roundChars = 0
    private var evidenceChars = 0

    init { require(system.length <= 16_384 && goal.length <= 2_000) }

    fun acceptCallId(id: String): Boolean {
        if (id.length !in 1..128 || id.any { it.isWhitespace() || it.isISOControl() } || id in callIds) return false
        callIds += id
        while (callIds.size > MAX_CALL_IDS) callIds.remove(callIds.first())
        return true
    }

    fun append(turn: AgentModelTurn, response: JSONObject, sourceFingerprint: String = "") {
        require(turn.toolCalls.size == 1)
        val call = turn.toolCalls.single()
        val assistant = JSONObject(turn.message.toString())
        require(assistant.optString("role") == "assistant" &&
            assistant.optJSONArray("tool_calls")?.let { it.length() == 1 && it.optJSONObject(0)?.optString("id") == call.id } == true)
        if (assistant.optString("content").length > 2_000) assistant.put("content", assistant.getString("content").take(2_000))
        if (assistant.optString("reasoning_content").length > 16_384) assistant.remove("reasoning_content")
        var sanitized = sanitize(response, 0) as JSONObject
        if (sanitized.toString().length > MAX_RESULT_CHARS) {
            // 单份工具正文也必须低于模型消息上限；保留可核对的身份和错误，不截断 JSON。
            sanitized = AgentContextBudget.project(sanitized).put("truncated", true)
        }
        val tool = JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", sanitized.toString())
        if (sanitized.optJSONObject("data")?.optString("backend") == "accessibility" &&
            sanitized.optJSONObject("data")?.has("snapshot_id") == true) compactOldUi()
        val note = AgentContextBudget.evidence(call, sanitized)
        val round = Round(assistant, tool, note, sourceFingerprint, assistant.toString().length + tool.toString().length + 2)
        rounds.addLast(round)
        roundChars += round.encodedChars
        while (rounds.size > MAX_ROUNDS) {
            archive(removeRound().evidence)
        }
        // 优先释放更旧摘要，保留最近的完整工具轮，尤其是最后一轮当前页面事实。
        while (encodedChars() > AgentContextBudget.TARGET_CHARS && evidence.isNotEmpty()) removeEvidence()
        while (encodedChars() > AgentContextBudget.TARGET_CHARS && rounds.size > 1) removeRound()
        if (encodedChars() > MAX_CONTEXT_CHARS) rounds.lastOrNull()?.let { last ->
            if (last.assistant.remove("reasoning_content") != null) {
                val revised = last.assistant.toString().length + last.tool.toString().length + 2
                rounds.removeLast()
                rounds.addLast(last.copy(encodedChars = revised))
                roundChars += revised - last.encodedChars
            }
        }
    }

    fun messages(): JSONArray = build(null)
    fun forSource(fingerprint: String): JSONArray = build(fingerprint)
    fun clear() { rounds.clear(); evidence.clear(); callIds.clear(); roundChars = 0; evidenceChars = 0 }

    private fun removeRound(): Round = rounds.removeFirst().also { roundChars -= it.encodedChars }

    /** 只有最新完整快照可以选动作；历史页面保留简短文字证据，不携带可复用的控件身份。 */
    private fun compactOldUi() {
        val revised = rounds.map { round ->
            val response = runCatching { JSONObject(round.tool.getString("content")) }.getOrNull()
            val data = response?.optJSONObject("data")
            if (data?.optString("backend") != "accessibility" || !data.has("snapshot_id")) round else {
                val nodes = data.optJSONArray("nodes") ?: JSONArray()
                val labels = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
                    .filter { !it.optBoolean("protected") }.map { it.optString("label") }
                    .filter { it.isNotBlank() }.distinct()
                val history = JSONObject().put("backend", "accessibility").put("historical", true)
                    .put("complete", data.optBoolean("complete")).put("visible_labels", JSONArray(labels))
                    .put("instructions", "历史界面不可用于当前操作；请使用最新快照。")
                for (key in listOf("action", "observation_after_action", "verification_required", "observation_error", "offset", "total_nodes", "next_offset")) {
                    if (data.has(key)) history.put(key, data.opt(key))
                }
                response.put("data", history)
                val oldTool = JSONObject(round.tool.toString()).put("content", response.toString())
                round.copy(tool = oldTool, encodedChars = round.assistant.toString().length + oldTool.toString().length + 2)
            }
        }
        rounds.clear(); revised.forEach(rounds::addLast)
        roundChars = revised.sumOf { it.encodedChars }
    }
    private fun removeEvidence() { evidenceChars -= evidence.removeFirst().encodedChars }

    private fun archive(note: JSONObject) {
        val key = AgentContextBudget.key(note)
        val duplicate = evidence.firstOrNull { it.key == key }
        if (duplicate != null) { evidence.remove(duplicate); evidenceChars -= duplicate.encodedChars }
        // 内容在 user 字符串中再次编码，计数包括转义，避免只按未经转义的 JSON 长度估算预算。
        val encoded = JSONObject.quote(note.toString()).length - 2
        evidence.addLast(Evidence(note, key, encoded))
        evidenceChars += encoded
        while (evidence.size > MAX_EVIDENCE) removeEvidence()
    }

    private fun encodedChars(): Int = baseChars + roundChars + if (evidence.isEmpty()) 0 else
        historyShellChars + 1 + evidenceChars + evidence.size - 1

    private fun build(source: String?, keepReasoning: Boolean = false): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", goal))
        .apply {
            if (evidence.isNotEmpty()) put(JSONObject().put("role", "user").put("content",
                AgentContextBudget.HISTORY_NOTICE + "\n" + JSONArray(evidence.map { it.value }).toString()))
            rounds.forEach { round ->
                val assistant = JSONObject(round.assistant.toString())
                if (!keepReasoning && (source == null || source != round.source)) assistant.remove("reasoning_content")
                put(assistant).put(JSONObject(round.tool.toString()))
            }
        }

    /** 图片永不进入规划历史；字段、文本、数组和深度均有界，避免单份回复占满全部窗口。 */
    private fun sanitize(value: Any?, depth: Int): Any = when {
        depth > 7 -> JSONObject.NULL
        value is JSONObject -> JSONObject().apply {
            val priority = listOf("ok", "error", "data", "video_id", "query", "videos", "next_cursor", "page", "source",
                "title", "author", "author_uid", "duration", "verification", "official_source", "observed_at_elapsed", "cache_hit")
            (priority.asSequence().filter(value::has) + value.keys().asSequence()).distinct()
                .filter { it != "image_data_url" }.take(40).forEach { key ->
                put(key.take(128), sanitize(value.opt(key), depth + 1))
            }
        }
        value is JSONArray -> JSONArray().apply {
            val uiNodes = value.optJSONObject(0)?.has("node_id") == true
            val limit = if (uiNodes) 96 else if (depth >= 4) 6 else 20
            (0 until minOf(value.length(), limit)).forEach { put(sanitize(value.opt(it), depth + 1)) }
        }
        value is String -> if (value.contains("data:image/", true)) "[image omitted]" else value.take(if (depth >= 5) 300 else 4_000)
        value is Number || value is Boolean -> value
        else -> JSONObject.NULL
    }

    companion object {
        const val MAX_ROUNDS = 6
        const val MAX_EVIDENCE = 8
        const val MAX_CALL_IDS = 512
        const val MAX_CONTEXT_CHARS = 120_000
        const val MAX_RESULT_CHARS = 32_768
    }
}
