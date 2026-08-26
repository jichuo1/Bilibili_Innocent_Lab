package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.loadClassOrNull
import com.highcapable.kavaref.extension.makeAccessible
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * KavaRef 成员定位边界。
 *
 * 只缓存进程生命周期内稳定的 Class/Member 与负查询结果，绝不缓存宿主实例。
 * 调用方在热路径仍应保留就地 Member 引用并直接 invoke/get/newInstance。
 */
internal object KavaMemberLookup {

    data class Diagnostics(
        val cacheHits: Long,
        val cacheMisses: Long,
        val lookupFailures: Long,
        val cachedClasses: Int,
        val cachedMethods: Int,
        val cachedFields: Int,
        val cachedConstructors: Int,
        val cachedMemberCollections: Int
    )

    private data class CachedValue<T>(val value: T?)
    private data class ClassKey(val classLoader: ClassLoader, val name: String)
    private data class MethodKey(
        val declaringClass: Class<*>,
        val name: String,
        val parameterTypes: List<Class<*>>,
        val includeSuperclasses: Boolean
    )
    private data class FieldKey(
        val declaringClass: Class<*>,
        val name: String,
        val includeSuperclasses: Boolean
    )
    private data class ConstructorKey(
        val declaringClass: Class<*>,
        val parameterTypes: List<Class<*>>
    )
    private data class MethodCollectionKey(
        val declaringClass: Class<*>,
        val includeSuperclasses: Boolean
    )
    private data class FieldCollectionKey(
        val declaringClass: Class<*>,
        val includeSuperclasses: Boolean
    )

    private val classCache = ConcurrentHashMap<ClassKey, CachedValue<Class<*>>>()
    private val methodCache = ConcurrentHashMap<MethodKey, CachedValue<Method>>()
    private val fieldCache = ConcurrentHashMap<FieldKey, CachedValue<Field>>()
    private val constructorCache = ConcurrentHashMap<ConstructorKey, CachedValue<Constructor<*>>>()
    private val methodCollectionCache = ConcurrentHashMap<MethodCollectionKey, List<Method>>()
    private val fieldCollectionCache = ConcurrentHashMap<FieldCollectionKey, List<Field>>()
    private val constructorCollectionCache = ConcurrentHashMap<Class<*>, List<Constructor<*>>>()

    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val lookupFailures = AtomicLong()

    private fun <K, V> cachedValue(
        cache: ConcurrentHashMap<K, CachedValue<V>>,
        key: K,
        resolver: () -> V?
    ): V? {
        cache[key]?.let {
            cacheHits.incrementAndGet()
            return it.value
        }
        cacheMisses.incrementAndGet()
        val resolved = try {
            resolver()
        } catch (_: Throwable) {
            lookupFailures.incrementAndGet()
            null
        }
        val value = CachedValue(resolved)
        return cache.putIfAbsent(key, value)?.value ?: resolved
    }

    private fun <K, V> cachedList(
        cache: ConcurrentHashMap<K, List<V>>,
        key: K,
        resolver: () -> List<V>
    ): List<V> {
        cache[key]?.let {
            cacheHits.incrementAndGet()
            return it
        }
        cacheMisses.incrementAndGet()
        val resolved = try {
            resolver()
        } catch (_: Throwable) {
            lookupFailures.incrementAndGet()
            emptyList()
        }
        return cache.putIfAbsent(key, resolved) ?: resolved
    }

    /** 通过指定宿主 ClassLoader 加载类，不触发类初始化；版本漂移时返回 null。 */
    fun classOrNull(classLoader: ClassLoader?, name: String): Class<*>? {
        if (classLoader == null) return null
        return cachedValue(classCache, ClassKey(classLoader, name)) {
            classLoader.loadClassOrNull(name)
        }
    }

    /** 判断类是否存在，供版本适配和 Hook 分流的一次性探测使用。 */
    fun hasClass(classLoader: ClassLoader, name: String): Boolean =
        classOrNull(classLoader, name) != null

    /** 精确定位当前类声明的指定方法；缺失或不可访问时返回 null。 */
    fun methodOrNull(
        declaringClass: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = cachedValue(
        methodCache,
        MethodKey(declaringClass, name, parameterTypes.toList(), includeSuperclasses = false)
    ) {
        declaringClass.resolve()
            .optional(silent = true)
            .firstMethodOrNull {
                this.name = name
                parameters(*parameterTypes)
            }
            ?.self
            ?.takeIf { it.makeAccessible() }
    }

    /** 精确定位类或其父类声明的指定方法。 */
    fun inheritedMethodOrNull(
        declaringClass: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = cachedValue(
        methodCache,
        MethodKey(declaringClass, name, parameterTypes.toList(), includeSuperclasses = true)
    ) {
        declaringClass.resolve()
            .optional(silent = true)
            .firstMethodOrNull {
                this.name = name
                parameters(*parameterTypes)
                superclass()
            }
            ?.self
            ?.takeIf { it.makeAccessible() }
    }

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
    ): List<Method> = cachedList(
        methodCollectionCache,
        MethodCollectionKey(declaringClass, includeSuperclasses)
    ) {
        declaringClass.resolve()
            .optional(silent = true)
            .method {
                if (includeSuperclasses) superclass()
            }
            .map { it.self }
    }.asSequence()
        .filter(predicate)
        .filter { !makeAccessible || it.makeAccessible() }
        .toList()

    /** 精确定位字段；可选择向父类查找，缺失或不可访问时返回 null。 */
    fun fieldOrNull(
        declaringClass: Class<*>,
        name: String,
        includeSuperclasses: Boolean = false
    ): Field? = cachedValue(
        fieldCache,
        FieldKey(declaringClass, name, includeSuperclasses)
    ) {
        declaringClass.resolve()
            .optional(silent = true)
            .firstFieldOrNull {
                this.name = name
                if (includeSuperclasses) superclass()
            }
            ?.self
            ?.takeIf { it.makeAccessible() }
    }

    /** 解析当前类声明的字段集合；读取型调用方可要求统一开放访问权限。 */
    fun declaredFields(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Field) -> Boolean = { true }
    ): List<Field> = fields(
        declaringClass = declaringClass,
        includeSuperclasses = false,
        makeAccessible = makeAccessible,
        predicate = predicate
    )

    /** 解析字段集合；[includeSuperclasses] 为 true 时由 KavaRef 统一遍历父类。 */
    fun fields(
        declaringClass: Class<*>,
        includeSuperclasses: Boolean,
        makeAccessible: Boolean = false,
        predicate: (Field) -> Boolean = { true }
    ): List<Field> = cachedList(
        fieldCollectionCache,
        FieldCollectionKey(declaringClass, includeSuperclasses)
    ) {
        val owners = if (includeSuperclasses) {
            generateSequence(declaringClass) { it.superclass }.toList()
        } else {
            listOf(declaringClass)
        }
        owners.flatMap { owner ->
            owner.resolve()
                .optional(silent = true)
                .field { }
                .map { it.self }
        }
    }.asSequence()
        .filter(predicate)
        .filter { !makeAccessible || it.makeAccessible() }
        .toList()

    /** 精确定位当前类声明的构造器；调用方继续缓存并直接 newInstance。 */
    fun constructorOrNull(
        declaringClass: Class<*>,
        vararg parameterTypes: Class<*>
    ): Constructor<*>? = cachedValue(
        constructorCache,
        ConstructorKey(declaringClass, parameterTypes.toList())
    ) {
        declaringClass.resolve()
            .optional(silent = true)
            .firstConstructorOrNull {
                parameters(*parameterTypes)
            }
            ?.self
            ?.takeIf { it.makeAccessible() }
    }

    /** 解析当前类声明的构造器集合，供参数结构会随宿主版本漂移的 Hook 点使用。 */
    fun declaredConstructors(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Constructor<*>) -> Boolean = { true }
    ): List<Constructor<*>> = cachedList(constructorCollectionCache, declaringClass) {
        declaringClass.resolve()
            .optional(silent = true)
            .constructor { }
            .map { it.self }
    }.asSequence()
        .filter(predicate)
        .filter { !makeAccessible || it.makeAccessible() }
        .toList()

    fun diagnostics(): Diagnostics = Diagnostics(
        cacheHits = cacheHits.get(),
        cacheMisses = cacheMisses.get(),
        lookupFailures = lookupFailures.get(),
        cachedClasses = classCache.size,
        cachedMethods = methodCache.size,
        cachedFields = fieldCache.size,
        cachedConstructors = constructorCache.size,
        cachedMemberCollections = methodCollectionCache.size +
            fieldCollectionCache.size + constructorCollectionCache.size
    )

    /** 仅供单元测试隔离状态；生产进程不清空稳定的 Class/Member 缓存。 */
    internal fun resetForTests() {
        classCache.clear()
        methodCache.clear()
        fieldCache.clear()
        constructorCache.clear()
        methodCollectionCache.clear()
        fieldCollectionCache.clear()
        constructorCollectionCache.clear()
        cacheHits.set(0)
        cacheMisses.set(0)
        lookupFailures.set(0)
    }
}
