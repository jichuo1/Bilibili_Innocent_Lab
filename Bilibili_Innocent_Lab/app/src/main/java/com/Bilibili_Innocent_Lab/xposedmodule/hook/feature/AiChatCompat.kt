package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.bilab.core.agent.protocol.CoreAiChatCompat
import com.bilab.core.agent.protocol.CoreReasoningLevel
import org.json.JSONObject

/**
 * 推理（深度思考）强度档位，按"越省越先试"排列。
 *
 * 判定这类任务不需要长思考：能关就关；关不掉就用该模型支持的最低强度（哪怕慢一点）；都不认才用默认。
 */
internal enum class ReasoningLevel { OFF, MINIMAL, LOW, DEFAULT }

private fun ReasoningLevel.toCore(): CoreReasoningLevel = when (this) {
    ReasoningLevel.OFF -> CoreReasoningLevel.OFF
    ReasoningLevel.MINIMAL -> CoreReasoningLevel.MINIMAL
    ReasoningLevel.LOW -> CoreReasoningLevel.LOW
    ReasoningLevel.DEFAULT -> CoreReasoningLevel.DEFAULT
}

private fun CoreReasoningLevel.toLegacy(): ReasoningLevel = when (this) {
    CoreReasoningLevel.OFF -> ReasoningLevel.OFF
    CoreReasoningLevel.MINIMAL -> ReasoningLevel.MINIMAL
    CoreReasoningLevel.LOW -> ReasoningLevel.LOW
    CoreReasoningLevel.DEFAULT -> ReasoningLevel.DEFAULT
}

/**
 * OpenAI 兼容对话接口的通用兼容层：**与具体业务无关**，语义判定之外、以后依赖 AI 的功能也用它。
 *
 * 纯 JVM 实现已下沉至 `:core-agent-protocol` 的 [CoreAiChatCompat]，此处保留既有门面与签名委托调用。
 */
internal object AiChatCompat {

    /** 一级请求写法。[strict]：带 temperature=0 / max_tokens / response_format=json_object。 */
    data class Variant(val strict: Boolean, val reasoning: ReasoningLevel, val systemRole: Boolean = true) {
        val label: String
            get() = CoreAiChatCompat.Variant(strict, reasoning.toCore(), systemRole).label
    }

    val LADDER: List<Variant> = CoreAiChatCompat.LADDER.map {
        Variant(strict = it.strict, reasoning = it.reasoning.toLegacy(), systemRole = it.systemRole)
    }

    fun variant(index: Int): Variant =
        CoreAiChatCompat.variant(index).let { Variant(it.strict, it.reasoning.toLegacy(), it.systemRole) }

    fun applies(index: Int, endpoint: String): Boolean = CoreAiChatCompat.applies(index, endpoint)

    fun reasoningParams(endpoint: String, level: ReasoningLevel): JSONObject? =
        CoreAiChatCompat.reasoningParams(endpoint, level.toCore())

    fun buildRequest(
        model: String,
        system: String,
        user: String,
        variantIndex: Int,
        endpoint: String,
        maxTokens: Int
    ): JSONObject = CoreAiChatCompat.buildRequest(model, system, user, variantIndex, endpoint, maxTokens)

    fun contentOf(payload: String): String? = CoreAiChatCompat.contentOf(payload)

    fun truncated(payload: String): Boolean = CoreAiChatCompat.truncated(payload)

    fun findJsonObject(content: String, accept: (JSONObject) -> Boolean): JSONObject? =
        CoreAiChatCompat.findJsonObject(content, accept)
}
