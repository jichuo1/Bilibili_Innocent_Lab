package com.bilab.core.agent.protocol

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded HTTP POST request descriptor. [toString] omits headers and payload to avoid leaking secrets.
 */
data class LlmHttpRequest(
    val url: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Int,
    val maxRequestBytes: Int = LlmHttpTransport.DEFAULT_MAX_REQUEST_BYTES,
    val maxResponseBytes: Int = LlmHttpTransport.DEFAULT_MAX_RESPONSE_BYTES,
    val maxStreamWireBytes: Int = LlmHttpTransport.DEFAULT_MAX_STREAM_WIRE_BYTES,
    val maxTimeoutMs: Int = LlmHttpTransport.DEFAULT_MAX_TIMEOUT_MS,
    val errorBytesLimit: Int = LlmHttpTransport.DEFAULT_ERROR_BYTES,
    val acceptHeader: String = "application/json"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LlmHttpRequest) return false
        return url == other.url &&
            body.contentEquals(other.body) &&
            headers == other.headers &&
            timeoutMs == other.timeoutMs &&
            maxRequestBytes == other.maxRequestBytes &&
            maxResponseBytes == other.maxResponseBytes &&
            maxStreamWireBytes == other.maxStreamWireBytes &&
            maxTimeoutMs == other.maxTimeoutMs &&
            errorBytesLimit == other.errorBytesLimit &&
            acceptHeader == other.acceptHeader
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + timeoutMs
        result = 31 * result + maxRequestBytes
        result = 31 * result + maxResponseBytes
        result = 31 * result + maxStreamWireBytes
        result = 31 * result + maxTimeoutMs
        result = 31 * result + errorBytesLimit
        result = 31 * result + acceptHeader.hashCode()
        return result
    }

    override fun toString(): String =
        "LlmHttpRequest(url=$url, bodyBytes=${body.size}, timeoutMs=$timeoutMs)"
}

/**
 * Result of a successful 2xx HTTP POST call.
 */
data class LlmHttpResult(
    val status: Int,
    val body: String,
    val contentType: String? = null
)

/**
 * Bounded transport failure without exposing sensitive headers or unbounded payloads in [toString].
 */
class LlmHttpException(
    val reason: Reason,
    val status: Int? = null,
    val errorBody: String = "",
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
    val overloaded: Boolean = false,
    detailMessage: String = if (overloaded) LlmHttpTransport.ERROR_OVERLOADED else reason.name
) : Exception(detailMessage, cause) {
    enum class Reason {
        CANCELLED,
        TIMEOUT,
        NETWORK,
        REDIRECT,
        TOO_LARGE,
        HTTP_STATUS
    }

    override fun toString(): String =
        if (overloaded) "LlmHttpException(reason=$reason, status=$status, overloaded=true)"
        else "LlmHttpException(reason=$reason, status=$status)"
}

/**
 * Pure-JVM bounded HTTP POST and SSE wire reading primitives with redirect denial and
 * a shared bounded worker pool whose core threads time out after 30s of idle time.
 */
object LlmHttpTransport {
    const val DEFAULT_MAX_REQUEST_BYTES = 2 * 1024 * 1024
    const val DEFAULT_MAX_RESPONSE_BYTES = 512 * 1024
    const val DEFAULT_MAX_STREAM_WIRE_BYTES = 4 * 1024 * 1024
    const val DEFAULT_MAX_TIMEOUT_MS = 300_000
    const val DEFAULT_ERROR_BYTES = 16_384
    const val DEFAULT_MAX_SSE_LINE_CHARS = 131_072
    const val ERROR_OVERLOADED = "http_transport_overloaded"

    val sharedWorkers: ThreadPoolExecutor = ThreadPoolExecutor(
        2,
        6,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(16)
    ) { runnable ->
        Thread(runnable, "BIL-AgentHttp").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    fun post(request: LlmHttpRequest, cancelled: () -> Boolean): LlmHttpResult =
        postWithConnection(request, cancelled) { it.openConnection() as HttpURLConnection }

    fun postWithConnection(
        request: LlmHttpRequest,
        cancelled: () -> Boolean,
        connect: (URL) -> HttpURLConnection
    ): LlmHttpResult = executeWithConnection(request, cancelled, null, connect)

    fun executeWithConnection(
        request: LlmHttpRequest,
        cancelled: () -> Boolean,
        onStream: ((InputStream, () -> Unit) -> String)? = null,
        connect: (URL) -> HttpURLConnection
    ): LlmHttpResult {
        if (request.body.size > request.maxRequestBytes) {
            throw LlmHttpException(LlmHttpException.Reason.TOO_LARGE)
        }
        if (cancelled()) {
            throw LlmHttpException(LlmHttpException.Reason.CANCELLED)
        }
        val connection = AtomicReference<HttpURLConnection?>()
        val started = System.nanoTime()
        val budgetNs = request.timeoutMs.coerceIn(1, request.maxTimeoutMs) * 1_000_000L
        val deadlineNs = AtomicLong(started + budgetNs)
        val idleNs = minOf(budgetNs, 60_000_000_000L)
        val idleTimeoutMs = (idleNs / 1_000_000L).coerceAtLeast(1L).toInt()
        val hardCapNs = started + request.maxTimeoutMs * 1_000_000L

        fun touch() {
            val now = System.nanoTime()
            deadlineNs.updateAndGet { current -> minOf(maxOf(current, now + idleNs), hardCapNs) }
        }

        fun alive() = checkAlive(deadlineNs.get(), cancelled)

        fun remainingMs(): Int = ((deadlineNs.get() - System.nanoTime()) / 1_000_000L)
            .coerceIn(1, request.maxTimeoutMs.toLong()).toInt()

        val task = try {
            sharedWorkers.submit(Callable {
                alive()
                val http = connect(URL(request.url))
                connection.set(http)
                var keepAliveSucceeded = false
                try {
                    alive()
                    http.instanceFollowRedirects = false
                    http.useCaches = false
                    http.requestMethod = "POST"
                    val rem = remainingMs()
                    http.connectTimeout = rem
                    http.readTimeout = minOf(rem, idleTimeoutMs)
                    http.doOutput = true
                    http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    http.setRequestProperty("Accept", request.acceptHeader)
                    request.headers.forEach { (key, value) ->
                        val emptyBearer = key.equals("Authorization", ignoreCase = true) &&
                            value.trim().equals("Bearer", ignoreCase = true)
                        val emptyApiKeyHeader = key.equals("x-api-key", ignoreCase = true) && value.isBlank()
                        if (!emptyBearer && !emptyApiKeyHeader) {
                            http.setRequestProperty(key, value)
                        }
                    }
                    http.setFixedLengthStreamingMode(request.body.size)
                    http.outputStream.use { it.write(request.body) }
                    alive()
                    val status = http.responseCode
                    if (status in 300..399) {
                        throw LlmHttpException(LlmHttpException.Reason.REDIRECT, status = status)
                    }
                    if (status !in 200..299) {
                        val retryAfter = http.getHeaderField("Retry-After")?.toLongOrNull()
                            ?.coerceIn(0, 300)?.times(1000)
                        val error = http.errorStream?.use {
                            readBounded(it, request.errorBytesLimit, deadlineNs.get(), cancelled)
                        }.orEmpty()
                        throw LlmHttpException(
                            LlmHttpException.Reason.HTTP_STATUS,
                            status = status,
                            errorBody = error,
                            retryAfterMs = retryAfter
                        )
                    }
                    val contentType = http.contentType
                    val streaming = onStream != null && contentType?.substringBefore(';')?.trim()
                        ?.equals("text/event-stream", ignoreCase = true) == true
                    val maxBytes = if (streaming) request.maxStreamWireBytes else request.maxResponseBytes
                    if (http.contentLengthLong > maxBytes) {
                        throw LlmHttpException(LlmHttpException.Reason.TOO_LARGE)
                    }
                    val payload = http.inputStream.use { input ->
                        if (streaming) {
                            checkNotNull(onStream)(input) { touch(); alive() }
                        } else {
                            readBounded(input, request.maxResponseBytes, deadlineNs.get(), cancelled)
                        }
                    }
                    keepAliveSucceeded = true
                    connection.compareAndSet(http, null)
                    LlmHttpResult(status = status, body = payload, contentType = contentType)
                } finally {
                    connection.compareAndSet(http, null)
                    if (!keepAliveSucceeded) {
                        runCatching { http.disconnect() }
                    }
                }
            })
        } catch (rejected: RejectedExecutionException) {
            throw LlmHttpException(
                reason = LlmHttpException.Reason.NETWORK,
                cause = rejected,
                overloaded = true,
                detailMessage = ERROR_OVERLOADED
            )
        }

        try {
            while (true) {
                alive()
                try {
                    return task.get(minOf(100, remainingMs()).toLong(), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Poll in short slices so cancellation takes effect promptly.
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    if (cause is LlmHttpException) throw cause
                    if (cause is SocketTimeoutException) {
                        throw LlmHttpException(LlmHttpException.Reason.TIMEOUT, cause = cause)
                    }
                    throw LlmHttpException(LlmHttpException.Reason.NETWORK, cause = cause)
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmHttpException(LlmHttpException.Reason.CANCELLED, cause = interrupted)
        } finally {
            task.cancel(true)
            (task as? Runnable)?.let(sharedWorkers::remove)
            connection.getAndSet(null)?.disconnect()
        }
    }

    fun checkAlive(deadlineNs: Long, cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) {
            throw LlmHttpException(LlmHttpException.Reason.CANCELLED)
        }
        if (System.nanoTime() >= deadlineNs) {
            throw LlmHttpException(LlmHttpException.Reason.TIMEOUT)
        }
    }

    fun readBounded(
        input: InputStream,
        limit: Int,
        deadlineNs: Long,
        cancelled: () -> Boolean
    ): String {
        require(limit >= 0) { "limit must be non-negative" }
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkAlive(deadlineNs, cancelled)
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > limit) {
                throw LlmHttpException(LlmHttpException.Reason.TOO_LARGE)
            }
            result.write(buffer, 0, count)
        }
        return result.toString(Charsets.UTF_8.name())
    }

    /**
     * Reads SSE `data:` frames with strict byte, line, and trailing-boundary bounds.
     * [onData] returns `true` to continue reading or `false` to stop cleanly.
     */
    fun readSseEvents(
        input: InputStream,
        maxWireBytes: Int = DEFAULT_MAX_STREAM_WIRE_BYTES,
        maxLineChars: Int = DEFAULT_MAX_SSE_LINE_CHARS,
        checkAlive: () -> Unit,
        onData: (String) -> Boolean
    ) {
        val bounded = object : FilterInputStream(input) {
            var bytes = 0
            fun counted(count: Int): Int {
                if (count > 0) bytes += count
                if (bytes > maxWireBytes) throw LlmHttpException(LlmHttpException.Reason.TOO_LARGE)
                return count
            }

            override fun read(): Int = super.read().also { if (it >= 0) counted(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                counted(`in`.read(buffer, offset, length))
        }
        val reader = bounded.bufferedReader(Charsets.UTF_8)
        val event = StringBuilder()
        var firstLine = true
        var stop = false
        while (!stop) {
            checkAlive()
            val rawLine = reader.readLine() ?: break
            val line = if (firstLine) rawLine.removePrefix("\uFEFF") else rawLine
            firstLine = false
            if (line.length > maxLineChars) throw LlmHttpException(LlmHttpException.Reason.TOO_LARGE)
            if (line.isEmpty()) {
                if (event.isNotEmpty()) {
                    checkAlive()
                    val payload = event.toString()
                    event.setLength(0)
                    stop = !onData(payload)
                }
            } else if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.substring(5).removePrefix(" "))
                if (event.length > maxLineChars) throw LlmHttpException(LlmHttpException.Reason.TOO_LARGE)
            }
        }
        if (event.isNotEmpty()) {
            throw LlmHttpException(LlmHttpException.Reason.NETWORK)
        }
        checkAlive()
    }
}
