package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentHttpTransport
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentHttpsTransport
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelClient
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelException
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentSourceProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置页「测试连接」：走与 AI Insight 生成**完全相同**的请求入口
 * [AgentModelClient.generateStructuredStreaming]（协议适配、鉴权头、端点补全、json_schema、
 * SSE 流式及参数自动回退），只把 prompt 换成一条极短探针。因此「通过」等价于宿主里 AI Insight
 * 能用，而不是像判定探针那样只测了另一套请求。会真实消耗一次请求，不写缓存或文件。
 *
 * 在模块 App 进程的后台线程、调用方线程上同步执行；[transport] 仅供单测注入替身，不联网。
 */
internal object InsightConnectivity {
    data class Result(val ok: Boolean, val outcome: String, val elapsedMs: Long, val variant: String)

    const val TIMEOUT_MS = 20_000

    fun probe(
        source: AgentModelSource,
        transport: AgentHttpTransport? = null,
        clock: () -> Long = System::currentTimeMillis
    ): Result {
        val client = AgentModelClient(
            transport = transport ?: AgentHttpsTransport,
            outputTokenBudget = 256,
            allowReasoningParams = false
        )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Reply with one short JSON object and nothing else."))
            .put(JSONObject().put("role", "user").put("content", "Return {\"ok\":true}."))
        val schema = JSONObject()
            .put("name", "insight_connectivity")
            .put("strict", true)
            .put("schema", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("ok", JSONObject().put("type", "boolean")))
                .put("required", JSONArray().put("ok"))
                .put("additionalProperties", false))
        val variant = when (source.protocol) {
            AgentSourceProtocol.CHAT -> "OpenAI"
            AgentSourceProtocol.ANTHROPIC -> "Anthropic"
            else -> ""
        }
        val started = clock()
        return try {
            // 与 InsightRepository 同一入口：不带工具、走结构化流式，沿用流式/格式参数的自动回退。
            client.generateStructuredStreaming(source, messages, schema, TIMEOUT_MS, { false }) {}
            Result(true, "ok", clock() - started, variant)
        } catch (e: AgentModelException) {
            Result(false, classify(e), clock() - started, variant)
        } catch (e: Exception) {
            Result(false, "network", clock() - started, variant)
        }
    }

    /** 统一成 semantic_test_* 文案约定的 outcome：http-<status> / network / parse / deadline / 其它。 */
    private fun classify(e: AgentModelException): String {
        val status = e.status
        return when {
            e.reason == AgentModelException.Reason.NETWORK -> "network"
            e.reason == AgentModelException.Reason.TIMEOUT -> "deadline"
            e.reason == AgentModelException.Reason.INVALID_RESPONSE ||
                e.reason == AgentModelException.Reason.OUTPUT_LIMIT -> "parse"
            status != null -> "http-$status"
            else -> e.reason.name.lowercase(java.util.Locale.ROOT)
        }
    }
}
