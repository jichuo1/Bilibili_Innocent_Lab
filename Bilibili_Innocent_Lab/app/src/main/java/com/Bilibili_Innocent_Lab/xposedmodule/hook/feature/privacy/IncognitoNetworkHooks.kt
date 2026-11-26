package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.hostProxyDefaultValue
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

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
        val libSignQuery = requireNotNull(Lookup.methodOrNull(signType, "signQuery", classOf<Map<*, *>>()))
            .takeIf { Modifier.isStatic(it.modifiers) } ?: error("LibBili.signQuery")
        val signFn: (Map<String, String>) -> String = { params ->
            requireNotNull(libSignQuery.invoke(null, params)).toString()
        }
        val rbType = host.type("okhttp3.RequestBody")
        val formType = host.type("okhttp3.FormBody")
        val formBuilder = host.type("okhttp3.FormBody\$Builder")
        val formSize = requireNotNull(Lookup.methodOrNull(formType, "size"))
        val formName = requireNotNull(Lookup.methodOrNull(formType, "name", Int::class.javaPrimitiveType!!))
        val formValue = requireNotNull(Lookup.methodOrNull(formType, "value", Int::class.javaPrimitiveType!!))
        val formBuilderCtor = requireNotNull(Lookup.constructorOrNull(formBuilder))
        val fbAdd = requireNotNull(Lookup.methodOrNull(formBuilder, "add", String::class.java, String::class.java))
        val fbBuild = requireNotNull(Lookup.methodOrNull(formBuilder, "build"))

        val bufferedSinkType = host.type("okio.BufferedSink")
        val bufferType = host.type("okio.Buffer")
        val bufferCtor = requireNotNull(Lookup.constructorOrNull(bufferType))
        val bufferSizeMethod = Lookup.methodOrNull(bufferType, "size")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
        val bufferSizeField = Lookup.fieldOrNull(bufferType, "size")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.type == Long::class.javaPrimitiveType }
        require(bufferSizeMethod != null || bufferSizeField != null) { "okio.Buffer.size" }
        val bufferReadByteArray = requireNotNull(Lookup.methodOrNull(bufferType, "readByteArray"))
        val bufferClose = Lookup.methodOrNull(bufferType, "close")
        val rbContentType = requireNotNull(Lookup.methodOrNull(rbType, "contentType"))
        val rbContentLength = requireNotNull(Lookup.methodOrNull(rbType, "contentLength"))
        val rbWriteTo = requireNotNull(Lookup.methodOrNull(rbType, "writeTo", bufferedSinkType))
        val rbIsOneShot = Lookup.methodOrNull(rbType, "isOneShot")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType }
        val rbCreateString = requireNotNull(Lookup.methodOrNull(rbType, "create", mediaType, classOf<String>()))
        val rbCreateBytes = requireNotNull(
            Lookup.methodOrNull(rbType, "create", mediaType, ByteArray::class.java)
                ?: Lookup.methodOrNull(rbType, "create", ByteArray::class.java, mediaType)
        )
        val rbBytesFirst = rbCreateBytes.parameterTypes[0] == ByteArray::class.java
        val mediaParse = requireNotNull(Lookup.methodOrNull(mediaType, "parse", classOf<String>()))
        val formMediaType = mediaParse.invoke(null, "application/x-www-form-urlencoded")

        val reqNewBuilder = requireNotNull(Lookup.methodOrNull(requestType, "newBuilder"))
        val reqBuilderType = reqNewBuilder.returnType
        val reqUrl = requireNotNull(Lookup.methodOrNull(requestType, "url"))
        val reqMethod = requireNotNull(Lookup.methodOrNull(requestType, "method"))
        val reqBody = requireNotNull(Lookup.methodOrNull(requestType, "body"))
        val reqHeader = requireNotNull(Lookup.methodOrNull(requestType, "header", classOf<String>()))
        val rbRemoveHeader = requireNotNull(Lookup.methodOrNull(reqBuilderType, "removeHeader", classOf<String>()))
        val rbHeader = requireNotNull(Lookup.methodOrNull(reqBuilderType, "header", classOf<String>(), classOf<String>()))
        val rbMethod = requireNotNull(Lookup.methodOrNull(reqBuilderType, "method", classOf<String>(), rbType))
        val rbBuild = requireNotNull(Lookup.methodOrNull(reqBuilderType, "build"))

        val httpUrlType = reqUrl.returnType
        val rbUrl = requireNotNull(Lookup.methodOrNull(reqBuilderType, "url", httpUrlType))
        val urlHost = requireNotNull(Lookup.methodOrNull(httpUrlType, "host"))
        val urlEncodedPath = requireNotNull(Lookup.methodOrNull(httpUrlType, "encodedPath"))
        val urlQuerySize = requireNotNull(Lookup.methodOrNull(httpUrlType, "querySize"))
        val urlQueryParamName = requireNotNull(Lookup.methodOrNull(httpUrlType, "queryParameterName", Int::class.javaPrimitiveType!!))
        val urlQueryParamValue = requireNotNull(Lookup.methodOrNull(httpUrlType, "queryParameterValue", Int::class.javaPrimitiveType!!))
        val urlNewBuilder = requireNotNull(Lookup.methodOrNull(httpUrlType, "newBuilder"))
        val urlBuilderType = urlNewBuilder.returnType
        val ubEncodedQuery = requireNotNull(Lookup.methodOrNull(urlBuilderType, "encodedQuery", classOf<String>()))
        val ubQuery = requireNotNull(Lookup.methodOrNull(urlBuilderType, "query", classOf<String>()))
        val ubAddQueryParam = requireNotNull(Lookup.methodOrNull(urlBuilderType, "addQueryParameter", classOf<String>(), classOf<String>()))
        val ubBuild = requireNotNull(Lookup.methodOrNull(urlBuilderType, "build"))

        val respBuilderCtor = requireNotNull(Lookup.constructorOrNull(responseBuilder))
        val respRequest = requireNotNull(Lookup.methodOrNull(responseBuilder, "request", requestType))
        val respProtocol = requireNotNull(Lookup.methodOrNull(responseBuilder, "protocol", protocol))
        val respCode = requireNotNull(Lookup.methodOrNull(responseBuilder, "code", Int::class.javaPrimitiveType!!))
        val respMessage = requireNotNull(Lookup.methodOrNull(responseBuilder, "message", classOf<String>()))
        val respHeader = requireNotNull(Lookup.methodOrNull(responseBuilder, "header", classOf<String>(), classOf<String>()))
        val respBody = requireNotNull(Lookup.methodOrNull(responseBuilder, "body", bodyType))
        val respBuild = requireNotNull(Lookup.methodOrNull(responseBuilder, "build"))

        // gRPC/Moss boundary resolved up front: if any of these is missing we must NOT register the
        // HTTP interceptors either, because the unprotected RPC path would then leak real identity.
        val moss = host.type("com.bilibili.lib.moss.api.MossServiceImp")
        val descriptor = host.type("io.grpc.MethodDescriptor")
        val mdFullName = requireNotNull(Lookup.methodOrNull(descriptor, "getFullMethodName"))
        val mdResponseMarshaller = requireNotNull(Lookup.methodOrNull(descriptor, "getResponseMarshaller"))
        val marshallerParse = requireNotNull(Lookup.methodOrNull(mdResponseMarshaller.returnType, "parse", java.io.InputStream::class.java))
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
        fun createByteBody(bodyMediaType: Any?, bytes: ByteArray): Any = requireNotNull(
            if (rbBytesFirst) rbCreateBytes.invoke(null, bytes, bodyMediaType)
            else rbCreateBytes.invoke(null, bodyMediaType, bytes)
        )
        fun success(original: Any): Any {
            val builder = respBuilderCtor.newInstance()
            respRequest.invoke(builder, original)
            respProtocol.invoke(builder, http11)
            respCode.invoke(builder, 200)
            respMessage.invoke(builder, "OK")
            respHeader.invoke(builder, "Cache-Control", "no-store")
            respBody.invoke(builder, createBody.invoke(null, jsonType,
                "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{}}"))
            return requireNotNull(respBuild.invoke(builder))
        }
        fun sanitizeNonFormBody(old: Any, identity: IncognitoSession.Identity): Any? {
            if (rbIsOneShot?.invoke(old) == true) {
                throw IOException("One-shot request body cannot be sanitized in incognito")
            }
            val declaredLength = rbContentLength.invoke(old) as Long
            if (declaredLength > MAX_BODY_BYTES) {
                throw IOException("Request body exceeds 64 KiB cap")
            }
            if (declaredLength == 0L) return null
            val buffer = bufferCtor.newInstance()
            val bytes = try {
                rbWriteTo.invoke(old, buffer)
                val writtenSize = (bufferSizeMethod?.invoke(buffer) ?: bufferSizeField!!.getLong(buffer)) as Long
                if (writtenSize > MAX_BODY_BYTES) {
                    throw IOException("Request body exceeds 64 KiB cap")
                }
                bufferReadByteArray.invoke(buffer) as ByteArray
            } finally {
                runCatching { bufferClose?.invoke(buffer) }
            }
            if (bytes.isEmpty()) return null
            val bodyMediaType = rbContentType.invoke(old)
            val contentTypeStr = bodyMediaType?.toString()
            val cleanBytes = sanitizeRawRequestBody(bytes, contentTypeStr, identity, signFn)
            return createByteBody(bodyMediaType, cleanBytes)
        }
        fun sanitize(original: Any, identity: IncognitoSession.Identity): Any {
            val builder = requireNotNull(reqNewBuilder.invoke(original))
            listOf("Cookie", "Authorization", "Proxy-Authorization", "X-Bili-Access-Key", "X-Bili-Mid",
                "Display-ID", "X-Bili-Ticket", "X-Bili-Ticket-Bin", "Bili-Ticket", "X-Bili-Aurora-Eid",
                "X-Bili-Trace-Id", "X-Bili-Gaia-Vtoken", "X-Bili-Exps-Bin",
                "X-Bili-Metadata-Bin", "X-Bili-Device-Bin", "X-Bili-Fawkes-Req-Bin")
                .forEach { rbRemoveHeader.invoke(builder, it) }
            listOf("Buvid", "Device-ID", "fp_local", "fp_remote", "GuestId").forEach {
                rbHeader.invoke(builder, it, identity.buvid)
            }
            listOf("Buvid3", "Buvid4").forEach { name ->
                if (reqHeader.invoke(original, name) != null) {
                    rbHeader.invoke(builder, name, identity.buvid)
                }
            }
            rbHeader.invoke(builder, "Session-ID", identity.sessionId)
            rbHeader.invoke(builder, "session_id", identity.sessionId)
            rbHeader.invoke(builder, "Cache-Control", "no-store, no-cache")
            val url = requireNotNull(reqUrl.invoke(original))
            val params = linkedMapOf<String, String>()
            repeat(urlQuerySize.invoke(url) as Int) { i ->
                params[urlQueryParamName.invoke(url, i) as String] =
                    urlQueryParamValue.invoke(url, i) as? String ?: ""
            }
            val clean = PrivacyNetworkPolicy.parameters(params, identity).toMutableMap()
            if (clean != params) {
                val signed = clean.remove("sign") != null
                val ub = requireNotNull(urlNewBuilder.invoke(url))
                if (signed) ubEncodedQuery.invoke(ub, signFn(clean))
                else {
                    ubQuery.invoke(ub, null)
                    clean.forEach { (k, v) -> ubAddQueryParam.invoke(ub, k, v) }
                }
                rbUrl.invoke(builder, requireNotNull(ubBuild.invoke(ub)))
            }
            val old = reqBody.invoke(original)
            if (old != null) {
                val methodStr = reqMethod.invoke(original) as String
                if (formType.isInstance(old)) {
                    val values = linkedMapOf<String, String>()
                    repeat(formSize.invoke(old) as Int) { i ->
                        values[formName.invoke(old, i) as String] = formValue.invoke(old, i) as String
                    }
                    val safe = PrivacyNetworkPolicy.parameters(values, identity).toMutableMap()
                    if (safe != values) {
                        val signed = safe.remove("sign") != null
                        val replacement = if (signed) {
                            val value = signFn(safe)
                            rbCreateString.invoke(null, formMediaType, value)
                        } else {
                            val fb = formBuilderCtor.newInstance()
                            safe.forEach { (k, v) -> fbAdd.invoke(fb, k, v) }
                            fbBuild.invoke(fb)
                        }
                        rbMethod.invoke(builder, methodStr, replacement)
                    }
                } else {
                    sanitizeNonFormBody(old, identity)?.let { replacement ->
                        rbMethod.invoke(builder, methodStr, replacement)
                    }
                }
            }
            return requireNotNull(rbBuild.invoke(builder))
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
                        val url = requireNotNull(reqUrl.invoke(original))
                        val targetHost = urlHost.invoke(url) as String
                        val targetPath = urlEncodedPath.invoke(url) as String
                        if (!isolationReady() || PrivacyNetworkPolicy.blockHttp(targetHost, targetPath,
                                reqMethod.invoke(original) as String)) {
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
            val marshaller = requireNotNull(mdResponseMarshaller.invoke(md))
            return requireNotNull(marshallerParse.invoke(marshaller, ByteArrayInputStream(byteArrayOf())))
        }

        // Everything required resolved. Return the applier; nothing is registered until it runs.
        return@runCatching {
            var registeredHooks = 0
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
            registeredHooks++
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
            registeredHooks++
            env.registrar.exact("incognito.http.cookies", client, cookieJarGetter.name) { before { result = noCookies } }
            registeredHooks++
            // Internal, obfuscated cache getter (bypasses the public cache() getter) when identified.
            internalCacheGetter?.let { getter ->
                env.registrar.exact("incognito.http.cache", client, getter.name) { before { result = null } }
                registeredHooks++
            }
            publicCacheGetter?.let {
                env.registrar.exact("incognito.http.public_cache", client, it.name) { before { result = null } }
                registeredHooks++
            }
            env.registrar.exact("incognito.http.sign", retro, signQuery.name, classOf<Map<*, *>>()) {
                before {
                    val identity = session.identity() ?: return@before
                    @Suppress("UNCHECKED_CAST")
                    val params = argOrNull(0) as? Map<String, String> ?: return@before
                    args[0] = PrivacyNetworkPolicy.parameters(params, identity).toMutableMap()
                }
            }
            registeredHooks++
            for ((name, member, types) in listOf(
                Triple("blockingUnaryCall", unaryBlocking, arrayOf(descriptor, message, rule)),
                Triple("asyncUnaryCall", unaryAsync, arrayOf(descriptor, message, handler, rule))
            )) {
                env.registrar.exact("incognito.rpc.$name", moss, member.name, *types) {
                    before {
                        val md = argOrNull(0)
                        // Unknown descriptors fail closed as well: the protective framework would otherwise
                        // retry the original RPC after a reflection/marshaller exception in this callback.
                        val blocked = runCatching {
                            !isolationReady() || PrivacyNetworkPolicy.blockRpc(mdFullName.invoke(requireNotNull(md)) as String)
                        }.getOrDefault(true)
                        if (!blocked) {
                            // Only error class/code, once per RPC method; no URL, content, identity or token.
                            if (name == "asyncUnaryCall") argOrNull(2)?.let { callback ->
                                val rpc = runCatching { mdFullName.invoke(requireNotNull(md)) as String }.getOrDefault("unknown")
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
                registeredHooks++
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
                                    else -> hostProxyDefaultValue(m.returnType)
                                }
                            }
                            applied("stream")
                        }.onFailure { env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR) }
                    }
                }
                registeredHooks++
            }
            env.registrar.exact("incognito.rpc.asyncServerStreamingCall", moss, serverStream.name,
                descriptor, message, handler) {
                before {
                    val blocked = runCatching {
                        !isolationReady() || PrivacyNetworkPolicy.blockRpc(
                            mdFullName.invoke(requireNotNull(argOrNull(0))) as String)
                    }.getOrDefault(true)
                    if (!blocked) return@before
                    result = null
                    runCatching {
                        argOrNull(2)?.let { callback -> onComplete.invoke(callback) }
                        applied("server_stream")
                    }.onFailure { env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR) }
                }
            }
            registeredHooks++
            registeredHooks
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

    internal fun sanitizeRawRequestBody(
        bytes: ByteArray,
        contentType: String?,
        identity: IncognitoSession.Identity,
        sign: (Map<String, String>) -> String
    ): ByteArray {
        val text = runCatching {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { throw IOException("Unparseable binary request body", it) }
        if (0.toByte() in bytes || text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            throw IOException("Unparseable binary request body")
        }
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mediaType.isNotEmpty() &&
            mediaType != "application/x-www-form-urlencoded" &&
            mediaType != "application/json" &&
            !mediaType.endsWith("+json") &&
            !mediaType.startsWith("text/")
        ) {
            throw IOException("Unsupported request body media type in incognito: $mediaType")
        }
        if (mediaType.startsWith("text/") && bytes.isNotEmpty() &&
            !IgnetHttpSanitizer.looksLikeFormEncoded(bytes) &&
            !IgnetHttpSanitizer.looksLikeJsonText(bytes)
        ) {
            throw IOException("Unsupported text request body in incognito: $mediaType")
        }
        return IgnetHttpSanitizer.formBody(bytes, contentType, identity, sign)
    }

    private companion object {
        const val MAX_BODY_BYTES = 64L * 1024L
        const val MAX_SEEN_HTTP = 64
    }
}
