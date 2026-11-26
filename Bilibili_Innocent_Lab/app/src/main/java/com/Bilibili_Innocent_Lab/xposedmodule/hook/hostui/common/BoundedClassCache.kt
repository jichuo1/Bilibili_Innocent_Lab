package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.view.View
import java.lang.ref.ReferenceQueue
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

/**
 * 弱键有界 LRU [Class] 缓存，用于宿主侧样式判定或反射探测结果的无泄漏复用。
 *
 * 设计约束：
 * - 键使用 [WeakReference] + [ReferenceQueue] 按引用身份哈希索引，绝不反向强持有宿主 `Class` 或 `ClassLoader`。
 * - 值禁止直接存入 [View] 或 [ClassLoader]；当缓存反射成员（如反向引用声明类的 `Method`/`Field`）时，
 *   可传入 [softValues] = `true` 允许 GC 及时解除反向强引用链。
 * - 当 [registerTrim] 为 `true` 时，通过弱引用向 [HostResourceRegistry] 注册租约句柄，
 *   在 [HostTrimSeverity.HIDDEN] / [HostTrimSeverity.MODERATE] / [HostTrimSeverity.CRITICAL] 下自动裁剪或清空。
 */
internal class BoundedClassCache<V : Any>(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val tag: String = "BoundedClassCache",
    private val softValues: Boolean = false,
    private val hiddenRetentionPercent: Int = 50,
    private val moderateRetentionPercent: Int = 25,
    registerTrim: Boolean = true
) : AutoCloseable {

    init {
        require(maxEntries > 0) { "maxEntries must be > 0, got $maxEntries" }
        require(hiddenRetentionPercent in 0..100) {
            "hiddenRetentionPercent must be in 0..100, got $hiddenRetentionPercent"
        }
        require(moderateRetentionPercent in 0..100) {
            "moderateRetentionPercent must be in 0..100, got $moderateRetentionPercent"
        }
    }

    private object MissSentinel

    private class SoftValue(value: Any) : SoftReference<Any>(value)

    private class WeakClassKey(
        clazz: Class<*>,
        queue: ReferenceQueue<Class<*>>
    ) : WeakReference<Class<*>>(clazz, queue) {
        val hash: Int = System.identityHashCode(clazz)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val referent = get() ?: return false
            return when (other) {
                is WeakClassKey -> referent === other.get()
                is LookupProbe -> referent === other.clazz
                else -> false
            }
        }
    }

    private class LookupProbe {
        var clazz: Class<*>? = null
        var hash: Int = 0

        fun bind(target: Class<*>) {
            clazz = target
            hash = System.identityHashCode(target)
        }

        fun clear() {
            clazz = null
            hash = 0
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            val target = clazz ?: return false
            return when (other) {
                is LookupProbe -> target === other.clazz
                is WeakClassKey -> target === other.get()
                else -> false
            }
        }
    }

    private val lock = Any()
    private val queue = ReferenceQueue<Class<*>>()
    private val entries = LinkedHashMap<WeakClassKey, Any>(16, 0.75f, true)
    private val probe = LookupProbe()

    @Volatile
    private var lease: AutoCloseable? = null

    init {
        if (registerTrim) {
            val weakSelf = WeakReference(this)
            val leaseHolder = arrayOfNulls<AutoCloseable>(1)
            val handle = HostResourceRegistry.register(tag) { severity ->
                val target = weakSelf.get()
                if (target == null) {
                    leaseHolder[0]?.close()
                } else {
                    target.trim(severity)
                }
            }
            leaseHolder[0] = handle
            lease = handle
        }
    }

    val size: Int
        get() = synchronized(lock) {
            expungeStaleLocked()
            entries.size
        }

    fun get(clazz: Class<*>): V? = synchronized(lock) {
        expungeStaleLocked()
        val stored = lookupLocked(clazz) ?: return null
        unwrapNonNullLocked(clazz, stored)
    }

    fun containsKey(clazz: Class<*>): Boolean = synchronized(lock) {
        expungeStaleLocked()
        val stored = lookupLocked(clazz) ?: return false
        if (stored === MissSentinel) return true
        if (stored is SoftValue) {
            val unwrapped = stored.get()
            if (unwrapped == null) {
                removeLocked(clazz)
                return false
            }
        }
        true
    }

    fun put(clazz: Class<*>, value: V): V? {
        guardValue(value)
        return synchronized(lock) {
            expungeStaleLocked()
            val encoded = encodeValue(value)
            val existing = lookupLocked(clazz)
            val previous = decodeIfAlive(existing)
            removeLocked(clazz)
            entries[WeakClassKey(clazz, queue)] = encoded
            evictToLocked(maxEntries)
            previous
        }
    }

    fun putNull(clazz: Class<*>) {
        synchronized(lock) {
            expungeStaleLocked()
            removeLocked(clazz)
            entries[WeakClassKey(clazz, queue)] = MissSentinel
            evictToLocked(maxEntries)
        }
    }

    inline fun getOrPut(clazz: Class<*>, compute: (Class<*>) -> V): V {
        get(clazz)?.let { return it }
        val computed = compute(clazz)
        return putIfAbsentOrGet(clazz, computed)
    }

    inline fun getOrPutNullable(clazz: Class<*>, compute: (Class<*>) -> V?): V? {
        val state = peekNullableState(clazz)
        if (state !== CACHE_MISS_MARKER) {
            @Suppress("UNCHECKED_CAST")
            return state as V?
        }
        val computed = compute(clazz)
        return putNullableIfAbsentOrGet(clazz, computed)
    }

    fun remove(clazz: Class<*>): V? = synchronized(lock) {
        expungeStaleLocked()
        val removed = removeLocked(clazz)
        decodeIfAlive(removed)
    }

    fun trim(severity: HostTrimSeverity) {
        synchronized(lock) {
            expungeStaleLocked()
            when (severity) {
                HostTrimSeverity.HIDDEN -> {
                    val target = (entries.size * hiddenRetentionPercent) / 100
                    evictToLocked(target)
                }
                HostTrimSeverity.MODERATE -> {
                    val target = (entries.size * moderateRetentionPercent) / 100
                    evictToLocked(target)
                }
                HostTrimSeverity.CRITICAL -> {
                    entries.clear()
                    drainQueueLocked()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            drainQueueLocked()
        }
    }

    fun expungeStale(): Int = synchronized(lock) {
        expungeStaleLocked()
    }

    /**
     * 释放底层 [HostResourceRegistry] 租约并清空缓存，允许重复调用。
     */
    override fun close() {
        val current = lease
        lease = null
        current?.close()
        clear()
    }

    @PublishedApi
    internal fun putIfAbsentOrGet(clazz: Class<*>, value: V): V {
        guardValue(value)
        return synchronized(lock) {
            expungeStaleLocked()
            val existing = lookupLocked(clazz)
            val alive = decodeIfAlive(existing)
            if (alive != null) return alive
            if (existing != null) removeLocked(clazz)
            entries[WeakClassKey(clazz, queue)] = encodeValue(value)
            evictToLocked(maxEntries)
            value
        }
    }

    @PublishedApi
    internal fun peekNullableState(clazz: Class<*>): Any? = synchronized(lock) {
        expungeStaleLocked()
        val stored = lookupLocked(clazz) ?: return CACHE_MISS_MARKER
        if (stored === MissSentinel) return null
        unwrapNonNullLocked(clazz, stored) ?: CACHE_MISS_MARKER
    }

    @PublishedApi
    internal fun putNullableIfAbsentOrGet(clazz: Class<*>, value: V?): V? {
        if (value != null) guardValue(value)
        return synchronized(lock) {
            expungeStaleLocked()
            val existing = lookupLocked(clazz)
            if (existing === MissSentinel) return null
            val alive = decodeIfAlive(existing)
            if (alive != null) return alive
            if (existing != null) removeLocked(clazz)
            entries[WeakClassKey(clazz, queue)] = value?.let(::encodeValue) ?: MissSentinel
            evictToLocked(maxEntries)
            value
        }
    }

    private fun lookupLocked(clazz: Class<*>): Any? {
        @Suppress("UNCHECKED_CAST")
        val map = entries as MutableMap<Any, Any>
        probe.bind(clazz)
        return try {
            map[probe]
        } finally {
            probe.clear()
        }
    }

    private fun removeLocked(clazz: Class<*>): Any? {
        @Suppress("UNCHECKED_CAST")
        val map = entries as MutableMap<Any, Any>
        probe.bind(clazz)
        return try {
            map.remove(probe)
        } finally {
            probe.clear()
        }
    }

    private fun unwrapNonNullLocked(clazz: Class<*>, stored: Any): V? {
        if (stored === MissSentinel) return null
        if (stored is SoftValue) {
            val unwrapped = stored.get()
            if (unwrapped == null) {
                removeLocked(clazz)
                return null
            }
            @Suppress("UNCHECKED_CAST")
            return unwrapped as V
        }
        @Suppress("UNCHECKED_CAST")
        return stored as V
    }

    private fun decodeIfAlive(stored: Any?): V? {
        if (stored == null || stored === MissSentinel) return null
        if (stored is SoftValue) {
            @Suppress("UNCHECKED_CAST")
            return stored.get() as? V
        }
        @Suppress("UNCHECKED_CAST")
        return stored as V
    }

    private fun encodeValue(value: V): Any = if (softValues) SoftValue(value) else value

    private fun guardValue(value: V) {
        require(value !is View) { "BoundedClassCache must not retain View instances" }
        require(value !is ClassLoader) { "BoundedClassCache must not retain ClassLoader instances" }
    }

    private fun expungeStaleLocked(): Int {
        var removed = 0
        while (true) {
            val ref = queue.poll() ?: break
            if (ref is WeakClassKey && entries.remove(ref) != null) {
                removed++
            }
        }
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val deadKey = entry.key.get() == null
            val deadValue = entry.value.let { it is SoftValue && it.get() == null }
            if (deadKey || deadValue) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private fun drainQueueLocked() {
        while (queue.poll() != null) {
            // Drain queue so cleared weak references are discarded immediately.
        }
    }

    private fun evictToLocked(targetSize: Int) {
        val boundedTarget = targetSize.coerceAtLeast(0)
        if (entries.size <= boundedTarget) return
        val iterator = entries.entries.iterator()
        while (entries.size > boundedTarget && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    companion object {
        internal const val DEFAULT_MAX_ENTRIES = 64

        @PublishedApi
        internal val CACHE_MISS_MARKER = Any()

        /**
         * 构造面向 `Method`/`Field`/`Constructor` 等强关联声明类的软引用值缓存。
         */
        fun <V : Any> forReflection(
            maxEntries: Int = DEFAULT_MAX_ENTRIES,
            tag: String = "BoundedReflectionClassCache",
            registerTrim: Boolean = true
        ): BoundedClassCache<V> = BoundedClassCache(
            maxEntries = maxEntries,
            tag = tag,
            softValues = true,
            registerTrim = registerTrim
        )
    }
}
