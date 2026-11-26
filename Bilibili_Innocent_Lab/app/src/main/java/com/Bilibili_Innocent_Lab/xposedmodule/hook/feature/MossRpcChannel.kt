package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一挂载 Java Moss（同步 / 异步）、KmpGrpcWireChannel 与 KotlinMoss 四通道 RPC 拦截。
 *
 * 按 RPC 粒度共享同一枚 [wireActive] 原子标记：一旦 KMP wire 链已直接接管或运行期收到响应激活，
 * 同一 RPC 的 KotlinMoss 回调入口立即通过 `supersededBy` 短路，避免双通道重复处理同一响应。
 */
internal object MossRpcChannel {

    internal data class KmpMountResult(
        val kmpWireInstalled: Boolean,
        val kotlinMossInstalled: Boolean,
        val wireActive: AtomicBoolean
    ) {
        val installedAny: Boolean get() = kmpWireInstalled || kotlinMossInstalled
    }

    internal data class AllMountResult(
        val javaSyncInstalled: Boolean,
        val javaAsyncInstalled: Boolean,
        val kmpWireInstalled: Boolean,
        val kotlinMossInstalled: Boolean,
        val wireActive: AtomicBoolean
    ) {
        val javaRouteCount: Int
            get() = (if (javaSyncInstalled) 1 else 0) + (if (javaAsyncInstalled) 1 else 0)

        val kotlinChannelInstalled: Boolean
            get() = kmpWireInstalled || kotlinMossInstalled

        val totalInstalledCount: Int
            get() = javaRouteCount + if (kotlinChannelInstalled) 1 else 0
    }

    fun resolveWireSurface(
        loader: ClassLoader,
        engine: VersionAdapter.HookPoint?
    ): KmpGrpcWireAdapter.WireSurface? = KmpGrpcWireAdapter.resolve(loader, engine)

    fun prepareKotlinBridge(
        environment: HookEnvironment,
        loader: ClassLoader,
        what: String,
        logKey: String
    ): KotlinMossBridgeMembers? = KotlinMossChannel.prepare(environment, loader, what, logKey)

    fun mountJavaSync(
        environment: HookEnvironment,
        mossClass: Class<*>?,
        syncMethod: String?,
        requestClass: Class<*>?,
        hookId: String,
        logKey: String,
        what: String,
        transform: (request: Any?, reply: Any) -> Any
    ): Boolean {
        if (mossClass == null || syncMethod.isNullOrEmpty() || requestClass == null) return false
        return runCatching {
            environment.registrar.exact(hookId, mossClass, syncMethod, requestClass) {
                after {
                    if (hasThrowable) return@after
                    val original = result ?: return@after
                    val updated = transform(argOrNull(0), original)
                    if (updated !== original) result = updated
                }
            }
            true
        }.onFailure { throwable ->
            environment.logError(logKey, "[BIL] $what 同步响应 Hook 注册失败: $throwable")
        }.getOrDefault(false)
    }

    fun mountJavaAsync(
        environment: HookEnvironment,
        mossClass: Class<*>?,
        asyncMethod: String,
        requestClass: Class<*>?,
        handlerClass: Class<*>?,
        hookId: String,
        logKey: String,
        what: String,
        handlerArgIndex: Int = 1,
        requestArgIndex: Int = 0,
        transform: (request: Any?, reply: Any) -> Any
    ): Boolean {
        if (mossClass == null || requestClass == null || handlerClass == null) return false
        return runCatching {
            val parameterTypes = if (requestArgIndex < handlerArgIndex) {
                arrayOf(requestClass, handlerClass)
            } else {
                arrayOf(handlerClass, requestClass)
            }
            environment.registrar.exact(hookId, mossClass, asyncMethod, *parameterTypes) {
                before {
                    val original = argOrNull(handlerArgIndex) ?: return@before
                    val request = argOrNull(requestArgIndex)
                    val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, original) { reply ->
                        transform(request, reply)
                    } ?: return@before
                    args[handlerArgIndex] = proxy
                }
            }
            true
        }.onFailure { throwable ->
            environment.logError(logKey, "[BIL] $what 异步响应 Hook 注册失败: $throwable")
        }.getOrDefault(false)
    }

    fun mountKmpWireOrKotlin(
        environment: HookEnvironment,
        loader: ClassLoader,
        javaMossClassName: String,
        rpc: String,
        javaReplyClass: Class<*>,
        what: String,
        logKey: String,
        kotlinHookId: String,
        wireSurface: KmpGrpcWireAdapter.WireSurface? = null,
        deferWireIfUnavailable: Boolean = true,
        wireActive: AtomicBoolean = AtomicBoolean(false),
        kotlinBridge: KotlinMossBridgeMembers? = null,
        prepareKotlinIfMissing: Boolean = true,
        allowKotlinFallback: Boolean = true,
        javaRequestClass: Class<*>? = null,
        transformRequest: ((Any) -> Any)? = null,
        shareUnchangedReply: Boolean = false,
        resolvedKotlinEntry: Method? = null,
        confirmedTransform: ((Any) -> KotlinMossResponseHandlerProxy.ConfirmedReply)? = null,
        transformWithRequest: ((Any?, Any) -> Any)? = null,
        transformJava: (Any) -> Any
    ): KmpMountResult {
        if (wireActive.get()) {
            return KmpMountResult(
                kmpWireInstalled = true,
                kotlinMossInstalled = false,
                wireActive = wireActive
            )
        }
        val wireInstalled = when {
            wireSurface != null -> KmpGrpcWireChannel.install(
                environment = environment,
                surface = wireSurface,
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
            )
            deferWireIfUnavailable -> KmpGrpcWireChannel.installOrDefer(
                environment = environment,
                surface = null,
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
            )
            else -> false
        }
        if (wireInstalled) {
            return KmpMountResult(
                kmpWireInstalled = true,
                kotlinMossInstalled = false,
                wireActive = wireActive
            )
        }
        val kotlinInstalled = if (allowKotlinFallback) {
            val bridge = kotlinBridge
                ?: if (prepareKotlinIfMissing) prepareKotlinBridge(environment, loader, what, logKey) else null
            if (bridge != null) {
                KotlinMossChannel.install(
                    environment = environment,
                    loader = loader,
                    members = bridge,
                    javaMossClassName = javaMossClassName,
                    rpc = rpc,
                    javaReplyClass = javaReplyClass,
                    hookId = kotlinHookId,
                    what = what,
                    logKey = logKey,
                    javaRequestClass = javaRequestClass,
                    transformRequest = transformRequest,
                    shareUnchangedReply = shareUnchangedReply,
                    resolvedEntry = resolvedKotlinEntry,
                    confirmedTransform = confirmedTransform,
                    supersededBy = wireActive,
                    transformJava = transformJava
                )
            } else {
                false
            }
        } else {
            false
        }
        return KmpMountResult(
            kmpWireInstalled = false,
            kotlinMossInstalled = kotlinInstalled,
            wireActive = wireActive
        )
    }

    fun mountAll(
        environment: HookEnvironment,
        loader: ClassLoader,
        hookIdPrefix: String,
        javaMossClassName: String,
        rpc: String,
        javaReplyClass: Class<*>,
        what: String,
        logKey: String,
        syncMethod: String? = null,
        javaMossClass: Class<*>? = KavaMemberLookup.classOrNull(loader, javaMossClassName),
        javaRequestClass: Class<*>? = null,
        handlerClass: Class<*>? = if (javaMossClass != null && javaRequestClass != null) {
            KavaMemberLookup.classOrNull(loader, DEFAULT_MOSS_HANDLER_CLASS)
        } else {
            null
        },
        wireSurface: KmpGrpcWireAdapter.WireSurface? = null,
        deferWireIfUnavailable: Boolean = true,
        wireActive: AtomicBoolean = AtomicBoolean(false),
        kotlinBridge: KotlinMossBridgeMembers? = null,
        prepareKotlinIfMissing: Boolean = true,
        allowKotlinFallback: Boolean = true,
        transformRequest: ((Any) -> Any)? = null,
        shareUnchangedReply: Boolean = false,
        resolvedKotlinEntry: Method? = null,
        confirmedTransform: ((Any) -> KotlinMossResponseHandlerProxy.ConfirmedReply)? = null,
        transformWithRequest: ((Any?, Any) -> Any)? = null,
        transformJava: (request: Any?, reply: Any) -> Any
    ): AllMountResult {
        val syncOk = mountJavaSync(
            environment = environment,
            mossClass = javaMossClass,
            syncMethod = syncMethod,
            requestClass = javaRequestClass,
            hookId = "$hookIdPrefix.sync.${syncMethod.orEmpty()}",
            logKey = "${logKey}_sync",
            what = what,
            transform = transformJava
        )
        val asyncOk = mountJavaAsync(
            environment = environment,
            mossClass = javaMossClass,
            asyncMethod = rpc,
            requestClass = javaRequestClass,
            handlerClass = handlerClass,
            hookId = "$hookIdPrefix.async.$rpc",
            logKey = "${logKey}_async",
            what = what,
            transform = transformJava
        )
        val kmpOutcome = mountKmpWireOrKotlin(
            environment = environment,
            loader = loader,
            javaMossClassName = javaMossClassName,
            rpc = rpc,
            javaReplyClass = javaReplyClass,
            what = what,
            logKey = logKey,
            kotlinHookId = "$hookIdPrefix.kmoss.$rpc",
            wireSurface = wireSurface,
            deferWireIfUnavailable = deferWireIfUnavailable,
            wireActive = wireActive,
            kotlinBridge = kotlinBridge,
            prepareKotlinIfMissing = prepareKotlinIfMissing,
            allowKotlinFallback = allowKotlinFallback,
            javaRequestClass = javaRequestClass,
            transformRequest = transformRequest,
            shareUnchangedReply = shareUnchangedReply,
            resolvedKotlinEntry = resolvedKotlinEntry,
            confirmedTransform = confirmedTransform,
            transformWithRequest = transformWithRequest,
            transformJava = { reply -> transformJava(null, reply) }
        )
        return AllMountResult(
            javaSyncInstalled = syncOk,
            javaAsyncInstalled = asyncOk,
            kmpWireInstalled = kmpOutcome.kmpWireInstalled,
            kotlinMossInstalled = kmpOutcome.kotlinMossInstalled,
            wireActive = wireActive
        )
    }

    private const val DEFAULT_MOSS_HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
}
