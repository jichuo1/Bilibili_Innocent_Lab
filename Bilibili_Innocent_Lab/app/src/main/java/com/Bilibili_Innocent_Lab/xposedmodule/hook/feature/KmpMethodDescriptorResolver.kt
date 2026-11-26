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
        val exactStringFields: Triple<Field, Field, Field>?
    )

    private class CachedResolution(val fullMethodName: String?)

    private class WeakDescriptorKey(
        descriptor: Any,
        queue: ReferenceQueue<Any>? = null
    ) : WeakReference<Any>(descriptor, queue) {
        val identityHash: Int = System.identityHashCode(descriptor)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WeakDescriptorKey) return false
            val mine = get()
            val theirs = other.get()
            return mine != null && theirs != null && mine === theirs
        }
    }

    private val refQueue = ReferenceQueue<Any>()
    private val instanceCache = ConcurrentHashMap<WeakDescriptorKey, CachedResolution>()
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
        instanceCache[WeakDescriptorKey(descriptor)]?.let { return it.fullMethodName }
        val resolved = resolveUncached(descriptor)
        expungeStaleKeys()
        if (instanceCache.size >= MAX_CACHED_DESCRIPTORS) {
            instanceCache.clear()
        }
        instanceCache.putIfAbsent(WeakDescriptorKey(descriptor, refQueue), CachedResolution(resolved))
        return resolved
    }

    private fun resolveUncached(descriptor: Any): String? {
        val raw = runCatching { descriptor.toString() }.getOrNull() ?: return null
        DESCRIPTOR.find(raw)?.let { match ->
            return "${match.groupValues[1]}.${match.groupValues[2]}/${match.groupValues[3]}"
        }
        if (!OBJECT_TO_STRING.matches(raw)) return null
        val plan = planFor(descriptor.javaClass)
        readFromNamedMembers(descriptor, plan)?.let { return it }
        if (!plan.hasDescriptorShape) return null
        readFromComponents(descriptor, plan)?.let { return it }
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
        val stringFields = if (hasShape) {
            val candidates = Lookup.fields(type, includeSuperclasses = true, makeAccessible = true).filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.type == String::class.java
            }
            if (candidates.size == 3) Triple(candidates[0], candidates[1], candidates[2]) else null
        } else null
        val built = MemberPlan(
            pkgAccessor = pkgAccessor,
            svcAccessor = svcAccessor,
            mtdAccessor = mtdAccessor,
            hasDescriptorShape = hasShape,
            componentMethods = components,
            exactStringFields = stringFields
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

    private fun readFromNamedMembers(descriptor: Any, plan: MemberPlan): String? {
        val pkg = plan.pkgAccessor?.read(descriptor) ?: return null
        val svc = plan.svcAccessor?.read(descriptor) ?: return null
        val mtd = plan.mtdAccessor?.read(descriptor) ?: return null
        return "$pkg.$svc/$mtd"
    }

    private fun readFromComponents(descriptor: Any, plan: MemberPlan): String? {
        val (c1, c2, c3) = plan.componentMethods ?: return null
        val pkg = (runCatching { c1.invoke(descriptor) as? String }.getOrNull()) ?: return null
        val svc = (runCatching { c2.invoke(descriptor) as? String }.getOrNull()) ?: return null
        val mtd = (runCatching { c3.invoke(descriptor) as? String }.getOrNull()) ?: return null
        if (!isValidSegment(pkg) || !isValidSegment(svc) || !isValidSegment(mtd)) return null
        return "$pkg.$svc/$mtd"
    }

    private fun readFromThreeStringFields(descriptor: Any, plan: MemberPlan): String? {
        val (f0, f1, f2) = plan.exactStringFields ?: return null
        val pkg = (runCatching { f0.get(descriptor) as? String }.getOrNull()) ?: return null
        val svc = (runCatching { f1.get(descriptor) as? String }.getOrNull()) ?: return null
        val mtd = (runCatching { f2.get(descriptor) as? String }.getOrNull()) ?: return null
        if (!isValidPackageName(pkg) || !isValidSegment(svc) || !isValidSegment(mtd)) return null
        return "$pkg.$svc/$mtd"
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
