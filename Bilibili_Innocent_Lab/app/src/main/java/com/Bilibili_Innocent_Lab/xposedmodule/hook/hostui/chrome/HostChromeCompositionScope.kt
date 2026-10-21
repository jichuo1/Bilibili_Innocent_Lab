package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

/** Per-invocation scopes, including nested composition; no allocation outside the home chrome. */
internal class HostChromeCompositionScope<T> {
    internal class Entry<T>(val value: T) { var claimed = false }
    private val active = ThreadLocal<ArrayDeque<Entry<T>>>()
    fun enter(value: T): Entry<T> = Entry(value).also(::push)
    fun push(entry: Entry<T>) {
        val stack = active.get() ?: ArrayDeque<Entry<T>>().also(active::set)
        stack.addLast(entry)
    }
    fun exit(entry: Entry<T>) {
        val stack = active.get() ?: return
        stack.remove(entry)
        if (stack.isEmpty()) active.remove()
    }
    fun claim(): T? {
        val entry = active.get()?.lastOrNull() ?: return null
        if (entry.claimed) return null
        entry.claimed = true
        return entry.value
    }
    fun claimWhere(predicate: (T) -> Boolean): T? {
        val entry = active.get()?.lastOrNull { !it.claimed && predicate(it.value) } ?: return null
        entry.claimed = true
        return entry.value
    }
    fun current(): T? = active.get()?.lastOrNull()?.takeUnless { it.claimed }?.value
}
