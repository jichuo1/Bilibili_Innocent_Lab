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
    /** Two nested restart boundaries may emit the same root; both must consume that one claim. */
    fun claimTogether(predicate: (T) -> Boolean): T? {
        val stack = active.get() ?: return null
        val entry = stack.lastOrNull { !it.claimed && predicate(it.value) } ?: return null
        stack.forEach { if (predicate(it.value)) it.claimed = true }
        return entry.value
    }
    fun current(): T? = active.get()?.lastOrNull()?.takeUnless { it.claimed }?.value
    /** The innermost matching invocation, claimed or not; for reads nested anywhere inside it. */
    fun peek(predicate: (T) -> Boolean): T? = active.get()?.lastOrNull { predicate(it.value) }?.value
}
