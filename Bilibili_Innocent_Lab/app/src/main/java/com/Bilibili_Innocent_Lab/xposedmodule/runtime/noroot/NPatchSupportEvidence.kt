package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.json.JSONArray
import org.json.JSONObject

internal data class NPatchCrashEvidence(val at: Long, val kind: String, val moduleFrame: Boolean, val frames: List<String>)
internal data class NPatchSupportEvidence(
    val crashState: String = "NOT_QUERIED",
    val crashes: List<NPatchCrashEvidence> = emptyList(),
    val mediaState: String = "NOT_QUERIED",
    val mediaLines: List<String> = emptyList()
)

/** No exception messages, thread names, filenames, URLs or raw log lines cross this boundary. */
internal object NPatchSupportCodec {
    val states = setOf("NOT_QUERIED", "AVAILABLE", "EMPTY", "UNAVAILABLE", "INVALID")
    private val kinds = setOf("NULL", "STATE", "ARGUMENT", "IO", "REFLECTION", "MEMORY", "OTHER")
    private val frame = Regex("(?:com\\.Bilibili_Innocent_Lab\\.|com\\.bilibili\\.|tv\\.danmaku\\.|android\\.|java\\.|kotlin\\.|top\\.nkbe\\.|org\\.matrix\\.|io\\.github\\.libxposed\\.)[A-Za-z0-9_.$]{1,150}:[-0-9]{1,8}")
    private val event = setOf("JAVA_CRASH", "BOOTSTRAP_COMPLETED", "MODULE_EVENT", "FRAMEWORK_EVENT")

    fun encode(value: NPatchSupportEvidence): JSONObject {
        require(value.crashState in states && value.mediaState in states && value.crashes.size <= 3 && value.mediaLines.size <= 40)
        require(value.crashes.isEmpty() || value.crashState == "AVAILABLE")
        require(value.mediaLines.isEmpty() || value.mediaState == "AVAILABLE")
        return JSONObject().put("schema", 1).put("crashState", value.crashState).put("mediaState", value.mediaState)
            .put("crashes", JSONArray().apply { value.crashes.forEach { crash ->
                require(crash.at > 0 && crash.kind in kinds && crash.frames.size <= 8 && crash.frames.all(::validFrame))
                put(JSONObject().put("at", crash.at).put("kind", crash.kind).put("moduleFrame", crash.moduleFrame)
                    .put("frames", JSONArray(crash.frames)))
            } }).put("media", JSONArray(value.mediaLines.onEach { require(it in event || validFrame(it)) }))
    }

    fun decode(value: JSONObject): NPatchSupportEvidence {
        require(value.length() == 5 && value.strictLong("schema") == 1L)
        val crashes = value.getJSONArray("crashes")
        val media = value.getJSONArray("media")
        require(crashes.length() <= 3 && media.length() <= 40)
        return NPatchSupportEvidence(value.get("crashState") as String, List(crashes.length()) { index ->
            val row = crashes.getJSONObject(index)
            require(row.length() == 4)
            val frames = row.getJSONArray("frames")
            require(frames.length() <= 8)
            NPatchCrashEvidence(row.strictLong("at"), row.get("kind") as String, row.get("moduleFrame") as Boolean,
                List(frames.length()) { frames.get(it) as String })
        }, value.get("mediaState") as String, List(media.length()) { media.get(it) as String }).also { encode(it) }
    }

    fun validFrame(value: String): Boolean = value.length <= 180 && frame.matches(value)

    fun crash(throwable: Throwable, at: Long): NPatchCrashEvidence {
        val traces = ArrayList<StackTraceElement>()
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = throwable
        repeat(8) {
            val error = current ?: return@repeat
            if (seen.put(error, true) != null) { current = null; return@repeat }
            traces += error.stackTrace.take(64)
            current = error.cause
        }
        val module = traces.any { it.className.startsWith("com.Bilibili_Innocent_Lab.xposedmodule.") }
        // Preserve module frames when a deep cause contains them; absence is not proof of fault ownership.
        val frames = traces.sortedByDescending { it.className.startsWith("com.Bilibili_Innocent_Lab.xposedmodule.") }
            .map { "${it.className}.${it.methodName}:${it.lineNumber.coerceIn(-1, 1_000_000)}" }
            .filter(::validFrame).distinct().take(8)
        return NPatchCrashEvidence(at, when (throwable) {
            is NullPointerException -> "NULL"
            is IllegalStateException -> "STATE"
            is IllegalArgumentException -> "ARGUMENT"
            is java.io.IOException -> "IO"
            is ReflectiveOperationException -> "REFLECTION"
            is OutOfMemoryError -> "MEMORY"
            else -> "OTHER"
        }, module, frames)
    }

    /** Allowlist extraction, not regex redaction of arbitrary business messages. */
    fun media(raw: String): List<String> = raw.lineSequence().takeLastBounded(200).mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("at ")) {
            val position = trimmed.removePrefix("at ").substringBefore('(')
            val number = Regex(":([0-9]{1,7})\\)").find(trimmed)?.groupValues?.get(1) ?: "-1"
            "$position:$number".takeIf(::validFrame)
        } else when {
            !Regex("\\[[VDIWE]/(?:NPatch[^]\\s]*|LSPosed[^]\\s]*|Vector[^]\\s]*)] ").containsMatchIn(line) -> null
            line.contains("Uncaught exception in ") -> "JAVA_CRASH"
            line.endsWith("NPatch bootstrap completed") -> "BOOTSTRAP_COMPLETED"
            line.contains("[BIL]") -> "MODULE_EVENT"
            else -> "FRAMEWORK_EVENT"
        }
    }.toList().takeLast(40)

    private fun Sequence<String>.takeLastBounded(count: Int): List<String> {
        val queue = java.util.ArrayDeque<String>(count)
        for (line in this) { if (queue.size == count) queue.removeFirst(); queue.addLast(line) }
        return queue.toList()
    }
}
