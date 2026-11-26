package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.util.concurrent.ConcurrentHashMap

/**
 * Boundary for the host's KMP gRPC pipeline (`GrpcEngine` + `MossInterceptor` chain), which the
 * Java `MossServiceImp` boundary never sees. One instance per process:
 *  - incognito: seals identity, blocks writes/telemetry and answers local-library RPCs;
 *  - guest only (not incognito): only answers local-library RPCs, everything else is untouched.
 *
 * The interceptor is injected into each engine's interceptor list on its first call, so engines
 * created before our hooks are covered as well. In an incognito session a call that cannot be
 * routed through the interceptor is failed instead of being sent.
 */
internal class KmpMossBoundary private constructor(
    private val env: HookEnvironment,
    loader: ClassLoader,
    private val surface: KmpMossSurface,
    private val session: IncognitoSession?,
    isolationReady: () -> Boolean,
    sanitizer: KmpHeaderSanitizer?
) {
    sealed interface Prepared {
        /** The host build has no KMP gRPC engine; nothing to protect. */
        data object Absent : Prepared
        class Ready(val boundary: KmpMossBoundary) : Prepared
    }

    /** Sees the serialized replies of [rpcs] (full method names); it can never alter or delay them. */
    class ReplyObserver(val rpcs: Set<String>, val onReply: (rpc: String, bytes: ByteArray) -> Unit)

    private val featureId = if (session != null) "incognito_privacy" else "guest_browsing"
    private val host = PrivacyHostAccess(loader)
    @Volatile private var guestCheck: () -> Boolean = { false }
    @Volatile private var reply: ((String, ByteArray) -> ByteArray?)? = null
    @Volatile private var observer: ReplyObserver? = null
    private val lock = Any()
    private val statuses = ConcurrentHashMap.newKeySet<String>()
    private val loggedRpcs = ConcurrentHashMap.newKeySet<String>()
    private val loggedHeaders = ConcurrentHashMap.newKeySet<String>()

    private val interceptor = KmpMossInterceptor(surface, loader, session, isolationReady, sanitizer,
        guest = { guestCheck() }, localReply = { reply }, events = object : KmpMossInterceptor.Events {
            override fun replied(local: Boolean, rpc: String?) {
                evidence(FeatureRuntimeStage.APPLIED)
                if (statuses.add(if (local) "local" else "blocked")) {
                    env.reportStatus("incognito_transport_kmp", if (local) "local-success" else "blocked-success")
                }
                if (rpc != null && loggedRpcs.size < MAX_LOGGED && loggedRpcs.add("${if (local) "local" else "blocked"}:$rpc")) {
                    // logInfo dedupes on its first argument, so the key must be unique per message.
                    env.logInfo("kmp_moss_rpc:${if (local) "local" else "blocked"}:$rpc",
                        "[BIL] KMP RPC ${if (local) "answered locally" else "blocked"}: $rpc")
                }
            }

            override fun sanitized(names: Set<String>) {
                evidence(FeatureRuntimeStage.APPLIED)
                if (statuses.add("sanitized")) env.reportStatus("incognito_transport_kmp_headers", "sanitized")
                // Header NAMES only, once per distinct set; values never leave the request.
                val key = names.joinToString(",")
                if (loggedHeaders.size < MAX_LOGGED && loggedHeaders.add(key)) {
                    env.logInfo("kmp_moss_headers:$key", "[BIL] KMP request headers: $key")
                }
            }

            override fun passed(rpc: String) {
                if (loggedRpcs.size < MAX_LOGGED && loggedRpcs.add("passed:$rpc")) {
                    env.logInfo("kmp_moss_rpc:passed:$rpc", "[BIL] KMP RPC sealed and sent: $rpc")
                }
            }

            override fun failed(reason: Throwable) {
                evidence(FeatureRuntimeStage.ERROR)
                if (statuses.add("error:" + reason.javaClass.simpleName)) {
                    env.logError("kmp_moss:error:${reason.javaClass.simpleName}",
                        "[BIL] KMP boundary error: ${reason.javaClass.simpleName}")
                }
            }
        })

    /** Wire the local library after the guest installer built it; until then local RPCs are not served. */
    fun attach(isGuest: () -> Boolean, localReply: (String, ByteArray) -> ByteArray?, observer: ReplyObserver? = null) {
        guestCheck = isGuest
        reply = localReply
        this.observer = observer
    }

    /** Register the engine hook; returns how many hooks were installed. */
    fun install(): Int {
        val entry = surface.engineEntry
        env.reportStatus("kmp_moss_reply_observation",
            if (surface.entryDescriptorIndex >= 0 && surface.entryHandlerIndex >= 0) "available"
            else "unavailable:${surface.entryDescriptorIndex},${surface.entryHandlerIndex}")
        env.registrar.exact("kmp.moss.engine", surface.engine, entry.name, *entry.parameterTypes) {
            before {
                val engine = instance ?: return@before
                if (inject(engine)) {
                    observe(args)
                    return@before
                }
                // The interceptor could not be placed. A guest-only call goes through unchanged; an
                // incognito call must not be sent at all, so answer its handler with an error.
                evidence(FeatureRuntimeStage.ERROR)
                if (session != null) {
                    result = null
                    failHandler(args)
                }
            }
        }
        return 1
    }

    /** Wrap the reply handler of an observed RPC so its serialized reply is also shown to the observer. */
    private fun observe(args: Array<Any?>) {
        val watch = observer ?: return
        val descriptorAt = surface.entryDescriptorIndex
        val handlerAt = surface.entryHandlerIndex
        if (descriptorAt < 0 || handlerAt < 0) return
        runCatching {
            val rpc = interceptor.fullMethodName(args[descriptorAt] ?: return) ?: return
            if (rpc !in watch.rpcs) return
            val original = args[handlerAt] ?: return
            args[handlerAt] = observing(surface.engineEntry.parameterTypes[handlerAt], original) { bytes ->
                watch.onReply(rpc, bytes)
            }
        }
    }

    private fun inject(engine: Any): Boolean = runCatching {
        val list = surface.engineList.get(engine) as? List<*> ?: return false
        if (list.any { it === interceptor.proxy }) return true
        synchronized(lock) {
            val current = surface.engineList.get(engine) as List<*>
            // Copy-on-write: concurrent calls keep iterating the old list. Priority is MAX_VALUE, which
            // is where the host's own priority sort would have put the interceptor.
            if (current.none { it === interceptor.proxy }) surface.engineList.set(engine, current + interceptor.proxy)
        }
        true
    }.getOrDefault(false)

    private fun failHandler(args: Array<Any?>) {
        runCatching {
            val exceptionType = surface.exceptionCtor.declaringClass
            // The engine entry takes (service, descriptor, bytes, handler, rest); the handler is the
            // argument that implements onError(KMossException).
            val handler = args.firstOrNull { arg ->
                arg != null && Lookup.methods(arg.javaClass, includeSuperclasses = true) {
                    it.name == "onError" && it.parameterTypes.contentEquals(arrayOf(exceptionType))
                }.isNotEmpty()
            } ?: return
            val error = surface.exceptionCtor.newInstance("KMP request isolation failed", java.io.IOException("isolation"))
            host.call(handler, "onError", error)
            host.call(handler, "onCompleted")
        }
    }

    private fun evidence(stage: FeatureRuntimeStage) = env.reportRuntimeEvidence(featureId, stage)

    companion object {
        private const val MAX_LOGGED = 48

        /**
         * A proxy of the engine's reply-handler interface that forwards every call to [original] and, for
         * onNext(bytes), first shows the bytes to [onBytes]. The observer's failures never reach the host.
         */
        internal fun observing(handlerType: Class<*>, original: Any, onBytes: (ByteArray) -> Unit): Any =
            java.lang.reflect.Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType)) { self, method, args ->
                if (method.declaringClass == Any::class.java) {
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(self)
                        "equals" -> self === args?.getOrNull(0)
                        else -> original.toString()
                    }
                } else {
                    if (method.name == "onNext") (args?.getOrNull(0) as? ByteArray)?.let { runCatching { onBytes(it) } }
                    try {
                        method.invoke(original, *(args ?: emptyArray()))
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }

        /**
         * Resolve everything the boundary needs. Returns [Prepared.Absent] when the host has no KMP
         * engine, null when it has one this build cannot route (an incognito session must then not
         * start: the channel would stay open), otherwise a boundary that registers nothing until
         * [install] is called.
         */
        fun prepare(
            env: HookEnvironment,
            session: IncognitoSession?,
            isolationReady: () -> Boolean,
            cachedEngine: VersionAdapter.HookPoint? = null
        ): Prepared? {
            val loader = env.classLoader ?: return null
            return when (val resolved = KmpMossSurface.resolve(loader, cachedEngine = cachedEngine)) {
                KmpMossSurface.Result.Absent -> Prepared.Absent
                is KmpMossSurface.Result.Unresolved -> {
                    runCatching { env.logError("kmp_moss:unresolved", "[BIL] KMP moss pipeline unresolved: ${resolved.reason}") }
                    null
                }
                is KmpMossSurface.Result.Resolved -> {
                    val sanitizer = if (session == null) null else identitySanitizer(loader) ?: run {
                        runCatching { env.logError("kmp_moss:sanitizer", "[BIL] KMP identity sanitizer unavailable") }
                        return null
                    }
                    Prepared.Ready(KmpMossBoundary(env, loader, resolved.surface, session, isolationReady, sanitizer))
                }
            }
        }

        private class MessageRewriterPlan(
            private val parseFrom: java.lang.reflect.Method,
            private val toBuilder: java.lang.reflect.Method,
            private val build: java.lang.reflect.Method,
            private val toByteArray: java.lang.reflect.Method,
            val setters: Map<String, java.lang.reflect.Method>
        ) {
            fun rewrite(bytes: ByteArray, edit: (Any, Map<String, java.lang.reflect.Method>) -> Unit): ByteArray {
                val parsed = requireNotNull(parseFrom.invoke(null, bytes))
                val builder = requireNotNull(toBuilder.invoke(parsed))
                edit(builder, setters)
                val rebuilt = requireNotNull(build.invoke(builder))
                return toByteArray.invoke(rebuilt) as ByteArray
            }
        }

        /** Re-writes the binary identity headers with the Java protobuf classes the host already ships. */
        internal fun identitySanitizer(loader: ClassLoader): KmpHeaderSanitizer? = runCatching {
            val host = PrivacyHostAccess(loader)
            val metadata = host.type("com.bapis.bilibili.metadata.Metadata")
            val device = host.type("com.bapis.bilibili.metadata.device.Device")
            fun requireShape(
                message: Class<*>,
                setters: List<Pair<String, Array<Class<*>>>>
            ): MessageRewriterPlan {
                val parseFrom = requireNotNull(
                    Lookup.methodOrNull(message, "parseFrom", ByteArray::class.java)
                        ?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                ) { "parseFrom" }
                // toBuilder() lives on the protobuf-lite base class, not on the generated message itself.
                val toBuilder = requireNotNull(
                    Lookup.inheritedMethodOrNull(message, "toBuilder")
                        ?.takeIf { !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
                ) { "toBuilder" }
                val builder = requireNotNull(
                    Lookup.methodOrNull(message, "newBuilder")
                        ?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
                ) { "newBuilder" }.returnType
                val build = requireNotNull(
                    Lookup.inheritedMethodOrNull(builder, "build")
                        ?.takeIf { !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
                ) { "build" }
                val toByteArray = requireNotNull(
                    (Lookup.inheritedMethodOrNull(message, "toByteArray")
                        ?: Lookup.inheritedMethodOrNull(build.returnType, "toByteArray"))
                        ?.takeIf {
                            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                                it.parameterCount == 0 &&
                                it.returnType == ByteArray::class.java
                        }
                ) { "toByteArray" }
                val setterMethods = setters.associate { (name, paramTypes) ->
                    val method = requireNotNull(Lookup.methodOrNull(builder, name, *paramTypes)) { name }
                    name to method
                }
                return MessageRewriterPlan(parseFrom, toBuilder, build, toByteArray, setterMethods)
            }
            val metadataPlan = requireShape(
                metadata,
                listOf(
                    "setBuvid" to arrayOf(String::class.java),
                    "setAccessKey" to arrayOf(String::class.java)
                )
            )
            val devicePlan = requireShape(
                device,
                listOf(
                    "setBuvid" to arrayOf(String::class.java),
                    "setFp" to arrayOf(String::class.java),
                    "setFpLocal" to arrayOf(String::class.java),
                    "setFpRemote" to arrayOf(String::class.java),
                    "setGuestId" to arrayOf(String::class.java),
                    "setFts" to arrayOf(Long::class.javaPrimitiveType!!)
                )
            )
            KmpHeaderSanitizer(
                rewriteMetadata = { bytes, identity ->
                    metadataPlan.rewrite(bytes) { builder, methods ->
                        methods.getValue("setBuvid").invoke(builder, identity.buvid)
                        methods.getValue("setAccessKey").invoke(builder, "")
                    }
                },
                rewriteDevice = { bytes, identity ->
                    devicePlan.rewrite(bytes) { builder, methods ->
                        methods.getValue("setBuvid").invoke(builder, identity.buvid)
                        methods.getValue("setFp").invoke(builder, identity.buvid)
                        methods.getValue("setFpLocal").invoke(builder, identity.buvid)
                        methods.getValue("setFpRemote").invoke(builder, identity.buvid)
                        methods.getValue("setGuestId").invoke(builder, identity.buvid)
                        methods.getValue("setFts").invoke(builder, 0L)
                    }
                }
            )
        }.getOrNull()
    }
}
