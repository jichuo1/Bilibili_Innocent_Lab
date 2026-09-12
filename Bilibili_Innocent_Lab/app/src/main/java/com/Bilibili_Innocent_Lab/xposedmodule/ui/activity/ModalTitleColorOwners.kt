package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.util.WeakHashMap

/** 主线程上的来源文字颜色记账。旧弹窗延迟释放时，不得恢复新弹窗仍借用的标题。 */
internal class ModalTitleColorOwners<K : Any, V : Any> {
    private class Entry<V>(val original: V, val owners: MutableMap<Any, Float> = mutableMapOf())
    private val entries = WeakHashMap<K, Entry<V>>()

    fun original(key: K, fallback: V): V = entries[key]?.original ?: fallback
    fun peek(key: K): V? = entries[key]?.original

    fun acquire(key: K, owner: Any, value: V) {
        entries.getOrPut(key) { Entry(value) }.owners.putIfAbsent(owner, 1f)
    }

    fun weight(key: K): Float = entries[key]?.owners?.values?.minOrNull() ?: 1f

    fun updateWeight(key: K, owner: Any, value: Float): Float {
        val entry = entries[key] ?: return 1f
        if (entry.owners.containsKey(owner)) entry.owners[owner] = value.coerceIn(0f, 1f)
        return weight(key)
    }

    /** 只有最后一个所有者释放时，才交还最初的颜色。重复释放不会产生额外恢复。 */
    fun release(key: K, owner: Any): V? {
        val entry = entries[key] ?: return null
        if (entry.owners.remove(owner) == null || entry.owners.isNotEmpty()) return null
        entries.remove(key)
        return entry.original
    }
}
