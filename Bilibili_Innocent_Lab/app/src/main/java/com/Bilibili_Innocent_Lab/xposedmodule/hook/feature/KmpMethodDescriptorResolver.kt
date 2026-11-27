package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared resolver for KMP `MethodDescriptor` instances (`packageName`, `serviceName`, `methodName`).
 *
 * Uses weak instance references and a `WeakHashMap<Class<*>, MemberPlan>` so neither descriptor
 * objects nor dynamically loaded classes are pinned in memory across host reloads.
 */
internal object KmpMethodDescriptorResolver {
    private const val KCLASS = "kotlin.reflect.KClass"
    private const val SERIALIZATION_STRATEGY = "kotlinx.serialization.SerializationStrategy"
    private const val DESERIALIZATION_STRATEGY = "kotlinx.serialization.DeserializationStrategy"
    private const val MAX_CACHED_DESCRIPTORS = 4096

    private val DESCRIPTOR = Regex("""packageName=([^,]+), serviceName=([^,]+), methodName=([^,]+),""")
    private val OBJECT_TO_STRING = Regex("""^[A-Za-z0-9_.$]+@[0-9a-fA-F]+$""")

    private sealed interface Accessor {
        fun read(target: Any): String?

        class FieldAccessor(private val field: Field) : Accessor {
            override fun read(target: Any): String? =
                (runCatching { field.get(target) as? String }.getOrNull())?.takeIf(String::isNotBlank)
        }

        class MethodAccessor(private val method: Method) : Accessor {
            override fun read(target: Any): String? =
                (runCatching { method.invoke(target) as? String }.getOrNull())?.takeIf(String::isNotBlank)
        }
    }

    private class MemberPlan(
        val pkgAccessor: Accessor?,
        val svcAccessor: Accessor?,
        val mtdAccessor: Accessor?,
        val hasDescriptorShape: Boolean,
        val componentMethods: Triple<Method, Method, Method>?,
        val exactStringFields: Triple<Field, Field, Field>?,
        val kClassFields: List<Field> = emptyList()
    )

    private class CachedResolution(val fullMethodName: String?)

    private class IdentityProbe {
        var target: Any? = null
        var identityHash: Int = 0

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            val active = target ?: return false
            return when (other) {
                is WeakDescriptorKey -> other.get() === active
                is IdentityProbe -> other.target === active
                else -> false
            }
        }
    }

    private class WeakDescriptorKey(
        descriptor: Any,
        queue: ReferenceQueue<Any>? = null
    ) : WeakReference<Any>(descriptor, queue) {
        val identityHash: Int = System.identityHashCode(descriptor)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val mine = get() ?: return false
            return when (other) {
                is WeakDescriptorKey -> other.get() === mine
                is IdentityProbe -> other.target === mine
                else -> false
            }
        }
    }

    private val refQueue = ReferenceQueue<Any>()
    private val probeThreadLocal = ThreadLocal.withInitial(::IdentityProbe)
    private val instanceCache = ConcurrentHashMap<Any, CachedResolution>()
    private val classPlans = WeakHashMap<Class<*>, java.lang.ref.SoftReference<MemberPlan>>()

    fun isDescriptorType(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray || type.name.startsWith("java.") || type.name.startsWith("kotlin.")) {
            return false
        }
        val fieldTypes = Lookup.fields(type, includeSuperclasses = true, makeAccessible = true).map { it.type.name }
        return KCLASS in fieldTypes &&
            (SERIALIZATION_STRATEGY in fieldTypes || DESERIALIZATION_STRATEGY in fieldTypes)
    }

    fun resolve(descriptor: Any): String? {
        expungeStaleKeys()
        val probe = probeThreadLocal.get() ?: IdentityProbe().also(probeThreadLocal::set)
        probe.target = descriptor
        probe.identityHash = System.identityHashCode(descriptor)
        val cached = try {
            instanceCache[probe]
        } finally {
            probe.target = null
        }
        if (cached != null) return cached.fullMethodName

        val resolved = resolveUncached(descriptor)
        expungeStaleKeys()
        if (instanceCache.size >= MAX_CACHED_DESCRIPTORS) {
            instanceCache.clear()
        }
        val key = WeakDescriptorKey(descriptor, refQueue)
        if (instanceCache.putIfAbsent(key, CachedResolution(resolved)) != null) {
            key.clear()
        }
        return resolved
    }

    fun clearCaches() {
        instanceCache.clear()
        synchronized(classPlans) {
            classPlans.clear()
        }
        while (refQueue.poll() != null) {
            // Drain cleared reference keys after cache reset.
        }
    }

    private fun resolveUncached(descriptor: Any): String? {
        val raw = runCatching { descriptor.toString() }.getOrNull() ?: return null
        DESCRIPTOR.find(raw)?.let { match ->
            return "${match.groupValues[1]}.${match.groupValues[2]}/${match.groupValues[3]}"
        }
        val matchesObjectToString = OBJECT_TO_STRING.matches(raw)
        val plan = planFor(descriptor.javaClass)
        if (!plan.hasDescriptorShape && !matchesObjectToString) return null
        val requireDotPackage = !matchesObjectToString
        readFromNamedMembers(descriptor, plan, requireDotPackage)?.let { return it }
        if (!plan.hasDescriptorShape) return null
        readFromComponents(descriptor, plan, requireDotPackage)?.let { return it }
        return readFromThreeStringFields(descriptor, plan)
    }

    private fun planFor(type: Class<*>): MemberPlan {
        synchronized(classPlans) {
            classPlans[type]?.get()?.let { return it }
        }
        val pkgAccessor = findNamedAccessor(type, "packageName", "getPackageName")
        val svcAccessor = findNamedAccessor(type, "serviceName", "getServiceName")
        val mtdAccessor = findNamedAccessor(type, "methodName", "getMethodName")
        val hasShape = isDescriptorType(type)
        val components = if (hasShape) {
            val c1 = zeroArgStringMethod(type, "component1")
            val c2 = zeroArgStringMethod(type, "component2")
            val c3 = zeroArgStringMethod(type, "component3")
            if (c1 != null && c2 != null && c3 != null) Triple(c1, c2, c3) else null
        } else null
        val declaredNonStaticFields = if (hasShape) {
            Lookup.fields(type, includeSuperclasses = true, makeAccessible = true).filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }
        } else emptyList()
        val stringFields = if (hasShape) {
            val candidates = declaredNonStaticFields.filter { it.type == String::class.java }
            if (candidates.size == 3) Triple(candidates[0], candidates[1], candidates[2]) else null
        } else null
        val kClassFields = if (hasShape) {
            declaredNonStaticFields.filter { it.type.name == KCLASS }
        } else emptyList()
        val built = MemberPlan(
            pkgAccessor = pkgAccessor,
            svcAccessor = svcAccessor,
            mtdAccessor = mtdAccessor,
            hasDescriptorShape = hasShape,
            componentMethods = components,
            exactStringFields = stringFields,
            kClassFields = kClassFields
        )
        return synchronized(classPlans) {
            classPlans[type]?.get() ?: built.also { classPlans[type] = java.lang.ref.SoftReference(it) }
        }
    }

    private fun findNamedAccessor(type: Class<*>, vararg memberNames: String): Accessor? {
        for (name in memberNames) {
            Lookup.fieldOrNull(type, name, includeSuperclasses = true)
                ?.takeIf { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                ?.let { return Accessor.FieldAccessor(it) }
            zeroArgStringMethod(type, name)?.let { return Accessor.MethodAccessor(it) }
        }
        return null
    }

    private fun zeroArgStringMethod(type: Class<*>, name: String): Method? =
        Lookup.inheritedMethodOrNull(type, name)
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == String::class.java }

    private fun readFromNamedMembers(descriptor: Any, plan: MemberPlan, requireDotPackage: Boolean): String? {
        val pkg = plan.pkgAccessor?.read(descriptor) ?: return null
        val svc = plan.svcAccessor?.read(descriptor) ?: return null
        val mtd = plan.mtdAccessor?.read(descriptor) ?: return null
        val pkgValid = if (requireDotPackage) isValidPackageName(pkg) else isValidSegment(pkg)
        if (!pkgValid || !isValidSegment(svc) || !isValidSegment(mtd)) return null
        return "$pkg.$svc/$mtd"
    }

    private fun readFromComponents(descriptor: Any, plan: MemberPlan, requireDotPackage: Boolean): String? {
        val (c1, c2, c3) = plan.componentMethods ?: return null
        val pkg = (runCatching { c1.invoke(descriptor) as? String }.getOrNull()) ?: return null
        val svc = (runCatching { c2.invoke(descriptor) as? String }.getOrNull()) ?: return null
        val mtd = (runCatching { c3.invoke(descriptor) as? String }.getOrNull()) ?: return null
        val pkgValid = if (requireDotPackage) isValidPackageName(pkg) else isValidSegment(pkg)
        if (!pkgValid || !isValidSegment(svc) || !isValidSegment(mtd)) return null
        return "$pkg.$svc/$mtd"
    }

    private fun readFromThreeStringFields(descriptor: Any, plan: MemberPlan): String? {
        val (f0, f1, f2) = plan.exactStringFields ?: return null
        val values = listOf(
            runCatching { f0.get(descriptor) as? String }.getOrNull() ?: return null,
            runCatching { f1.get(descriptor) as? String }.getOrNull() ?: return null,
            runCatching { f2.get(descriptor) as? String }.getOrNull() ?: return null
        )
        if (values.any { !isValidSegment(it) }) return null
        val pkgMatches = values.withIndex().filter { isValidPackageName(it.value) }
        if (pkgMatches.size != 1) return null
        val pkgEntry = pkgMatches.single()
        val pkg = pkgEntry.value
        val remaining = values.withIndex()
            .filter { it.index != pkgEntry.index }
            .map { it.value }
        val first = remaining[0]
        val second = remaining[1]
        if (first.contains('.') || second.contains('.')) return null
        val (svc, mtd) = classifyServiceAndMethod(descriptor, plan, first, second)
        if (!isValidSegment(svc) || !isValidSegment(mtd)) return null
        return "$pkg.$svc/$mtd"
    }

    private fun classifyServiceAndMethod(
        descriptor: Any,
        plan: MemberPlan,
        first: String,
        second: String
    ): Pair<String, String> {
        val kClassSimpleNames = plan.kClassFields.mapNotNull { field ->
            val kClass = runCatching { field.get(descriptor) as? kotlin.reflect.KClass<*> }.getOrNull()
            kClass?.java?.simpleName?.takeIf { it.isNotBlank() && it != "Object" && it != "Any" && it != "Unit" }
        }
        if (kClassSimpleNames.isNotEmpty()) {
            fun matchesMessageModel(candidate: String): Boolean = kClassSimpleNames.any { simple ->
                simple.startsWith(candidate) && (
                    simple == "${candidate}Req" ||
                        simple == "${candidate}Reply" ||
                        simple == "${candidate}Resp" ||
                        simple == "${candidate}Request" ||
                        simple == "${candidate}Response"
                    )
            }
            val firstMatchesMethod = matchesMessageModel(first)
            val secondMatchesMethod = matchesMessageModel(second)
            if (firstMatchesMethod && !secondMatchesMethod) return second to first
            if (secondMatchesMethod && !firstMatchesMethod) return first to second
        }
        fun looksLikeServiceName(value: String): Boolean =
            value.endsWith("Service") || value.endsWith("Svc") || value.endsWith("ServiceV2") || value.endsWith("Api")
        val firstIsService = looksLikeServiceName(first)
        val secondIsService = looksLikeServiceName(second)
        if (firstIsService != secondIsService) {
            return if (firstIsService) first to second else second to first
        }
        val firstLower = first.firstOrNull()?.isLowerCase() == true
        val secondLower = second.firstOrNull()?.isLowerCase() == true
        if (firstLower != secondLower) {
            return if (secondLower) first to second else second to first
        }
        return first to second
    }

    private fun isValidPackageName(value: String): Boolean =
        value.isNotBlank() &&
            value.contains('.') &&
            !value.startsWith('.') &&
            !value.endsWith('.') &&
            isValidSegment(value)

    private fun isValidSegment(value: String): Boolean =
        value.isNotBlank() && !value.contains('/') && value.none(Char::isWhitespace)

    private fun expungeStaleKeys() {
        while (true) {
            val stale = refQueue.poll() as? WeakDescriptorKey ?: break
            instanceCache.remove(stale)
        }
    }
}
