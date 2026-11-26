package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** 保留宿主 repeated 字段的 protobuf 类型、不可变性及消息缓存；只缓存稳定成员。 */
internal object VideoRelateResponseWriteback {
    private const val PROTOBUF_LIST = "com.google.protobuf.Internal\$ProtobufList"
    private data class ListPlan(val copy: Method?, val freeze: Method?, val mutable: Method?)
    private val lists = ConcurrentHashMap<Class<*>, ListPlan>()
    private val messages = ConcurrentHashMap<Class<*>, Any>()
    private val NO_CACHE = Any()

    fun write(target: Any?, field: Field, items: List<*>): Boolean {
        if (target == null || field.isStatic || !field.declaringClass.isInstance(target)) return false
        return synchronized(target) {
            val original = runCatching { field.get(target) as? List<*> }.getOrNull()
                ?: return@synchronized false
            val protobuf = field.type.name == PROTOBUF_LIST ||
                (List::class.java.isAssignableFrom(field.type) && implementsProtobufList(original.javaClass))
            val cache = if (protobuf) cachePlan(target.javaClass) else null
            if (protobuf && cache == null) {
                return@synchronized false
            }
            if (protobuf && cache?.defaultInstance != null && target === cache.defaultInstance) {
                return@synchronized false
            }
            val replacement = if (protobuf) protobufCopy(original.javaClass, original, items) else items
            if (replacement == null || !field.type.isInstance(replacement)) return@synchronized false
            // 普通 List 保持既有写回语义；protobuf 必须使用本次消息的原始列表创建独立副本。
            var oldSize: Int? = null
            var oldHash: Int? = null
            var changed = false
            runCatching {
                oldSize = cache?.readSize(target)
                oldHash = cache?.readHash(target)
                if (protobuf) check(oldSize != null && oldHash != null)
                check(field.get(target) === original)
                field.set(target, replacement)
                changed = true
                cache?.invalidate(target)
                check(field.get(target) === replacement)
                if (protobuf) {
                    val activeCache = checkNotNull(cache)
                    check(activeCache.readSize(target) == activeCache.sentinel)
                    check(activeCache.readHash(target) == 0)
                }
                true
            }.getOrElse {
                if (changed) runCatching {
                    if (field.get(target) === replacement) {
                        field.set(target, original)
                        if (cache != null && oldSize != null && oldHash != null) {
                            cache.restore(target, oldSize, oldHash)
                        }
                    }
                }
                false
            }
        }
    }

    private fun implementsProtobufList(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name == PROTOBUF_LIST || current.interfaces.any(::implementsProtobufList)) return true
            current = current.superclass
        }
        return false
    }

    private fun protobufCopy(runtimeType: Class<*>, original: List<*>, items: List<*>): List<*>? = runCatching {
        val plan = lists.computeIfAbsent(runtimeType) {
            val copy = Lookup.methodOrNull(it, "mutableCopyWithCapacity", Int::class.javaPrimitiveType!!)
                ?.takeIf { method ->
                    !method.isStatic &&
                        (method.returnType == it || method.returnType.name == PROTOBUF_LIST || method.returnType.isAssignableFrom(it))
                }
            val freeze = Lookup.methodOrNull(it, "makeImmutable")
                ?.takeIf { method -> !method.isStatic && method.returnType == Void.TYPE }
            val mutable = Lookup.methodOrNull(it, "isModifiable")
                ?.takeIf { method -> !method.isStatic && method.returnType == Boolean::class.javaPrimitiveType }
            ListPlan(copy, freeze, mutable)
        }
        val copy = plan.copy?.invoke(original, maxOf(original.size, items.size)) ?: return@runCatching null
        if (copy === original || !implementsProtobufList(copy.javaClass) || plan.mutable?.invoke(copy) != true) {
            return@runCatching null
        }
        @Suppress("UNCHECKED_CAST")
        val list = copy as? MutableList<Any?> ?: return@runCatching null
        list.clear()
        list.addAll(items)
        if (list.size != items.size || list.indices.any { list[it] !== items[it] }) return@runCatching null
        val freeze = plan.freeze ?: return@runCatching null
        freeze.invoke(copy)
        if (plan.mutable.invoke(copy) != false) return@runCatching null
        list
    }.getOrNull()

    private fun cachePlan(type: Class<*>): ProtobufCachePlan? =
        messages.computeIfAbsent(type) { ProtobufCachePlan.resolve(it) ?: NO_CACHE } as? ProtobufCachePlan
}
