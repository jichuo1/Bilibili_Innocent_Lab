package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/** DEBUG opt-in only. Fixed logical IDs; no host members, preferences or grant material. */
internal object StartupTrace {
    val enabled = BuildConfig.DEBUG && BuildConfig.STARTUP_TRACE
    private val recorder by lazy { StartupTraceRecorder(roots = setOf("entry", "authorization", "application.ready")) }
    fun attempt() { if (enabled) recorder.attempt() }
    fun <T> measure(id: String, action: () -> T): T =
        if (enabled) recorder.measure(id, action) else action()
    fun json(): String = if (enabled) recorder.json() else ""
}

/** Aggregates repeated spans so registration count cannot grow memory with Hook count. */
internal class StartupTraceRecorder(
    private val clock: () -> Long = System::nanoTime,
    private val mainThread: () -> Boolean = { Thread.currentThread().name == "main" },
    private val roots: Set<String>? = null
) {
    private data class Key(val attempt: Int, val id: String, val main: Boolean)
    private data class Totals(var calls: Int = 0, var failures: Int = 0, var ns: Long = 0, var own: Long = 0,
        val parents: MutableSet<String> = linkedSetOf())
    private class Frame(val id: String, val start: Long, var children: Long = 0)
    private val frames = ThreadLocal.withInitial { ArrayDeque<Frame>() }
    private val totals = linkedMapOf<Key, Totals>()
    private val session = UUID.randomUUID().toString()
    private var attempt = 0
    private var dropped = 0

    @Synchronized fun attempt() { attempt = (attempt + 1).coerceAtMost(2) }

    fun <T> measure(id: String, action: () -> T): T {
        // IDs are code-owned. A malformed diagnostic must never prevent the original action.
        if (!validId(id)) return action()
        val stack = checkNotNull(frames.get()) // withInitial always supplies a non-null deque.
        // Shared lookup helpers also run in business callbacks. Only explicit startup roots collect.
        if (stack.isEmpty() && roots != null && id !in roots) return action()
        val key = synchronized(this) { Key(attempt, id, mainThread()) }
        val parent = stack.lastOrNull()?.id ?: "root"
        val frame = Frame(id, clock())
        stack.addLast(frame)
        var failed = false
        try { return action() } catch (failure: Throwable) { failed = true; throw failure }
        finally {
            val ns = (clock() - frame.start).coerceIn(0, MAX_NS)
            stack.removeLast()
            stack.lastOrNull()?.let { it.children = (it.children + ns).coerceAtMost(MAX_NS) }
            synchronized(this) {
                val aggregate = totals[key] ?: if (totals.keys.count { it.attempt == key.attempt } < MAX_ROWS) {
                    Totals().also { totals[key] = it }
                } else null
                if (aggregate == null) dropped = (dropped + 1).coerceAtMost(MAX_COUNT)
                else {
                    aggregate.calls = (aggregate.calls + 1).coerceAtMost(MAX_COUNT)
                    if (failed) aggregate.failures = (aggregate.failures + 1).coerceAtMost(MAX_COUNT)
                    aggregate.ns = (aggregate.ns + ns).coerceAtMost(MAX_NS)
                    aggregate.own = (aggregate.own + (ns - frame.children).coerceAtLeast(0)).coerceAtMost(MAX_NS)
                    if (aggregate.parents.size < MAX_ROWS) aggregate.parents += parent
                }
            }
        }
    }

    @Synchronized fun json(): String = JSONObject().put("schema", 1).put("session", session)
        .put("dropped", dropped).put("rows", JSONArray().apply {
            totals.forEach { (key, total) -> put(JSONObject().put("attempt", key.attempt).put("id", key.id)
                .put("main", key.main).put("calls", total.calls).put("failures", total.failures)
                .put("ns", total.ns).put("ownNs", total.own).put("parents", JSONArray(total.parents.toList()))) }
        }).toString()

    companion object {
        const val MAX_ROWS = 128
        const val MAX_COUNT = 1_000_000
        const val MAX_NS = 3_600_000_000_000L
        private val idPattern = Regex("[a-z][a-z0-9_.-]{0,79}")
        private fun validId(id: String) = idPattern.matches(id)

        /** Strict DEBUG snapshot validator, separate from all production diagnostic schemas. */
        fun validate(raw: String): JSONObject {
            require(raw.length <= 128 * 1024)
            val parser = JSONTokener(raw)
            val root = parser.nextValue() as JSONObject
            require(parser.nextClean() == '\u0000')
            require(root.length() == 4 && root.get("schema") == 1)
            require(UUID.fromString(root.get("session") as String).toString() == root.getString("session"))
            require(root.integer("dropped") in 0..MAX_COUNT.toLong())
            val rows = root.getJSONArray("rows")
            require(rows.length() <= 3 * MAX_ROWS)
            val keys = hashSetOf<String>()
            val sizes = mutableMapOf<Long, Int>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                require(row.length() == 8)
                val attempt = row.integer("attempt").also { require(it in 0..2) }
                require(validId(row.get("id") as String) && row.get("main") is Boolean)
                require(keys.add("$attempt/${row.getString("id")}/${row.getBoolean("main")}"))
                sizes[attempt] = (sizes[attempt] ?: 0) + 1
                require(sizes.getValue(attempt) <= MAX_ROWS)
                val calls = row.integer("calls").also { require(it in 1..MAX_COUNT.toLong()) }
                require(row.integer("failures") in 0..calls)
                val ns = row.integer("ns").also { require(it in 0..MAX_NS) }
                require(row.integer("ownNs") in 0..ns)
                val parents = row.getJSONArray("parents")
                require(parents.length() in 1..MAX_ROWS)
                val parentIds = (0 until parents.length()).map { parents.get(it) as String }
                require(parentIds.all(::validId) && parentIds.distinct().size == parentIds.size)
            }
            return root
        }

        private fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> error("invalid_integer")
        }
    }
}
