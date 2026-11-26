package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KmpGrpcWireChannel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossChannel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossBridgeMembers
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MossResponseHandlerProxy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic

/** 每个响应闭包绑定其请求，不能用“最近一次 CID”关联并发 RPC。网络和模型从不在 Hook 内执行。 */
internal object InsightRpc {
    fun install(
        env: HookEnvironment,
        mossName: String,
        rpc: String,
        requestClass: Class<*>,
        replyClass: Class<*>,
        transformRequest: ((Any) -> Any)? = null,
        kmpGrpcWireEngine: VersionAdapter.HookPoint? = null,
        transform: (Any, Any) -> Any
    ): Int =
        register(
            env, mossName, rpc, requestClass, replyClass,
            "insight.rpc", "AI Insight", true, transformRequest, kmpGrpcWireEngine
        ) { req, reply ->
            transform(requireNotNull(req), reply)
        }

    /** 只观察响应，不对 Kotlin 请求做字节往返。 */
    fun observe(
        env: HookEnvironment,
        mossName: String,
        rpc: String,
        requestClass: Class<*>,
        replyClass: Class<*>,
        hookIdPrefix: String,
        what: String,
        kmpGrpcWireEngine: VersionAdapter.HookPoint? = null,
        onReply: (Any) -> Unit
    ): Int =
        register(
            env, mossName, rpc, requestClass, replyClass,
            hookIdPrefix, what, false, null, kmpGrpcWireEngine
        ) { _, reply ->
            if (replyClass.isInstance(reply)) onReply(reply)
            reply
        }

    private fun register(
        env: HookEnvironment,
        mossName: String,
        rpc: String,
        requestClass: Class<*>,
        replyClass: Class<*>,
        hookIdPrefix: String,
        what: String,
        needsRequest: Boolean,
        transformRequest: ((Any) -> Any)?,
        kmpGrpcWireEngine: VersionAdapter.HookPoint?,
        transform: (Any?, Any) -> Any
    ): Int {
        val loader = env.classLoader ?: return 0
        var count = 0
        val handler = Lookup.classOrNull(loader, "com.bilibili.lib.moss.api.MossResponseHandler")
        Lookup.classOrNull(loader, mossName)?.let { moss ->
            Lookup.declaredMethods(moss, true) { method ->
                !method.isStatic && KotlinMossBridgeMembers.rpcName(method.name) == rpc &&
                    (method.parameterTypes.toList() == listOf(requestClass) ||
                        (handler != null && method.parameterTypes.toList() == listOf(requestClass, handler)))
            }.forEach { method ->
                env.registrar.exact("$hookIdPrefix.$mossName.${method.name}", moss, method.name, *method.parameterTypes) {
                    before {
                        val original = argOrNull(0) ?: return@before
                        val request = transformRequest?.invoke(original) ?: original
                        if (request !== original) args[0] = request
                        if (method.parameterCount == 2 && handler != null) {
                            val delegate = argOrNull(1) ?: return@before
                            args[1] = MossResponseHandlerProxy.wrapTransform(handler, delegate) { reply -> transform(request, reply) } ?: delegate
                        }
                    }
                    if (method.parameterCount == 1) after {
                        if (!hasThrowable) { val request = argOrNull(0); val reply = result
                            if (request != null && reply != null && replyClass.isInstance(reply)) result = transform(request, reply) }
                    }
                }
                count++
            }
        }
        // 通用 KotlinMossChannel 的变换器只接收响应；这里需要请求 CID，复用它的 codec/代理边界并按单次调用捕获。
        val wireSurface = KmpGrpcWireAdapter.resolve(loader, kmpGrpcWireEngine)
        val wireActive = java.util.concurrent.atomic.AtomicBoolean(false)
        val requestClassOrNull = if (needsRequest || transformRequest != null) requestClass else null
        val transformWithRequestOrNull: ((Any?, Any) -> Any)? = if (needsRequest) { req, reply ->
            if (req != null) transform(req, reply) else reply
        } else null
        val wireInstalled = if (wireSurface != null) {
            KmpGrpcWireChannel.install(
                environment = env,
                surface = wireSurface,
                javaMossClassName = mossName,
                rpc = rpc,
                javaReplyClass = replyClass,
                what = what,
                logKey = hookIdPrefix,
                javaRequestClass = requestClassOrNull,
                transformRequest = transformRequest,
                transformWithRequest = transformWithRequestOrNull,
                wireActive = wireActive,
                transformJava = { reply -> if (needsRequest) reply else transform(null, reply) }
            )
        } else {
            KmpGrpcWireChannel.installOrDefer(
                environment = env,
                surface = null,
                javaMossClassName = mossName,
                rpc = rpc,
                javaReplyClass = replyClass,
                what = what,
                logKey = hookIdPrefix,
                javaRequestClass = requestClassOrNull,
                transformRequest = transformRequest,
                transformWithRequest = transformWithRequestOrNull,
                wireActive = wireActive,
                transformJava = { reply -> if (needsRequest) reply else transform(null, reply) }
            )
        }
        if (wireInstalled) {
            return count + 1
        }
        val kotlinName = KotlinMossBridgeMembers.kotlinMossClassName(mossName) ?: return count
        val kotlin = Lookup.classOrNull(loader, kotlinName) ?: return count
        val entry = KotlinMossBridgeMembers.callbackEntry(kotlin, rpc)
            ?: KotlinMossBridgeMembers.legacyCallbackEntry(kotlin, rpc) ?: return count
        val members = KotlinMossChannel.prepare(env, loader, what, hookIdPrefix) ?: return count
        // 旧版 4 参入口不传 ProtoBuf，宿主 moss 服务使用 ProtoBuf.Default；取不到就不装，Java 通道照常。
        val legacy = entry.parameterCount == 4
        val defaultFormat = if (legacy) members.defaultProtoBuf() ?: return count else null
        val requestCodec = if (needsRequest) KotlinMossBridgeMembers.JavaReplyCodec.resolve(requestClass) ?: return count else null
        val replyCodec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(replyClass) ?: return count
        val bridgeErrorLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        env.registrar.exact("$hookIdPrefix.$kotlinName.$rpc", kotlin, entry.name, *entry.parameterTypes) { before {
            if (wireActive.get()) return@before
            val format = if (legacy) defaultFormat else argOrNull(4)
            val replyBridge = members.bridgeFor(format, argOrNull(2), replyCodec) ?: return@before
            val updated = if (requestCodec != null) {
                val original = argOrNull(0) ?: return@before
                val transformedResult = runCatching {
                    val requestBridge = members.bridgeFor(format, argOrNull(1), requestCodec) ?: return@before
                    val request = requestBridge.read(original)
                    val transformed = transformRequest?.invoke(request) ?: request
                    if (transformed !== request) args[0] = requestBridge.write(transformed)
                    transformed
                }.getOrElse { failure ->
                    if (bridgeErrorLogged.compareAndSet(false, true)) {
                        env.logError("${hookIdPrefix}_kmoss_req", "[BIL] $what Kotlin 请求改写失败，保留原请求: $failure")
                    }
                    return@before
                }
                transformedResult
            } else null
            val delegate = argOrNull(3) ?: return@before
            args[3] = MossResponseHandlerProxy.wrapTransform(entry.parameterTypes[3], delegate) { response ->
                runCatching {
                    replyBridge.transform(response) { reply -> transform(updated, reply) }
                }.getOrElse { failure ->
                    if (bridgeErrorLogged.compareAndSet(false, true)) {
                        env.logError("${hookIdPrefix}_kmoss_resp", "[BIL] $what Kotlin 响应转换失败，保留原响应: $failure")
                    }
                    response
                }
            } ?: delegate
        } }
        return count + 1
    }
}
