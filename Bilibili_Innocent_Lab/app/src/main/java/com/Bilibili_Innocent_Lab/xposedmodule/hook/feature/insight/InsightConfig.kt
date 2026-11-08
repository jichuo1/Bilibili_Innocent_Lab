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

    /**
     * 把四项专用配置解析成单一对话来源。
     *
     * 地址可为纯域名 / 域名+路径（按 https 补全，复用 [SemanticBackend.parseUrl]），
     * 最终请求路径（/chat/completions 或 /v1/messages）由 [AgentModelSource] 按协议决定，只补一次。
     * http 地址会被 [AgentModelSource] 的 HTTPS 约束拒掉，返回 null 由 UI 明确提示而非静默失效。
     */
    fun resolve(protocol: String, baseUrl: String, apiKey: String, model: String): AgentModelSource? {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        val normalized = SemanticBackend.parseUrl(baseUrl)?.first ?: return null
        val agentProtocol = if (protocol == PROTOCOL_ANTHROPIC) AgentSourceProtocol.ANTHROPIC
            else AgentSourceProtocol.CHAT
        return AgentModelSource.from(1, normalized, apiKey.trim(), model.trim(), agentProtocol)
    }
}
