package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import java.util.WeakHashMap

/**
 * Module overrides on host-owned properties. The first observed value, or a later value the host
 * wrote over ours, is the base; [restore] only writes back while our value is still current.
 * Keys are weak: a destroyed host view needs no restoration.
 */
internal class HostChromeValueLedger<K : Any, V>(
    private val read: (K) -> V,
    private val write: (K, V) -> Unit,
    private val restored: (V) -> V = { it }
) {
    private class Entry<V>(val base: V, val applied: V)
    private val entries = WeakHashMap<K, Entry<V>>()

    fun owns(key: K): Boolean = entries.containsKey(key)

    fun keys(): List<K> = entries.keys.toList()

    /** Returns true when a new value was written. */
    fun apply(key: K, target: (base: V) -> V): Boolean {
        val current = read(key)
        val previous = entries[key]
        val base = if (previous != null && current == previous.applied) previous.base else current
        val next = target(base)
        // 稳定布局下 base/applied 都没变：不再每次分配相同的 Entry（所有权记账仍先于免写判断完成）。
        if (previous == null || previous.base != base || previous.applied != next) entries[key] = Entry(base, next)
        if (current == next) return false
        write(key, next)
        return true
    }

    fun restore(key: K): Boolean {
        val entry = entries.remove(key) ?: return false
        if (entry.applied == entry.base || read(key) != entry.applied) return false
        write(key, restored(entry.base))
        return true
    }

    fun restoreAll(except: K? = null): Boolean {
        var changed = false
        for (key in keys()) if (key !== except) changed = restore(key) || changed
        return changed
    }
}
