package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Identify the native fill in this root's Modifier chain, even when R8 renames its private class. */
internal class HostChromeRootFill(
    private val element: Class<*>, private val brush: Class<*>, private val shape: Class<*>,
    private val function: Class<*>, private val empty: Any, private val factory: Method
) {
    private val verified = HashMap<Class<*>, Boolean>()

    fun matches(value: Any): Boolean = verified.getOrPut(value.javaClass) {
        val owner = value.javaClass
        if (!element.isInstance(value)) return@getOrPut false
        val fields = Lookup.declaredFields(owner, makeAccessible = true).filterNot { Modifier.isStatic(it.modifiers) }
        val basic = listOf(Long::class.javaPrimitiveType!!, brush, Float::class.javaPrimitiveType!!, shape)
        val expected = when (fields.size) {
            4 -> basic
            5 -> basic + function
            else -> return@getOrPut false
        }
        if (fields.map { it.type }.groupingBy { it }.eachCount() != expected.groupingBy { it }.eachCount()) return@getOrPut false
        val constructor = Lookup.declaredConstructors(owner).any {
            val p = it.parameterTypes.toList()
            p.take(expected.size) == expected && p.drop(expected.size).all { tail ->
                tail == Int::class.javaPrimitiveType || tail.name == "kotlin.jvm.internal.DefaultConstructorMarker"
            }
        }
        if (!constructor) return@getOrPut false
        val nativeShape = fields.single { it.type == shape }.get(value) ?: return@getOrPut false
        // A lookalike shape is insufficient. The host's own color-background factory must create
        // this exact class from an empty Modifier. The probe is never attached or drawn.
        val probe = requireNotNull(factory.invoke(null, empty, 0xFF010203L shl 32, nativeShape))
        probe.javaClass === owner
    }
}
