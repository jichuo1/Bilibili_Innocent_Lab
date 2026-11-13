package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap

/** 只读观察评论树；保持 raw 读取、单条槽位和后序访问，不创建编辑列表或 builder。 */
internal class ProtobufReplyTreeVisitor(
    private val replyInfoClass: Class<*>,
    private val onReply: (Any) -> Unit = {}
) {
    private class Single(val getter: Method, val has: Method)
    private class Shape(val lists: List<Method>, val singles: List<Single>)
    private val shapes = ConcurrentHashMap<Class<*>, Shape>()

    fun visit(message: Any) = visit(message, Int.MAX_VALUE, onReply)

    fun visit(message: Any, limit: Int, consumer: (Any) -> Unit) {
        visit(message, 0, limit, consumer)
    }

    private fun visit(message: Any, depth: Int, limit: Int, consumer: (Any) -> Unit): Int {
        if (depth >= ProtobufReplyTreeRewriter.MAX_DEPTH) return limit
        var remaining = limit
        val shape = shapes[message.javaClass] ?: resolve(message.javaClass).also { shapes[message.javaClass] = it }
        for (getter in shape.lists) {
            val source = KotlinMossChannel.raw { getter.invoke(message) as? List<*> } ?: continue
            for (index in source.indices) {
                if (remaining <= 0) return 0
                val reply = source[index] ?: continue
                remaining = visit(reply, depth + 1, remaining - 1, consumer)
                consumer(reply)
            }
        }
        for (field in shape.singles) {
            if (remaining <= 0) return 0
            val present = KotlinMossChannel.raw {
                runCatching { field.has.invoke(message) as? Boolean }.getOrNull()
            } == true
            if (!present) continue
            val reply = KotlinMossChannel.raw { field.getter.invoke(message) } ?: continue
            remaining = visit(reply, depth + 1, remaining - 1, consumer)
            consumer(reply)
        }
        return remaining
    }

    private fun resolve(type: Class<*>): Shape {
        val getters = KavaMemberLookup.declaredMethods(type, makeAccessible = true) {
            !it.isStatic && it.parameterCount == 0 && it.name.startsWith("get")
        }
        val lists = getters.filter { getter ->
            if (!getter.name.endsWith("List") || !(getter.returnType isSubclassOf classOf<List<*>>())) return@filter false
            if (getter.name.removePrefix("get").removeSuffix("List").isEmpty()) return@filter false
            val argument = (getter.genericReturnType as? ParameterizedType)?.actualTypeArguments?.singleOrNull()
            val raw = when (argument) {
                is Class<*> -> argument
                is ParameterizedType -> argument.rawType
                else -> null
            }
            raw == replyInfoClass
        }
        val singles = getters.mapNotNull { getter ->
            if (getter.returnType != replyInfoClass) return@mapNotNull null
            val has = KavaMemberLookup.methodOrNull(type, "has${getter.name.removePrefix("get")}")
                ?.takeIf { !it.isStatic && it.returnType == classOf<Boolean>() } ?: return@mapNotNull null
            Single(getter, has)
        }
        return Shape(lists, singles)
    }
}
