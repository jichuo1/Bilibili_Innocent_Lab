package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentSourceProtocol
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticBackend

/**
 * AI Insight 的独立服务配置：与智能判定的「来源 1–8」彻底分开。
 *
 * 只解析出**一个**对话来源，专用配置为空或非法时返回 null，由卡片给出明确引导，
 * 不自动继承、也不回退到判定来源表（避免昂贵模型被四个自动过滤面重复调用）。
 * 宿主侧读 hook_config 下发快照、设置页读模块 prefs，两边都把原始字符串交给 [resolve]，
 * 因此这里不依赖任何 Android / 配置源类型，便于单测。
 */
internal object InsightConfig {
    const val PROTOCOL_OPENAI = "openai"
    const val PROTOCOL_ANTHROPIC = "anthropic"
    val IDS: Set<String> = linkedSetOf(PROTOCOL_OPENAI, PROTOCOL_ANTHROPIC)
    const val DEFAULT_PROTOCOL = PROTOCOL_OPENAI

    // 生成调参（预算/时限）作为签名下发的运行时键，不进备份：避免异常预算被恢复到别的设备。
    // 范围与 AgentModelClient(256..8192) 及 AgentHttpsTransport.MAX_TIMEOUT_MS 对齐。
    const val DEFAULT_OUTPUT_BUDGET = 8192
    val OUTPUT_BUDGET_RANGE: IntRange = 256..8192
    const val DEFAULT_TIMEOUT_MS = 180_000
    val TIMEOUT_RANGE: IntRange = 30_000..300_000

    /**
     * 「自动补全 /v1」开关的默认值：开启，即沿用开关出现前的补全规则，已配置的用户升级后行为不变。
     * 关闭后改为按所填端点逐字使用，只补协议最后一段，由用户自己写 /v1 或服务商指定的版本路径。
     */
    const val DEFAULT_AUTO_V1 = true

    /**
     * 把四项专用配置解析成单一对话来源。
     *
     * 两种补全规则由 [autoV1] 选择；无论哪种，已经写到完整请求地址（以 `/chat/completions`
     * 或 `/messages` 结尾）的都原样使用、不再二次拼接，带 query 时路径段插到 query 之前。
     * - 开启（默认，旧规则）：只填域名时补 `/v1`；OpenAI 其余路径只补 `/chat/completions`，
     *   Anthropic 路径不以 `/v1` 结尾时连 `/v1` 一起补成 `/v1/messages`。
     * - 关闭：**不自动追加 /v1**，版本段由用户提供的端点自带，只按协议补最后一段。
     *
     * 纯域名由 [SemanticBackend.parseUrl] 补 https。http 地址会被 [AgentModelSource] 的
     * HTTPS 约束拒掉，返回 null 由 UI 明确提示而非静默失效。
     */
    fun resolve(protocol: String, baseUrl: String, apiKey: String, model: String, autoV1: Boolean): AgentModelSource? {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        val (normalized, url) = SemanticBackend.parseUrl(baseUrl) ?: return null
        val agentProtocol = if (protocol == PROTOCOL_ANTHROPIC) AgentSourceProtocol.ANTHROPIC
            else AgentSourceProtocol.CHAT
        val full = completeEndpoint(agentProtocol, normalized, url.path.trimEnd('/'), autoV1)
        return AgentModelSource.from(1, full, apiKey.trim(), model.trim(), agentProtocol)
    }

    /**
     * 补成以协议段结尾的完整地址。[AgentModelSource] 见到已以 `/messages`、`/chat/completions`
     * 结尾的路径不会再改，所以 /v1 的取舍只在这里决定，两种规则互不影响。
     */
    private fun completeEndpoint(protocol: AgentSourceProtocol, normalized: String, path: String, autoV1: Boolean): String {
        val anthropic = protocol == AgentSourceProtocol.ANTHROPIC
        val segment = if (anthropic) "messages" else "chat/completions"
        if (path.endsWith("/$segment")) return normalized
        val appended = when {
            !autoV1 -> segment
            path.isEmpty() -> "v1/$segment"
            anthropic && !path.endsWith("/v1") -> "v1/$segment"
            else -> segment
        }
        return SemanticBackend.withPathSegment(normalized, appended)
    }
}
