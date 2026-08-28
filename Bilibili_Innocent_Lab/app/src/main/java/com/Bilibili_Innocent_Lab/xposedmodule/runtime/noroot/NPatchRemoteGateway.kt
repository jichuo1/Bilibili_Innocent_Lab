package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.core.net.toUri
import java.io.Serializable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * NPatch v1.0.7 Remote Provider 的最小模块侧网关。
 *
 * 官方 v1.0.1 AAR 声明 minCompileSdk 37，会破坏本项目 compileSdk 35 的 CI；其连接
 * 字节码还在 API 28-32 直接调用 API 33 的 String.isBlank。这里依照官方公开的
 * Provider + IXposedService 102 AIDL 合约，用原生 Binder/Parcel 只保留本项目
 * 需要的 Preferences 原子写入，避免引入高 compileSdk 依赖。
 */
internal object NPatchRemoteGateway {
    const val AUTHORITY = "top.nkbe.npatch.remote"
    private const val MANAGER_PACKAGE = "top.nkbe.npatch"
    private const val METHOD_GET_REMOTE_SERVICE = "getRemoteService"
    private const val KEY_MODULE_PACKAGE = "modulePackageName"
    private const val KEY_BINDER = "binder"
    private const val GROUP = "innocent_lab_v1"
    private const val CONNECT_TIMEOUT_SECONDS = 3L
    private const val SERVICE_DESCRIPTOR = "io.github.libxposed.service.IXposedService"
    private const val TRANSACTION_UPDATE_REMOTE_PREFERENCES = 22

    sealed interface SyncResult {
        data object Success : SyncResult
        data object ManagerMissing : SyncResult
        data object ModuleNotRegistered : SyncResult
        data object ConnectionTimeout : SyncResult
        data class Failure(val errorClass: String) : SyncResult
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

    /** 调用方必须先确认用户已开启免 Root；关闭路径不会触发此对象的 executor。 */
    fun syncAsync(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        stillCurrent: () -> Boolean = { true },
        callback: (SyncResult) -> Unit
    ) {
        val appContext = context.applicationContext
        coordinatorExecutor.execute {
            if (!isStillCurrent(stillCurrent)) return@execute
            val result = sync(appContext, snapshot, stillCurrent) ?: return@execute
            if (isStillCurrent(stillCurrent)) callback(result)
        }
    }

    private fun sync(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        stillCurrent: () -> Boolean
    ): SyncResult? {
        if (!isStillCurrent(stillCurrent)) return null
        if (!isAvailable(context)) return SyncResult.ManagerMissing
        return try {
            val payload = NoRootConfigSnapshotCodec.encode(snapshot)
            val puts = HashMap<String, Any>().apply {
                put("schema", snapshot.schemaVersion)
                put("catalog_version", snapshot.catalogVersion)
                put("module_version_code", snapshot.moduleVersionCode)
                put("revision", snapshot.revision)
                put("adapter_reset_revision", snapshot.adapterResetRevision)
                put("enabled", snapshot.enabled)
                put("snapshot_json", payload)
            }
            val diff = Bundle().apply {
                putBoolean("clear", false)
                putSerializable("delete", HashSet<String>() as Serializable)
                putSerializable("put", puts as Serializable)
            }

            runRemoteWriteWithTimeout(context, diff, stillCurrent)
        } catch (_: SecurityException) {
            SyncResult.ModuleNotRegistered
        } catch (_: ConnectionTimeoutException) {
            SyncResult.ConnectionTimeout
        } catch (throwable: Throwable) {
            val root = unwrap(throwable)
            when (root) {
                is SecurityException -> SyncResult.ModuleNotRegistered
                is ConnectionTimeoutException -> SyncResult.ConnectionTimeout
                else -> SyncResult.Failure(root.javaClass.simpleName.ifBlank { "Unknown" })
            }
        }
    }

    private fun isAvailable(context: Context): Boolean = runCatching {
        context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName == MANAGER_PACKAGE
    }.getOrDefault(false)

    /** Provider 连接与 Binder 写入共享同一个 3 秒截止时间。 */
    private fun runRemoteWriteWithTimeout(
        context: Context,
        diff: Bundle,
        stillCurrent: () -> Boolean
    ): SyncResult? {
        val call = try {
            connectionExecutor.submit<SyncResult?> {
                if (!isStillCurrent(stillCurrent)) return@submit null
                val binder = connectServiceBlocking(context)
                if (!isStillCurrent(stillCurrent)) return@submit null
                updateRemotePreferences(binder, diff)
                SyncResult.Success
            }
        } catch (exception: RejectedExecutionException) {
            throw ConnectionTimeoutException(exception)
        }
        val result = try {
            call.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: TimeoutException) {
            call.cancel(true)
            connectionExecutor.purge()
            throw ConnectionTimeoutException(exception)
        } catch (exception: InterruptedException) {
            call.cancel(true)
            connectionExecutor.purge()
            Thread.currentThread().interrupt()
            throw IllegalStateException("NPatch connection interrupted", exception)
        } catch (exception: ExecutionException) {
            throw unwrap(exception)
        }
        return result
    }

    /** 只能在 [connectionExecutor] 中调用，超时由外层 Future 统一控制。 */
    private fun connectServiceBlocking(context: Context): IBinder {
        val extras = Bundle().apply {
            putString(KEY_MODULE_PACKAGE, context.packageName)
        }
        val result = context.contentResolver.call(
            "content://$AUTHORITY".toUri(),
            METHOD_GET_REMOTE_SERVICE,
            null,
            extras
        )
        return result?.getBinder(KEY_BINDER)
            ?: throw SecurityException("NPatch rejected module identity")
    }

    /**
     * IXposedService.updateRemotePreferences(String, Bundle) 在 API 102 AIDL 中的固定事务。
     * 不使用 API 33 的 writeTypedObject，保持项目 minSdk 27 兼容性。
     */
    private fun updateRemotePreferences(binder: IBinder, diff: Bundle) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeString(GROUP)
            data.writeInt(1)
            diff.writeToParcel(data, 0)
            val handled = binder.transact(
                TRANSACTION_UPDATE_REMOTE_PREFERENCES,
                data,
                reply,
                0
            )
            if (!handled) {
                throw RemoteException("NPatch service rejected updateRemotePreferences")
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

    private class ConnectionTimeoutException(cause: Throwable) :
        IllegalStateException("NPatch connection timed out", cause)

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
