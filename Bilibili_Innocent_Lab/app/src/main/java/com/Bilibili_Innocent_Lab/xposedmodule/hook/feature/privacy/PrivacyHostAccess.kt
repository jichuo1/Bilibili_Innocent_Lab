package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Host-loader-only lookup; caches classes/members, never host objects. Ambiguous overloads fail. */
internal class PrivacyHostAccess(val loader: ClassLoader) {
    private data class Key(val owner: Class<*>, val name: String, val types: List<Class<*>?>, val static: Boolean)
    private val methods = ConcurrentHashMap<Key, Method>()
    fun type(name: String): Class<*> = requireNotNull(Lookup.classOrNull(loader, name)) { name }
    fun call(target: Any, name: String, vararg args: Any?): Any? =
        member(target.javaClass, name, false, args).invoke(target, *args)
    fun static(owner: Class<*>, name: String, vararg args: Any?): Any? =
        member(owner, name, true, args).invoke(null, *args)
    fun create(owner: Class<*>): Any = requireNotNull(Lookup.constructorOrNull(owner)).newInstance()
    private fun member(owner: Class<*>, name: String, static: Boolean, args: Array<out Any?>): Method {
        val key = Key(owner, name, args.map { it?.javaClass }, static)
        return methods.getOrPut(key) {
            requireNotNull(Lookup.methods(owner, includeSuperclasses = true, makeAccessible = true) { m ->
                m.name == name && m.isStatic == static &&
                    m.parameterCount == args.size && m.parameterTypes.indices.all { i ->
                        val value = args[i]
                        if (value == null) !m.parameterTypes[i].isPrimitive
                        else boxed(m.parameterTypes[i]).isInstance(value)
                    }
            }.distinctBy { it.name to it.parameterTypes.toList() }.singleOrNull()) {
                "Missing or ambiguous host method: ${owner.name}#$name"
            }
        }
    }
    private fun boxed(type: Class<*>): Class<*> = when (type) {
        classOf<Int>() -> classOf<Int>(primitiveType = false)
        classOf<Long>() -> classOf<Long>(primitiveType = false)
        classOf<Boolean>() -> classOf<Boolean>(primitiveType = false)
        classOf<Float>() -> classOf<Float>(primitiveType = false)
        classOf<Double>() -> classOf<Double>(primitiveType = false)
        else -> type
    }
}
