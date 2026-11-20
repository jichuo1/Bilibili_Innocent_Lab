package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Reentrant adapter calls must not clear their outer main-player scope. */
internal class FrameCallScope {
    private val depth = ThreadLocal<Int>()
    val active: Boolean get() = (depth.get() ?: 0) > 0
    fun enter() { depth.set((depth.get() ?: 0) + 1) }
    fun leave() { val next = (depth.get() ?: 0) - 1; if (next <= 0) depth.remove() else depth.set(next) }
}

/** Both ends are weak; identity, rather than host-defined equals(), determines ownership. */
internal class FrameSurfaceOwnership<O : Any, S : Any>(private val maxOwners: Int = 16) {
    private class Binding<O : Any, S : Any>(owner: O) {
        val owner = WeakReference(owner)
        var surface = WeakReference<S>(null)
        @Volatile var speed = 1.0
        @Volatile var generation = 0L
        var hasSurface = false
    }
    private val bindings = ArrayList<Binding<O, S>>()
    private fun binding(owner: O, allowNew: Boolean, attachingSurface: Boolean = false): Binding<O, S>? {
        bindings.removeAll { it.owner.get() == null }
        bindings.firstOrNull { it.owner.get() === owner }?.let { return it }
        if (!allowNew) return null
        if (bindings.size >= maxOwners) {
            // A rate can arrive before the surface. Provisional owners must not block an active display.
            if (!attachingSurface) return null
            val unused = bindings.indexOfFirst { !it.hasSurface }
            if (unused < 0) return null
            bindings.removeAt(unused)
        }
        return Binding<O, S>(owner).also(bindings::add)
    }
    @Synchronized fun bind(owner: O, surface: S?, inMainPlayer: Boolean): Boolean {
        val binding = binding(owner, inMainPlayer, attachingSurface = surface != null) ?: return false
        if (surface !== binding.surface.get() || binding.hasSurface != (surface != null)) {
            binding.generation++
            if (surface != null) {
                // Existing readers cannot keep treating a newly shared display as an unambiguous owner.
                for (other in bindings) if (other !== binding && other.surface.get() === surface) other.generation++
            }
            binding.surface = WeakReference(surface)
        }
        binding.hasSurface = surface != null
        return true
    }

    @Synchronized fun updateSpeed(owner: O, speed: Float, allowNew: Boolean) {
        if (speed.isFinite() && speed > 0f) binding(owner, allowNew)?.speed = speed.toDouble()
    }

    /** Resolve once per decoder. A native Surface alias can use the only active owner at attachment time. */
    @Synchronized fun rateReader(surface: S, allowServiceAlias: Boolean): () -> Double {
        var matched: Binding<O, S>? = null
        var active: Binding<O, S>? = null
        var activeCount = 0
        for (current in bindings) {
            if (!current.hasSurface || current.owner.get() == null) continue
            activeCount++
            active = current
            if (current.surface.get() === surface) {
                if (matched != null) return { 1.0 }
                matched = current
            }
        }
        val selected = matched ?: active?.takeIf { allowServiceAlias && activeCount == 1 } ?: return { 1.0 }
        val generation = selected.generation
        return {
            val rate = selected.speed
            if (selected.generation == generation && selected.owner.get() != null) rate else 1.0
        }
    }

    @Synchronized fun contains(surface: S): Boolean = bindings.any { it.owner.get() != null && it.surface.get() === surface }
    @Synchronized fun release(owner: O) {
        bindings.removeAll {
            val released = it.owner.get() == null || it.owner.get() === owner
            if (released) it.generation++
            released
        }
    }
}

internal const val IJK_PLAYBACK_RATE = 10003

/** A released or reconfigured decoder cannot acquire a bridge from an older async preparation. */
internal class FrameDecoderGeneration<C : Any> {
    class Ticket internal constructor() {
        @Volatile private var active = true
        fun isActive(): Boolean = active
        internal fun revoke() { active = false }
    }
    private val current = WeakHashMap<C, Ticket>()
    @Synchronized fun begin(codec: C, onReplace: () -> Unit = {}): Ticket =
        Ticket().also { current.put(codec, it)?.revoke(); onReplace() }
    @Synchronized fun end(codec: C, ticket: Ticket? = null, action: () -> Unit = {}): Boolean {
        if (ticket != null && current[codec] !== ticket) return false
        current.remove(codec)?.revoke()
        action()
        return true
    }
    @Synchronized fun matches(codec: C, ticket: Ticket): Boolean = current[codec] === ticket
    @Synchronized fun replace(codec: C, expected: Ticket, action: (Ticket) -> Unit): Ticket? {
        if (current[codec] !== expected) return null
        expected.revoke()
        return Ticket().also { current[codec] = it; action(it) }
    }
    @Synchronized fun commit(codec: C, ticket: Ticket, action: () -> Unit): Boolean {
        if (current[codec] !== ticket) return false
        action()
        return true
    }
}

/** Store before checking readiness, so a Ready callback racing this insertion cannot lose it. */
internal class FramePendingTakeovers<C : Any, D : Any> {
    private val waiting = WeakHashMap<C, D>()
    fun await(codec: C, decoder: D, isReady: () -> Boolean, wake: () -> Unit) {
        synchronized(this) { waiting[codec] = decoder }
        if (isReady()) wake()
    }
    @Synchronized fun remove(codec: C) { waiting.remove(codec) }
    @Synchronized fun hasPending(): Boolean = waiting.isNotEmpty()
    @Synchronized fun hasReady(ready: (D) -> Boolean): Boolean = waiting.values.any(ready)
    @Synchronized fun takeReady(ready: (D) -> Boolean): List<Pair<C, D>> {
        val result = ArrayList<Pair<C, D>>()
        val entries = waiting.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (ready(entry.value)) { result += entry.key to entry.value; entries.remove() }
        }
        return result
    }
    @Synchronized fun take(): List<Pair<C, D>> =
        waiting.entries.map { it.key to it.value }.also { waiting.clear() }
}

internal object FrameCodecConfigureShape {
    fun cryptoIndex(parameters: List<String>): Int? {
        if (parameters.size != 4 || parameters[0] != "android.media.MediaFormat" ||
            parameters[1] != "android.view.Surface") return null
        return when {
            parameters[2] == "android.media.MediaCrypto" && parameters[3] == "int" -> 2
            parameters[2] == "int" && parameters[3] == "android.media.MediaDescrambler" -> 3
            else -> null
        }
    }
}

internal object FrameHookCoverage {
    private val required = setOf("display", "op", "surface", "surface.holder", "surface.release", "speed",
        "configure", "start", "output.surface", "flush", "stop", "reset", "release", "audio.ctor")
    private val serviceRequired = setOf("client.setSurface", "client.release", "client.speed")
    fun complete(installed: Set<String>, playerProcess: Boolean): Boolean =
        installed.containsAll(required) && (!playerProcess || installed.containsAll(serviceRequired))
}
