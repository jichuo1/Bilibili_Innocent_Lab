package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class NPatchRouteEvidence(val route: String, val result: String)
internal data class NPatchStartupEvidence(
    val session: String,
    val startedAt: Long,
    val attempt: Int,
    val admissionMs: Long,
    val installMs: Long?,
    val totalMs: Long,
    val result: String,
    val selectedRoute: String,
    val routes: List<NPatchRouteEvidence>
)

/** v1 support evidence is separate from activation/authorization. No identifiers or grant material. */
internal object NPatchStartupCodec {
    const val MAX_SAMPLES = 8
    const val MAX_MS = 3_600_000L
    val routes = setOf("provider", "binder", "local", "broadcast", "remote_config", "unknown")
    val results = setOf("granted", "denied", "timeout", "unavailable", "disabled")
    private val sessionPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun encode(samples: List<NPatchStartupEvidence>): JSONArray = JSONArray().apply {
        require(samples.size <= MAX_SAMPLES)
        samples.forEach { sample ->
            require(valid(sample))
            put(JSONObject().put("session", sample.session).put("started", sample.startedAt)
                .put("attempt", sample.attempt).put("admissionMs", sample.admissionMs)
                .put("installMs", sample.installMs ?: JSONObject.NULL).put("totalMs", sample.totalMs)
                .put("result", sample.result).put("selected", sample.selectedRoute)
                .put("routes", JSONArray().apply { sample.routes.forEach {
                    put(JSONObject().put("route", it.route).put("result", it.result))
                } }))
        }
    }

    fun decode(array: JSONArray): List<NPatchStartupEvidence> {
        require(array.length() <= MAX_SAMPLES)
        return List(array.length()) { index ->
            val row = array.getJSONObject(index)
            require(row.length() == 9)
            val routes = row.getJSONArray("routes")
            require(routes.length() <= 4)
            NPatchStartupEvidence(row.get("session") as String, row.strictLong("started"),
                row.strictLong("attempt").also { require(it in 1..8) }.toInt(), row.strictLong("admissionMs"),
                if (row.isNull("installMs")) null else row.strictLong("installMs"), row.strictLong("totalMs"),
                row.get("result") as String, row.get("selected") as String,
                List(routes.length()) { i -> routes.getJSONObject(i).let {
                    require(it.length() == 2)
                    NPatchRouteEvidence(it.get("route") as String, it.get("result") as String)
                } }).also { require(valid(it)) }
        }.also { require(it.map { row -> row.session to row.attempt }.distinct().size == it.size) }
    }

    private fun valid(sample: NPatchStartupEvidence): Boolean =
        sessionPattern.matches(sample.session) && sample.startedAt > 0 && sample.attempt in 1..8 &&
            sample.admissionMs in 0..MAX_MS && (sample.installMs == null || sample.installMs in 0..MAX_MS) &&
            sample.totalMs in 0..MAX_MS && sample.result in results && sample.selectedRoute in routes &&
            sample.routes.size <= 4 && sample.routes.map { it.route }.distinct().size == sample.routes.size &&
            sample.routes.all { it.route in routes - setOf("unknown", "remote_config") && it.result in results }

    /** One latest observation per boot, bounded history; refreshes never count as new boots. */
    fun merge(old: List<NPatchStartupEvidence>, fresh: List<NPatchStartupEvidence>): List<NPatchStartupEvidence> =
        (old + fresh).associateBy { it.session }.values.sortedBy { it.startedAt }.takeLast(MAX_SAMPLES)

    fun p50(samples: List<Long>): Long? = samples.sorted().takeIf { it.isNotEmpty() }?.let { it[(it.size - 1) / 2] }
}

internal fun JSONObject.strictLong(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("invalid_integer")
}

/** Session-local timings only. Late transports cannot mutate a finished attempt. */
internal class NPatchStartupTracker {
    private val session = UUID.randomUUID().toString()
    private val started = System.currentTimeMillis()
    private var attempts = 0
    private var sample: NPatchStartupEvidence? = null

    @Synchronized fun admission(ms: Long, result: String, route: String, routes: List<NPatchRouteEvidence>) {
        attempts = (attempts + 1).coerceAtMost(8)
        sample = NPatchStartupEvidence(session, started, attempts, ms.coerceIn(0, NPatchStartupCodec.MAX_MS),
            null, ms.coerceIn(0, NPatchStartupCodec.MAX_MS), result, route, routes)
    }

    @Synchronized fun installation(ms: Long) {
        sample = sample?.let { it.copy(installMs = ms.coerceIn(0, NPatchStartupCodec.MAX_MS),
            totalMs = (it.admissionMs + ms).coerceIn(0, NPatchStartupCodec.MAX_MS)) }
    }

    @Synchronized fun snapshot(): List<NPatchStartupEvidence> = listOfNotNull(sample)
}
