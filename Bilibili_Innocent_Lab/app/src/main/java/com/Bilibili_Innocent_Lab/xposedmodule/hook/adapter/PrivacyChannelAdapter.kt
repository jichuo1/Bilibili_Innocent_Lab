package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IncognitoTelemetryHooks
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Discovers and structurally verifies critical incognito privacy boundaries (`IgHttpRequest` and
 * `KNeuron`) when relocated or flattened by R8; every cached result is revalidated against the live
 * host ClassLoader ABI.
 */
internal object PrivacyChannelAdapter {
    const val IGNET_REQUEST = "com.bilibili.lib.ighttp.IgHttpRequest"
    const val IGNET_RESPONSE = "com.bilibili.lib.ighttp.IgHttpResponse"
    const val IGNET_STREAM_PROVIDER = "com.bilibili.lib.ighttp.IgHttpStreamDataProvider"
    const val IGNET_ENGINE = "kntr.base.ktor.ignet.IgnetHttpEngine"
    const val KNEURON = "kntr.base.neuron.KNeuron"
    const val DIAGNOSTIC_IGNET = "dex.assist.privacy_ignet_http"
    const val DIAGNOSTIC_KNEURON = "dex.assist.privacy_kneuron"
    val queryIgnet = DexAssistQuery.PRIVACY_IGNET_HTTP
    val queryKNeuron = DexAssistQuery.PRIVACY_KNEURON
    private const val IGNET_PACKAGE = "com.bilibili.lib.ighttp."
    private const val KNTR_NEURON_PACKAGE = "kntr.base.neuron."
    private const val NEURONS_JAVA = "com.bilibili.lib.neuron.api.Neurons"
    private const val MAX_CANDIDATES = 32

    data class IgHttpMembers(
        val request: Class<*>,
        val builder: Class<*>,
        val build: Method,
        val setUrl: Method,
        val urlField: Field,
        val headersField: Field,
        val bodyField: Field,
        val streamField: Field?,
        val produce: Method?,
        val execute: Method,
        val getUrl: Method,
        val getMethod: Method,
        val close: Method
    ) {
        fun executePoint(): HookPoint = HookPoint(
            request.name,
            execute.name,
            execute.parameterTypes.map(Class<*>::getName)
        )
    }

    fun applicableIgnet(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, IGNET_REQUEST) ||
            Lookup.hasClass(loader, IGNET_RESPONSE) ||
            Lookup.hasClass(loader, IGNET_ENGINE)

    fun applicableKNeuron(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, KNEURON) ||
            Lookup.hasClass(loader, "kntr.base.config.KConfig") ||
            Lookup.hasClass(loader, "kntr.base.moss.MossInterceptor") ||
            Lookup.hasClass(loader, IGNET_ENGINE)

    fun allowedIgnetOwnerName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith(IGNET_PACKAGE) || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun allowedKNeuronOwnerName(name: String): Boolean = name.length in 1..512 &&
        name != NEURONS_JAVA &&
        (name.startsWith(KNTR_NEURON_PACKAGE) || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun inspectIgHttpRequest(loader: ClassLoader, request: Class<*>): IgHttpMembers? = runCatching {
        if (!allowedIgnetOwnerName(request.name) || request.isInterface || Modifier.isAbstract(request.modifiers)) {
            return null
        }
        val execute = Lookup.methodOrNull(request, "execute")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && !it.returnType.isPrimitive && it.returnType != Void.TYPE }
            ?: return null
        val expectedResponse = Lookup.classOrNull(loader, IGNET_RESPONSE)
        if (expectedResponse != null && execute.returnType != expectedResponse) return null
        val getUrl = Lookup.methodOrNull(request, "url")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.returnType == String::class.java } ?: return null
        val getMethod = Lookup.methodOrNull(request, "method")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.returnType == String::class.java } ?: return null
        val close = Lookup.methodOrNull(request, "close")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE } ?: return null

        val builder = Lookup.classOrNull(loader, "${request.name}\$Builder")
            ?: Lookup.declaredConstructors(request, makeAccessible = true) { it.parameterCount == 1 }
                .singleOrNull()?.parameterTypes?.singleOrNull()
            ?: return null
        val build = Lookup.methodOrNull(builder, "build")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.returnType == request } ?: return null
        val setUrl = Lookup.methodOrNull(builder, "url", classOf<String>())
            ?.takeIf { !Modifier.isStatic(it.modifiers) } ?: return null
        val urlField = Lookup.fieldOrNull(builder, "mUrl")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.type == String::class.java } ?: return null
        val headersField = Lookup.fieldOrNull(builder, "mHeaders")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && MutableMap::class.java.isAssignableFrom(it.type) } ?: return null
        val bodyField = Lookup.fieldOrNull(builder, "mBody")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.type == ByteArray::class.java } ?: return null
        val streamProviderType = Lookup.classOrNull(loader, IGNET_STREAM_PROVIDER)
        val streamField = Lookup.fieldOrNull(builder, "mStreamDataProvider")
            ?.takeIf { !Modifier.isStatic(it.modifiers) }
        val produce = streamField?.type?.let { Lookup.methodOrNull(it, "onProduceData", Int::class.javaPrimitiveType!!) }
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.returnType == ByteArray::class.java }
        val requiresStream = streamProviderType != null ||
            declaresStreamProviderField(request, streamProviderType) ||
            declaresStreamProviderField(builder, streamProviderType)
        if (requiresStream && (streamField == null || !streamField.type.isInterface || produce == null)) {
            return null
        }

        IgHttpMembers(
            request = request,
            builder = builder,
            build = build,
            setUrl = setUrl,
            urlField = urlField,
            headersField = headersField,
            bodyField = bodyField,
            streamField = streamField,
            produce = produce,
            execute = execute,
            getUrl = getUrl,
            getMethod = getMethod,
            close = close
        )
    }.getOrNull()

    fun verifyKNeuron(owner: Class<*>): Boolean = runCatching {
        if (!allowedKNeuronOwnerName(owner.name)) return false
        if (owner.isInterface || Modifier.isAbstract(owner.modifiers)) return false
        IncognitoTelemetryHooks.kmpProducers(owner) != null
    }.getOrDefault(false)

    fun directIgnet(loader: ClassLoader): IgHttpMembers? =
        Lookup.classOrNull(loader, IGNET_REQUEST)?.let { inspectIgHttpRequest(loader, it) }

    fun resolveIgHttpRequest(loader: ClassLoader, cached: HookPoint?): IgHttpMembers? {
        directIgnet(loader)?.let { return it }
        cached ?: return null
        if (cached.viewField != null || !cached.paramClassNames.isNullOrEmpty() || !allowedIgnetOwnerName(cached.className)) {
            return null
        }
        val owner = Lookup.classOrNull(loader, cached.className) ?: return null
        val members = inspectIgHttpRequest(loader, owner) ?: return null
        return members.takeIf { it.execute.name == cached.methodName }
    }

    private fun declaresStreamProviderField(owner: Class<*>, streamProviderType: Class<*>?): Boolean =
        Lookup.fields(owner, includeSuperclasses = false).any { field ->
            if (Modifier.isStatic(field.modifiers)) return@any false
            val type = field.type
            if (field.name == "mStreamDataProvider") return@any true
            if (streamProviderType != null && streamProviderType.isAssignableFrom(type)) return@any true
            if (!type.isInterface || type.name.startsWith("java.") || type.name.startsWith("kotlin.") ||
                MutableMap::class.java.isAssignableFrom(type)
            ) {
                return@any false
            }
            type.simpleName.contains("StreamDataProvider") ||
                Lookup.declaredMethods(type) { !Modifier.isStatic(it.modifiers) }.any { m ->
                    m.name == "onProduceData" ||
                        (m.parameterCount == 1 &&
                            m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            m.returnType == ByteArray::class.java)
                }
        }

    fun directKNeuron(loader: ClassLoader): Class<*>? =
        Lookup.classOrNull(loader, KNEURON)?.takeIf(::verifyKNeuron)

    fun resolveKNeuron(loader: ClassLoader, cached: String?): Class<*>? {
        directKNeuron(loader)?.let { return it }
        val name = cached?.takeIf(::allowedKNeuronOwnerName) ?: return null
        return Lookup.classOrNull(loader, name)?.takeIf(::verifyKNeuron)
    }

    fun needsIgnetQuery(loader: ClassLoader, cached: HookPoint?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && applicableIgnet(loader) && resolveIgHttpRequest(loader, cached) == null

    fun needsKNeuronQuery(loader: ClassLoader, cached: String?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && applicableKNeuron(loader) && resolveKNeuron(loader, cached) == null

    fun locateIgnet(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: HookPoint?,
        planned: Boolean
    ): HookPoint? {
        if (!enabled) return cached
        resolveIgHttpRequest(loader, cached)?.let { return it.executePoint() }
        if (!planned) return null
        val candidates = (session?.result(queryIgnet) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates.map { it.declaringClass }
            .distinct()
            .mapNotNull { inspectIgHttpRequest(loader, it) }
            .singleOrNull()
            ?.executePoint()
    }

    fun locateKNeuron(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: String?,
        planned: Boolean
    ): String? {
        if (!enabled) return cached
        resolveKNeuron(loader, cached)?.let { return it.name }
        if (!planned) return null
        val candidates = (session?.result(queryKNeuron) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates.map { it.declaringClass }
            .distinct()
            .filter(::verifyKNeuron)
            .map { it.name }
            .singleOrNull()
    }
}
