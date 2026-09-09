package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.lang.ref.WeakReference

/** Stable IDs enrich the existing search index; this registry contains no settings values or actions. */
internal class SettingsDestinationRegistry<T : Any> {
    private val targets = linkedMapOf<String, MutableList<WeakReference<T>>>()
    fun bind(id: String, target: T) {
        val entries = targets.getOrPut(id) { mutableListOf() }
        entries.removeAll { it.get() == null }
        if (entries.none { it.get() === target }) entries += WeakReference(target)
    }
    fun idsFor(target: T): Set<String> = targets.filterValues { refs -> refs.any { it.get() === target } }.keys
    fun resolve(id: String, available: (T) -> Boolean): T? =
        targets[id]?.mapNotNull { it.get() }?.filter(available)?.singleOrNull()
    fun clear() = targets.clear()
}
