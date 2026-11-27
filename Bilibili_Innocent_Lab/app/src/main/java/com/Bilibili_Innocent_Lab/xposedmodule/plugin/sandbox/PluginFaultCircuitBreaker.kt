package com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sliding time-window fault circuit breaker for isolated plugin callbacks.
 *
 * When a plugin records [maxFaults] failures within [windowMs], the breaker trips once and
 * synchronously notifies [onTrip] and registered listeners so active hooks/sessions are torn down.
 */
internal class PluginFaultCircuitBreaker @JvmOverloads constructor(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxFaults: Int = DEFAULT_MAX_FAULTS,
    private val onTrip: ((pluginId: String) -> Unit)? = null
) {
    init {
        require(windowMs > 0L) { "windowMs must be positive: $windowMs" }
        require(maxFaults > 0) { "maxFaults must be positive: $maxFaults" }
    }

    private class PluginFaultState {
        val timestamps = ArrayDeque<Long>()
        var tripped: Boolean = false
    }

    private val lock = Any()
    private val states = ConcurrentHashMap<String, PluginFaultState>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addOnTripListener(listener: (pluginId: String) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /**
     * Records a fault for [pluginId]. Returns `true` if the plugin just tripped or was already tripped.
     */
    fun recordFault(pluginId: String): Boolean {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        val now = nowMs()
        var justTripped = false
        synchronized(lock) {
            val state = states.computeIfAbsent(pluginId) { PluginFaultState() }
            if (state.tripped) {
                return true
            }
            pruneExpiredLocked(state.timestamps, now)
            state.timestamps.addLast(now)
            if (state.timestamps.size >= maxFaults) {
                state.tripped = true
                justTripped = true
            }
        }
        if (justTripped) {
            notifyTripListeners(pluginId)
        }
        return justTripped
    }

    fun isTripped(pluginId: String): Boolean = synchronized(lock) {
        states[pluginId]?.tripped == true
    }

    fun faultCount(pluginId: String): Int {
        val now = nowMs()
        return synchronized(lock) {
            val state = states[pluginId] ?: return@synchronized 0
            if (!state.tripped) {
                pruneExpiredLocked(state.timestamps, now)
            }
            state.timestamps.size
        }
    }

    fun reset(pluginId: String) {
        synchronized(lock) {
            states.remove(pluginId)
        }
    }

    fun resetAll() {
        synchronized(lock) {
            states.clear()
        }
    }

    private fun pruneExpiredLocked(timestamps: ArrayDeque<Long>, now: Long) {
        while (timestamps.isNotEmpty()) {
            val oldest = timestamps.first()
            if (now - oldest >= windowMs) {
                timestamps.removeFirst()
            } else {
                break
            }
        }
    }

    private fun notifyTripListeners(pluginId: String) {
        onTrip?.let { callback ->
            runCatching { callback(pluginId) }
        }
        for (listener in listeners) {
            runCatching { listener(pluginId) }
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 10_000L
        const val DEFAULT_MAX_FAULTS = 10
    }
}
