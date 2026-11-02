package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal fun interface AgentHttpTransport {
    fun post(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean): String
    /** 默认实现保留注入替身与不支持增量的传输；生产 HTTPS 实现读取 SSE。 */
    fun postStreaming(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
        onDelta: (String) -> Unit): String = post(source, body, timeoutMs, cancelled)
}

/** 全程 HTTPS；不跟随重定向；DNS/写入阻塞也不得无限占住任务线程。 */
internal object AgentHttpsTransport : AgentHttpTransport {
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(2)) { runnable ->
        Thread(runnable, "BIL-AgentHttp").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

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
        if (body.size > MAX_REQUEST_BYTES) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
        if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
        val connection = AtomicReference<HttpURLConnection?>()
        val deadline = System.nanoTime() + timeoutMs.coerceIn(1, MAX_TIMEOUT_MS) * 1_000_000L
        val task = try {
            workers.submit(Callable {
                // Future完成到worker重新等待之间仍占用线程，有限队列吸收快速重试而不误报网络失败。
                checkAlive(deadline, cancelled)
                val http = connect(URL(source.resolvedEndpoint))
                connection.set(http)
                try {
                    checkAlive(deadline, cancelled)
                    http.instanceFollowRedirects = false
                    http.useCaches = false
                    http.requestMethod = "POST"
                    http.connectTimeout = remaining(deadline)
                    http.readTimeout = remaining(deadline)
                    http.doOutput = true
                    http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    http.setRequestProperty("Accept", if (onDelta == null) "application/json" else "text/event-stream, application/json")
                    http.setRequestProperty("Authorization", "Bearer ${source.apiKey}")
                    http.setFixedLengthStreamingMode(body.size)
                    http.outputStream.use { it.write(body) }
                    checkAlive(deadline, cancelled)
                    val status = http.responseCode
                    if (status in 300..399) throw AgentModelException(AgentModelException.Reason.REDIRECT, status)
                    if (status !in 200..299) {
                        val retryAfter = http.getHeaderField("Retry-After")?.toLongOrNull()
                            ?.coerceIn(0, 300)?.times(1000)
                        val error = http.errorStream?.use { readBounded(it, 16_384, deadline, cancelled) }.orEmpty()
                        throw httpFailure(status, error, retryAfter)
                    }
                    val streaming = onDelta != null && http.contentType?.substringBefore(';')?.trim()
                        ?.equals("text/event-stream", ignoreCase = true) == true
                    if (http.contentLengthLong > if (streaming) AgentChatStream.MAX_WIRE_BYTES else MAX_RESPONSE_BYTES) {
                        throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
                    }
                    http.inputStream.use { input ->
                        if (streaming) AgentChatStream.read(input, { checkAlive(deadline, cancelled) }, checkNotNull(onDelta))
                        else readBounded(input, MAX_RESPONSE_BYTES, deadline, cancelled)
                    }
                } finally {
                    http.disconnect()
                    connection.compareAndSet(http, null)
                }
            })
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            throw AgentModelException(AgentModelException.Reason.NETWORK)
        }
        try {
            while (true) {
                checkAlive(deadline, cancelled)
                try {
                    return task.get(minOf(100, remaining(deadline)).toLong(), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // 有界等待同时响应用户取消；真实 Socket 超时只是第二道防线。
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    if (cause is AgentModelException) throw cause
                    if (cause is java.net.SocketTimeoutException) throw AgentModelException(AgentModelException.Reason.TIMEOUT)
                    throw AgentModelException(AgentModelException.Reason.NETWORK)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentModelException(AgentModelException.Reason.CANCELLED)
        } finally {
            task.cancel(true)
            (task as? Runnable)?.let(workers::remove)
            connection.getAndSet(null)?.disconnect()
        }
    }

    private fun readBounded(input: InputStream, limit: Int, deadline: Long, cancelled: () -> Boolean): String {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkAlive(deadline, cancelled)
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > limit) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
            result.write(buffer, 0, count)
        }
        return result.toString(Charsets.UTF_8.name())
    }

    private fun remaining(deadline: Long): Int =
        ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1, MAX_TIMEOUT_MS.toLong()).toInt()

    private fun checkAlive(deadline: Long, cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw AgentModelException(AgentModelException.Reason.CANCELLED)
        if (System.nanoTime() >= deadline) throw AgentModelException(AgentModelException.Reason.TIMEOUT)
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
    const val MAX_TIMEOUT_MS = 120_000
}
