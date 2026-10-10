package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 无限预算仍拒绝短循环；只有真实候选、页面、截图或详情证据变化才重置停滞计数。 */
internal class AgentProgressGuard(private val maximumStagnant: Int = 8) {
    private val evidence = linkedSetOf<String>()
    private var stagnant = 0

    init { require(maximumStagnant in 1..64) }

    fun observe(call: AgentModelToolCall, response: JSONObject): Boolean {
        if (!response.optBoolean("ok") || call.name == "open_video" ||
            call.name in AgentToolCatalog.uiActions && response.optJSONObject("data")?.optBoolean("observation_after_action") != true) return stagnate()
        val data = response.optJSONObject("data") ?: return stagnate()
        val facts = if (call.name == "search_videos") searchFacts(data) ?: return stagnate() else {
            if (data.length() == 0) return stagnate()
            data
        }
        // 不把模型换查询词/游标/请求 ID 当成进展；搜索只比较宿主实际返回的候选内容。
        val ui = facts.optString("backend") == "accessibility"
        val signature = stableDigest(JSONObject().put("operation", if (ui) "ui_state" else call.name).put("facts", facts))
        if (evidence.add(signature)) {
            stagnant = 0
            while (evidence.size > MAX_EVIDENCE) evidence.remove(evidence.first())
        } else return stagnate()
        return stagnant < maximumStagnant
    }

    private fun stagnate(): Boolean {
        stagnant = minOf(maximumStagnant, stagnant + 1)
        return stagnant < maximumStagnant
    }

    private fun searchFacts(data: JSONObject): JSONObject? {
        val videos = data.optJSONArray("videos") ?: return null
        val facts = linkedMapOf<String, JSONObject>()
        for (index in 0 until minOf(videos.length(), 256)) {
            val video = videos.optJSONObject(index) ?: continue
            val id = (video.opt("video_id") as? String ?: video.opt("id") as? String)
                ?.takeIf { it.isNotBlank() && it.length <= 128 } ?: continue
            facts[id] = JSONObject().put("video_id", id).apply {
                for (key in listOf("title", "author", "author_uid", "duration", "duration_seconds", "official_source", "verification")) {
                    if (video.has(key)) put(key, video.opt(key))
                }
            }
        }
        if (facts.isEmpty()) return null
        return JSONObject().put("videos", JSONArray(facts.toSortedMap().values.toList()))
    }

    fun clear() { evidence.clear(); stagnant = 0 }

    companion object {
        const val MAX_EVIDENCE = 128
        private val VOLATILE_KEYS = setOf("image_data_url", "capture_elapsed", "observed_at_elapsed", "cache_hit",
            "snapshot_id", "offset", "next_offset", "action", "observation_after_action", "verification_required",
            "deadline_elapsed", "lease_until", "step", "observations", "sequence", "source_index", "tool_call_id", "progress_digest",
            "decision_review", "decision_review_status", "decision_review_is_unverified", "visual_assessment", "visual_status")

        /** 仅用于缓存与停滞观测，不用于授权；规范化字段顺序，忽略执行时间和图片原文。 */
        fun stableDigest(value: JSONObject): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(text: String) { digest.update(text.toByteArray(Charsets.UTF_8)) }
            fun visit(item: Any?, depth: Int) {
                if (depth > 24) { add("null"); return }
                when (item) {
                    is JSONObject -> {
                        add("{")
                        item.keys().asSequence().filter { it !in VOLATILE_KEYS }.sorted().forEach { key ->
                            add(JSONObject.quote(key)); add(":"); visit(item.opt(key), depth + 1); add(",")
                        }
                        add("}")
                    }
                    is JSONArray -> {
                        add("[")
                        for (index in 0 until minOf(item.length(), 256)) { visit(item.opt(index), depth + 1); add(",") }
                        add("]")
                    }
                    is String -> add(JSONObject.quote(if (item.contains("data:image/", true)) "[image omitted]" else item.take(8_192)))
                    is Number -> add(JSONObject.numberToString(item))
                    is Boolean -> add(item.toString())
                    else -> add("null")
                }
            }
            visit(value, 0)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
