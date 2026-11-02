package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 安装期验证宿主 Auto/Fixed ABI；只保留成员和无状态单例，不持有播放器会话。 */
internal class StoryQualityPreferenceAccess private constructor(
    val auto: Any,
    private val fixedClass: Class<*>,
    private val fixedConstructor: Constructor<*>,
    private val quality: Field
) {
    fun isAuto(value: Any?): Boolean = value === auto

    fun qualityOf(value: Any?): Int? =
        if (value?.javaClass != fixedClass) null else runCatching { quality.getInt(value) }.getOrNull()

    /** 使用安装期解析的工厂，并核实实际存入的 QN。异常或负值不产生替换对象。 */
    fun fixed(qn: Int): Any? = if (qn < 0) null else runCatching {
        create(fixedConstructor, qn).takeIf { qualityOf(it) == qn }
    }.getOrNull()

    companion object {
        private const val MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"
        private const val MAX_SUBTYPES = 8
        private const val MAX_MEMBERS = 16
        private val ROUND_TRIP_QNS = listOf(16, 80, 127)

        fun resolve(loader: ClassLoader, base: Class<*>): StoryQualityPreferenceAccess? = runCatching {
            if (!Modifier.isAbstract(base.modifiers) || base.isAnnotation ||
                (base.isInterface && base.interfaces.isNotEmpty()) ||
                Lookup.classOrNull(loader, base.name) !== base) return null
            if (Lookup.declaredFields(base).any { !Modifier.isStatic(it.modifiers) }) return null
            // R8 匿名子类可能不在 MemberClasses；后缀只是补全候选，语义仍按 ABI 判定。
            val declared = base.declaredClasses.toList()
            if (declared.size > MAX_SUBTYPES) return null
            val named = listOf("a", "b").mapNotNull {
                val name = base.name + '$' + it
                Lookup.classOrNull(loader, name)?.takeIf { candidate -> candidate.name == name }
            }
            val children = (declared + named).distinct().filter { it != base && base.isAssignableFrom(it) }
            // 8.90.2 是接口，Auto/Fixed 直接实现它且仅继承 Object；旧抽象类契约继续保留。
            fun directChild(child: Class<*>): Boolean = if (base.isInterface) {
                child.superclass == Any::class.java && child.interfaces.contentEquals(arrayOf(base))
            } else child.superclass == base
            if (children.size != 2 || children.any {
                    !directChild(it) || !Modifier.isFinal(it.modifiers) ||
                        Lookup.classOrNull(loader, it.name) !== it
                }) return null
            val marker = Lookup.classOrNull(loader, MARKER)
            val autos = mutableListOf<Any>()
            val fixed = mutableListOf<FixedFactory>()
            for (child in children) {
                val fields = Lookup.declaredFields(child)
                val constructors = Lookup.declaredConstructors(child)
                if (fields.size > MAX_MEMBERS || constructors.size > MAX_MEMBERS) return null
                val values = fields.filter { !Modifier.isStatic(it.modifiers) }
                if (values.isEmpty()) {
                    val singleton = fields.filter {
                        Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.type == child
                    }.singleOrNull() ?: return null
                    val readable = Lookup.fieldOrNull(child, singleton.name) ?: return null
                    val instance = readable.get(null)?.takeIf { it.javaClass == child } ?: return null
                    autos += instance
                } else {
                    val value = values.singleOrNull()?.takeIf {
                        it.type == Int::class.javaPrimitiveType && Modifier.isFinal(it.modifiers)
                    } ?: return null
                    val quality = Lookup.fieldOrNull(child, value.name) ?: return null
                    val factories = constructors.filter { constructor ->
                        val params = constructor.parameterTypes
                        params.contentEquals(arrayOf(Int::class.javaPrimitiveType)) ||
                            (marker != null && params.contentEquals(arrayOf(Int::class.javaPrimitiveType, marker)))
                    }
                    if (factories.isEmpty() || factories.size != constructors.size) return null
                    val accessible = factories.map { constructor ->
                        Lookup.constructorOrNull(child, *constructor.parameterTypes) ?: return null
                    }
                    // 私有主构造和 public marker 桥属于同一 Fixed 家族；两者都必须保持 QN。
                    if (accessible.any { factory -> ROUND_TRIP_QNS.any { qn ->
                            val instance = create(factory, qn)
                            instance.javaClass != child || quality.getInt(instance) != qn
                        } }) return null
                    fixed += FixedFactory(child, accessible.minBy { it.parameterCount }, quality)
                }
            }
            val singleton = autos.singleOrNull() ?: return null
            val factory = fixed.singleOrNull() ?: return null
            StoryQualityPreferenceAccess(singleton, factory.owner, factory.constructor, factory.quality)
        }.getOrNull()

        private fun create(constructor: Constructor<*>, qn: Int): Any =
            if (constructor.parameterCount == 1) constructor.newInstance(qn)
            else constructor.newInstance(qn, null)

        private data class FixedFactory(val owner: Class<*>, val constructor: Constructor<*>, val quality: Field)
    }
}
