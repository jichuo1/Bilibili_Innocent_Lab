package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Identify the native fill in this root's Modifier chain, even when R8 renames or reshapes its private class. */
internal class HostChromeRootFill(
    private val element: Class<*>, private val brush: Class<*>, private val shape: Class<*>,
    private val function: Class<*>, private val empty: Any, private val factory: Method,
    private val probeShape: Any? = null
) {
    private data class Verified(val shape: Field?)
    private val verified = HashMap<Class<*>, Verified>()
    @Volatile private var probedAttempted = false
    @Volatile private var probedOwner: Class<*>? = null
    @Volatile private var probedShapeField: Field? = null

    fun matches(value: Any): Boolean = shapeField(value) != null

    /** A fresh native element keeps identity ownership even when the rest is a shared Modifier. */
    fun transparent(value: Any): Any? {
        val field = shapeField(value) ?: return null
        val nativeShape = field.get(value) ?: return null
        return requireNotNull(factory.invoke(null, empty, 0L, nativeShape)).also {
            check(it.javaClass === value.javaClass)
        }
    }

    private fun ensureProbed() {
        val seedShape = probeShape ?: return
        if (probedAttempted) return
        synchronized(verified) {
            if (probedAttempted) return
            try {
                val probe = runCatching {
                    factory.invoke(null, empty, 0xFF010203L shl 32, seedShape)
                }.getOrNull() ?: return
                val owner = probe.javaClass
                if (!element.isAssignableFrom(owner)) return
                val shapeFields = Lookup.fields(owner, includeSuperclasses = true, makeAccessible = true)
                    .filter { !Modifier.isStatic(it.modifiers) && shape.isAssignableFrom(it.type) }
                val resolvedField = shapeFields.singleOrNull { it.type == shape } ?: shapeFields.singleOrNull()
                if (resolvedField != null) {
                    probedOwner = owner
                    probedShapeField = resolvedField
                }
            } finally {
                probedAttempted = true
            }
        }
    }

    private fun shapeField(value: Any): Field? {
        if (!element.isInstance(value)) return null
        if (probeShape != null) {
            ensureProbed()
            val nativeOwner = probedOwner
            if (nativeOwner != null) {
                return if (value.javaClass === nativeOwner) probedShapeField else null
            }
        }
        return synchronized(verified) { verified.getOrPut(value.javaClass) {
            val owner = value.javaClass
            val fields = Lookup.declaredFields(owner, makeAccessible = true).filterNot { Modifier.isStatic(it.modifiers) }
            val basic = listOf(Long::class.javaPrimitiveType!!, brush, Float::class.javaPrimitiveType!!, shape)
            val expected = when (fields.size) {
                4 -> basic
                5 -> basic + function
                else -> return@getOrPut Verified(null)
            }
            if (fields.map { it.type }.groupingBy { it }.eachCount() != expected.groupingBy { it }.eachCount()) return@getOrPut Verified(null)
            val constructor = Lookup.declaredConstructors(owner).any {
                val p = it.parameterTypes.toList()
                p.take(expected.size) == expected && p.drop(expected.size).all { tail ->
                    tail == Int::class.javaPrimitiveType || tail.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                }
            }
            if (!constructor) return@getOrPut Verified(null)
            val field = fields.single { it.type == shape }
            val nativeShape = field.get(value) ?: return@getOrPut Verified(null)
            // A lookalike shape is insufficient. The host's own color-background factory must create
            // this exact class from an empty Modifier. The probe is never attached or drawn.
            val probe = requireNotNull(factory.invoke(null, empty, 0xFF010203L shl 32, nativeShape))
            Verified(field.takeIf { probe.javaClass === owner })
        } }.shape
    }
}
