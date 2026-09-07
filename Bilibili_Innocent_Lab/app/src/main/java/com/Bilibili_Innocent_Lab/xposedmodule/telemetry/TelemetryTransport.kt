package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class TelemetryTransportResult(
    val outcome: TelemetryHttpOutcome,
    val retryAfterMs: Long = TelemetryPolicy.DEFAULT_RETRY_AFTER_MS
)

internal object TelemetryEndpoint {
    const val PRODUCTION_BASE_URL = "https://telemetry.bilibili.date"
    const val STAGING_BASE_URL = "https://telemetry-staging.bilibili.date"

    fun baseUrl(debug: Boolean = BuildConfig.DEBUG): String =
        if (debug) STAGING_BASE_URL else PRODUCTION_BASE_URL

    fun endpointKey(debug: Boolean = BuildConfig.DEBUG): String =
        "${baseUrl(debug)}|schema=${TelemetryPayloadCodec.SCHEMA_VERSION}"
}

internal interface TelemetryTransport {
    fun upload(baseUrl: String, payload: ByteArray): TelemetryTransportResult
    fun purge(baseUrl: String, purgeToken: String): TelemetryTransportResult
}

internal object TelemetryHttpTransport : TelemetryTransport {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val USER_AGENT = "InnocentLab-Telemetry/1"
    private val allowedHosts = setOf(
        "telemetry.bilibili.date",
        "telemetry-staging.bilibili.date"
    )

    override fun upload(baseUrl: String, payload: ByteArray): TelemetryTransportResult {
        require(payload.size <= TelemetryPayloadCodec.MAX_PAYLOAD_BYTES) { "payload_too_large" }
        return post(baseUrl, "/v1/report", payload)
    }

    override fun purge(baseUrl: String, purgeToken: String): TelemetryTransportResult {
        val payload = JSONObject()
            .put("purge_token", purgeToken)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        return post(baseUrl, "/v1/purge", payload)
    }

    private fun post(baseUrl: String, path: String, payload: ByteArray): TelemetryTransportResult {
        val connection = runCatching {
            openEndpoint(baseUrl, path).openConnection() as HttpURLConnection
        }.getOrElse {
            return TelemetryTransportResult(TelemetryHttpOutcome.RETRYABLE_FAILURE)
        }
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.use { output ->
                output.write(payload)
                output.flush()
            }
            val status = connection.responseCode
            runCatching { connection.errorStream?.close() }
            runCatching { if (status < 400) connection.inputStream?.close() }
            TelemetryTransportResult(
                outcome = TelemetryPolicy.classifyHttpStatus(status),
                retryAfterMs = retryAfterMs(connection.getHeaderField("Retry-After"))
            )
        } catch (_: Exception) {
            TelemetryTransportResult(TelemetryHttpOutcome.RETRYABLE_FAILURE)
        } finally {
            connection.disconnect()
        }
    }

    private fun openEndpoint(baseUrl: String, path: String): URL {
        require(path == "/v1/report" || path == "/v1/purge") { "endpoint_path_invalid" }
        val base = URL(baseUrl)
        require(base.protocol == "https" && base.host in allowedHosts && base.port == -1) {
            "endpoint_invalid"
        }
        require(base.path.isEmpty() || base.path == "/") { "endpoint_base_path_invalid" }
        return URL(base, path)
    }

    private fun retryAfterMs(raw: String?): Long {
        val seconds = raw?.trim()?.toLongOrNull()?.coerceIn(1L, 3_600L)
            ?: return TelemetryPolicy.DEFAULT_RETRY_AFTER_MS
        return seconds * 1_000L
    }
}
