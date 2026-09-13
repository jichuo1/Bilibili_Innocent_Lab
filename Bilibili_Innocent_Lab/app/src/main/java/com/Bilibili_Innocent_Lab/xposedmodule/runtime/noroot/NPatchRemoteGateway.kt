package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityPolicy
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.io.Serializable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * NPatch Remote Provider 的最小模块侧网关。
 *
 * **为什么不用官方 AAR**：其连接字节码在 API 28-32 上直接调用 API 33 才有的
 * `String.isBlank`，而本模块 minSdk 27 且未开启 core library desugaring，装不得。
 * （最初还有一条"AAR 声明 minCompileSdk 37、本项目当时是 compileSdk 35"的理由，
 * 2026-09-03 抬到 compileSdk 37 后已失效，2026-09-08 复核时删除。）
 * 这里依照官方公开的 Provider + IXposedService 102 AIDL 合约，只保留本项目需要的
 * Preferences 原子写入与完整读回。
 *
 * **适用边界**：只支持 NPatch 的**管理器模式**（`--manager`）。集成模式（`--embed`）
 * 把 Remote Store 放在被修补应用自己的数据目录里，注入侧只读，模块设置 App 无法写入，
 * 结构上不可能从这里同步——与 LSPatch 嵌入模式是同一条边界，见
 * `docs/lspatch_compatibility.md` 的 Explicit non-goals。
 *
 * **官方声明的下限**：NPatch ≥ 1.0.7、Android 9（API 28）。模块不做版本探测，
 * 缺失/被拒的两条状态文案已把这个下限写进去（见 `no_root_status_*` 字符串）。
 */
internal object NPatchRemoteGateway {
    const val AUTHORITY = "top.nkbe.npatch.remote"
    private const val MANAGER_PACKAGE = "top.nkbe.npatch"
    private const val METHOD_GET_REMOTE_SERVICE = "getRemoteService"
    private const val KEY_MODULE_PACKAGE = "modulePackageName"
    private const val KEY_BINDER = "binder"
    private const val CONNECTION_RETRY_COOLDOWN_SECONDS = 15L
    private const val SERVICE_DESCRIPTOR = "io.github.libxposed.service.IXposedService"
    /**
     * `IXposedService` 的两个交易码，抄自 libxposed AIDL。
     *
     * 这份 AIDL **显式编号**（2-6 / 11-15 / 21-23 / 31-33 之间有明显空档），所以上游新增
     * 方法不会顶掉这两个号；但重排或改号会让本网关静默打到别的方法上，失败表现只是
     * `remote_service_error`，极难反查。因此由 `NPatchRemoteGatewayPolicyTest` 钉住取值。
     *
     * 升级 `io.github.libxposed:interface` 后按以下步骤复核，对不上就同时改常量与测试：
     * ```
     * unzip -o interface-<ver>.aar classes.jar && unzip -o classes.jar
     * javap -p -constants -classpath . 'io.github.libxposed.service.IXposedService$Stub' | grep TRANSACTION
     * ```
     * 2026-09-08 依 `interface-102.0.0` 复核：21 / 22 与 wire format（token → group
     * 字符串 → `writeTypedObject` → flags=0 → `readException`）逐字段一致。
     */
    internal const val TRANSACTION_REQUEST_REMOTE_PREFERENCES = 21
    internal const val TRANSACTION_UPDATE_REMOTE_PREFERENCES = 22

    sealed interface SyncResult {
        data object Success : SyncResult
        data object ManagerMissing : SyncResult
        data object ModuleNotRegistered : SyncResult
        data object ConnectionTimeout : SyncResult
        data class Failure(val errorCode: String) : SyncResult
    }

    /**
     * 两个有界单线程池分别承担编排与可能阻塞的 Provider/Binder 调用。即使厂商 Binder
     * 无视 interrupt，悬挂线程与排队任务数量也各自至多为 1，不会随页面恢复累积。
     */
    private val coordinatorExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        boundedExecutor("BIL-NPatch-Sync", ThreadPoolExecutor.DiscardOldestPolicy())
    }
    private val connectionExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        boundedExecutor("BIL-NPatch-Provider", ThreadPoolExecutor.AbortPolicy())
    }
    private val connectionBlockedUntilNanos = AtomicLong(0L)

    /** 调用方必须先确认用户已开启免 Root；关闭路径不会触发此对象的 executor。 */
    fun syncAsync(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        decision: UserTermsDecision,
        stillCurrent: () -> Boolean = { true },
        callback: (SyncResult, com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity?) -> Unit
    ) {
        val appContext = context.applicationContext
        val identity = com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.current(appContext)?.identity
        coordinatorExecutor.execute {
            if (!isStillCurrent(stillCurrent)) return@execute
            val result = sync(appContext, snapshot, decision, stillCurrent) ?: return@execute
            if (isStillCurrent(stillCurrent)) callback(result, identity)
        }
    }

    private fun sync(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        decision: UserTermsDecision,
        stillCurrent: () -> Boolean
    ): SyncResult? {
        if (!isStillCurrent(stillCurrent)) return null
        if (!isAvailable(context)) return SyncResult.ManagerMissing
        return try {
            val rawSource = HashMap<String, Any>().apply {
                context.modulePreferences().all.forEach { (key, value) ->
                    if (value != null) put(key, value)
                }
            }
            val values = resolveRemoteValues(snapshot, rawSource)

            runRemoteWriteWithTimeout(
                context = context,
                snapshot = snapshot,
                decision = decision,
                values = values,
                stillCurrent = stillCurrent
            )
        } catch (_: SecurityException) {
            SyncResult.ModuleNotRegistered
        } catch (_: ConnectionTimeoutException) {
            SyncResult.ConnectionTimeout
        } catch (throwable: Throwable) {
            val root = unwrap(throwable)
            when (root) {
                is SecurityException -> SyncResult.ModuleNotRegistered
                is ConnectionTimeoutException -> SyncResult.ConnectionTimeout
                is IncompatibleServiceException -> SyncResult.Failure("incompatible_service")
                is RemoteReadBackException -> SyncResult.Failure("remote_readback_mismatch")
                is UnsupportedOperationException -> SyncResult.Failure("remote_read_only")
                is RemoteException -> SyncResult.Failure("remote_service_error")
                else -> SyncResult.Failure(
                    root.javaClass.simpleName.ifBlank { "unknown_failure" }
                )
            }
        }
    }

    private fun isAvailable(context: Context): Boolean = runCatching {
        context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName == MANAGER_PACKAGE
    }.getOrDefault(false)

    /** Provider 连接与 Binder 写入共享同一个 6 秒截止时间。 */
    private fun runRemoteWriteWithTimeout(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        decision: UserTermsDecision,
        values: Map<String, Any>,
        stillCurrent: () -> Boolean
    ): SyncResult? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (isConnectionCircuitOpen(nowNanos, connectionBlockedUntilNanos.get())) {
            throw ConnectionTimeoutException()
        }
        val call = try {
            connectionExecutor.submit<SyncResult?> {
                if (!isStillCurrent(stillCurrent)) return@submit null
                val service = connectServiceBlocking(context)
                if (!isStillCurrent(stillCurrent)) return@submit null
                val publication = com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.current(context)
                    ?.takeIf { it.decision == decision &&
                        (if (snapshot.enabled) NoRootSupportStore.isDesiredEnabled(context) && it.values == values
                        else !NoRootSupportStore.isDesiredEnabled(context)) } ?: return@submit null
                publishRemotePreferences(service, snapshot, decision, values) {
                    stillCurrent() && com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.matches(context, publication)
                }
            }
        } catch (exception: RejectedExecutionException) {
            openConnectionCircuit()
            throw ConnectionTimeoutException(exception)
        }
        val result = try {
            call.get(CommunicationCompatibilityPolicy.managerTimeout(CommunicationCompatibilityStore.isEnabled(context)), TimeUnit.MILLISECONDS)
        } catch (exception: TimeoutException) {
            call.cancel(true)
            connectionExecutor.purge()
            openConnectionCircuit()
            throw ConnectionTimeoutException(exception)
        } catch (exception: InterruptedException) {
            call.cancel(true)
            connectionExecutor.purge()
            Thread.currentThread().interrupt()
            throw IllegalStateException("NPatch connection interrupted", exception)
        } catch (exception: ExecutionException) {
            throw unwrap(exception)
        }
        connectionBlockedUntilNanos.set(0L)
        return result
    }

    /** 只能在 [connectionExecutor] 中调用，超时由外层 Future 统一控制。 */
    private fun connectServiceBlocking(context: Context): IBinder {
        val extras = Bundle().apply {
            putString(KEY_MODULE_PACKAGE, context.packageName)
        }
        fun standardCall() = context.contentResolver.call(
            "content://$AUTHORITY".toUri(), METHOD_GET_REMOTE_SERVICE, null, extras)
        // 两种模式共用稳定协议；仅 Provider 进程死亡可重建一次，不吞身份拒绝。
        val result = try {
            context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)?.use {
                it.call(METHOD_GET_REMOTE_SERVICE, null, extras)
            }
        } catch (_: android.os.DeadObjectException) { standardCall() }
        val binder = result?.getBinder(KEY_BINDER)
            ?: throw SecurityException("NPatch rejected module identity")
        if (!binder.isBinderAlive || !binder.pingBinder()) {
            throw RemoteException("NPatch remote service binder is dead")
        }
        val descriptor = binder.interfaceDescriptor
        if (!acceptsServiceDescriptor(descriptor)) {
            throw IncompatibleServiceException(descriptor)
        }
        return binder
    }

    private fun publishRemotePreferences(
        service: IBinder,
        snapshot: NoRootConfigSnapshot,
        decision: UserTermsDecision,
        values: Map<String, Any>,
        stillCurrent: () -> Boolean
    ): SyncResult? {
        val currentRaw = requestRemoteValues(service)
        val current = RemoteHookConfigContract.decode(currentRaw)
        if (current is RemoteHookConfigDecodeResult.Ready &&
            current.snapshot.moduleVersionCode == BuildConfig.VERSION_CODE.toLong() &&
            current.snapshot.deliveryEnabled == snapshot.enabled &&
            current.snapshot.noRootRevision == snapshot.revision &&
            current.snapshot.decision == decision &&
            current.snapshot.values == values
        ) {
            return SyncResult.Success
        }
        if (!isStillCurrent(stillCurrent)) return null
        val previousGeneration = (currentRaw[RemoteHookConfigContract.KEY_GENERATION] as? Long)
            ?.coerceAtLeast(0L) ?: 0L
        val generation = maxOf(
            System.currentTimeMillis().coerceAtLeast(1L),
            previousGeneration.nextGeneration()
        )
        val encoded = RemoteHookConfigContract.encode(
            generation = generation,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = snapshot.enabled,
            noRootRevision = snapshot.revision,
            decision = decision,
            values = values
        )
        val diff = Bundle().apply {
            putBoolean("clear", true)
            putSerializable("delete", HashSet<String>() as Serializable)
            putSerializable("put", HashMap(encoded) as Serializable)
        }
        updateRemotePreferences(service, diff)
        if (!isStillCurrent(stillCurrent)) return null
        val readBack = RemoteHookConfigContract.decode(requestRemoteValues(service))
        val ready = readBack as? RemoteHookConfigDecodeResult.Ready
            ?: throw RemoteReadBackException(
                (readBack as RemoteHookConfigDecodeResult.Invalid).reason
            )
        if (ready.snapshot.generation != generation ||
            ready.snapshot.moduleVersionCode != BuildConfig.VERSION_CODE.toLong() ||
            ready.snapshot.deliveryEnabled != snapshot.enabled ||
            ready.snapshot.noRootRevision != snapshot.revision ||
            ready.snapshot.decision != decision ||
            ready.snapshot.values != values
        ) {
            throw RemoteReadBackException("content_mismatch")
        }
        return SyncResult.Success
    }

    @Suppress("DEPRECATION")
    private fun requestRemoteValues(service: IBinder): Map<String, Any> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val result = try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeString(RemoteHookConfigContract.GROUP)
            if (!service.transact(TRANSACTION_REQUEST_REMOTE_PREFERENCES, data, reply, 0)) {
                throw RemoteException("NPatch requestRemotePreferences transaction rejected")
            }
            reply.readException()
            if (reply.readInt() != 0) {
                Bundle.CREATOR.createFromParcel(reply)
            } else {
                null
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
        val serialized = result?.getSerializable("map")
        val raw = serialized as? Map<*, *> ?: return emptyMap()
        return linkedMapOf<String, Any>().apply {
            raw.forEach { (key, value) ->
                if (key is String && value != null) put(key, value)
            }
        }
    }

    private fun updateRemotePreferences(service: IBinder, diff: Bundle) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeString(RemoteHookConfigContract.GROUP)
            data.writeInt(1)
            diff.writeToParcel(data, 0)
            if (!service.transact(TRANSACTION_UPDATE_REMOTE_PREFERENCES, data, reply, 0)) {
                throw RemoteException("NPatch updateRemotePreferences transaction rejected")
            }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun isStillCurrent(predicate: () -> Boolean): Boolean =
        runCatching(predicate).getOrDefault(false)

    private tailrec fun unwrap(throwable: Throwable): Throwable {
        val cause = throwable.cause ?: return throwable
        return when (throwable) {
            is ExecutionException,
            is java.util.concurrent.CompletionException -> unwrap(cause)
            else -> throwable
        }
    }

    private class ConnectionTimeoutException(cause: Throwable? = null) :
        IllegalStateException("NPatch connection unavailable", cause)

    private class IncompatibleServiceException(descriptor: String?) :
        RemoteException("Unexpected NPatch service descriptor: ${descriptor ?: "null"}")

    private class RemoteReadBackException(reason: String) :
        IllegalStateException("NPatch remote read-back failed: $reason")

    private fun openConnectionCircuit() {
        val cooldownNanos = TimeUnit.SECONDS.toNanos(CONNECTION_RETRY_COOLDOWN_SECONDS)
        connectionBlockedUntilNanos.set(SystemClock.elapsedRealtimeNanos() + cooldownNanos)
    }

    internal fun acceptsServiceDescriptor(descriptor: String?): Boolean =
        descriptor == SERVICE_DESCRIPTOR

    internal fun resolveRemoteValues(
        snapshot: NoRootConfigSnapshot,
        rawSource: Map<String, *>
    ): Map<String, Any> = RemoteHookConfigContract.resolveSourceValues(
        HashMap<String, Any>().apply {
            rawSource.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
            putAll(snapshot.values)
            put(
                RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP,
                snapshot.adapterResetRevision
            )
        }
    )

    internal fun isConnectionCircuitOpen(nowNanos: Long, blockedUntilNanos: Long): Boolean =
        blockedUntilNanos != 0L && blockedUntilNanos - nowNanos > 0L

    private fun Long.nextGeneration(): Long = when {
        this < 1L -> 1L
        this == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> this + 1L
    }

    private fun boundedExecutor(
        threadName: String,
        rejectionHandler: java.util.concurrent.RejectedExecutionHandler
    ) = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
        rejectionHandler
    )
}
