package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把一条 Java moss RPC 的过滤"搬"到它的 Kotlin 孪生通道上（`…Moss` → `…KMoss`）的通用安装器。
 *
 * 宿主正把页面逐个迁到 KMP（`kntr.*`），迁过去的页面直接调 `K*Moss`、拿 Kotlin 序列化数据类，
 * 挂在 Java 响应 getter 上的过滤对它无声失效（2026-10-01 研判：9.14.0 的 KMP 评论页、新版搜索都是这样）。
 * 这里沿用 [KotlinMossReplyBridge] 的线格式往返：Kotlin 响应 → 同一 proto 的 Java 响应 → 调用方给的
 * [install] `transformJava` → 有改动才回写成新的 Kotlin 响应。Kotlin 版 suspend / 回调两种调用最终都汇入
 * 回调形态泛型入口（[KotlinMossBridgeMembers.callbackEntry]），挂一处即可。
 *
 * **原始读取**：同一进程里 Java getter 上通常还挂着本功能的 getter 过滤，`transformJava` 里读列表若经过它，
 * 读到的是已过滤结果，就判断不出"有没有删东西"。所以 `transformJava` 要在 [raw] 里读，getter 过滤开头用
 * [isRaw] 放行。
 *
 * 任何一步装不上返回 false（调用方照常走 Java 链路）；运行期任何异常都放行原响应。
 */
internal object KotlinMossChannel {

    private val rawDepth = ThreadLocal<IntArray>()

    /** 在"原始读取"作用域里执行：本线程上的 getter 过滤一律放行。可重入。 */
    fun <T> raw(block: () -> T): T {
        val depth = rawDepth.get() ?: IntArray(1).also(rawDepth::set)
        depth[0]++
        try {
            return block()
        } finally {
            depth[0]--
        }
    }

    /** getter 过滤开头调用：处于 [raw] 作用域时不过滤。 */
    fun isRaw(): Boolean = (rawDepth.get()?.get(0) ?: 0) > 0

    /** 解析 kotlinx.serialization 成员并跑一次真实往返自检；不可用返回 null。 */
    fun prepare(environment: HookEnvironment, loader: ClassLoader, what: String, logKey: String): KotlinMossBridgeMembers? {
        val members = KotlinMossBridgeMembers.resolve(loader)
        if (members == null) {
            environment.logInfo("${logKey}_skip", "[BIL] $what 新通道未安装: kotlinx.serialization 成员缺失")
            return null
        }
        return members.takeIf { KotlinMossBridgeSelfTest.allows(environment, loader, it, what) }
    }

    /**
     * 在 Kotlin 孪生 moss 的 [rpc] 回调入口上装过滤。
     *
     * @param javaMossClassName Java moss 全名；Kotlin 版按 `K` + 简单名推出。
     * @param javaReplyClass 与 Kotlin 响应同一 proto 的 Java 响应类。
     * @param transformJava 在 Java 响应上做过滤；返回同一个对象表示没改动。
     */
    fun install(
        environment: HookEnvironment,
        loader: ClassLoader,
        members: KotlinMossBridgeMembers,
        javaMossClassName: String,
        rpc: String,
        javaReplyClass: Class<*>,
        hookId: String,
        what: String,
        logKey: String,
        /** 可选：请求也走同样的往返（例如在请求里声明宿主自己的开关）；返回同一对象表示不改。 */
        javaRequestClass: Class<*>? = null,
        transformRequest: ((Any) -> Any)? = null,
        /** 评论管线显式启用；其他 RPC 保持原代理与变换行为。 */
        shareUnchangedReply: Boolean = false,
        /** 适配器缓存的 DEX 定位结果；传入前后都复核 ABI，不在这里做 DEX 查询。 */
        resolvedEntry: Method? = null,
        /** 可选确认回执：只在改写往返验真且宿主成功收到最终新响应后执行。 */
        confirmedTransform: ((Any) -> KotlinMossResponseHandlerProxy.ConfirmedReply)? = null,
        /** 当延迟 wire 通道激活后置 true，立即跳过 KotlinMoss 回调避免双重处理。 */
        supersededBy: AtomicBoolean? = null,
        transformJava: (Any) -> Any
    ): Boolean {
        fun skip(reason: String): Boolean {
            environment.logInfo("${logKey}_skip_$rpc", "[BIL] $what 新通道跳过 $rpc: $reason")
            return false
        }
        val kotlinName = KotlinMossBridgeMembers.kotlinMossClassName(javaMossClassName) ?: return skip("no-kotlin-name")
        if (resolvedEntry != null &&
            !KotlinMossBridgeMembers.isCallbackEntry(resolvedEntry) &&
            !KotlinMossBridgeMembers.isLegacyCallbackEntry(resolvedEntry)
        ) return skip("invalid-adapted-entry")
        val kotlinMoss = resolvedEntry?.declaringClass ?: KavaMemberLookup.classOrNull(loader, kotlinName)
            ?: return skip("no-kotlin-moss")
        val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(javaReplyClass) ?: return skip("no-java-codec")
        val entry = resolvedEntry
            ?: KotlinMossBridgeMembers.callbackEntry(kotlinMoss, rpc)
            ?: KotlinMossBridgeMembers.legacyCallbackEntry(kotlinMoss, rpc)
            ?: return skip("no-callback-entry")
        val hasProtoBufParam = entry.parameterCount >= 5
        val fallbackProtoBuf = if (hasProtoBufParam) null else members.defaultProtoBuf()
            ?: return skip("no-default-protobuf")
        val handlerClass = entry.parameterTypes[3]
        val requestCodec = javaRequestClass?.takeIf { transformRequest != null }
            ?.let { KotlinMossBridgeMembers.JavaReplyCodec.resolve(it) ?: return skip("no-java-request-codec") }
        val called = AtomicBoolean(false)
        val failedLogged = AtomicBoolean(false)
        val requestFailedLogged = AtomicBoolean(false)
        return runCatching {
            environment.registrar.exact(hookId, entry.declaringClass, entry.name, *entry.parameterTypes) {
                before {
                    if (supersededBy?.get() == true) return@before
                    if (called.compareAndSet(false, true)) {
                        environment.logInfo("${logKey}_call", "[BIL] $what 新通道请求已进入 ${kotlinMoss.simpleName}#${entry.name}")
                    }
                    val protoBuf = if (hasProtoBufParam) args.getOrNull(4) else fallbackProtoBuf
                    if (requestCodec != null && transformRequest != null) {
                        val request = args.getOrNull(0)
                        // 请求的生成 `$serializer` 同时是序列化与反序列化策略，可以原样往返。
                        val requestBridge = members.bridgeFor(protoBuf, args.getOrNull(1), requestCodec)
                        if (request != null && requestBridge != null) {
                            runCatching { requestBridge.transform(request, transformRequest) }
                                .onSuccess { updated -> if (updated !== request) args[0] = updated }
                                .onFailure { throwable ->
                                    if (requestFailedLogged.compareAndSet(false, true)) {
                                        environment.logError(
                                            "${logKey}_request_failed",
                                            "[BIL] $what 新通道请求改写失败，已放行原请求(${entry.name}): $throwable"
                                        )
                                    }
                                }
                        }
                    }
                    val delegate = args.getOrNull(3) ?: return@before
                    val serializer = args.getOrNull(2) ?: return@before
                    val bridge = members.bridgeFor(protoBuf, serializer, codec) ?: return@before
                    val failed: (Throwable) -> Unit = { throwable ->
                        if (failedLogged.compareAndSet(false, true)) {
                            environment.logError("${logKey}_failed",
                                "[BIL] $what 新通道过滤失败，已放行原响应(${entry.name}): $throwable")
                        }
                    }
                    val proxy = if (shareUnchangedReply || confirmedTransform != null) {
                        val safeProtoBuf = protoBuf ?: return@before
                        val key = KotlinMossResponseHandlerProxy.Key(entry, javaReplyClass, safeProtoBuf, serializer)
                        KotlinMossResponseHandlerProxy.wrap(handlerClass, delegate, key, bridge, failed, transformJava, confirmedTransform)
                    } else MossResponseHandlerProxy.wrapTransform(handlerClass, delegate) { reply ->
                        runCatching { bridge.transform(reply, transformJava) }.getOrElse { throwable ->
                            failed(throwable)
                            reply
                        }
                    } ?: return@before
                    args[3] = proxy
                }
            }
            true
        }.getOrElse { throwable ->
            environment.logError("${logKey}_register_$rpc", "[BIL] $what 新通道注册失败($rpc): $throwable")
            false
        }
    }
}
