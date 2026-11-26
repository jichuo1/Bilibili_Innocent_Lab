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
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.util.WeakHashMap
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
    private data class ZeroArgMethodKey(
        val declaringClass: Class<*>,
        val name: String,
        val includeSuperclasses: Boolean
    )
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

    private class LoaderCache {
        val classes = ConcurrentHashMap<String, CachedValue<Class<*>>>()
        val methods = ConcurrentHashMap<MethodKey, CachedValue<Method>>()
        val zeroArgMethods = ConcurrentHashMap<ZeroArgMethodKey, CachedValue<Method>>()
        val fields = ConcurrentHashMap<FieldKey, CachedValue<Field>>()
        val constructors = ConcurrentHashMap<ConstructorKey, CachedValue<Constructor<*>>>()
        val zeroArgConstructors = ConcurrentHashMap<Class<*>, CachedValue<Constructor<*>>>()
        val methodCollections = ConcurrentHashMap<MethodCollectionKey, List<Method>>()
        val fieldCollections = ConcurrentHashMap<FieldCollectionKey, List<Field>>()
        val constructorCollections = ConcurrentHashMap<Class<*>, List<Constructor<*>>>()

        fun clear() {
            classes.clear()
            methods.clear()
            zeroArgMethods.clear()
            fields.clear()
            constructors.clear()
            zeroArgConstructors.clear()
            methodCollections.clear()
            fieldCollections.clear()
            constructorCollections.clear()
        }
    }

    private val EMPTY_CLASS_ARRAY: Array<Class<*>> = emptyArray()
    private val bootCache = LoaderCache()
    private val loaderCaches = WeakHashMap<ClassLoader, LoaderCache>()
    private class LoaderSlot(val loaderRef: WeakReference<ClassLoader>, val cache: LoaderCache)
    @Volatile private var hostLoaderRef: WeakReference<ClassLoader>? = null
    @Volatile private var lastLoaderSlot: LoaderSlot? = null
    private val moduleLoader: ClassLoader? = KavaMemberLookup::class.java.classLoader
    private val platformLoader: ClassLoader? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as? ClassLoader
        }.getOrNull()
    }

    internal fun registerHostClassLoader(loader: ClassLoader?) {
        hostLoaderRef = loader?.let(::WeakReference)
    }

    internal fun isCacheableLoader(loader: ClassLoader): Boolean {
        val host = hostLoaderRef?.get()
        val system = runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()
        val platform = platformLoader
        if (loader === moduleLoader || loader === system || (platform != null && loader === platform)) {
            return true
        }
        if (host != null) {
            return loader === host || isAncestorLoader(loader, host)
        }
        if (isAncestorLoader(loader, moduleLoader)) {
            return true
        }
        return !isIsolatedOrDynamicLoader(loader, system, platform)
    }

    private fun isAncestorLoader(candidate: ClassLoader, descendant: ClassLoader?): Boolean {
        var cur = descendant
        var hops = 0
        while (cur != null && hops < 16) {
            if (cur === candidate) return true
            cur = cur.parent
            hops++
        }
        return false
    }

    private fun isIsolatedOrDynamicLoader(
        loader: ClassLoader,
        system: ClassLoader?,
        platform: ClassLoader?
    ): Boolean {
        if (loader is URLClassLoader) return true
        if (loader.parent == null && loader !== system && loader !== platform) return true
        var cls: Class<*>? = loader::class.java
        while (cls != null && cls != ClassLoader::class.java && cls != Any::class.java) {
            val name = cls.name
            if (name == "dalvik.system.DexClassLoader" ||
                name == "dalvik.system.InMemoryDexClassLoader" ||
                name == "dalvik.system.DelegateLastClassLoader"
            ) {
                return true
            }
            val simple = cls.simpleName
            if (simple.contains("Plugin", ignoreCase = true) ||
                simple.contains("Dynamic", ignoreCase = true) ||
                simple.contains("Isolated", ignoreCase = true) ||
                simple.contains("Hotfix", ignoreCase = true) ||
                simple.contains("Tinker", ignoreCase = true) ||
                simple.contains("Patch", ignoreCase = true)
            ) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    private fun loaderCache(loader: ClassLoader?): LoaderCache {
        if (loader == null) return bootCache
        val slot = lastLoaderSlot
        if (slot != null && slot.loaderRef.get() === loader) {
            return slot.cache
        }
        if (!isCacheableLoader(loader)) {
            // 第三方/动态/隔离 ClassLoader 不写入全局弱引用表与单槽快路径，防止 Value -> Class -> ClassLoader 强引用环阻碍回收。
            return LoaderCache()
        }
        val resolved = synchronized(loaderCaches) {
            loaderCaches[loader] ?: LoaderCache().also { loaderCaches[loader] = it }
        }
        val host = hostLoaderRef?.get()
        if (host == null || loader === host) {
            lastLoaderSlot = LoaderSlot(WeakReference(loader), resolved)
        }
        return resolved
    }

    /** One resolver schema index per synchronous adaptation pass; warm BIL cache hits never open it. */
    private class Batch(val loader: ClassLoader) {
        var scope: CodeScope? = null
        var bootScope: CodeScope? = null

        fun close() {
            try {
                scope?.close()
            } finally {
                bootScope?.close()
            }
        }
    }
    private val batch = ThreadLocal<Batch>()

    internal fun <T> withLookupBatch(loader: ClassLoader, work: () -> T): T {
        if (BuildConfig.MEGUVIZ_LEGACY_LOOKUP) return work()
        val previous = batch.get()
        if (previous?.loader === loader) return work()
        val current = Batch(loader)
        batch.set(current)
        return try {
            work()
        } finally {
            if (previous == null) batch.remove() else batch.set(previous)
            current.close()
        }
    }

    internal fun <T> withMemberScope(loader: ClassLoader?, work: (CodeScope) -> T): T {
        val current = batch.get()
        if (current != null) {
            if (loader == null) {
                val scope = current.bootScope ?: openScope(null).also { current.bootScope = it }
                return work(scope)
            }
            if (current.loader === loader) {
                val scope = current.scope ?: openScope(loader).also { current.scope = it }
                return work(scope)
            }
        }
        return openScope(loader).use(work)
    }

    private fun openScope(loader: ClassLoader?) = CodeScope.open(loader, AccessPolicy.PRESERVE_JAVA_ACCESS, 16384)

    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val lookupFailures = AtomicLong()

    private fun <K, V> cachedValue(
        cache: ConcurrentHashMap<K, CachedValue<V>>,
        key: K,
        resolver: () -> V?
    ): V? = StartupTrace.measure("lookup.value") {
        cache[key]?.let {
            cacheHits.incrementAndGet()
            return@measure it.value
        }
        cacheMisses.incrementAndGet()
        val resolved = try {
            resolver()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            lookupFailures.incrementAndGet()
            return@measure null
        }
        val value = CachedValue(resolved)
        cache.putIfAbsent(key, value)?.value ?: resolved
    }

    private fun <K, V> cachedList(
        cache: ConcurrentHashMap<K, List<V>>,
        key: K,
        resolver: () -> List<V>
    ): List<V> = StartupTrace.measure("lookup.list") {
        cache[key]?.let {
            cacheHits.incrementAndGet()
            return@measure it
        }
        cacheMisses.incrementAndGet()
        val resolved = try {
            resolver()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            lookupFailures.incrementAndGet()
            return@measure emptyList()
        }
        cache.putIfAbsent(key, resolved) ?: resolved
    }

    /** 通过指定宿主 ClassLoader 加载类，不触发类初始化；版本漂移时返回 null。 */
    fun classOrNull(classLoader: ClassLoader?, name: String): Class<*>? {
        if (classLoader == null) return null
        return cachedValue(loaderCache(classLoader).classes, name) {
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
    ): Method? = resolveMethod(declaringClass, name, parameterTypes, includeSuperclasses = false)

    /** 精确定位类或其父类声明的指定方法。 */
    fun inheritedMethodOrNull(
        declaringClass: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = resolveMethod(declaringClass, name, parameterTypes, includeSuperclasses = true)

    private fun resolveMethod(
        declaringClass: Class<*>,
        name: String,
        parameterTypes: Array<out Class<*>>,
        includeSuperclasses: Boolean
    ): Method? {
        val bucket = loaderCache(declaringClass.classLoader)
        if (parameterTypes.isEmpty()) {
            return cachedValue(
                bucket.zeroArgMethods,
                ZeroArgMethodKey(declaringClass, name, includeSuperclasses)
            ) {
                firstMember(declaringClass, MemberKind.METHOD, name, EMPTY_CLASS_ARRAY, includeSuperclasses)
                    ?.let { it as Method }?.takeIf { it.makeAccessible() }
            }
        }
        return cachedValue(
            bucket.methods,
            MethodKey(declaringClass, name, parameterTypes.toList(), includeSuperclasses)
        ) {
            firstMember(declaringClass, MemberKind.METHOD, name, parameterTypes, includeSuperclasses)
                ?.let { it as Method }?.takeIf { it.makeAccessible() }
        }
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

    private fun declaredMethodsCachedOrThrow(owner: Class<*>): List<Method> {
        val cache = loaderCache(owner.classLoader).methodCollections
        val key = MethodCollectionKey(owner, includeSuperclasses = false)
        cache[key]?.let { return it }
        val resolved = rawMembers(owner, MemberKind.METHOD).map { it as Method }
        return cache.putIfAbsent(key, resolved) ?: resolved
    }

    private fun methodCollection(declaringClass: Class<*>, includeSuperclasses: Boolean): List<Method> {
        val cache = loaderCache(declaringClass.classLoader).methodCollections
        if (!includeSuperclasses) {
            return cachedList(cache, MethodCollectionKey(declaringClass, includeSuperclasses = false)) {
                rawMembers(declaringClass, MemberKind.METHOD).map { it as Method }
            }
        }
        return cachedList(cache, MethodCollectionKey(declaringClass, includeSuperclasses = true)) {
            val superType = declaringClass.superclass ?: return@cachedList declaredMethodsCachedOrThrow(declaringClass)
            val inherited = loaderCache(superType.classLoader).methodCollections[
                MethodCollectionKey(superType, includeSuperclasses = true)
            ]
            if (inherited != null) {
                val declared = declaredMethodsCachedOrThrow(declaringClass)
                return@cachedList ArrayList<Method>(declared.size + inherited.size).apply {
                    addAll(declared)
                    addAll(inherited)
                }
            }
            val collected = ArrayList<Method>()
            var current: Class<*>? = declaringClass
            while (current != null) {
                collected.addAll(declaredMethodsCachedOrThrow(current))
                current = current.superclass
            }
            collected
        }
    }

    /** 精确定位字段；可选择向父类查找，缺失或不可访问时返回 null。 */
    fun fieldOrNull(
        declaringClass: Class<*>,
        name: String,
        includeSuperclasses: Boolean = false
    ): Field? = cachedValue(
        loaderCache(declaringClass.classLoader).fields,
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

    private fun declaredFieldsCachedOrThrow(owner: Class<*>): List<Field> {
        val cache = loaderCache(owner.classLoader).fieldCollections
        val key = FieldCollectionKey(owner, includeSuperclasses = false)
        cache[key]?.let { return it }
        val resolved = rawMembers(owner, MemberKind.FIELD).map { it as Field }
        return cache.putIfAbsent(key, resolved) ?: resolved
    }

    private fun fieldCollection(declaringClass: Class<*>, includeSuperclasses: Boolean): List<Field> {
        val cache = loaderCache(declaringClass.classLoader).fieldCollections
        if (!includeSuperclasses) {
            return cachedList(cache, FieldCollectionKey(declaringClass, includeSuperclasses = false)) {
                rawMembers(declaringClass, MemberKind.FIELD).map { it as Field }
            }
        }
        return cachedList(cache, FieldCollectionKey(declaringClass, includeSuperclasses = true)) {
            val superType = declaringClass.superclass ?: return@cachedList declaredFieldsCachedOrThrow(declaringClass)
            val inherited = loaderCache(superType.classLoader).fieldCollections[
                FieldCollectionKey(superType, includeSuperclasses = true)
            ]
            if (inherited != null) {
                val declared = declaredFieldsCachedOrThrow(declaringClass)
                return@cachedList ArrayList<Field>(declared.size + inherited.size).apply {
                    addAll(declared)
                    addAll(inherited)
                }
            }
            val collected = ArrayList<Field>()
            var current: Class<*>? = declaringClass
            while (current != null) {
                collected.addAll(declaredFieldsCachedOrThrow(current))
                current = current.superclass
            }
            collected
        }
    }

    /** 精确定位当前类声明的构造器；调用方继续缓存并直接 newInstance。 */
    fun constructorOrNull(
        declaringClass: Class<*>,
        vararg parameterTypes: Class<*>
    ): Constructor<*>? {
        val bucket = loaderCache(declaringClass.classLoader)
        if (parameterTypes.isEmpty()) {
            return cachedValue(bucket.zeroArgConstructors, declaringClass) {
                firstMember(declaringClass, MemberKind.CONSTRUCTOR, parameterTypes = EMPTY_CLASS_ARRAY)
                    ?.let { it as Constructor<*> }?.takeIf { it.makeAccessible() }
            }
        }
        return cachedValue(
            bucket.constructors,
            ConstructorKey(declaringClass, parameterTypes.toList())
        ) {
            firstMember(declaringClass, MemberKind.CONSTRUCTOR, parameterTypes = parameterTypes)
                ?.let { it as Constructor<*> }?.takeIf { it.makeAccessible() }
        }
    }

    /** 解析当前类声明的构造器集合，供参数结构会随宿主版本漂移的 Hook 点使用。 */
    fun declaredConstructors(
        declaringClass: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Constructor<*>) -> Boolean = { true }
    ): List<Constructor<*>> = cachedList(loaderCache(declaringClass.classLoader).constructorCollections, declaringClass) {
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
        return withMemberScope(owner.classLoader) { scope ->
            val query = QuerySpec.builder(owner, kind).selection(Selection.FIRST)
            name?.let(query::name)
            parameterTypes?.let { query.parameters(*it) }
            if (includeSuperclasses) query.hierarchy(Hierarchy.SUPERCLASSES)
            val result = scope.resolveRaw(query.build())
            when (result.status()) {
                ResolutionStatus.RESOLVED -> result.members().single()
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
        return withMemberScope(owner.classLoader) { scope ->
            val query = QuerySpec.builder(owner, kind).selection(Selection.ALL).resultLimit(16384)
            name?.let(query::name)
            parameterTypes?.let { query.parameters(*it) }
            if (includeSuperclasses) query.hierarchy(Hierarchy.SUPERCLASSES)
            val result = scope.resolveRaw(query.build())
            if (!result.receipt().coverage().complete()) throw IncompleteMemberLookup(result.status())
            result.members()
        }
    }

    private fun platformMembers(owner: Class<*>, kind: MemberKind): List<Member> = when (kind) {
        MemberKind.METHOD -> owner.declaredMethods.toList()
        MemberKind.FIELD -> owner.declaredFields.toList()
        MemberKind.CONSTRUCTOR -> owner.declaredConstructors.toList()
    }

    fun diagnostics(): Diagnostics {
        var cachedClasses = 0
        var cachedMethods = 0
        var cachedFields = 0
        var cachedConstructors = 0
        var cachedMemberCollections = 0
        fun accumulate(bucket: LoaderCache) {
            cachedClasses += bucket.classes.size
            cachedMethods += bucket.methods.size + bucket.zeroArgMethods.size
            cachedFields += bucket.fields.size
            cachedConstructors += bucket.constructors.size + bucket.zeroArgConstructors.size
            cachedMemberCollections += bucket.methodCollections.size +
                bucket.fieldCollections.size + bucket.constructorCollections.size
        }
        accumulate(bootCache)
        val buckets = synchronized(loaderCaches) { loaderCaches.values.toList() }
        for (bucket in buckets) accumulate(bucket)
        return Diagnostics(
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get(),
            lookupFailures = lookupFailures.get(),
            cachedClasses = cachedClasses,
            cachedMethods = cachedMethods,
            cachedFields = cachedFields,
            cachedConstructors = cachedConstructors,
            cachedMemberCollections = cachedMemberCollections
        )
    }

    /** 仅供单元测试隔离状态；生产进程不清空稳定的 Class/Member 缓存。 */
    internal fun resetForTests() {
        lastLoaderSlot = null
        hostLoaderRef = null
        bootCache.clear()
        synchronized(loaderCaches) {
            loaderCaches.values.forEach(LoaderCache::clear)
            loaderCaches.clear()
        }
        cacheHits.set(0)
        cacheMisses.set(0)
        lookupFailures.set(0)
    }
}
