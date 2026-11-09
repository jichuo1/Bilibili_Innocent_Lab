package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CompatibilityReceiptService
import com.Bilibili_Innocent_Lab.xposedmodule.receiver.RoamingOpenReceiver
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.os.Bundle
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 两阶段广播授权的回执归属器。
 *
 * PREPARE/CONFIRM 共用 nonce，但每个阶段必须拥有独立 action/Future；空回执只代表
 * 当前运输没有给出结果，不能抢先结束等待并吞掉其他回退运输的有效回执。
 */
internal class AdmissionResponseCoordinator {
    data class Awaiter(
        val action: String,
        val response: CompletableFuture<Bundle?>
    )

    private val active = AtomicReference<Awaiter?>(null)

    fun begin(action: String): Awaiter = Awaiter(action, CompletableFuture<Bundle?>()).also {
        active.set(it)
    }

    fun complete(action: String?, response: Bundle?): Boolean {
        if (action == null || response == null) return false
        val awaiter = active.get() ?: return false
        if (awaiter.action != action) return false
        return awaiter.response.complete(response)
    }

    /** 回调线程上的 Bundle 解码失败只能视为本次运输无结果，不能逃逸杀宿主。 */
    fun completeSafely(action: String?, response: () -> Bundle?): Boolean =
        runCatching { complete(action, response()) }.getOrDefault(false)

    /** 系统确认有序投递无人处理；结束本路等待，不产生许可或恢复缓存授权。 */
    fun completeUnhandled(action: String, canFallback: Boolean): Boolean {
        if (!canFallback) return false
        val awaiter = active.get() ?: return false
        if (awaiter.action != action) return false
        return awaiter.response.complete(null)
    }

    fun clear(awaiter: Awaiter) {
        active.compareAndSet(awaiter, null)
    }
}

/** 启动窗口内完成新鲜许可；工作线程只读取，不在迟到回调中安装 Hook。 */
internal object HostAdmissionClient {
    data class Grant(val snapshot: RemoteHookConfigSnapshot, val source: String, val identity: PublicationIdentity? = null)
    data class Result(
        val grant: Grant? = null,
        val reason: String = HostAdmissionRouteClassifier.REASON_UNAVAILABLE,
        val diagnostics: List<HostAdmissionDiagnostic> = emptyList(),
        val disabledRevision: Long = 0L,
        val selectedRoute: String = "unknown",
        val routeEvidence: List<com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRouteEvidence> = emptyList()
    )
    private data class Routed(val route: String, val result: Result)
    private val boundWorker = HostReceiptWire.executor("bil-bound-admission")
    private val worker = HostReceiptWire.executor("bil-host-admission")
    private val broadcastWorker = HostReceiptWire.executor("bil-broadcast-admission")
    private val localWorker = HostReceiptWire.executor("bil-local-admission")
    private val fallbackState = HostAdmissionFallbackState()

    private const val TAG = "BilibiliInnocentLab"

    private fun log(priority: Int, message: String) { runCatching { Log.println(priority, TAG, message) } }

    private fun transportFailure(route: String, status: String, reason: String = HostAdmissionRouteClassifier.REASON_UNAVAILABLE) =
        Result(reason = reason, diagnostics = listOf(HostAdmissionDiagnostic(route, "transport", status)))

    fun admit(context: Context, normal: RemoteHookConfigSnapshot?, normalFailure: String?, nPatchRuntime: Boolean = false,
              lspatchRuntime: Boolean = false): Result {
        val runtime = when {
            nPatchRuntime -> com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HookRuntimeMode.NPATCH_LEGACY
            lspatchRuntime -> com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HookRuntimeMode.LSPATCH
            else -> com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HookRuntimeMode.STANDARD_XPOSED
        }
        // 在任何传输及 Remote Preferences 回退之前阻断，所有宿主进程使用同一门控。
        if (com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.EmbeddedModePolicy.isHostBlocked(context, runtime)) {
            return Result(reason = "embedded_mode_unsupported")
        }
        val observed = ArrayList<com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRouteEvidence>(4)
        return admitObserved(context, normal, normalFailure, nPatchRuntime, lspatchRuntime, observed).let {
            it.copy(routeEvidence = observed.toList())
        }
    }

    private fun admitObserved(context: Context, normal: RemoteHookConfigSnapshot?, normalFailure: String?, nPatchRuntime: Boolean,
        lspatchRuntime: Boolean,
        observed: MutableList<com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRouteEvidence>): Result {
        val app = context.applicationContext ?: context
        val deadline = SystemClock.elapsedRealtime() + HostAdmissionContract.BOOTSTRAP_TIMEOUT_MS
        val responses = LinkedBlockingQueue<Routed>(4)
        fun launch(bound: Boolean) = runCatching {
            (if (bound) boundWorker else worker).submit {
                val route = if (bound) HostAdmissionRoutePlan.BINDER else HostAdmissionRoutePlan.PROVIDER
                val result = try { exchange(app, normal, normalFailure, deadline, bound = bound, nPatchRuntime = nPatchRuntime) }
                catch (error: SecurityException) {
                    // 平台拒绝跨包绑定/查询，不是条款或来源上的权威拒绝。
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=$route, type=security)")
                    transportFailure(route, "security")
                }
                catch (error: TimeoutException) {
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=$route, type=timeout)")
                    transportFailure(route, "timeout", HostAdmissionRouteClassifier.REASON_TIMEOUT)
                }
                catch (error: Exception) {
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=$route, type=${error.javaClass.simpleName})")
                    transportFailure(route, "exception")
                }
                log(Log.INFO, "[BIL] 启动授权通道结果(route=$route, reason=${result.reason})")
                responses.offer(Routed(route, result))
            }
        }.getOrNull()
        fun launchBroadcast() = runCatching {
            broadcastWorker.submit {
                val result = try { exchange(app, normal, normalFailure, deadline, broadcast = true, nPatchRuntime = nPatchRuntime) }
                catch (error: SecurityException) {
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=${HostAdmissionRoutePlan.BROADCAST}, type=security)")
                    transportFailure(HostAdmissionRoutePlan.BROADCAST, "security")
                }
                catch (error: TimeoutException) {
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=${HostAdmissionRoutePlan.BROADCAST}, type=timeout)")
                    transportFailure(HostAdmissionRoutePlan.BROADCAST, "timeout", HostAdmissionRouteClassifier.REASON_TIMEOUT)
                }
                catch (error: Exception) {
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=${HostAdmissionRoutePlan.BROADCAST}, type=${error.javaClass.simpleName})")
                    transportFailure(HostAdmissionRoutePlan.BROADCAST, "exception")
                }
                log(Log.INFO, "[BIL] 启动授权通道结果(route=${HostAdmissionRoutePlan.BROADCAST}, reason=${result.reason})")
                responses.offer(Routed(HostAdmissionRoutePlan.BROADCAST, result))
            }
        }.getOrNull()
        fun launchLocal() = runCatching {
            localWorker.submit {
                val result = try { exchange(app, normal, normalFailure, deadline, local = true, nPatchRuntime = nPatchRuntime) }
                catch (error: SecurityException) {
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=${HostAdmissionRoutePlan.LOCAL}, type=security)")
                    transportFailure(HostAdmissionRoutePlan.LOCAL, "security")
                }
                catch (error: TimeoutException) {
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=${HostAdmissionRoutePlan.LOCAL}, type=timeout)")
                    transportFailure(HostAdmissionRoutePlan.LOCAL, "timeout", HostAdmissionRouteClassifier.REASON_TIMEOUT)
                }
                catch (error: Exception) {
                    log(Log.WARN, "[BIL] 启动授权通道异常(route=${HostAdmissionRoutePlan.LOCAL}, type=${error.javaClass.simpleName})")
                    transportFailure(HostAdmissionRoutePlan.LOCAL, "exception")
                }
                log(Log.INFO, "[BIL] 启动授权通道结果(route=${HostAdmissionRoutePlan.LOCAL}, reason=${result.reason})")
                responses.offer(Routed(HostAdmissionRoutePlan.LOCAL, result))
            }
        }.getOrNull()
        val calls = ArrayList<java.util.concurrent.Future<*>>(4)
        fun remember(call: java.util.concurrent.Future<*>?) {
            if (call != null) calls.add(call)
        }
        remember(launch(false))
        if (HostAdmissionRoutePlan.binderRequired(Build.VERSION.SDK_INT)) remember(launch(true))
        var broadcastStarted = false
        fun startBroadcast() {
            if (broadcastStarted) return
            broadcastStarted = true
            remember(launchBroadcast())
        }
        // t=0 并行 local：模块冻结、包可见性、binder 挂起都不能挡住同进程保底。
        val localCall = launchLocal()
        remember(localCall)
        if (localCall == null) startBroadcast()
        if (calls.isEmpty()) return Result()
        var collect = fallbackState.newAttempt()
        try {
            var completed = 0
            while (completed < calls.size) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    return finishAdmit(normal, normalFailure, drainRouted(collect, responses, acceptGrant = false, observed = observed), nPatchRuntime, lspatchRuntime)
                }
                val routed = responses.poll(remaining, TimeUnit.MILLISECONDS)
                if (routed == null) {
                    val timedOut = drainRouted(collect, responses, acceptGrant = false, observed = observed)
                    val failure = if (timedOut.fallbackBlocked) timedOut.failure else Result(reason = HostAdmissionRouteClassifier.REASON_TIMEOUT)
                    return remoteConfigFallback(normal, normalFailure, timedOut.sawDenied, timedOut.fallbackBlocked,
                        failure.copy(diagnostics = timedOut.diagnostics), nPatchRuntime, lspatchRuntime)
                }
                completed += 1
                observed += routed.evidence()
                collect = collect.accept(routed.result.copy(selectedRoute = routed.route), acceptGrant = true)
                fallbackState.observe(routed.result)
                if (collect.grant != null) {
                    // 不等待其他通道，但不能跳过已经到达的真实拒绝。
                    return finishAdmit(normal, normalFailure, drainRouted(collect, responses, acceptGrant = false, observed = observed), nPatchRuntime, lspatchRuntime)
                }
                if (collect.sawDenied) {
                    return finishAdmit(normal, normalFailure, drainRouted(collect, responses, acceptGrant = false, observed = observed), nPatchRuntime, lspatchRuntime)
                }
                when (HostAdmissionRoutePlan.followUp(routed.route, broadcastStarted)) {
                    HostAdmissionRoutePlan.FollowUp.START_BROADCAST -> startBroadcast()
                    HostAdmissionRoutePlan.FollowUp.NONE -> Unit
                }
            }
            return finishAdmit(normal, normalFailure, drainRouted(collect, responses, acceptGrant = false, observed = observed), nPatchRuntime, lspatchRuntime)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result()
        } finally {
            // 仅取消排队及结果所有权；Binder 事务可能仍在运行，但绝不安装迟到 Hook。
            calls.forEach { it.cancel(false) }
        }
    }

    private fun exchange(
        context: Context,
        normal: RemoteHookConfigSnapshot?,
        normalFailure: String?,
        deadline: Long,
        bound: Boolean = false,
        broadcast: Boolean = false,
        local: Boolean = false,
        nPatchRuntime: Boolean = false
    ): Result {
        val authority = "${BuildConfig.APPLICATION_ID}.admission"
        // 目标宿主可能因 Android 包可见性策略无法从自身 PackageManager 查询模块包。
        // Provider/Service 端会在 Binder 事务内校验真实调用 UID、nonce、版本和授权状态，
        // 不能把 getApplicationInfo/resolveContentProvider 的可见性结果当作拒绝依据。
        // 只有端点明确的条款拒绝 / 来源必须同步才禁止更宽松回退；
        // 本路 identity_rejected、信封残缺、绑定对不上或平台 SecurityException 只表示通道不可用。
        val directFallback = nPatchRuntime || normalFailure == null || normalFailure in setOf(
            "remote_group_unavailable", "remote_read_exception", "remote_group_missing", "remote_stale_protocol")
        val nonce = UUID.randomUUID().toString()
        val request = Bundle().apply {
            putLong("deadline", deadline)
            putInt("version", HostAdmissionContract.VERSION)
            putString("nonce", nonce)
            putBoolean("allowDirect", directFallback)
            putLong("normalNoRootRevision", normal?.noRootRevision ?: 0L)
            normal?.takeIf { it.authorized }?.let { putString("normalFingerprint", RemoteHookConfigContract.contentFingerprint(it)) }
        }
        val route = when {
            broadcast -> HostAdmissionRoutePlan.BROADCAST
            local -> HostAdmissionRoutePlan.LOCAL
            bound -> HostAdmissionRoutePlan.BINDER
            else -> HostAdmissionRoutePlan.PROVIDER
        }
        fun reply(value: Bundle) = HostAdmissionHandshake.Reply(
            version = value.getInt("version"), moduleVersion = value.getLong("moduleVersion"),
            nonce = value.getString("nonce"), status = value.getString("status"),
            identity = HostAdmissionContract.identity(value), source = value.getString("source"),
            challenge = value.getString("challenge"), document = value.getString("document"),
            disabledRevision = value.getLong(HostAdmissionContract.DISABLED_REVISION)
        )
        fun exchangeUsing(call: (String, Bundle) -> Bundle?): Result = HostAdmissionHandshake.exchange(
            route, nonce, normal, directFallback, deadline, SystemClock::elapsedRealtime,
            prepare = { call(HostAdmissionContract.METHOD_PREPARE, request)?.let(::reply) },
            confirm = { challenge, identity ->
                val confirmation = Bundle().apply {
                    putInt("version", HostAdmissionContract.VERSION)
                    putString("nonce", nonce)
                    putString("challenge", challenge)
                    HostAdmissionContract.putIdentity(this, identity)
                }
                call(HostAdmissionContract.METHOD_CONFIRM, confirmation)?.let(::reply)
            },
            nPatchRuntime = nPatchRuntime
        )
        if (SystemClock.elapsedRealtime() >= deadline) return transportFailure(route, "timeout", HostAdmissionRouteClassifier.REASON_TIMEOUT)
        return when {
            broadcast -> broadcastExchange(context, deadline,
                canFinishUnhandled = nPatchRuntime || normal != null && normal.noRootRevision > 0L &&
                    HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                        false, normalFailure, normal.authorized,
                        normal.moduleVersionCode == BuildConfig.VERSION_CODE.toLong(), normal.generation
                    ), exchange = ::exchangeUsing)
            local -> localExchange(context, deadline, ::exchangeUsing)
            bound && Build.VERSION.SDK_INT >= 29 -> boundExchange(context, deadline, ::exchangeUsing)
            else -> context.contentResolver.acquireUnstableContentProviderClient(authority)?.use { client ->
                exchangeUsing { method, extras -> client.call(method, null, extras) }
            } ?: Result()
        }.let { result ->
            if (result.diagnostics.isEmpty()) result.copy(diagnostics = listOf(HostAdmissionDiagnostic(route, "transport", null)))
            else result
        }
    }

    private fun Routed.evidence() = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRouteEvidence(route,
        when {
            result.grant != null -> "granted"
            result.disabledRevision > 0 -> "disabled"
            result.reason == HostAdmissionRouteClassifier.REASON_DENIED -> "denied"
            result.reason == HostAdmissionRouteClassifier.REASON_TIMEOUT -> "timeout"
            else -> "unavailable"
        })

    private fun drainRouted(
        collect: HostAdmissionCollect,
        queue: LinkedBlockingQueue<Routed>,
        acceptGrant: Boolean,
        observed: MutableList<com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRouteEvidence>,
    ): HostAdmissionCollect {
        var next = collect
        while (true) {
            val extra = queue.poll() ?: break
            observed += extra.evidence()
            next = next.accept(extra.result.copy(selectedRoute = extra.route), acceptGrant)
            if (acceptGrant || extra.result.grant == null) fallbackState.observe(extra.result)
        }
        return next
    }

    private fun finishAdmit(
        normal: RemoteHookConfigSnapshot?,
        normalFailure: String?,
        collect: HostAdmissionCollect,
        nPatchRuntime: Boolean,
        lspatchRuntime: Boolean,
    ): Result {
        collect.grant?.takeIf { !collect.sawDenied }?.let { return it.copy(diagnostics = collect.diagnostics) }
        return remoteConfigFallback(normal, normalFailure, collect.sawDenied, collect.fallbackBlocked,
            collect.failure.copy(diagnostics = collect.diagnostics), nPatchRuntime, lspatchRuntime)
    }

    /**
     * 所有跨进程传输均被系统丢弃时的最后保底。Remote Preferences 已由
     * RemoteHookConfigContract 完整校验，且只在没有收到任何明确拒绝时采用；
     * 仅用于标准框架；NPatch 文档即使 revision 为正也可能在 Manager 失联后过期，
     * 必须由已有授权端点确认当前开关，不能据此恢复功能 Hook。
     */
    private fun remoteConfigFallback(
        normal: RemoteHookConfigSnapshot?,
        normalFailure: String?,
        sawDenied: Boolean,
        fallbackBlocked: Boolean,
        failure: Result,
        nPatchRuntime: Boolean,
        lspatchRuntime: Boolean
    ): Result {
        if (sawDenied || fallbackBlocked) return failure
        // LSPatch 的宿主本地库和管理器缓存均不能替代设置 App 的当前授权。
        if (lspatchRuntime) return failure
        val snapshot = normal ?: return failure
        if (HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied,
                normalFailure,
                snapshot.authorized,
                snapshot.moduleVersionCode == BuildConfig.VERSION_CODE.toLong(),
                snapshot.generation,
                fallbackBlocked = fallbackBlocked,
                nPatchRuntime = nPatchRuntime
            )
        ) {
            log(Log.WARN, "[BIL] 启动授权传输不可达，采用已校验 Remote Preferences 回退")
            return Result(
                Grant(snapshot = snapshot, source = "remote_config_transport_fallback"),
                reason = "",
                diagnostics = failure.diagnostics
            )
        }
        if (nPatchRuntime && snapshot.authorized && snapshot.noRootRevision <= 0L) {
            Log.w(TAG, "[BIL] NPatch 拒绝旧标准文档回退，需重新同步免 Root 配置")
            return Result(reason = "manager_sync_required")
        }
        if (nPatchRuntime && snapshot.authorized && !sawDenied) {
            Log.w(TAG, "[BIL] NPatch 授权端点不可达，拒绝采用可能过期的免 Root 文档")
            return Result(reason = HostAdmissionRouteClassifier.REASON_UNAVAILABLE)
        }
        return failure
    }

    /**
     * LSPosed 已将模块代码加载到宿主进程时的同进程回退。它仍使用模块 Context、
     * 同一端点和同一 UID/nonce/租约校验；若系统拒绝创建模块 Context，则结果为不可用，
     * 不会把本地 Remote Preferences 直接当成授权。
     */
    private fun localExchange(
        context: Context,
        deadline: Long,
        exchange: ((String, Bundle) -> Bundle?) -> Result
    ): Result {
        if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
        val moduleContext = runCatching {
            context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrNull() ?: return Result()
        if (moduleContext.packageName != BuildConfig.APPLICATION_ID) return Result()
        val trust = HostAdmissionLocalIdentity.verify(
            modulePackageName = moduleContext.packageName,
            hostUid = runCatching { context.applicationInfo.uid }.getOrNull(),
            moduleUid = runCatching { moduleContext.applicationInfo.uid }.getOrNull(),
            hostPackageUid = runCatching {
                context.packageManager.getApplicationInfo(
                    HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
                    0,
                ).uid
            }.getOrNull(),
            expectedModulePackage = BuildConfig.APPLICATION_ID,
        )
        val hostUid = when (trust) {
            HostAdmissionLocalIdentity.Trust.Unavailable -> return Result()
            is HostAdmissionLocalIdentity.Trust.Verified -> trust.hostUid
        }
        return exchange { method, extras ->
            if (SystemClock.elapsedRealtime() >= deadline) null
            else HostAdmissionEndpoint.handleLocal(moduleContext, method, extras, hostUid)
        }
    }

    /** 包可见性隔离时，经显式有序广播调用模块端的同一两阶段握手。 */
    private fun broadcastExchange(
        context: Context,
        deadline: Long,
        canFinishUnhandled: Boolean,
        exchange: ((String, Bundle) -> Bundle?) -> Result
    ): Result {
        val nonce = UUID.randomUUID().toString()
        val responseActionPrefix = "${context.packageName}.HOST_ADMISSION_RESPONSE.$nonce"
        val prepareResponseAction = "$responseActionPrefix.${HostAdmissionContract.METHOD_PREPARE}"
        val confirmResponseAction = "$responseActionPrefix.${HostAdmissionContract.METHOD_CONFIRM}"
        // authorizeAndInstall() is called from Application.attach on the host main thread.
        // If the callback is scheduled onto that same Looper, this method waits for a
        // response that cannot be delivered until attach returns, turning the fallback route
        // into a full bootstrap timeout. Keep the callback dispatcher independent; the
        // authorization/UID/nonce/identity checks remain unchanged.
        val callbackThread = HandlerThread("bil-admission-broadcast-callback").apply { start() }
        val callbackHandler = Handler(callbackThread.looper)
        // PREPARE and CONFIRM must not share a Future: a late PREPARE reply could otherwise
        // be consumed as the CONFIRM result. An unhandled ordered broadcast also must not
        // complete the current Future with null before the ordinary/PendingIntent fallback
        // has a chance to reply.
        val responseCoordinator = AdmissionResponseCoordinator()
        val callbackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    responseCoordinator.completeSafely(intent.action) {
                        intent.getBundleExtra(RoamingOpenReceiver.EXTRA_ADMISSION_RESPONSE)
                    }
                }.onFailure {
                    log(Log.WARN, "[BIL] 启动授权私有回执解析失败(type=${it.javaClass.simpleName})"
                    )
                }
            }
        }
        return try {
            val callbackFilter = IntentFilter().apply {
                addAction(prepareResponseAction)
                addAction(confirmResponseAction)
            }
            CrossAppBroadcastCompat.registerPrivateCallbackReceiver(
                context, callbackReceiver, callbackFilter, callbackHandler
            )
            try {
                exchange { method, extras ->
                    if (SystemClock.elapsedRealtime() >= deadline) return@exchange null
                    val responseAction = when (method) {
                        HostAdmissionContract.METHOD_PREPARE -> prepareResponseAction
                        HostAdmissionContract.METHOD_CONFIRM -> confirmResponseAction
                        else -> return@exchange null
                    }
                    val awaiter = responseCoordinator.begin(responseAction)
                    val response = awaiter.response
                    val proof = runCatching {
                        PendingIntent.getBroadcast(
                            context,
                            nonce.hashCode() xor method.hashCode(),
                            Intent(responseAction).setPackage(context.packageName),
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    }.getOrNull()
                    if (proof == null) {
                        responseCoordinator.clear(awaiter)
                        return@exchange null
                    }
                    val intent = Intent(RoamingOpenReceiver.ACTION_QUERY_HOOK_ADMISSION)
                        .setComponent(ComponentName(BuildConfig.APPLICATION_ID, RoamingOpenReceiver::class.java.name))
                        .addFlags(
                            Intent.FLAG_INCLUDE_STOPPED_PACKAGES or
                                Intent.FLAG_RECEIVER_FOREGROUND or
                                FLAG_RECEIVER_INCLUDE_BACKGROUND
                        )
                        .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_METHOD, method)
                        .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_REQUEST, Bundle(extras))
                        .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_NONCE, extras.getString("nonce"))
                        .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_CALLER_PROOF, proof)
                        .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_RESPONSE_ACTION, responseAction)
                    val resultReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            runCatching {
                                val result = getResultExtras(false)
                                if (result?.getBoolean(RoamingOpenReceiver.EXTRA_ADMISSION_HANDLED, false) == true) {
                                    responseCoordinator.completeSafely(responseAction) {
                                        result.getBundle(RoamingOpenReceiver.EXTRA_ADMISSION_RESPONSE)
                                    }
                                } else if (responseCoordinator.completeUnhandled(responseAction, canFinishUnhandled)) {
                                    Log.i(TAG, "[BIL] 启动授权广播不可达，结束本路等待(method=$method)")
                                }
                            }.onFailure {
                                log(Log.WARN, "[BIL] 启动授权 ordered 回执解析失败(type=${it.javaClass.simpleName})"
                                )
                            }
                        }
                    }
                    try {
                        CrossAppBroadcastCompat.sendOrderedBroadcast(
                            context,
                            intent,
                            resultReceiver,
                            callbackHandler
                        )
                        log(Log.INFO, "[BIL] 启动授权广播已发送(method=$method)")
                        runCatching {
                            // MIUI 某些版本会丢弃后台有序广播，但允许同一显式组件的普通广播；
                            // 两路共用 nonce，由接收端短时去重并回送同一结果，保留有序回执作为首选。
                            CrossAppBroadcastCompat.sendBroadcast(context, Intent(intent))
                            log(Log.INFO, "[BIL] 启动授权广播回退已发送(method=$method)")
                        }.onFailure {
                            log(Log.WARN, "[BIL] 启动授权广播回退发送失败(method=$method, type=${it.javaClass.simpleName})")
                        }
                        runCatching {
                            // 部分 MIUI 构建只在 sendBroadcastAsUser 路径保留显式跨包投递；
                            // 该路仍受模块端 PendingIntent、UID、nonce 和租约校验保护。
                            context.sendBroadcastAsUser(Intent(intent), android.os.Process.myUserHandle())
                            log(Log.INFO, "[BIL] 启动授权广播用户回退已发送(method=$method)")
                        }.onFailure {
                            log(Log.WARN, "[BIL] 启动授权广播用户回退发送失败(method=$method, type=${it.javaClass.simpleName})")
                        }
                        runCatching {
                            // PendingIntent 由宿主 UID 创建后交给系统调度，可绕过 MIUI 对普通
                            // Context 广播的包可见性拦截；请求内容仍包含同一回调证明和 nonce。
                            val requestToken = PendingIntent.getBroadcast(
                                context,
                                nonce.hashCode() xor method.hashCode(),
                                Intent(intent),
                                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            try {
                                requestToken.send(context, 0, null)
                            } finally {
                                requestToken.cancel()
                            }
                            log(Log.INFO, "[BIL] 启动授权 PendingIntent 回退已发送(method=$method)")
                        }.onFailure {
                            log(Log.WARN, "[BIL] 启动授权 PendingIntent 回退发送失败(method=$method, type=${it.javaClass.simpleName})")
                        }
                        runCatching {
                            response.get(
                                (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L),
                                TimeUnit.MILLISECONDS
                            )
                        }.getOrNull().also {
                            if (it != null) log(Log.INFO, "[BIL] 启动授权广播收到回执(method=$method)")
                        }
                    } finally {
                        responseCoordinator.clear(awaiter)
                        runCatching { proof.cancel() }
                    }
                }
            } finally {
                runCatching { context.unregisterReceiver(callbackReceiver) }
            }
        } finally {
            callbackThread.quitSafely()
        }
    }
    // API 29 起可指定工作回调执行器，避免 Application.attach 主线程等待自己的绑定回调。
    @androidx.annotation.RequiresApi(29)
    private fun boundExchange(context: Context, deadline: Long, exchange: ((String, Bundle) -> Bundle?) -> Result): Result {
        val component = ComponentName(BuildConfig.APPLICATION_ID, CompatibilityReceiptService::class.java.name)
        val arrival = CompletableFuture<IBinder?>()
        val closed = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!closed.get()) arrival.complete(service.takeIf { name == component })
            }
            override fun onServiceDisconnected(name: ComponentName) { arrival.complete(null) }
            override fun onBindingDied(name: ComponentName) { arrival.complete(null) }
            override fun onNullBinding(name: ComponentName) { arrival.complete(null) }
        }
        try {
            if (!context.bindService(Intent().setComponent(component), Context.BIND_AUTO_CREATE,
                    java.util.concurrent.Executor { it.run() }, connection)) return Result()
            val binder = arrival.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L), TimeUnit.MILLISECONDS) ?: return Result()
            if (!binder.isBinderAlive || binder.interfaceDescriptor != CompatibilityReceiptService.DESCRIPTOR) return Result()
            return exchange { method, extras ->
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(CompatibilityReceiptService.DESCRIPTOR)
                    data.writeString(method); data.writeBundle(extras)
                    if (!binder.transact(CompatibilityReceiptService.ADMISSION, data, reply, 0)) null
                    else { reply.readException(); reply.readBundle(Bundle::class.java.classLoader) }
                } finally { data.recycle(); reply.recycle() }
            }
        } finally {
            closed.set(true)
            runCatching { context.unbindService(connection) }
        }
    }

    // Hidden framework flag (Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND), stable since API 26.
    private const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000
}
