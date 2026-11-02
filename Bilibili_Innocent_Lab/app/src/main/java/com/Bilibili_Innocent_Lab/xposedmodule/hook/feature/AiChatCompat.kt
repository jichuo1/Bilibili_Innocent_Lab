package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * 推理（深度思考）强度档位，按"越省越先试"排列。
 *
 * 判定这类任务不需要长思考：能关就关；关不掉就用该模型支持的最低强度（哪怕慢一点）；都不认才用默认。
 */
internal enum class ReasoningLevel { OFF, MINIMAL, LOW, DEFAULT }

/**
 * OpenAI 兼容对话接口的通用兼容层：**与具体业务无关**，语义判定之外、以后依赖 AI 的功能也用它。
 *
 * 提供三样东西：
 * 1. 请求写法阶梯 [LADDER]：严格参数 × 思考档位 × 是否带 system 角色，按成功率与省钱程度排序；
 *    调用方遇到 400/413/422 或读不出结果就换下一级，探到后按来源记住（见 [SemanticJudge] 的已探明表）。
 * 2. 按站点的思考控制参数 [reasoningParams]：各家关闭 / 降低思考的写法不一样（2026-09-30 各家文档 + 真机）。
 * 3. 回复解析工具：[contentOf] 取正文（兼容 Workers AI 原生写法），[findJsonObject] 从带思考文字、代码块的正文里找 JSON。
 */
internal object AiChatCompat {

    /** 一级请求写法。[strict]：带 temperature=0 / max_tokens / response_format=json_object。 */
    data class Variant(val strict: Boolean, val reasoning: ReasoningLevel, val systemRole: Boolean = true) {
        val label: String
            get() = buildString {
                append(if (strict) "strict" else "plain")
                when (reasoning) {
                    ReasoningLevel.OFF -> append("+nothink")
                    ReasoningLevel.MINIMAL -> append("+min-think")
                    ReasoningLevel.LOW -> append("+low-think")
                    ReasoningLevel.DEFAULT -> Unit
                }
                if (!systemRole) append("+no-system")
            }
    }

    /**
     * 写法阶梯。思考越少越靠前；带思考的档位不带 max_tokens（思考会占用输出额度，截断就读不出结果）。
     * 默认思考排在最后，只给连"低"都不认的模型（比如完全不支持推理参数的非推理模型——它们在前面几级被拒后落到这里，
     * 此时 strict 参数仍然可用）。
     */
    val LADDER: List<Variant> = listOf(
        Variant(strict = true, reasoning = ReasoningLevel.OFF),
        Variant(strict = false, reasoning = ReasoningLevel.OFF),
        Variant(strict = false, reasoning = ReasoningLevel.MINIMAL),
        Variant(strict = false, reasoning = ReasoningLevel.LOW),
        Variant(strict = true, reasoning = ReasoningLevel.DEFAULT),
        Variant(strict = false, reasoning = ReasoningLevel.DEFAULT),
        Variant(strict = false, reasoning = ReasoningLevel.DEFAULT, systemRole = false)
    )

    fun variant(index: Int): Variant = LADDER[index.coerceIn(0, LADDER.lastIndex)]

    /**
     * 这一级在该站点上是否有意义：某档思考在该站点没有专门参数时，请求会和"默认"那级一模一样，跳过以免重复发。
     */
    fun applies(index: Int, endpoint: String): Boolean {
        val level = variant(index).reasoning
        return level == ReasoningLevel.DEFAULT || reasoningParams(endpoint, level) != null
    }

    /**
     * 某站点某档思考要加的参数；null = 这一档在该站点没有写法。
     *
     * - 能**关闭**的站点（各家文档）：DeepSeek / 智谱 / 火山方舟 `thinking.type=disabled`；阿里百炼 / 硅基流动
     *   `enable_thinking=false`；Gemini `reasoning_effort=none`（仅 2.5 系列）；OpenRouter `reasoning.effort=none`。
     *   其中智谱、火山、百炼、硅基流动只有开关没有强度：关不掉时直接落到默认。
     * - **降低强度**：OpenRouter 用 `reasoning.effort`；其余（DeepSeek、Gemini、各类聚合转发）用 OpenAI 标准的
     *   `reasoning_effort`。DeepSeek 只认 none/low/high/max，没有 minimal。
     * - 未知站点也按 OpenAI 标准试 `reasoning_effort` 的 none → minimal → low：2026-09-30 真机，基元律动转发的
     *   GLM-5.3-FlashX 拒收 none 与 minimal（"只支持 low、high、max"），`low` 时思考 token 从 90 降到 0、2 条 1.7 s。
     *   不认这个参数的非推理模型会被拒，落到默认那级，已探明后不再重试。
     */
    fun reasoningParams(endpoint: String, level: ReasoningLevel): JSONObject? {
        if (level == ReasoningLevel.DEFAULT) return null
        val host = hostOf(endpoint)
        fun disabledThinking() = JSONObject().put("thinking", JSONObject().put("type", "disabled"))
        fun effort(value: String) = JSONObject().put("reasoning_effort", value)
        return when {
            host == "openrouter.ai" -> when (level) {
                ReasoningLevel.OFF -> JSONObject().put("reasoning", JSONObject().put("effort", "none"))
                ReasoningLevel.MINIMAL -> JSONObject().put("reasoning", JSONObject().put("effort", "minimal"))
                else -> JSONObject().put("reasoning", JSONObject().put("effort", "low"))
            }
            // River 的 DeepSeek-V4.1-Flash-1017 已用长材料实测；enable_thinking=false 无效。
            host == "api.deepseek.com" || host == "api.river.ai" -> when (level) {
                ReasoningLevel.OFF -> disabledThinking()
                ReasoningLevel.MINIMAL -> null
                else -> effort("low")
            }
            host == "open.bigmodel.cn" || host == "api.z.ai" || host.endsWith(".volces.com") ->
                if (level == ReasoningLevel.OFF) disabledThinking() else null
            host.endsWith(".aliyuncs.com") || host == "api.siliconflow.cn" || host == "api.siliconflow.com" ->
                if (level == ReasoningLevel.OFF) JSONObject().put("enable_thinking", false) else null
            else -> when (level) {
                ReasoningLevel.OFF -> effort("none")
                ReasoningLevel.MINIMAL -> effort("minimal")
                else -> effort("low")
            }
        }
    }

    /**
     * 组一个对话请求体。[maxTokens] 只在 strict 级别使用；带思考的级别不设上限，避免思考把正文挤掉。
     */
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
        reasoningParams(endpoint, variant.reasoning)?.let { extra -> extra.keys().forEach { body.put(it, extra.get(it)) } }
        if (variant.strict) {
            body.put("temperature", 0)
            body.put("max_tokens", maxTokens)
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        return body
    }

    /**
     * 回复正文：OpenAI 写法 `choices[0].message.content`；Workers AI 原生写法 `result.response`
     * （JSON 模式下可能已是对象）。结构认不出返回 null。
     */
    fun contentOf(payload: String): String? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.let { message ->
            return message.opt("content")?.takeIf { it != JSONObject.NULL }?.toString()
        }
        val response = root.optJSONObject("result")?.opt("response") ?: root.opt("response")
        return response?.takeIf { it != JSONObject.NULL }?.toString()
    }

    /** 回复是否因长度上限被截断（`finish_reason=length`）。 */
    fun truncated(payload: String): Boolean = runCatching {
        JSONObject(payload).getJSONArray("choices").getJSONObject(0).optString("finish_reason") == "length"
    }.getOrDefault(false)

    /**
     * 从模型正文里找出第一个满足 [accept] 的 JSON 对象：先剥掉 `<think>…</think>`，优先取 ``` 代码块，
     * 再依次以每个 `{` 为起点尝试（思考文字里出现的 `{` 不会让整体失败），最多试 [MAX_JSON_STARTS] 个。
     */
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

    private fun hostOf(endpoint: String): String = runCatching { URL(endpoint).host.lowercase() }.getOrNull().orEmpty()

    private const val MAX_JSON_STARTS = 8
    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
}
