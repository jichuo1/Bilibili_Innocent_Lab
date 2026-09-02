package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.app.Application
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.toClass
import java.lang.reflect.Field
import java.lang.reflect.Method

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
        val owner = "android.app.ActivityThread".toClass()
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
                    method.isStatic == requireStatic &&
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

    /**
     * 这里必须是 **装箱** 类型：`isInstance` 对原始类型 Class 恒返回 false。
     *
     * `classOf` 默认解析为原始类型，因此每一处都必须显式传 `primitiveType = false`；
     * 漏传会让所有原始类型参数的方法匹配静默失效。由 `ReflectAccessPrimitiveArgTest` 锁定。
     */
    private fun compatible(parameterType: Class<*>, argument: Any?): Boolean {
        if (argument == null) return !parameterType.isPrimitive
        val boxed = when (parameterType) {
            classOf<java.lang.Boolean>() -> classOf<java.lang.Boolean>(primitiveType = false)
            classOf<java.lang.Byte>() -> classOf<java.lang.Byte>(primitiveType = false)
            classOf<java.lang.Character>() -> classOf<java.lang.Character>(primitiveType = false)
            classOf<java.lang.Short>() -> classOf<java.lang.Short>(primitiveType = false)
            classOf<java.lang.Integer>() -> classOf<java.lang.Integer>(primitiveType = false)
            classOf<java.lang.Long>() -> classOf<java.lang.Long>(primitiveType = false)
            classOf<java.lang.Float>() -> classOf<java.lang.Float>(primitiveType = false)
            classOf<java.lang.Double>() -> classOf<java.lang.Double>(primitiveType = false)
            else -> parameterType
        }
        return boxed.isInstance(argument)
    }
}
