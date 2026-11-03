package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.highcapable.kavaref.extension.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Per-process HTTP/RPC boundary. Adapter failures never send the original authenticated request. */
internal class IncognitoNetworkHooks(
    private val session: IncognitoSession,
    private val isolationReady: () -> Boolean = { true }
) {
    /**
     * Resolve the whole boundary first; only when every REQUIRED anchor is present return an
     * applier that performs the registrations. A missing anchor returns null so the caller can
     * skip the entire incognito feature — never a partial boundary that blacks out HTTP while a
     * still-authenticated gRPC path stays open.
     */
    fun prepare(env: HookEnvironment): (() -> Int)? = runCatching {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val client = host.type("okhttp3.OkHttpClient")
        val interceptor = host.type("okhttp3.Interceptor")
        val chainType = host.type("okhttp3.Interceptor\$Chain")
        val requestType = host.type("okhttp3.Request")
        val responseBuilder = host.type("okhttp3.Response\$Builder")
        val bodyType = host.type("okhttp3.ResponseBody")
        val mediaType = host.type("okhttp3.MediaType")
        val protocol = host.type("okhttp3.Protocol")
        val http11 = requireNotNull(Lookup.fieldOrNull(protocol, "HTTP_1_1")).get(null)
        val jsonType = host.static(mediaType, "parse", "application/json; charset=utf-8")
        val createBody = requireNotNull(Lookup.methodOrNull(bodyType, "create", mediaType, classOf<String>()))
        val request = requireNotNull(Lookup.methodOrNull(chainType, "request"))
        val proceed = requireNotNull(Lookup.methodOrNull(chainType, "proceed", requestType))
        val signType = host.type("com.bilibili.nativelibrary.LibBili")
        val formType = host.type("okhttp3.FormBody")
        val formBuilder = host.type("okhttp3.FormBody\$Builder")
        // gRPC/Moss boundary resolved up front: if any of these is missing we must NOT register the
        // HTTP interceptors either, because the unprotected RPC path would then leak real identity.
        val moss = host.type("com.bilibili.lib.moss.api.MossServiceImp")
        val descriptor = host.type("io.grpc.MethodDescriptor")
        val message = host.type("com.google.protobuf.GeneratedMessageLite")
        val handler = host.type("com.bilibili.lib.moss.api.MossResponseHandler")
        val rule = host.type("com.bilibili.lib.moss.api.MossHttpRule")
        val onNext = requireNotNull(Lookup.methodOrNull(handler, "onNext", classOf<Any>()))
        val onComplete = requireNotNull(Lookup.methodOrNull(handler, "onCompleted"))
        val noCookies = requireNotNull(Lookup.fieldOrNull(host.type("okhttp3.CookieJar"), "NO_COOKIES")).get(null)
        val retro = host.type("com.bilibili.okretro.interceptor.DefaultRequestInterceptor")
        // Required RPC entries: every unary/streaming variant must resolve before registration.
        val unaryBlocking = requireNotNull(Lookup.methodOrNull(moss, "blockingUnaryCall", descriptor, message, rule))
        val unaryAsync = requireNotNull(Lookup.methodOrNull(moss, "asyncUnaryCall", descriptor, message, handler, rule))
        val serverStream = requireNotNull(Lookup.methodOrNull(moss, "asyncServerStreamingCall", descriptor, message, handler))
        val clientStream = requireNotNull(Lookup.methodOrNull(moss, "asyncClientStreamingCall", descriptor, handler))
        val bidiStream = requireNotNull(Lookup.methodOrNull(moss, "asyncBidiStreamingCall", descriptor, handler))
        val signQuery = requireNotNull(Lookup.methodOrNull(retro, "signQuery", classOf<Map<*, *>>()))
        val interceptorsGetter = requireNotNull(Lookup.methodOrNull(client, "interceptors"))
        val networkInterceptorsGetter = requireNotNull(Lookup.methodOrNull(client, "networkInterceptors"))
        val cookieJarGetter = requireNotNull(Lookup.methodOrNull(client, "cookieJar"))
        val publicCacheGetter = Lookup.methodOrNull(client, "cache")
        // Internal, obfuscated cache getter varies per host build and is NOT required: it is a
        // belt-and-suspenders over the public cache()/no-store path. Resolve it structurally when a
        // single unambiguous candidate exists; skip it otherwise rather than pinning one host build.
        val internalCacheGetter = resolveInternalCacheGetter(client, publicCacheGetter)

        val appliedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun applied(path: String) {
            env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.APPLIED)
            if (appliedPaths.add(path)) env.reportStatus("incognito_transport_$path", "local-success")
        }
        fun success(original: Any): Any {
            val builder = host.create(responseBuilder)
            host.call(builder, "request", original)
            host.call(builder, "protocol", http11)
            host.call(builder, "code", 200)
            host.call(builder, "message", "OK")
            host.call(builder, "header", "Cache-Control", "no-store")
            host.call(builder, "body", createBody.invoke(null, jsonType,
                "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{}}"))
            return requireNotNull(host.call(builder, "build"))
        }
        fun sanitize(original: Any, identity: IncognitoSession.Identity): Any {
            val builder = requireNotNull(host.call(original, "newBuilder"))
            listOf("Cookie", "Authorization", "Proxy-Authorization", "X-Bili-Access-Key", "X-Bili-Mid",
                "Display-ID", "X-Bili-Ticket", "Bili-Ticket", "X-Bili-Aurora-Eid").forEach { host.call(builder, "removeHeader", it) }
            listOf("Buvid", "Device-ID", "fp_local", "fp_remote", "GuestId").forEach {
                host.call(builder, "header", it, identity.buvid)
            }
            host.call(builder, "header", "Session-ID", identity.sessionId)
            host.call(builder, "header", "session_id", identity.sessionId)
            host.call(builder, "header", "Cache-Control", "no-store, no-cache")
            val url = requireNotNull(host.call(original, "url"))
            val params = linkedMapOf<String, String>()
            repeat(host.call(url, "querySize") as Int) { i ->
                params[host.call(url, "queryParameterName", i) as String] =
                    host.call(url, "queryParameterValue", i) as? String ?: ""
            }
            val clean = PrivacyNetworkPolicy.parameters(params, identity).toMutableMap()
            if (clean != params) {
                val signed = clean.remove("sign") != null
                val ub = requireNotNull(host.call(url, "newBuilder"))
                if (signed) host.call(ub, "encodedQuery", requireNotNull(host.static(signType, "signQuery", clean)).toString())
                else {
                    host.call(ub, "query", null)
                    clean.forEach { (k, v) -> host.call(ub, "addQueryParameter", k, v) }
                }
                host.call(builder, "url", requireNotNull(host.call(ub, "build")))
            }
            val old = host.call(original, "body")
            if (old != null && formType.isInstance(old)) {
                val values = linkedMapOf<String, String>()
                repeat(host.call(old, "size") as Int) { i ->
                    values[host.call(old, "name", i) as String] = host.call(old, "value", i) as String
                }
                val safe = PrivacyNetworkPolicy.parameters(values, identity).toMutableMap()
                if (safe != values) {
                    val signed = safe.remove("sign") != null
                    val replacement = if (signed) {
                        val value = requireNotNull(host.static(signType, "signQuery", safe)).toString()
                        val rb = host.type("okhttp3.RequestBody")
                        requireNotNull(Lookup.methodOrNull(rb, "create", mediaType, classOf<String>())).invoke(null,
                            host.static(mediaType, "parse", "application/x-www-form-urlencoded"), value)
                    } else {
                        val fb = host.create(formBuilder)
                        safe.forEach { (k, v) -> host.call(fb, "add", k, v) }
                        host.call(fb, "build")
                    }
                    host.call(builder, "method", host.call(original, "method"), replacement)
                }
            }
            return requireNotNull(host.call(builder, "build"))
        }
        val seenHttp = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val gameTrackerEvidence = java.util.concurrent.atomic.AtomicBoolean()
        val adMeasurementEvidence = java.util.concurrent.atomic.AtomicBoolean()
        val proxy = Proxy.newProxyInstance(host.loader, arrayOf(interceptor)) { p, method, args ->
            when (method.name) {
                "toString" -> "BIL incognito network boundary"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.getOrNull(0)
                "intercept" -> {
                    val chain = requireNotNull(args?.getOrNull(0))
                    val original = requireNotNull(request.invoke(chain))
                    try {
                        val url = requireNotNull(host.call(original, "url"))
                        val targetHost = host.call(url, "host") as String
                        val targetPath = host.call(url, "encodedPath") as String
                        if (!isolationReady() || PrivacyNetworkPolicy.blockHttp(targetHost, targetPath,
                                host.call(original, "method") as String)) {
                            applied("http")
                            if (PrivacyNetworkPolicy.isGameTrackerReport(targetHost, targetPath) &&
                                gameTrackerEvidence.compareAndSet(false, true)) {
                                runCatching { env.reportStatus("incognito_game_tracker_report", "withheld-okhttp") }
                            }
                            if (PrivacyNetworkPolicy.isAdMeasurementBeacon(targetHost, targetPath) &&
                                adMeasurementEvidence.compareAndSet(false, true)) {
                                runCatching { env.reportStatus("incognito_ad_measurement_okhttp", "withheld") }
                            }
                            // Some third-party paths contain identifiers, so redact the observed beacon before logging.
                            if (BuildConfig.DEBUG && seenHttp.size < MAX_SEEN_HTTP) {
                                val target = PrivacyNetworkPolicy.diagnosticTarget(targetHost, targetPath)
                                if (seenHttp.add("blocked:$target")) env.logInfo("incognito_http_blocked:$target", "[BIL] HTTP withheld locally: $target")
                            }
                            success(original)
                        } else {
                            // Debug builds only: host and path (never the query) of what is sent on, once each.
                            if (BuildConfig.DEBUG && seenHttp.size < MAX_SEEN_HTTP) {
                                val target = PrivacyNetworkPolicy.diagnosticTarget(targetHost, targetPath)
                                if (seenHttp.add(target)) env.logInfo("incognito_http_seen:$target", "[BIL] HTTP sealed and sent: $target")
                            }
                            proceed.invoke(chain, sanitize(original, requireNotNull(session.identity())))
                        }
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    } catch (e: Exception) {
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                        throw IOException("Incognito request isolation failed", e)
                    }
                }
                else -> error("Unexpected interceptor method")
            }
        }
        val readErrors = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun reply(md: Any): Any {
            val marshaller = requireNotNull(host.call(md, "getResponseMarshaller"))
            return requireNotNull(host.call(marshaller, "parse", ByteArrayInputStream(byteArrayOf())))
        }

        // Everything required resolved. Return the applier; nothing is registered until it runs.
        return@runCatching {
            env.registrar.exact("incognito.http.interceptors", client, interceptorsGetter.name) {
                after {
                    if (!hasThrowable) {
                        val existing = result as? List<*> ?: return@after
                        // Host application interceptors can add credentials or turn a read into a
                        // report. Check both before them (avoid enqueue/retry) and after them (the
                        // verified native bridge is the next interceptor in the chain).
                        result = listOf(proxy) + existing.filterNot { it === proxy } + proxy
                    }
                }
            }
            env.registrar.exact("incognito.http.network_interceptors", client, networkInterceptorsGetter.name) {
                after {
                    if (!hasThrowable) {
                        val existing = result as? List<*> ?: return@after
                        // Standard socket requests, redirects and authentication follow-ups must be
                        // cleaned after the host network interceptors too, immediately before I/O.
                        result = existing.filterNot { it === proxy } + proxy
                    }
                }
            }
            env.registrar.exact("incognito.http.cookies", client, cookieJarGetter.name) { before { result = noCookies } }
            // Internal, obfuscated cache getter (bypasses the public cache() getter) when identified.
            internalCacheGetter?.let { getter ->
                env.registrar.exact("incognito.http.cache", client, getter.name) { before { result = null } }
            }
            publicCacheGetter?.let {
                env.registrar.exact("incognito.http.public_cache", client, it.name) { before { result = null } }
            }
            env.registrar.exact("incognito.http.sign", retro, signQuery.name, classOf<Map<*, *>>()) {
                before {
                    @Suppress("UNCHECKED_CAST")
                    val params = argOrNull(0) as? Map<String, String> ?: return@before
                    args[0] = PrivacyNetworkPolicy.parameters(params, requireNotNull(session.identity())).toMutableMap()
                }
            }
            for ((name, member, types) in listOf(
                Triple("blockingUnaryCall", unaryBlocking, arrayOf(descriptor, message, rule)),
                Triple("asyncUnaryCall", unaryAsync, arrayOf(descriptor, message, handler, rule))
            )) env.registrar.exact("incognito.rpc.$name", moss, member.name, *types) {
                before {
                    val md = argOrNull(0)
                    // Unknown descriptors fail closed as well: the protective framework would otherwise
                    // retry the original RPC after a reflection/marshaller exception in this callback.
                    val blocked = runCatching {
                        !isolationReady() || PrivacyNetworkPolicy.blockRpc(host.call(requireNotNull(md), "getFullMethodName") as String)
                    }.getOrDefault(true)
                    if (!blocked) {
                        // Only error class/code, once per RPC method; no URL, content, identity or token.
                        if (name == "asyncUnaryCall") argOrNull(2)?.let { callback ->
                            val rpc = runCatching { host.call(requireNotNull(md), "getFullMethodName") as String }.getOrDefault("unknown")
                            args[2] = Proxy.newProxyInstance(host.loader, arrayOf(handler)) { _, m, values ->
                                if (m.name == "onError" && readErrors.add(rpc)) {
                                    val error = values?.getOrNull(0)
                                    val code = error?.let { runCatching { host.call(it, "getCode") as? Number }.getOrNull() }
                                    env.logError("incognito_rpc_read", "[BIL] Anonymous RPC failed: $rpc; type=${error?.javaClass?.simpleName}; code=$code")
                                }
                                try { m.invoke(callback, *(values ?: emptyArray())) }
                                catch (failure: InvocationTargetException) { throw failure.targetException }
                            }
                        }
                        return@before
                    }
                    result = null
                    runCatching {
                        val response = reply(requireNotNull(md))
                        if (name == "blockingUnaryCall") result = response
                        applied("rpc")
                        if (name == "asyncUnaryCall") argOrNull(2)?.let { callback ->
                            onNext.invoke(callback, response)
                            onComplete.invoke(callback)
                        }
                    }.onFailure {
                        // Keep result assigned even when a malformed reply or host callback fails.
                        // No exception may escape PROTECT_HOST and resume the original network call.
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                    }
                }
            }
            // Client/bidirectional streams carry presence and activity (e.g. Reply/Room's
            // join/leave events). Give callers a live local sink, rather than null or an error
            // that causes reconnection; no original stream or queued request is created.
            for ((name, member) in listOf("asyncClientStreamingCall" to clientStream, "asyncBidiStreamingCall" to bidiStream)) {
                env.registrar.exact("incognito.rpc.$name", moss, member.name, descriptor, handler) {
                    before {
                        result = null
                        runCatching {
                            val callback = argOrNull(1)
                            val sink = IncognitoStreamSink(
                                acknowledge = { id -> callback?.let { host.call(it, "onUpstreamAck", id) } },
                                complete = { callback?.let { onComplete.invoke(it) } })
                            result = Proxy.newProxyInstance(host.loader, arrayOf(handler)) { proxy2, m, values ->
                                when (m.name) {
                                    "equals" -> proxy2 === values?.getOrNull(0)
                                    "hashCode" -> System.identityHashCode(proxy2)
                                    "toString" -> "BIL local incognito stream"
                                    "onNextForAck" -> sink.acceptForAck()
                                    "onCompleted", "onError" -> { sink.close(); null }
                                    else -> null
                                }
                            }
                            applied("stream")
                        }.onFailure { env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR) }
                    }
                }
            }
            env.registrar.exact("incognito.rpc.asyncServerStreamingCall", moss, serverStream.name,
                descriptor, message, handler) {
                before {
                    val blocked = runCatching {
                        !isolationReady() || PrivacyNetworkPolicy.blockRpc(
                            host.call(requireNotNull(argOrNull(0)), "getFullMethodName") as String)
                    }.getOrDefault(true)
                    if (!blocked) return@before
                    result = null
                    runCatching {
                        argOrNull(2)?.let { callback -> onComplete.invoke(callback) }
                        applied("server_stream")
                    }.onFailure { env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR) }
                }
            }
            REGISTERED_HOOK_COUNT
        }
    }.getOrNull()

    /** Register the boundary only when every required anchor resolved; 0 means nothing was installed. */
    fun install(env: HookEnvironment): Int = prepare(env)?.invoke() ?: 0

    /**
     * The host reads its internal cache through an obfuscated 0-arg getter whose name and return
     * type change per build. Accept a single unambiguous non-static 0-arg method that is not one of
     * the public getters and whose return type is an obfuscated (short lower-case package) class;
     * if absent or ambiguous, return null and rely on the public cache()/no-store neutralization.
     */
    private fun resolveInternalCacheGetter(
        client: Class<*>,
        publicCache: java.lang.reflect.Method?
    ): java.lang.reflect.Method? {
        val candidates = Lookup.declaredMethods(client) { m ->
            !java.lang.reflect.Modifier.isStatic(m.modifiers) && m.parameterCount == 0 &&
                m !== publicCache && m.returnType != client &&
                m.returnType.name.let { name ->
                    val pkg = name.substringBeforeLast('.', "")
                    val simple = name.substringAfterLast('.')
                    // Obfuscated internal type: short lower-case package and 1-2 char class name,
                    // e.g. t06.d / ns5.d — never a JDK, Android, okhttp3 or public bilibili type.
                    simple.length <= 2 && pkg.isNotEmpty() && pkg.length <= 5 &&
                        pkg.all { it.isLowerCase() || it.isDigit() } &&
                        !name.startsWith("okhttp3.") && !name.startsWith("okio.")
                }
        }
        return candidates.singleOrNull()
    }

    private companion object {
        const val REGISTERED_HOOK_COUNT = 11
        const val MAX_SEEN_HTTP = 64
    }
}
