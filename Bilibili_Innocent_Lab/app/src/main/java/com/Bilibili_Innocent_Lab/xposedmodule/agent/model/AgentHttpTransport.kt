package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import com.bilab.core.agent.protocol.LlmHttpException
import com.bilab.core.agent.protocol.LlmHttpRequest
import com.bilab.core.agent.protocol.LlmHttpTransport
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ThreadPoolExecutor

internal fun interface AgentHttpTransport {
    fun post(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean): String
    /** 默认实现保留注入替身与不支持增量的传输；生产 HTTPS 实现读取 SSE。 */
    fun postStreaming(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
        onDelta: (String) -> Unit): String = post(source, body, timeoutMs, cancelled)
}

/** 全程 HTTPS；不跟随重定向；DNS/写入阻塞也不得无限占住任务线程。 */
internal object AgentHttpsTransport : AgentHttpTransport {
    private val workers: ThreadPoolExecutor = LlmHttpTransport.sharedWorkers

    override fun post(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean): String =
        postWithConnection(source, body, timeoutMs, cancelled) { it.openConnection() as HttpURLConnection }

    override fun postStreaming(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
        onDelta: (String) -> Unit): String = postStreamingWithConnection(source, body, timeoutMs, cancelled, onDelta) {
            it.openConnection() as HttpURLConnection
        }

    /** 连接工厂仅用于传输边界的 JVM 验证，生产调用固定为平台 HTTPS 实现。 */
    internal fun postWithConnection(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
        connect: (URL) -> HttpURLConnection): String = requestWithConnection(source, body, timeoutMs, cancelled, null, connect)

    internal fun postStreamingWithConnection(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
        onDelta: (String) -> Unit, connect: (URL) -> HttpURLConnection): String =
        requestWithConnection(source, body, timeoutMs, cancelled, onDelta, connect)

    private fun requestWithConnection(
        source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
        onDelta: ((String) -> Unit)?, connect: (URL) -> HttpURLConnection
    ): String {
        val headers = if (source.protocol == AgentSourceProtocol.ANTHROPIC) {
            mapOf("x-api-key" to source.apiKey, "anthropic-version" to "2023-06-01")
        } else {
            mapOf("Authorization" to "Bearer ${source.apiKey}")
        }
        val request = LlmHttpRequest(
            url = source.resolvedEndpoint,
            body = body,
            headers = headers,
            timeoutMs = timeoutMs,
            maxRequestBytes = MAX_REQUEST_BYTES,
            maxResponseBytes = MAX_RESPONSE_BYTES,
            maxStreamWireBytes = AgentChatStream.MAX_WIRE_BYTES,
            maxTimeoutMs = MAX_TIMEOUT_MS,
            errorBytesLimit = 16_384,
            acceptHeader = if (onDelta == null) "application/json" else "text/event-stream, application/json"
        )
        return try {
            LlmHttpTransport.executeWithConnection(
                request = request,
                cancelled = cancelled,
                onStream = if (onDelta != null) { input, streamAlive ->
                    AgentChatStream.read(input, {
                        try {
                            streamAlive()
                        } catch (e: LlmHttpException) {
                            throw mapException(e)
                        }
                    }, onDelta, source.protocol)
                } else null,
                connect = connect
            ).body
        } catch (e: LlmHttpException) {
            throw mapException(e)
        }
    }

    internal fun readBounded(input: InputStream, limit: Int, deadline: Long, cancelled: () -> Boolean): String = try {
        LlmHttpTransport.readBounded(input, limit, deadline, cancelled)
    } catch (e: LlmHttpException) {
        throw mapException(e)
    }

    internal fun checkAlive(deadline: Long, cancelled: () -> Boolean) {
        try {
            LlmHttpTransport.checkAlive(deadline, cancelled)
        } catch (e: LlmHttpException) {
            throw mapException(e)
        }
    }

    private fun mapException(e: LlmHttpException): AgentModelException {
        (e.cause as? AgentModelException)?.let { return it }
        return when (e.reason) {
            LlmHttpException.Reason.CANCELLED -> AgentModelException(AgentModelException.Reason.CANCELLED)
            LlmHttpException.Reason.TIMEOUT -> AgentModelException(AgentModelException.Reason.TIMEOUT)
            LlmHttpException.Reason.REDIRECT -> AgentModelException(AgentModelException.Reason.REDIRECT, e.status)
            LlmHttpException.Reason.TOO_LARGE -> AgentModelException(AgentModelException.Reason.TOO_LARGE)
            LlmHttpException.Reason.HTTP_STATUS -> httpFailure(e.status ?: 0, e.errorBody, e.retryAfterMs)
            LlmHttpException.Reason.NETWORK -> AgentModelException(AgentModelException.Reason.NETWORK)
        }
    }

    internal fun httpFailure(status: Int, body: String, retryAfterMs: Long?): AgentModelException {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val error = root?.optJSONObject("error")
        val code = error?.optString("code").orEmpty()
        val parameter = error?.optString("param").orEmpty()
        val parameterRejected = status in setOf(400, 422) && code in setOf("unsupported_parameter", "invalid_request_error")
        val optionalParameter = parameter.substringBefore('.')
        val decisionParameter = if (status in setOf(400, 422)) rejectedDecisionParameter(root, error) else null
        // 只认结构化拒绝，不能从任意错误正文推断能力或把正文展示给用户。
        val reason = when {
            status in setOf(400, 422) && code in setOf("unsupported_parameter", "unsupported_value", "unsupported_feature") &&
                parameter in setOf("response_format", "response_format.type") -> AgentModelException.Reason.RESPONSE_FORMAT_PARAMETER
            status in setOf(400, 422) && code in setOf("unsupported_parameter", "unsupported_value", "unsupported_feature", "invalid_request_error") &&
                parameter == "stream" -> AgentModelException.Reason.STREAM_PARAMETER
            parameterRejected &&
                parameter in setOf("max_tokens", "max_completion_tokens") -> AgentModelException.Reason.TOKEN_PARAMETER
            parameterRejected && optionalParameter in setOf("enable_thinking", "thinking", "reasoning") &&
                parameter in setOf("enable_thinking", "thinking", "thinking.type", "reasoning", "reasoning.effort") ->
                AgentModelException.Reason.OPTIONAL_PARAMETER
            status in setOf(400, 422) && code == "unsupported_feature" && parameter == "tools" ->
                AgentModelException.Reason.TOOLS_UNSUPPORTED
            status in setOf(400, 422) && code == "unsupported_feature" && parameter in setOf("image_url", "vision") ->
                AgentModelException.Reason.VISION_UNSUPPORTED
            decisionParameter != null -> AgentModelException.Reason.DECISION_PARAMETER
            else -> AgentModelException.Reason.HTTP
        }
        return AgentModelException(reason, status, retryAfterMs,
            when (reason) {
                AgentModelException.Reason.OPTIONAL_PARAMETER -> optionalParameter
                AgentModelException.Reason.TOKEN_PARAMETER -> parameter
                AgentModelException.Reason.DECISION_PARAMETER -> decisionParameter
                AgentModelException.Reason.RESPONSE_FORMAT_PARAMETER -> "response_format"
                else -> null
            })
    }

    /** 只输出固定字段名；认证、模型不存在和未知 400 均不能触发题型/状态回退。 */
    private fun rejectedDecisionParameter(root: JSONObject?, error: JSONObject?): String? {
        fun field(parts: List<String>): String? {
            val path = parts.filter(String::isNotBlank).dropWhile { it == "body" || it == "$" }
            return when (path.firstOrNull()) {
                "state" -> "state"
                "questions" -> when (path.lastOrNull()) {
                    "type" -> "type"
                    "criteria" -> "criteria"
                    else -> "questions"
                }
                "type", "criteria" -> path.singleOrNull()
                else -> null
            }
        }
        error?.optString("param")?.takeIf { it.isNotBlank() }?.let { value ->
            field(value.split(Regex("[.\\[\\]/]+")))?.let { return it }
        }
        root?.optJSONArray("detail")?.let { details ->
            for (index in 0 until details.length()) {
                val location = details.optJSONObject(index)?.optJSONArray("loc") ?: continue
                val parts = (0 until location.length()).mapNotNull { location.opt(it) as? String }
                field(parts)?.let { return it }
            }
        }
        // 已见过的 Respan 写法拒绝只有短 message；严格限制为结构错误，不从一般正文猜能力。
        val message = error?.optString("message").orEmpty().lowercase(java.util.Locale.ROOT)
        if (message.contains("state must be a string") || message.contains("state should be a string") ||
            message.contains("expected state to be a string")) return "state"
        if (message.contains("unsupported question type") || message.contains("unknown question type")) return "type"
        return null
    }

    const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 512 * 1024
    /** 长字幕总结的流式输出可能持续数分钟；放宽绝对上限，空闲断流由 SSE 解析器单独检测。 */
    const val MAX_TIMEOUT_MS = 300_000
}
