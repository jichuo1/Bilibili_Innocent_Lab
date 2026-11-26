package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isPublic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method

/**
 * Resolves the host's KMP gRPC engine entrypoints (`GrpcEngine.a(service, descriptor, bytes, handler, ...)`)
 * independently of `MossInterceptor` so comment wire-byte channels keep working across build layout drifts.
 */
internal object KmpGrpcWireAdapter {
    const val ENGINE = "kntr.base.moss.ignet.impl.grpc.GrpcEngine"
    const val INTERCEPTOR = "kntr.base.moss.MossInterceptor"
    const val REPLY_MOSS = "com.bapis.bilibili.main.community.reply.v1.KReplyMoss"
    const val DIAGNOSTIC = "dex.assist.kmp_grpc_wire_engine"
    private const val SERIALIZATION_STRATEGY = "kotlinx.serialization.SerializationStrategy"
    private const val DESERIALIZATION_STRATEGY = "kotlinx.serialization.DeserializationStrategy"
    private const val KCLASS = "kotlin.reflect.KClass"

    val ENTRY_MARKS = listOf("GrpcEngine", "Building IgnetChain with ", "Handler is null for ")
    val query = DexAssistQuery.KMP_GRPC_WIRE_ENGINE

    data class WireSurface(
        val engine: Class<*>,
        val engineEntry: Method,
        val descriptorIndex: Int,
        val requestBytesIndex: Int,
        val handlerIndex: Int
    )

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, ENGINE) || Lookup.hasClass(loader, INTERCEPTOR) ||
            Lookup.hasClass(loader, REPLY_MOSS) || Lookup.hasClass(loader, "kntr.base.config.KConfig")

    fun inspectEntry(method: Method): WireSurface? {
        if (!method.isPublic || method.isStatic || method.isSynthetic || method.isBridge) return null
        if (method.returnType != Void.TYPE) return null
        val params = method.parameterTypes
        if (params.size !in 4..6) return null
        val requestBytesIndex = params.indices.filter { params[it] == ByteArray::class.java }.singleOrNull() ?: return null
        val descriptorIndex = params.indices.filter { index ->
            index != requestBytesIndex && looksLikeDescriptor(params[index])
        }.singleOrNull() ?: return null
        val handlerIndex = params.indices.filter { index ->
            index != requestBytesIndex && index != descriptorIndex &&
                params[index].isInterface && Lookup.declaredMethods(params[index]).any { candidate ->
                    candidate.name == "onNext" && candidate.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
                }
        }.singleOrNull() ?: return null
        return WireSurface(method.declaringClass, method, descriptorIndex, requestBytesIndex, handlerIndex)
    }

    fun direct(loader: ClassLoader): WireSurface? {
        val engine = Lookup.classOrNull(loader, ENGINE) ?: return null
        return Lookup.declaredMethods(engine, makeAccessible = true) { true }
            .mapNotNull(::inspectEntry)
            .distinctBy { it.engineEntry.toGenericString() }
            .singleOrNull()
    }

    fun resolve(loader: ClassLoader, cached: HookPoint?): WireSurface? {
        direct(loader)?.let { return it }
        if (cached == null || cached.viewField != null) return null
        val names = cached.paramClassNames?.takeIf { it.size in 4..6 } ?: return null
        val owner = Lookup.classOrNull(loader, cached.className) ?: return null
        val method = Lookup.declaredMethods(owner, makeAccessible = true) { method ->
            method.name == cached.methodName && method.parameterTypes.map(Class<*>::getName) == names
        }.singleOrNull() ?: return null
        return inspectEntry(method)
    }

    fun needsQuery(
        loader: ClassLoader,
        cached: HookPoint?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicable(loader) && resolve(loader, cached) == null

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: HookPoint?,
        planned: Boolean
    ): HookPoint? {
        if (!enabled) return cached
        resolve(loader, cached)?.let { return point(it.engineEntry) }
        if (!planned) return cached
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        return candidates.mapNotNull(::inspectEntry)
            .distinctBy { it.engineEntry.toGenericString() }
            .singleOrNull()
            ?.let { point(it.engineEntry) }
    }

    fun point(method: Method) = VersionAdapter.HookPoint(
        method.declaringClass.name,
        method.name,
        method.parameterTypes.map(Class<*>::getName)
    )

    private fun looksLikeDescriptor(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray || type.name.startsWith("java.") || type.name.startsWith("kotlin.")) return false
        val fieldTypes = Lookup.fields(type, includeSuperclasses = true).map { it.type.name }
        return KCLASS in fieldTypes &&
            (SERIALIZATION_STRATEGY in fieldTypes || DESERIALIZATION_STRATEGY in fieldTypes)
    }
}
