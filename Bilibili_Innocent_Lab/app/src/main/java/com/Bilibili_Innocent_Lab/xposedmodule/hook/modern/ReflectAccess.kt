package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.app.Application
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.toClass
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Legacy XposedHelpers 普通反射调用的窄接口替代；不承担 Hook 注册。 */
internal object ReflectAccess {
    private const val MAX_METHOD_CACHE_ENTRIES = 256
    private val EMPTY_ARG_TYPES: Array<Class<*>?> = emptyArray()

    private class MethodCacheKey(
        val owner: Class<*>,
        val name: String,
        val requireStatic: Boolean,
        val argTypes: Array<Class<*>?>,
        private val hash: Int = computeHash(owner, name, requireStatic, argTypes)
    ) {
        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MethodCacheKey) return false
            return hash == other.hash &&
                requireStatic == other.requireStatic &&
                owner === other.owner &&
                name == other.name &&
                argTypes.contentEquals(other.argTypes)
        }

        private companion object {
            fun computeHash(
                owner: Class<*>,
                name: String,
                requireStatic: Boolean,
                argTypes: Array<Class<*>?>
            ): Int {
                var result = System.identityHashCode(owner)
                result = 31 * result + name.hashCode()
                result = 31 * result + if (requireStatic) 1 else 0
                result = 31 * result + argTypes.contentHashCode()
                return result
            }
        }
    }

    private val compatibleMethodCache = object : LinkedHashMap<MethodCacheKey, Method>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MethodCacheKey, Method>?): Boolean =
            size > MAX_METHOD_CACHE_ENTRIES
    }

    @Volatile
    private var currentApplicationMethod: Method? = null

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
        val method = currentApplicationMethod ?: run {
            val owner = "android.app.ActivityThread".toClass()
            KavaMemberLookup.methodOrNull(owner, "currentApplication")?.also {
                currentApplicationMethod = it
            }
        } ?: return@runCatching null
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
        val argTypes: Array<Class<*>?> = if (args.isEmpty()) {
            EMPTY_ARG_TYPES
        } else {
            Array(args.size) { index -> args[index]?.javaClass }
        }
        val key = MethodCacheKey(owner, name, requireStatic, argTypes)
        synchronized(compatibleMethodCache) {
            compatibleMethodCache[key]?.let { return it }
        }
        var current: Class<*>? = owner
        while (current != null) {
            // 只要第一个兼容候选：在已缓存的成员集合上短路，不物化整份过滤结果。
            val match = KavaMemberLookup.firstDeclaredMethodOrNull(current, makeAccessible = true) { method ->
                if (method.name != name || method.isStatic != requireStatic || method.parameterCount != args.size) {
                    false
                } else {
                    val parameterTypes = method.parameterTypes
                    parameterTypes.indices.all { index -> compatible(parameterTypes[index], args[index]) }
                }
            }
            if (match != null) {
                synchronized(compatibleMethodCache) {
                    compatibleMethodCache[key] = match
                }
                return match
            }
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
