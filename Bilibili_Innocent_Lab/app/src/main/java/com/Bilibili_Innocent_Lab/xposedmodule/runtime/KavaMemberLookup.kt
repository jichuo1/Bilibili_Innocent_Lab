package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.hasClass
import com.highcapable.kavaref.extension.loadClassOrNull
import com.highcapable.kavaref.extension.makeAccessible
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * KavaRef 成员定位边界。
 *
 * 此对象只负责在调用方缓存未命中时定位原始 Member；它不缓存宿主实例，也不负责
 * 高频调用。调用方应继续缓存返回的 Java Member，并在热路径直接 invoke/get/newInstance。
 */
internal object KavaMemberLookup {

    /** 通过指定宿主 ClassLoader 加载类，不触发类初始化；版本漂移时返回 null。 */
    fun classOrNull(classLoader: ClassLoader?, name: String): Class<*>? =
        classLoader?.loadClassOrNull(name)

    /** 判断类是否存在，供版本适配和 Hook 分流的一次性探测使用。 */
    fun hasClass(classLoader: ClassLoader, name: String): Boolean =
        classLoader.hasClass(name)

    /** 精确定位当前类声明的指定方法；缺失或不可访问时返回 null。 */
    fun methodOrNull(
        declaringClass: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = runCatching {
        declaringClass.resolve()
            .optional(silent = true)
            .firstMethodOrNull {
                this.name = name
                parameters(*parameterTypes)
            }
            ?.self
            ?.takeIf { it.makeAccessible() }
    }.getOrNull()

    /** 精确定位类或其父类声明的指定方法。 */
    fun inheritedMethodOrNull(
        declaringClass: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = runCatching {
        declaringClass.resolve()
            .optional(silent = true)
            .firstMethodOrNull {
                this.name = name
                parameters(*parameterTypes)
                superclass()
            }
            ?.self
            ?.takeIf { it.makeAccessible() }
    }.getOrNull()

    /**
     * 解析当前类声明的方法集合。结构特征筛选仍由调用方表达，KavaRef 只承担统一枚举边界；
     * 返回原始 [Method]，便于 XposedBridge 注册和调用方现有缓存继续复用。
     */
    fun declaredMethods(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Method) -> Boolean = { true }
    ): List<Method> = methods(
        declaringClass = declaringClass,
        includeSuperclasses = false,
        makeAccessible = makeAccessible,
        predicate = predicate
    )

    /** 解析方法集合；[includeSuperclasses] 为 true 时由 KavaRef 统一遍历父类。 */
    fun methods(
        declaringClass: Class<*>,
        includeSuperclasses: Boolean,
        makeAccessible: Boolean = false,
        predicate: (Method) -> Boolean = { true }
    ): List<Method> = runCatching {
        declaringClass.resolve()
            .optional(silent = true)
            .method {
                if (includeSuperclasses) superclass()
            }
            .asSequence()
            .map { it.self }
            .filter(predicate)
            .filter { !makeAccessible || it.makeAccessible() }
            .toList()
    }.getOrDefault(emptyList())

    /** 精确定位字段；可选择向父类查找，缺失或不可访问时返回 null。 */
    fun fieldOrNull(
        declaringClass: Class<*>,
        name: String,
        includeSuperclasses: Boolean = false
    ): Field? = runCatching {
        declaringClass.resolve()
            .optional(silent = true)
            .firstFieldOrNull {
                this.name = name
                if (includeSuperclasses) superclass()
            }
            ?.self
            ?.takeIf { it.makeAccessible() }
    }.getOrNull()

    /** 解析当前类声明的字段集合；读取型调用方可要求统一开放访问权限。 */
    fun declaredFields(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Field) -> Boolean = { true }
    ): List<Field> = runCatching {
        declaringClass.resolve()
            .optional(silent = true)
            .field { }
            .asSequence()
            .map { it.self }
            .filter(predicate)
            .filter { !makeAccessible || it.makeAccessible() }
            .toList()
    }.getOrDefault(emptyList())

    /** 精确定位当前类声明的构造器；调用方继续缓存并直接 newInstance。 */
    fun constructorOrNull(
        declaringClass: Class<*>,
        vararg parameterTypes: Class<*>
    ): Constructor<*>? = runCatching {
        declaringClass.resolve()
            .optional(silent = true)
            .firstConstructorOrNull {
                parameters(*parameterTypes)
            }
            ?.self
            ?.takeIf { it.makeAccessible() }
    }.getOrNull()

    /** 解析当前类声明的构造器集合，供参数结构会随宿主版本漂移的 Hook 点使用。 */
    fun declaredConstructors(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Constructor<*>) -> Boolean = { true }
    ): List<Constructor<*>> = runCatching {
        declaringClass.resolve()
            .optional(silent = true)
            .constructor { }
            .asSequence()
            .map { it.self }
            .filter(predicate)
            .filter { !makeAccessible || it.makeAccessible() }
            .toList()
    }.getOrDefault(emptyList())
}
