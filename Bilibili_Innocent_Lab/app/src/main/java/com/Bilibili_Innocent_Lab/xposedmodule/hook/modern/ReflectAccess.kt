package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.app.Application
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Legacy XposedHelpers 普通反射调用的窄接口替代；不承担 Hook 注册。 */
internal object ReflectAccess {
    fun getField(target: Any, name: String): Any? = field(target.javaClass, name).get(target)

    fun setField(target: Any, name: String, value: Any?) {
        field(target.javaClass, name).set(target, value)
    }

    fun getLongField(target: Any, name: String): Long =
        (getField(target, name) as Number).toLong()

    fun setLongField(target: Any, name: String, value: Long) {
        field(target.javaClass, name).setLong(target, value)
    }

    fun setIntField(target: Any, name: String, value: Int) {
        field(target.javaClass, name).setInt(target, value)
    }

    fun callMethod(target: Any?, name: String, vararg args: Any?): Any? {
        requireNotNull(target) { "target is null for $name" }
        val method = findCompatibleMethod(target.javaClass, name, args)
            ?: throw NoSuchMethodException("${target.javaClass.name}#$name/${args.size}")
        return method.invoke(target, *args)
    }

    fun callStaticMethod(owner: Class<*>, name: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(owner, name, args, requireStatic = true)
            ?: throw NoSuchMethodException("${owner.name}#$name/${args.size}")
        return method.invoke(null, *args)
    }

    fun currentApplication(): Application? = runCatching {
        val owner = Class.forName("android.app.ActivityThread")
        val method = KavaMemberLookup.methodOrNull(owner, "currentApplication")
            ?: return@runCatching null
        method.invoke(null) as? Application
    }.getOrNull()

    private fun field(owner: Class<*>, name: String): Field =
        KavaMemberLookup.fieldOrNull(owner, name, includeSuperclasses = true)
            ?: throw NoSuchFieldException("${owner.name}#$name")

    private fun findCompatibleMethod(
        owner: Class<*>,
        name: String,
        args: Array<out Any?>,
        requireStatic: Boolean = false
    ): Method? {
        var current: Class<*>? = owner
        while (current != null) {
            val match = KavaMemberLookup.declaredMethods(current, makeAccessible = true) { method ->
                method.name == name &&
                    Modifier.isStatic(method.modifiers) == requireStatic &&
                    method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { index ->
                        compatible(method.parameterTypes[index], args[index])
                    }
            }.firstOrNull()
            if (match != null) return match
            current = current.superclass
        }
        return null
    }

    private fun compatible(parameterType: Class<*>, argument: Any?): Boolean {
        if (argument == null) return !parameterType.isPrimitive
        val boxed = when (parameterType) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> parameterType
        }
        return boxed.isInstance(argument)
    }
}
