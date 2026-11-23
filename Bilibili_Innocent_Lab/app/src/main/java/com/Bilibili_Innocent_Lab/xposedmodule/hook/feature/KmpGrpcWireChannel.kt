package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossBridgeMembers.JavaReplyCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossResponseHandlerProxy.ConfirmedReply
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
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
    private const val MAX_CACHED_DESCRIPTORS = 4096
    private val DESCRIPTOR = Regex("""packageName=([^,]+), serviceName=([^,]+), methodName=([^,]+),""")
    private val EMPTY_ARGS = emptyArray<Any?>()

    private val dispatchersLock = Any()
    private val dispatchers = WeakHashMap<HookRegistrar, MutableMap<Method, Dispatcher>>()
    private val descriptorNames = IdentityHashMap<Any, String?>()

    private class Registration(
        val what: String,
        val logKey: String,
        val javaReplyClass: Class<*>,
        val replyCodec: JavaReplyCodec,
        val javaRequestClass: Class<*>?,
        val requestCodec: JavaReplyCodec?,
        val transformRequest: ((Any) -> Any)?,
        val confirmedTransform: ((Any) -> ConfirmedReply)?,
        val transformJava: (Any) -> Any,
        val onCall: () -> Unit,
        val onRequestFailed: (Throwable) -> Unit,
        val onReplyFailed: (Throwable) -> Unit
    )

    private class Dispatcher(val surface: KmpGrpcWireAdapter.WireSurface) {
        val registrations = ConcurrentHashMap<String, CopyOnWriteArrayList<Registration>>()
        private val handlerType = surface.engineEntry.parameterTypes[surface.handlerIndex]

        fun onEngineCall(args: Array<Any?>) {
            val descriptor = args.getOrNull(surface.descriptorIndex) ?: return
            val rpc = parseDescriptorName(descriptor) ?: return
            val steps = registrations[rpc]
            if (steps == null || steps.isEmpty()) return
            for (index in steps.indices) {
                steps[index].onCall()
            }
            val rawRequest = args.getOrNull(surface.requestBytesIndex) as? ByteArray
            if (rawRequest != null && steps.any { it.requestCodec != null && it.transformRequest != null }) {
                var currentBytes: ByteArray = rawRequest
                var parsedRequest: Any? = null
                var parsedRequestClass: Class<*>? = null
                for (index in steps.indices) {
                    val step = steps[index]
                    val codec = step.requestCodec ?: continue
                    val transform = step.transformRequest ?: continue
                    try {
                        val source = if (parsedRequest != null && parsedRequestClass === step.javaRequestClass) {
                            parsedRequest
                        } else {
                            codec.parse(currentBytes).also {
                                parsedRequest = it
                                parsedRequestClass = step.javaRequestClass
                            }
                        }
                        val updated = transform(source)
                        if (updated !== source) {
                            currentBytes = codec.serialize(updated)
                            parsedRequest = updated
                            parsedRequestClass = step.javaRequestClass
                        }
                    } catch (failure: Throwable) {
                        runCatching { step.onRequestFailed(failure) }
                    }
                }
                if (currentBytes !== rawRequest) {
                    args[surface.requestBytesIndex] = currentBytes
                }
            }
            val delegate = args.getOrNull(surface.handlerIndex) ?: return
            if (!handlerType.isInstance(delegate)) return
            val loader = handlerType.classLoader ?: return
            val snapshot = steps.toTypedArray()
            runCatching {
                args[surface.handlerIndex] = Proxy.newProxyInstance(
                    loader,
                    arrayOf(handlerType),
                    WireHandler(delegate, snapshot)
                )
            }
        }
    }

    private class WireHandler(
        private val delegate: Any,
        private val steps: Array<Registration>
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
                    for (step in steps) {
                        try {
                            val source = if (mirror != null && mirrorClass === step.javaReplyClass) {
                                mirror
                            } else {
                                step.replyCodec.parse(currentBytes).also {
                                    mirror = it
                                    mirrorClass = step.javaReplyClass
                                }
                            }
                            val confirmed = step.confirmedTransform?.invoke(source)
                            val updated = confirmed?.message ?: step.transformJava(source)
                            if (updated !== source) {
                                val rewritten = step.replyCodec.serialize(updated)
                                val readback = if (confirmed != null) {
                                    step.replyCodec.parse(rewritten).also {
                                        check(it == updated) { "Wire reply readback changed the Java message" }
                                    }
                                } else null
                                currentBytes = rewritten
                                mirror = readback
                                mirrorClass = if (readback != null) step.javaReplyClass else null
                                if (confirmed != null) {
                                    val pending = receipts ?: ArrayList<() -> Unit>(2).also { receipts = it }
                                    pending += confirmed.onReplyTransformed
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
        val service = trimmed.substring(dot + 1).removeSuffix("Moss")
        if (pkg.isEmpty() || service.isEmpty() || normalized.isEmpty()) return null
        val method = normalized.replaceFirstChar { it.uppercaseChar() }
        return "$pkg.$service/$method"
    }

    internal fun parseDescriptorName(descriptor: Any): String? = synchronized(descriptorNames) {
        if (descriptorNames.containsKey(descriptor)) return descriptorNames[descriptor]
        val match = DESCRIPTOR.find(descriptor.toString())
        val name = match?.let { "${it.groupValues[1]}.${it.groupValues[2]}/${it.groupValues[3]}" }
        if (descriptorNames.size >= MAX_CACHED_DESCRIPTORS) descriptorNames.clear()
        descriptorNames[descriptor] = name
        name
    }

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
        transformJava: (Any) -> Any
    ): Boolean {
        fun skip(reason: String): Boolean {
            environment.logInfo("${logKey}_wire_skip_$rpc", "[BIL] $what 线格式通道跳过 $rpc: $reason")
            return false
        }
        val rpcFullName = fullMethodName(javaMossClassName, rpc) ?: return skip("no-full-method-name")
        val replyCodec = JavaReplyCodec.resolve(javaReplyClass) ?: return skip("no-java-codec")
        val requestCodec = javaRequestClass?.takeIf { transformRequest != null }
            ?.let { JavaReplyCodec.resolve(it) ?: return skip("no-java-request-codec") }
        val dispatcher = obtainDispatcher(environment, surface, rpc, what, logKey) ?: return false
        val called = AtomicBoolean(false)
        val failedLogged = AtomicBoolean(false)
        val requestFailedLogged = AtomicBoolean(false)
        val registration = Registration(
            what = what,
            logKey = logKey,
            javaReplyClass = javaReplyClass,
            replyCodec = replyCodec,
            javaRequestClass = javaRequestClass,
            requestCodec = requestCodec,
            transformRequest = transformRequest,
            confirmedTransform = confirmedTransform,
            transformJava = transformJava,
            onCall = {
                if (called.compareAndSet(false, true)) {
                    environment.logInfo("${logKey}_wire_call", "[BIL] $what 线格式通道请求已进入 $rpcFullName")
                }
            },
            onRequestFailed = { throwable ->
                if (requestFailedLogged.compareAndSet(false, true)) {
                    environment.logError(
                        "${logKey}_wire_request_failed",
                        "[BIL] $what 线格式请求改写失败，已放行原请求($rpc): $throwable"
                    )
                }
            },
            onReplyFailed = { throwable ->
                if (failedLogged.compareAndSet(false, true)) {
                    environment.logError(
                        "${logKey}_wire_failed",
                        "[BIL] $what 线格式处理失败，已放行原响应($rpc): $throwable"
                    )
                }
            }
        )
        dispatcher.registrations.getOrPut(rpcFullName) { CopyOnWriteArrayList() }.add(registration)
        return true
    }

    private fun obtainDispatcher(
        environment: HookEnvironment,
        surface: KmpGrpcWireAdapter.WireSurface,
        rpc: String,
        what: String,
        logKey: String
    ): Dispatcher? = synchronized(dispatchersLock) {
        val byEntry = dispatchers.getOrPut(environment.registrar) { HashMap() }
        byEntry[surface.engineEntry]?.let { return it }
        val created = Dispatcher(surface)
        val registered = runCatching {
            val entry = surface.engineEntry
            environment.registrar.exact(HOOK_ID, surface.engine, entry.name, *entry.parameterTypes) {
                before { created.onEngineCall(args) }
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
