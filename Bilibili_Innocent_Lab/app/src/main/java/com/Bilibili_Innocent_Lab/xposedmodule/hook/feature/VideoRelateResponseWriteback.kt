package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** 保留宿主 repeated 字段的 protobuf 类型、不可变性及消息缓存；只缓存稳定成员。 */
internal object VideoRelateResponseWriteback {
    private data class ListPlan(val copy: Method?, val freeze: Method?, val mutable: Method?)
    private data class CachePlan(val size: Method?, val resetSize: Method?, val hash: Field?)
    private val lists = ConcurrentHashMap<Class<*>, ListPlan>()
    private val messages = ConcurrentHashMap<Class<*>, CachePlan>()

    fun write(target: Any?, field: Field, items: List<*>): Boolean {
        if (target == null || field.isStatic || !field.declaringClass.isInstance(target)) return false
        return synchronized(target) {
            val original = runCatching { field.get(target) as? List<*> }.getOrNull()
                ?: return@synchronized false
            val protobuf = field.type.name == "com.google.protobuf.Internal\$ProtobufList"
            val cache = if (protobuf) cachePlan(target.javaClass) else null
            if (protobuf && (cache?.size == null || cache.resetSize == null || cache.hash == null)) {
                return@synchronized false
            }
            val replacement = if (protobuf) protobufCopy(field.type, original, items) else items
            if (replacement == null || !field.type.isInstance(replacement)) return@synchronized false
            // 普通 List 保持既有写回语义；protobuf 必须使用本次消息的原始列表创建独立副本。
            var oldSize: Int? = null
            var oldHash: Int? = null
            var changed = false
            runCatching {
                oldSize = cache?.size?.invoke(target) as? Int
                oldHash = cache?.hash?.getInt(target)
                if (protobuf) check(oldSize != null && oldHash != null)
                check(field.get(target) === original)
                field.set(target, replacement)
                changed = true
                cache?.resetSize?.invoke(target, -1)
                cache?.hash?.setInt(target, 0)
                check(field.get(target) === replacement)
                if (protobuf) {
                    check(cache?.size?.invoke(target) == -1)
                    check(cache.hash?.getInt(target) == 0)
                }
                true
            }.getOrElse {
                if (changed) runCatching {
                    if (field.get(target) === replacement) {
                        field.set(target, original)
                        oldSize?.let { value -> cache?.resetSize?.invoke(target, value) }
                        oldHash?.let { value -> cache?.hash?.setInt(target, value) }
                    }
                }
                false
            }
        }
    }

    private fun protobufCopy(type: Class<*>, original: List<*>, items: List<*>): List<*>? = runCatching {
        val plan = lists.computeIfAbsent(type) {
            val copy = Lookup.methodOrNull(it, "mutableCopyWithCapacity", Int::class.javaPrimitiveType!!)
                ?.takeIf { method -> !method.isStatic && method.returnType == type }
            val freeze = Lookup.methodOrNull(it, "makeImmutable")
                ?.takeIf { method -> !method.isStatic && method.returnType == Void.TYPE }
            val mutable = Lookup.methodOrNull(it, "isModifiable")
                ?.takeIf { method -> !method.isStatic && method.returnType == Boolean::class.javaPrimitiveType }
            ListPlan(copy, freeze, mutable)
        }
        val copy = plan.copy?.invoke(original, maxOf(original.size, items.size)) ?: return@runCatching null
        if (copy === original || !type.isInstance(copy) || plan.mutable?.invoke(copy) != true) {
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

    private fun cachePlan(type: Class<*>): CachePlan = messages.computeIfAbsent(type) {
        // 8.90.2 实际 GeneratedMessageLite 以 -1 / 0 表示未计算的 serialized size / hash。
        val size = Lookup.inheritedMethodOrNull(type, "getMemoizedSerializedSize")
            ?.takeIf { !it.isStatic && it.returnType == Int::class.javaPrimitiveType }
        val reset = Lookup.inheritedMethodOrNull(type, "setMemoizedSerializedSize", Int::class.javaPrimitiveType!!)
            ?.takeIf { !it.isStatic && it.returnType == Void.TYPE }
        val hash = Lookup.fieldOrNull(type, "memoizedHashCode", includeSuperclasses = true)
            ?.takeIf { !it.isStatic && it.type == Int::class.javaPrimitiveType }
        CachePlan(size, reset, hash)
    }
}
