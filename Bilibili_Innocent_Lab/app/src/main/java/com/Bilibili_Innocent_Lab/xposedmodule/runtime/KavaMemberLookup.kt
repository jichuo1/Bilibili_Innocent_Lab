package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.loadClassOrNull
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.makeAccessible
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind
import io.github.jichuo1.meguviz.ref.api.query.AccessPolicy
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec
import io.github.jichuo1.meguviz.ref.api.query.Selection
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus
import io.github.jichuo1.meguviz.ref.scope.CodeScope
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MeguvizRef 成员定位边界；对象名称保留以缩小调用方迁移。
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
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            lookupFailures.incrementAndGet()
            if (failure is IncompleteMemberLookup) return null
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
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            lookupFailures.incrementAndGet()
            if (failure is IncompleteMemberLookup) return emptyList()
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
        firstMember(declaringClass, MemberKind.METHOD, name, parameterTypes)
            ?.let { it as Method }?.takeIf { it.makeAccessible() }
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
        firstMember(declaringClass, MemberKind.METHOD, name, parameterTypes, true)
            ?.let { it as Method }?.takeIf { it.makeAccessible() }
    }

    /**
     * 解析当前类声明的方法集合。结构特征筛选仍由调用方表达，MeguvizRef 只承担统一枚举边界；
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

    /** 解析方法集合；[includeSuperclasses] 为 true 时由 MeguvizRef 统一遍历父类。 */
    fun methods(
        declaringClass: Class<*>,
        includeSuperclasses: Boolean,
        makeAccessible: Boolean = false,
        predicate: (Method) -> Boolean = { true }
    ): List<Method> = methodCollection(declaringClass, includeSuperclasses).asSequence()
        .filter(predicate)
        .filter { !makeAccessible || it.makeAccessible() }
        .toList()

    /**
     * 只要第一个满足条件的方法：在已缓存的成员集合上短路，不为只用 firstOrNull 的调用方物化整份过滤结果。
     * 返回的就是 [methods] 结果的第一项（顺序、可访问性跳过规则一致）；只对找到之前的候选调用 makeAccessible。
     */
    fun firstMethodOrNull(
        declaringClass: Class<*>,
        includeSuperclasses: Boolean,
        makeAccessible: Boolean = false,
        predicate: (Method) -> Boolean = { true }
    ): Method? {
        val all = methodCollection(declaringClass, includeSuperclasses)
        for (index in all.indices) {
            val candidate = all[index]
            if (predicate(candidate) && (!makeAccessible || candidate.makeAccessible())) return candidate
        }
        return null
    }

    fun firstDeclaredMethodOrNull(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Method) -> Boolean = { true }
    ): Method? = firstMethodOrNull(declaringClass, includeSuperclasses = false, makeAccessible = makeAccessible, predicate = predicate)

    private fun methodCollection(declaringClass: Class<*>, includeSuperclasses: Boolean): List<Method> = cachedList(
        methodCollectionCache,
        MethodCollectionKey(declaringClass, includeSuperclasses)
    ) {
        val owners = if (includeSuperclasses) {
            generateSequence(declaringClass) { owner -> owner.superclass }.toList()
        } else {
            listOf(declaringClass)
        }
        owners.flatMap { owner ->
            rawMembers(owner, MemberKind.METHOD).map { it as Method }
        }
    }

    /** 精确定位字段；可选择向父类查找，缺失或不可访问时返回 null。 */
    fun fieldOrNull(
        declaringClass: Class<*>,
        name: String,
        includeSuperclasses: Boolean = false
    ): Field? = cachedValue(
        fieldCache,
        FieldKey(declaringClass, name, includeSuperclasses)
    ) {
        firstMember(declaringClass, MemberKind.FIELD, name, includeSuperclasses = includeSuperclasses)?.let { it as Field }?.takeIf { it.makeAccessible() }
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

    /** 解析字段集合；[includeSuperclasses] 为 true 时由 MeguvizRef 统一遍历父类。 */
    fun fields(
        declaringClass: Class<*>,
        includeSuperclasses: Boolean,
        makeAccessible: Boolean = false,
        predicate: (Field) -> Boolean = { true }
    ): List<Field> = fieldCollection(declaringClass, includeSuperclasses).asSequence()
        .filter(predicate)
        .filter { !makeAccessible || it.makeAccessible() }
        .toList()

    /** 字段版 [firstMethodOrNull]：在已缓存的集合上短路，只对找到之前的候选调用 makeAccessible。 */
    fun firstFieldOrNull(
        declaringClass: Class<*>,
        includeSuperclasses: Boolean,
        makeAccessible: Boolean = false,
        predicate: (Field) -> Boolean = { true }
    ): Field? {
        val all = fieldCollection(declaringClass, includeSuperclasses)
        for (index in all.indices) {
            val candidate = all[index]
            if (predicate(candidate) && (!makeAccessible || candidate.makeAccessible())) return candidate
        }
        return null
    }

    fun firstDeclaredFieldOrNull(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Field) -> Boolean = { true }
    ): Field? = firstFieldOrNull(declaringClass, includeSuperclasses = false, makeAccessible = makeAccessible, predicate = predicate)

    private fun fieldCollection(declaringClass: Class<*>, includeSuperclasses: Boolean): List<Field> = cachedList(
        fieldCollectionCache,
        FieldCollectionKey(declaringClass, includeSuperclasses)
    ) {
        val owners = if (includeSuperclasses) {
            generateSequence(declaringClass) { it.superclass }.toList()
        } else {
            listOf(declaringClass)
        }
        owners.flatMap { owner ->
            rawMembers(owner, MemberKind.FIELD).map { it as Field }
        }
    }

    /** 精确定位当前类声明的构造器；调用方继续缓存并直接 newInstance。 */
    fun constructorOrNull(
        declaringClass: Class<*>,
        vararg parameterTypes: Class<*>
    ): Constructor<*>? = cachedValue(
        constructorCache,
        ConstructorKey(declaringClass, parameterTypes.toList())
    ) {
        firstMember(declaringClass, MemberKind.CONSTRUCTOR, parameterTypes = parameterTypes)?.let { it as Constructor<*> }?.takeIf { it.makeAccessible() }
    }

    /** 解析当前类声明的构造器集合，供参数结构会随宿主版本漂移的 Hook 点使用。 */
    fun declaredConstructors(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Constructor<*>) -> Boolean = { true }
    ): List<Constructor<*>> = cachedList(constructorCollectionCache, declaringClass) {
        rawMembers(declaringClass, MemberKind.CONSTRUCTOR).map { it as Constructor<*> }
    }.asSequence()
        .filter(predicate)
        .filter { !makeAccessible || it.makeAccessible() }
        .toList()

    private class IncompleteMemberLookup(status: ResolutionStatus) : IllegalStateException("Incomplete member lookup: $status")

    private fun firstMember(
        owner: Class<*>,
        kind: MemberKind,
        name: String? = null,
        parameterTypes: Array<out Class<*>>? = null,
        includeSuperclasses: Boolean = false
    ): Member? {
        if (Thread.currentThread().isInterrupted) throw IncompleteMemberLookup(ResolutionStatus.CANCELLED)
        if (BuildConfig.MEGUVIZ_LEGACY_LOOKUP) {
            val owners = if (includeSuperclasses) generateSequence(owner) { it.superclass } else sequenceOf(owner)
            for (type in owners) {
                val found = platformMembers(type, kind).firstOrNull { member ->
                    (name == null || member.name == name) && (parameterTypes == null || when (member) {
                        is Method -> member.parameterTypes.contentEquals(parameterTypes)
                        is Constructor<*> -> member.parameterTypes.contentEquals(parameterTypes)
                        else -> false
                    })
                }
                if (found != null) return found
            }
            return null
        }
        return CodeScope.open(owner.classLoader, AccessPolicy.PRESERVE_JAVA_ACCESS, 16384).use { scope ->
            val query = QuerySpec.builder(owner, kind).selection(Selection.FIRST)
            name?.let(query::name)
            parameterTypes?.let { query.parameters(*it) }
            if (includeSuperclasses) query.hierarchy(Hierarchy.SUPERCLASSES)
            val result = scope.resolve(query.build())
            when (result.status()) {
                ResolutionStatus.RESOLVED -> scope.exportRaw(result.requireSingle())
                ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, ResolutionStatus.UNAVAILABLE -> null
                else -> throw IncompleteMemberLookup(result.status())
            }
        }
    }

    /** Exported members are intentionally owned by the existing BIL caches after the short scope closes. */
    private fun rawMembers(
        owner: Class<*>,
        kind: MemberKind,
        name: String? = null,
        parameterTypes: Array<out Class<*>>? = null,
        includeSuperclasses: Boolean = false
    ): List<Member> {
        if (Thread.currentThread().isInterrupted) throw IncompleteMemberLookup(ResolutionStatus.CANCELLED)
        if (BuildConfig.MEGUVIZ_LEGACY_LOOKUP) return platformMembers(owner, kind)
        return CodeScope.open(owner.classLoader, AccessPolicy.PRESERVE_JAVA_ACCESS, 16384).use { scope ->
            val query = QuerySpec.builder(owner, kind).selection(Selection.ALL).resultLimit(16384)
            name?.let(query::name)
            parameterTypes?.let { query.parameters(*it) }
            if (includeSuperclasses) query.hierarchy(Hierarchy.SUPERCLASSES)
            val result = scope.resolve(query.build())
            if (!result.receipt().coverage().complete()) throw IncompleteMemberLookup(result.status())
            result.members().map { scope.exportRaw(it) }
        }
    }

    private fun platformMembers(owner: Class<*>, kind: MemberKind): List<Member> = when (kind) {
        MemberKind.METHOD -> owner.declaredMethods.toList()
        MemberKind.FIELD -> owner.declaredFields.toList()
        MemberKind.CONSTRUCTOR -> owner.declaredConstructors.toList()
    }

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
