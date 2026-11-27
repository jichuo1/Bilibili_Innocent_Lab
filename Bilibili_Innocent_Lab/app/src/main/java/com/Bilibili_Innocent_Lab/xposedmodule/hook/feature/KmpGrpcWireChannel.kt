package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossBridgeMembers.JavaReplyCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossResponseHandlerProxy.ConfirmedReply
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared KMP gRPC wire-byte channel built on [KmpGrpcWireAdapter.WireSurface] (`GrpcEngine`).
 *
 * Reads and rewrites raw protobuf `byte[]` directly at `GrpcEngine`'s reply handler boundary
 * (`onNext(ByteArray)`), before `kotlinx.serialization.protobuf.ProtoBuf` decodes or re-encodes
 * KMP data classes. This completely avoids kotlinx.serialization protobuf re-encoding failures on host models
 * that combine `NullableSerializer` map values with `ProtobufTaggedEncoder` (`9.1.0–9.20.0+`).
 */
internal object KmpGrpcWireChannel {
    private const val HOOK_ID = "kmp.grpc.wire.channel"
    internal const val FAULT_OWNER = "kmp_grpc_wire_channel"
    private val EMPTY_ARGS = emptyArray<Any?>()

    private val dispatchersLock = Any()
    private val dispatchers = WeakHashMap<HookRegistrar, MutableMap<Method, Dispatcher>>()
    private val pending = WeakHashMap<HookRegistrar, PendingBucket>()

    private class PendingBucket {
        val entries = ArrayList<PendingRegistration>()
    }

    private class PendingRegistration(
        val rpc: String,
        val rpcFullName: String,
        val registration: Registration
    )

    private class Registration(
        val what: String,
        val logKey: String,
        val rpcFullName: String,
        val javaReplyClass: Class<*>?,
        val replyCodec: JavaReplyCodec?,
        val javaRequestClass: Class<*>?,
        val requestCodec: JavaReplyCodec?,
        val transformRequest: ((Any) -> Any)?,
        val transformRawRequest: ((ByteArray) -> ByteArray)?,
        val confirmedTransform: ((Any) -> ConfirmedReply)?,
        val transformWithRequest: ((Any?, Any) -> Any)?,
        val transformJava: ((Any) -> Any)?,
        val transformRawReply: ((ByteArray) -> ByteArray)?,
        val wireActive: AtomicBoolean?,
        val onCall: () -> Unit,
        val onRequestFailed: (Throwable) -> Unit,
        val onReplyFailed: (Throwable) -> Unit,
        val readbackVerifySamplePending: AtomicBoolean = AtomicBoolean(true)
    )

    private class Dispatcher(val surface: KmpGrpcWireAdapter.WireSurface) {
        val registrations = ConcurrentHashMap<String, CopyOnWriteArrayList<Registration>>()
        private val handlerType = surface.engineEntry.parameterTypes[surface.handlerIndex]

        fun onEngineCall(param: ModernHookParam) {
            if (registrations.isEmpty()) return
            val descriptor = param.argOrNull(surface.descriptorIndex) ?: return
            val rpc = parseDescriptorName(descriptor) ?: return
            val steps = registrations[rpc]
            if (steps == null || steps.isEmpty()) return
            for (index in steps.indices) {
                steps[index].onCall()
            }
            val needsRequestHandle = steps.any {
                it.transformRawRequest != null ||
                    (it.requestCodec != null && (it.transformRequest != null || it.transformWithRequest != null))
            }
            val capturedRequests = if (steps.any { it.transformWithRequest != null }) arrayOfNulls<Any?>(steps.size) else null
            val rawRequest = param.argOrNull(surface.requestBytesIndex) as? ByteArray
            if (rawRequest != null && needsRequestHandle) {
                var currentBytes: ByteArray = rawRequest
                var parsedRequest: Any? = null
                var parsedRequestClass: Class<*>? = null
                for (index in steps.indices) {
                    val step = steps[index]
                    if (step.transformRawRequest != null) {
                        try {
                            val updatedBytes = step.transformRawRequest.invoke(currentBytes)
                            if (updatedBytes !== currentBytes) {
                                currentBytes = updatedBytes
                                parsedRequest = null
                                parsedRequestClass = null
                            }
                        } catch (failure: Throwable) {
                            runCatching { step.onRequestFailed(failure) }
                        }
                    }
                    val codec = step.requestCodec ?: continue
                    val transform = step.transformRequest
                    val capturesRequest = step.transformWithRequest != null
                    if (transform == null && !capturesRequest) continue
                    try {
                        val source = if (parsedRequest != null && parsedRequestClass === step.javaRequestClass) {
                            parsedRequest
                        } else {
                            codec.parse(currentBytes).also {
                                parsedRequest = it
                                parsedRequestClass = step.javaRequestClass
                            }
                        }
                        val effective = if (transform != null) {
                            val updated = transform(source)
                            if (updated !== source) {
                                currentBytes = codec.serialize(updated)
                                parsedRequest = updated
                                parsedRequestClass = step.javaRequestClass
                            }
                            updated
                        } else source
                        if (capturedRequests != null && capturesRequest) {
                            capturedRequests[index] = effective
                        }
                    } catch (failure: Throwable) {
                        runCatching { step.onRequestFailed(failure) }
                    }
                }
                if (currentBytes !== rawRequest) {
                    param.setArg(surface.requestBytesIndex, currentBytes)
                }
            }
            val delegate = param.argOrNull(surface.handlerIndex) ?: return
            if (!handlerType.isInstance(delegate)) return
            val loader = handlerType.classLoader ?: return
            val snapshot = steps.toTypedArray()
            runCatching {
                param.setArg(
                    surface.handlerIndex,
                    Proxy.newProxyInstance(
                        loader,
                        arrayOf(handlerType),
                        WireHandler(delegate, snapshot, capturedRequests)
                    )
                )
            }
        }
    }

    private class WireHandler(
        private val delegate: Any,
        private val steps: Array<Registration>,
        private val capturedRequests: Array<Any?>?
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> delegate.toString()
                }
            }
            var arguments = args ?: EMPTY_ARGS
            var receipts: MutableList<() -> Unit>? = null
            if (method.name == "onNext" && arguments.size == 1) {
                val originalBytes = arguments[0] as? ByteArray
                if (originalBytes != null) {
                    var currentBytes: ByteArray = originalBytes
                    var mirror: Any? = null
                    var mirrorClass: Class<*>? = null
                    for (index in steps.indices) {
                        val step = steps[index]
                        val rawTransform = step.transformRawReply
                        if (rawTransform != null) {
                            try {
                                val updatedBytes = rawTransform(currentBytes)
                                if (updatedBytes !== currentBytes) {
                                    currentBytes = updatedBytes
                                    mirror = null
                                    mirrorClass = null
                                }
                            } catch (failure: Throwable) {
                                runCatching { step.onReplyFailed(failure) }
                            }
                            continue
                        }
                        val codec = step.replyCodec ?: continue
                        val replyClass = step.javaReplyClass ?: continue
                        try {
                            val source = if (mirror != null && mirrorClass === replyClass) {
                                mirror
                            } else {
                                codec.parse(currentBytes).also {
                                    mirror = it
                                    mirrorClass = replyClass
                                }
                            }
                            val confirmed = step.confirmedTransform?.invoke(source)
                            val updated = confirmed?.message
                                ?: step.transformWithRequest?.invoke(capturedRequests?.getOrNull(index), source)
                                ?: step.transformJava?.invoke(source)
                                ?: source
                            if (updated !== source) {
                                val rewritten = codec.serialize(updated)
                                val nextMirror = if (confirmed != null &&
                                    (BuildConfig.DEBUG || step.readbackVerifySamplePending.compareAndSet(true, false))
                                ) {
                                    codec.parse(rewritten).also {
                                        check(it == updated) { "Wire reply readback changed the Java message" }
                                    }
                                } else {
                                    updated
                                }
                                currentBytes = rewritten
                                mirror = nextMirror
                                mirrorClass = replyClass
                                if (confirmed != null) {
                                    val pendingReceipts = receipts ?: ArrayList<() -> Unit>(2).also { receipts = it }
                                    pendingReceipts += confirmed.onReplyTransformed
                                }
                            }
                        } catch (failure: Throwable) {
                            runCatching { step.onReplyFailed(failure) }
                        }
                    }
                    if (currentBytes !== originalBytes) {
                        arguments = arrayOf(currentBytes)
                    } else {
                        receipts = null
                    }
                }
            }
            val returned = try {
                method.invoke(delegate, *arguments)
            } catch (invocation: InvocationTargetException) {
                throw invocation.targetException ?: invocation
            }
            receipts?.forEach { receipt -> HostThreadGuard.run("kmp.grpc.wire.receipt", receipt) }
            return returned
        }
    }

    internal fun fullMethodName(javaMossClassName: String, rpc: String): String? {
        val normalized = KotlinMossBridgeMembers.rpcName(rpc)
        val trimmed = javaMossClassName.removePrefix("com.bapis.")
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0) return null
        val pkg = trimmed.substring(0, dot)
            .split('.')
            .joinToString(".") { if (it == "interfaces") "interface" else it }
        val service = trimmed.substring(dot + 1).removeSuffix("Moss")
        if (pkg.isEmpty() || service.isEmpty() || normalized.isEmpty()) return null
        val method = normalized.replaceFirstChar { it.uppercaseChar() }
        return "$pkg.$service/$method"
    }

    internal fun parseDescriptorName(descriptor: Any): String? =
        KmpMethodDescriptorResolver.resolve(descriptor)

    fun install(
        environment: HookEnvironment,
        surface: KmpGrpcWireAdapter.WireSurface,
        javaMossClassName: String,
        rpc: String,
        javaReplyClass: Class<*>,
        what: String,
        logKey: String,
        javaRequestClass: Class<*>? = null,
        transformRequest: ((Any) -> Any)? = null,
        confirmedTransform: ((Any) -> ConfirmedReply)? = null,
        transformWithRequest: ((Any?, Any) -> Any)? = null,
        wireActive: AtomicBoolean? = null,
        transformJava: (Any) -> Any
    ): Boolean {
        val pendingReg = buildJavaRegistration(
            environment = environment,
            javaMossClassName = javaMossClassName,
            rpc = rpc,
            javaReplyClass = javaReplyClass,
            what = what,
            logKey = logKey,
            javaRequestClass = javaRequestClass,
            transformRequest = transformRequest,
            confirmedTransform = confirmedTransform,
            transformWithRequest = transformWithRequest,
            wireActive = wireActive,
            transformJava = transformJava
        ) ?: return false
        return bindRegistration(environment, surface, pendingReg)
    }

    fun installOrDefer(
        environment: HookEnvironment,
        surface: KmpGrpcWireAdapter.WireSurface?,
        javaMossClassName: String,
        rpc: String,
        javaReplyClass: Class<*>,
        what: String,
        logKey: String,
        javaRequestClass: Class<*>? = null,
        transformRequest: ((Any) -> Any)? = null,
        confirmedTransform: ((Any) -> ConfirmedReply)? = null,
        transformWithRequest: ((Any?, Any) -> Any)? = null,
        wireActive: AtomicBoolean? = null,
        transformJava: (Any) -> Any
    ): Boolean {
        val pendingReg = buildJavaRegistration(
            environment = environment,
            javaMossClassName = javaMossClassName,
            rpc = rpc,
            javaReplyClass = javaReplyClass,
            what = what,
            logKey = logKey,
            javaRequestClass = javaRequestClass,
            transformRequest = transformRequest,
            confirmedTransform = confirmedTransform,
            transformWithRequest = transformWithRequest,
            wireActive = wireActive,
            transformJava = transformJava
        ) ?: return false
        if (surface != null) {
            return bindRegistration(environment, surface, pendingReg)
        }
        queuePending(environment.registrar, pendingReg)
        return false
    }

    fun installRawBytes(
        environment: HookEnvironment,
        surface: KmpGrpcWireAdapter.WireSurface?,
        rpcFullName: String,
        what: String,
        logKey: String,
        transformRawRequest: ((ByteArray) -> ByteArray)? = null,
        wireActive: AtomicBoolean? = null,
        transformRawReply: (ByteArray) -> ByteArray
    ): Boolean {
        val pendingReg = buildRawRegistration(
            environment = environment,
            rpcFullName = rpcFullName,
            what = what,
            logKey = logKey,
            transformRawRequest = transformRawRequest,
            wireActive = wireActive,
            transformRawReply = transformRawReply
        ) ?: return false
        if (surface != null) {
            return bindRegistration(environment, surface, pendingReg)
        }
        queuePending(environment.registrar, pendingReg)
        return false
    }

    fun deferRawBytes(
        environment: HookEnvironment,
        rpcFullName: String,
        what: String,
        logKey: String,
        transformRawRequest: ((ByteArray) -> ByteArray)? = null,
        wireActive: AtomicBoolean? = null,
        transformRawReply: (ByteArray) -> ByteArray
    ) {
        val pendingReg = buildRawRegistration(
            environment = environment,
            rpcFullName = rpcFullName,
            what = what,
            logKey = logKey,
            transformRawRequest = transformRawRequest,
            wireActive = wireActive,
            transformRawReply = transformRawReply
        ) ?: return
        queuePending(environment.registrar, pendingReg)
    }

    fun bindDeferred(environment: HookEnvironment, surface: KmpGrpcWireAdapter.WireSurface): Int {
        val key = environment.registrar.rootRegistrar
        val entries = synchronized(dispatchersLock) {
            pending.remove(key)?.entries?.toList().orEmpty()
        }
        if (entries.isEmpty()) return 0
        var bound = 0
        for (entry in entries) {
            if (bindRegistration(environment, surface, entry, countDeduped = false)) {
                bound++
            }
        }
        return bound
    }

    private fun buildJavaRegistration(
        environment: HookEnvironment,
        javaMossClassName: String,
        rpc: String,
        javaReplyClass: Class<*>,
        what: String,
        logKey: String,
        javaRequestClass: Class<*>?,
        transformRequest: ((Any) -> Any)?,
        confirmedTransform: ((Any) -> ConfirmedReply)?,
        transformWithRequest: ((Any?, Any) -> Any)?,
        wireActive: AtomicBoolean?,
        transformJava: (Any) -> Any
    ): PendingRegistration? {
        fun skip(reason: String): Nothing? {
            environment.logInfo("${logKey}_wire_skip_$rpc", "[BIL] $what 线格式通道跳过 $rpc: $reason")
            return null
        }
        val rpcFullName = fullMethodName(javaMossClassName, rpc) ?: return skip("no-full-method-name")
        val replyCodec = JavaReplyCodec.resolve(javaReplyClass) ?: return skip("no-java-codec")
        val requestCodec = javaRequestClass?.takeIf { transformRequest != null || transformWithRequest != null }
            ?.let { JavaReplyCodec.resolve(it) ?: return skip("no-java-request-codec") }
        return PendingRegistration(
            rpc = rpc,
            rpcFullName = rpcFullName,
            registration = createRegistration(
                logInfo = environment.logInfo,
                logError = environment.logError,
                rpc = rpc,
                rpcFullName = rpcFullName,
                what = what,
                logKey = logKey,
                javaReplyClass = javaReplyClass,
                replyCodec = replyCodec,
                javaRequestClass = javaRequestClass,
                requestCodec = requestCodec,
                transformRequest = transformRequest,
                transformRawRequest = null,
                confirmedTransform = confirmedTransform,
                transformWithRequest = transformWithRequest,
                transformJava = transformJava,
                transformRawReply = null,
                wireActive = wireActive
            )
        )
    }

    private fun buildRawRegistration(
        environment: HookEnvironment,
        rpcFullName: String,
        what: String,
        logKey: String,
        transformRawRequest: ((ByteArray) -> ByteArray)?,
        wireActive: AtomicBoolean?,
        transformRawReply: (ByteArray) -> ByteArray
    ): PendingRegistration? {
        val cleanName = rpcFullName.trim()
        if (cleanName.isEmpty() || '/' !in cleanName) {
            environment.logInfo("${logKey}_wire_skip_raw", "[BIL] $what 线格式通道跳过: invalid-rpc-name")
            return null
        }
        val shortRpc = cleanName.substringAfterLast('/')
        return PendingRegistration(
            rpc = shortRpc,
            rpcFullName = cleanName,
            registration = createRegistration(
                logInfo = environment.logInfo,
                logError = environment.logError,
                rpc = shortRpc,
                rpcFullName = cleanName,
                what = what,
                logKey = logKey,
                javaReplyClass = null,
                replyCodec = null,
                javaRequestClass = null,
                requestCodec = null,
                transformRequest = null,
                transformRawRequest = transformRawRequest,
                confirmedTransform = null,
                transformWithRequest = null,
                transformJava = null,
                transformRawReply = transformRawReply,
                wireActive = wireActive
            )
        )
    }

    private fun createRegistration(
        logInfo: (String, String) -> Unit,
        logError: (String, String) -> Unit,
        rpc: String,
        rpcFullName: String,
        what: String,
        logKey: String,
        javaReplyClass: Class<*>?,
        replyCodec: JavaReplyCodec?,
        javaRequestClass: Class<*>?,
        requestCodec: JavaReplyCodec?,
        transformRequest: ((Any) -> Any)?,
        transformRawRequest: ((ByteArray) -> ByteArray)?,
        confirmedTransform: ((Any) -> ConfirmedReply)?,
        transformWithRequest: ((Any?, Any) -> Any)?,
        transformJava: ((Any) -> Any)?,
        transformRawReply: ((ByteArray) -> ByteArray)?,
        wireActive: AtomicBoolean?
    ): Registration {
        val called = AtomicBoolean(false)
        val failedLogged = AtomicBoolean(false)
        val requestFailedLogged = AtomicBoolean(false)
        return Registration(
            what = what,
            logKey = logKey,
            rpcFullName = rpcFullName,
            javaReplyClass = javaReplyClass,
            replyCodec = replyCodec,
            javaRequestClass = javaRequestClass,
            requestCodec = requestCodec,
            transformRequest = transformRequest,
            transformRawRequest = transformRawRequest,
            confirmedTransform = confirmedTransform,
            transformWithRequest = transformWithRequest,
            transformJava = transformJava,
            transformRawReply = transformRawReply,
            wireActive = wireActive,
            onCall = {
                if (called.compareAndSet(false, true)) {
                    logInfo("${logKey}_wire_call", "[BIL] $what 线格式通道请求已进入 $rpcFullName")
                }
            },
            onRequestFailed = { throwable ->
                if (requestFailedLogged.compareAndSet(false, true)) {
                    logError(
                        "${logKey}_wire_request_failed",
                        "[BIL] $what 线格式请求改写失败，已放行原请求($rpc): $throwable"
                    )
                }
            },
            onReplyFailed = { throwable ->
                if (failedLogged.compareAndSet(false, true)) {
                    logError(
                        "${logKey}_wire_failed",
                        "[BIL] $what 线格式处理失败，已放行原响应($rpc): $throwable"
                    )
                }
            }
        )
    }

    private fun queuePending(registrar: HookRegistrar, pendingReg: PendingRegistration) {
        val key = registrar.rootRegistrar
        synchronized(dispatchersLock) {
            val bucket = pending.getOrPut(key) { PendingBucket() }
            if (bucket.entries.none { it.registration.logKey == pendingReg.registration.logKey && it.rpcFullName == pendingReg.rpcFullName }) {
                bucket.entries += pendingReg
            }
        }
    }

    private fun bindRegistration(
        environment: HookEnvironment,
        surface: KmpGrpcWireAdapter.WireSurface,
        pendingReg: PendingRegistration,
        countDeduped: Boolean = true
    ): Boolean {
        val reg = pendingReg.registration
        val dispatcher = obtainDispatcher(environment, surface, pendingReg.rpc, reg.what, reg.logKey) ?: return false
        val list = dispatcher.registrations.getOrPut(pendingReg.rpcFullName) { CopyOnWriteArrayList() }
        synchronized(list) {
            val duplicate = list.any { it.logKey == reg.logKey && it.rpcFullName == reg.rpcFullName }
            if (duplicate) {
                reg.wireActive?.set(true)
                return countDeduped
            }
            list.add(reg)
        }
        reg.wireActive?.set(true)
        return true
    }

    private fun obtainDispatcher(
        environment: HookEnvironment,
        surface: KmpGrpcWireAdapter.WireSurface,
        rpc: String,
        what: String,
        logKey: String
    ): Dispatcher? = synchronized(dispatchersLock) {
        val key = environment.registrar.rootRegistrar
        val byEntry = dispatchers.getOrPut(key) { HashMap() }
        byEntry[surface.engineEntry]?.let { return it }
        val created = Dispatcher(surface)
        val registered = runCatching {
            val entry = surface.engineEntry
            key.exact(HOOK_ID, surface.engine, entry.name, *entry.parameterTypes) {
                faultOwner = FAULT_OWNER
                before { created.onEngineCall(this) }
            }
            true
        }.getOrElse { throwable ->
            environment.logError("${logKey}_wire_register_$rpc", "[BIL] $what 线格式通道注册失败($rpc): $throwable")
            false
        }
        if (!registered) return null
        byEntry[surface.engineEntry] = created
        created
    }
}
