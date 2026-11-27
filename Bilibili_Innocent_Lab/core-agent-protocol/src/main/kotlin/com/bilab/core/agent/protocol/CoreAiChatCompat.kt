package com.bilab.core.agent.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale

/**
 * Reasoning effort levels ordered from lowest token/latency cost to default model behavior.
 */
enum class CoreReasoningLevel { OFF, MINIMAL, LOW, DEFAULT }

/**
 * Pure-JVM compatibility layer for OpenAI-compatible chat completions and Workers AI responses.
 */
object CoreAiChatCompat {

    /** Single request variant in [LADDER]. */
    data class Variant(
        val strict: Boolean,
        val reasoning: CoreReasoningLevel,
        val systemRole: Boolean = true
    ) {
        val label: String
            get() = buildString {
                append(if (strict) "strict" else "plain")
                when (reasoning) {
                    CoreReasoningLevel.OFF -> append("+nothink")
                    CoreReasoningLevel.MINIMAL -> append("+min-think")
                    CoreReasoningLevel.LOW -> append("+low-think")
                    CoreReasoningLevel.DEFAULT -> Unit
                }
                if (!systemRole) append("+no-system")
            }
    }

    /** Fallback ladder ordered from strict/no-thinking to plain/no-system-role. */
    val LADDER: List<Variant> = listOf(
        Variant(strict = true, reasoning = CoreReasoningLevel.OFF),
        Variant(strict = false, reasoning = CoreReasoningLevel.OFF),
        Variant(strict = false, reasoning = CoreReasoningLevel.MINIMAL),
        Variant(strict = false, reasoning = CoreReasoningLevel.LOW),
        Variant(strict = true, reasoning = CoreReasoningLevel.DEFAULT),
        Variant(strict = false, reasoning = CoreReasoningLevel.DEFAULT),
        Variant(strict = false, reasoning = CoreReasoningLevel.DEFAULT, systemRole = false)
    )

    fun variant(index: Int): Variant = LADDER[index.coerceIn(0, LADDER.lastIndex)]

    fun applies(index: Int, endpoint: String): Boolean {
        val level = variant(index).reasoning
        return level == CoreReasoningLevel.DEFAULT || reasoningParams(endpoint, level) != null
    }

    fun reasoningParams(endpoint: String, level: CoreReasoningLevel): JSONObject? {
        if (level == CoreReasoningLevel.DEFAULT) return null
        val host = hostOf(endpoint)
        fun disabledThinking() = JSONObject().put("thinking", JSONObject().put("type", "disabled"))
        fun effort(value: String) = JSONObject().put("reasoning_effort", value)
        return when {
            host == "openrouter.ai" -> when (level) {
                CoreReasoningLevel.OFF -> JSONObject().put("reasoning", JSONObject().put("effort", "none"))
                CoreReasoningLevel.MINIMAL -> JSONObject().put("reasoning", JSONObject().put("effort", "minimal"))
                else -> JSONObject().put("reasoning", JSONObject().put("effort", "low"))
            }
            host == "api.deepseek.com" || host == "api.river.ai" -> when (level) {
                CoreReasoningLevel.OFF -> disabledThinking()
                CoreReasoningLevel.MINIMAL -> null
                else -> effort("low")
            }
            host == "open.bigmodel.cn" || host == "api.z.ai" || host.endsWith(".volces.com") ->
                if (level == CoreReasoningLevel.OFF) disabledThinking() else null
            host.endsWith(".aliyuncs.com") || host == "api.siliconflow.cn" || host == "api.siliconflow.com" ->
                if (level == CoreReasoningLevel.OFF) JSONObject().put("enable_thinking", false) else null
            else -> when (level) {
                CoreReasoningLevel.OFF -> effort("none")
                CoreReasoningLevel.MINIMAL -> effort("minimal")
                else -> effort("low")
            }
        }
    }

    fun buildRequest(
        model: String,
        system: String,
        user: String,
        variantIndex: Int,
        endpoint: String,
        maxTokens: Int
    ): JSONObject {
        val variant = variant(variantIndex)
        val messages = JSONArray()
        if (variant.systemRole) {
            messages.put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", "$system\n\n$user"))
        }
        val body = JSONObject().put("model", model).put("messages", messages)
        reasoningParams(endpoint, variant.reasoning)?.let { extra ->
            extra.keys().forEach { body.put(it, extra.get(it)) }
        }
        if (variant.strict) {
            body.put("temperature", 0)
            body.put("max_tokens", maxTokens)
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        return body
    }

    fun contentOf(payload: String): String? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.let { message ->
            return message.opt("content")?.takeIf { it != JSONObject.NULL }?.toString()
        }
        val response = root.optJSONObject("result")?.opt("response") ?: root.opt("response")
        return response?.takeIf { it != JSONObject.NULL }?.toString()
    }

    fun truncated(payload: String): Boolean = runCatching {
        JSONObject(payload).getJSONArray("choices").getJSONObject(0).optString("finish_reason") == "length"
    }.getOrDefault(false)

    fun findJsonObject(content: String, accept: (JSONObject) -> Boolean): JSONObject? {
        var text = content
        while (true) {
            val open = text.indexOf("<think>")
            if (open < 0) break
            val close = text.indexOf("</think>", open)
            text = if (close < 0) text.substring(0, open) else text.removeRange(open, close + "</think>".length)
        }
        val fence = FENCE.find(text)?.groupValues?.get(1)
        for (candidate in listOfNotNull(fence, text)) {
            val end = candidate.lastIndexOf('}')
            if (end < 0) continue
            var start = candidate.indexOf('{')
            var attempts = 0
            while (start in 0 until end && attempts < MAX_JSON_STARTS) {
                runCatching { JSONObject(candidate.substring(start, end + 1)) }.getOrNull()
                    ?.takeIf(accept)?.let { return it }
                start = candidate.indexOf('{', start + 1)
                attempts++
            }
        }
        return null
    }

    private fun hostOf(endpoint: String): String =
        runCatching { URL(endpoint).host.lowercase(Locale.ROOT) }.getOrNull().orEmpty()

    private const val MAX_JSON_STARTS = 8
    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
}
