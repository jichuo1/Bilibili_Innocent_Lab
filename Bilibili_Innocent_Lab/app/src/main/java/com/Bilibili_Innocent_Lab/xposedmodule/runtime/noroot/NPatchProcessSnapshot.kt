package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.json.JSONArray
import org.json.JSONObject

internal enum class NPatchProcessState { LOADED, REJECTED, DISABLED, GATED, INSTALLED, FAILED }

/** 本次查询中实际响应的进程；零 Hook 不等于没有注入，也不代表功能生效。 */
internal data class NPatchProcessSnapshot(
    val processName: String,
    val pid: Int,
    val mode: NPatchMode,
    val state: NPatchProcessState,
    val source: String = "unknown",
    val installedHookCount: Int = 0,
    val targetUpdateTime: Long,
    val moduleVersionCode: Long,
    val process64Bit: Boolean? = null,
    val dexKitState: String = "UNKNOWN",
    val frameNativeState: String = "UNKNOWN",
    val startups: List<NPatchStartupEvidence> = emptyList(),
    val guardedFailures: Long = 0L,
    val support: NPatchSupportEvidence = NPatchSupportEvidence()
)

/** 独立的本地只读协议，不改变主进程激活回执、许可或可导出报告 schema。 */
internal object NPatchProcessCodec {
    const val MAX_PROCESSES = 16
    const val MAX_CHARS = 128 * 1024
    private val process = Regex("tv\\.danmaku\\.bili(?::[A-Za-z0-9_.-]{1,96})?")
    private val sources = setOf("unknown", "module_direct", "manager")
    private val dexKitStates = setOf("UNKNOWN", "NOT_TRIED", "AVAILABLE", "UNAVAILABLE")
    private val frameStates = setOf("UNKNOWN", "NOT_PREPARED", "PREPARING", "READY", "BUSY", "UNSUPPORTED_ABI", "RESTART_REQUIRED", "FAILED")

    fun encode(rows: List<NPatchProcessSnapshot>): String {
        require(rows.size <= MAX_PROCESSES && rows.map { it.processName }.distinct().size == rows.size)
        return JSONArray().apply { rows.forEach { row ->
            require(valid(row))
            put(JSONObject().put("process", row.processName).put("pid", row.pid)
                .put("mode", row.mode.name).put("state", row.state.name).put("source", row.source)
                .put("hooks", row.installedHookCount).put("targetUpdate", row.targetUpdateTime)
                .put("moduleVersion", row.moduleVersionCode)
                .put("process64", row.process64Bit ?: JSONObject.NULL)
                .put("dexkit", row.dexKitState).put("frameNative", row.frameNativeState)
                .put("startups", NPatchStartupCodec.encode(row.startups)).put("guardedFailures", row.guardedFailures)
                .put("support", NPatchSupportCodec.encode(row.support)))
        } }.toString().also { require(it.length <= MAX_CHARS) }
    }

    fun decode(raw: String): List<NPatchProcessSnapshot>? = runCatching {
        require(raw.length <= MAX_CHARS)
        val array = JSONArray(raw)
        require(array.length() <= MAX_PROCESSES)
        List(array.length()) { index ->
            val row = array.getJSONObject(index)
            require(row.length() == 14)
            val process64 = row.get("process64")
            require(process64 == JSONObject.NULL || process64 is Boolean)
            NPatchProcessSnapshot(row.get("process") as String, row.integer("pid").toInt(),
                NPatchMode.valueOf(row.get("mode") as String),
                NPatchProcessState.valueOf(row.get("state") as String),
                row.get("source") as String, row.integer("hooks").toInt(),
                row.integer("targetUpdate"), row.integer("moduleVersion"), process64 as? Boolean,
                row.get("dexkit") as String, row.get("frameNative") as String,
                NPatchStartupCodec.decode(row.getJSONArray("startups")), row.strictLong("guardedFailures"),
                NPatchSupportCodec.decode(row.getJSONObject("support"))).also { require(valid(it)) }
        }.also { require(it.map { row -> row.processName }.distinct().size == it.size) }
    }.getOrNull()

    private fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("invalid_integer")
    }.also { if (key == "pid" || key == "hooks") require(it in 0L..Int.MAX_VALUE.toLong()) }

    private fun valid(row: NPatchProcessSnapshot): Boolean =
        process.matches(row.processName) && row.pid > 0 && row.source in sources &&
            row.dexKitState in dexKitStates && row.frameNativeState in frameStates && row.guardedFailures in 0L..1_000_000_000L &&
            row.installedHookCount in 0..4096 && row.targetUpdateTime > 0L && row.moduleVersionCode > 0L &&
            (row.state == NPatchProcessState.INSTALLED || row.installedHookCount == 0)
}
