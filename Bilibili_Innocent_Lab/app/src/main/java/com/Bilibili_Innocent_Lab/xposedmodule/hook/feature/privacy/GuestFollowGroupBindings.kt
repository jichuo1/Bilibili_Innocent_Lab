package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Associates host calls with the token of the dialog that created them. The token must not retain UI objects.
 * An unknown or evicted marker stays tagged as blocked, so restored pages cannot fall through to the network.
 */
internal class GuestFollowGroupBindings<T : Any>(
    private val capacity: Int = 128,
    private val newMarker: () -> String = { UUID.randomUUID().toString() }
) {
    data class Binding<T : Any>(val token: T, val mid: Long)
    data class Tagged<T : Any>(val binding: Binding<T>?)

    private class IdentityReference(value: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode() = hash
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityReference) return false
            val value = get() ?: return false
            return value === other.get()
        }
    }

    private val markerPrefix = newMarker().also { require(it.isNotBlank()) }
    private var sequence = 0L
    private val sessions = LinkedHashMap<String, Binding<T>>()
    private val collected = ReferenceQueue<Any>()
    private val calls = HashMap<IdentityReference, String>()

    init { require(capacity > 0) }

    @Synchronized
    fun register(token: T, mid: Long): String {
        require(mid > 0)
        check(sequence < Long.MAX_VALUE) { "Session marker limit reached" }
        val marker = "$markerPrefix:${sequence++}"
        sessions[marker] = Binding(token, mid)
        while (sessions.size > capacity) sessions.remove(sessions.keys.first())
        return marker
    }

    @Synchronized
    fun lookup(marker: String): Binding<T>? = sessions[marker]

    /** Marks an exact call by identity, retaining it weakly. Rebinding a call makes it permanently blocked. */
    @Synchronized
    fun tag(call: Any, marker: String) {
        drainCollected()
        val key = IdentityReference(call)
        val prior = calls[key]
        if (prior == null) calls[IdentityReference(call, collected)] = marker
        else if (prior != marker) calls[key] = ""
    }

    /** A host clone inherits the original call's marker, including a blocked marker. */
    @Synchronized
    fun forward(source: Any, target: Any) {
        drainCollected()
        val marker = calls[IdentityReference(source)] ?: return
        tag(target, marker)
    }
    /** Null means an unrelated host call. A tagged call without a binding must fail closed. */
    @Synchronized
    fun tagged(call: Any): Tagged<T>? {
        drainCollected()
        val marker = calls[IdentityReference(call)] ?: return null
        return Tagged(sessions[marker])
    }

    private fun drainCollected() {
        while (true) calls.remove(collected.poll() ?: break)
    }
}
