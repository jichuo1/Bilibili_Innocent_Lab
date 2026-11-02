package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * NPatch Manager 作用域的只读探测。
 *
 * 只用于纠正“作用域已被移除、界面仍按历史心跳显示已激活”（B10）。查询失败、Provider
 * 不可达或超时一律返回 [ScopeState.UNKNOWN]，交回原有状态归并，不把临时故障当成未激活。
 */
internal object NPatchScopeProbe {
    private const val TAG = "BilibiliInnocentLab"
    private const val MANAGER_AUTHORITY = "top.nkbe.npatch.manager.provider.config"
    private const val COLUMN_PACKAGE = "packageName"
    private const val MIN_INTERVAL_MS = 5_000L
    private const val QUERY_TIMEOUT_MS = 2_000L

    internal enum class ScopeState { UNKNOWN, IN_SCOPE, OUT_OF_SCOPE }

    /**
     * 与 [NPatchRemoteGateway] 相同的双线程边界：协调线程等 Future，查询线程可能被
     * 厂商 Binder 悬挂。两者都有界，悬挂不会随页面恢复累积。
     */
    private val coordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        boundedExecutor("BIL-NPatch-Scope")
    }
    private val queryExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        boundedExecutor("BIL-NPatch-ScopeQuery")
    }

    @Volatile
    private var cached = ScopeState.UNKNOWN

    @Volatile
    private var inFlight = false

    @Volatile
    private var lastProbeAtMs = 0L

    fun cached(context: Context): ScopeState = effectiveScope(NPatchRuntimeProfiles.read(context).mode, cached)

    /** Diagnostics worker only. Reuse the bounded read-only provider query; embed never probes Manager. */
    fun probeReachability(context: Context): NPatchManagerReachability {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val patch = NPatchRuntimeProfiles.read(context)
        if (!patch.markerPresent) return NPatchManagerReachability.NOT_APPLICABLE
        if (patch.mode != NPatchMode.MANAGER) return classifyReachability(patch.mode, ScopeState.UNKNOWN)
        return classifyReachability(patch.mode, probeWithTimeout(context))
    }

    internal fun classifyReachability(mode: NPatchMode, scope: ScopeState): NPatchManagerReachability = when {
        mode == NPatchMode.EMBEDDED -> NPatchManagerReachability.NOT_APPLICABLE
        mode == NPatchMode.MANAGER && scope != ScopeState.UNKNOWN -> NPatchManagerReachability.REACHABLE
        else -> NPatchManagerReachability.UNKNOWN
    }

    /** embed 的作用域固定在 APK 内，Manager 的空列表或旧查询结果不能否决它。 */
    internal fun effectiveScope(mode: NPatchMode, managerScope: ScopeState): ScopeState =
        if (mode == NPatchMode.EMBEDDED) ScopeState.UNKNOWN else managerScope

    /** 结果变化时才回调；调用方负责切回主线程。 */
    fun refreshAsync(context: Context, onChanged: (ScopeState) -> Unit = {}) {
        val appContext = context.applicationContext ?: context
        if (NPatchRuntimeProfiles.read(appContext).mode == NPatchMode.EMBEDDED) return
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (inFlight || (lastProbeAtMs > 0L && now - lastProbeAtMs < MIN_INTERVAL_MS)) return
            inFlight = true
            lastProbeAtMs = now
        }
        coordinator.execute {
            val managerResult = probeWithTimeout(appContext)
            val result = effectiveScope(NPatchRuntimeProfiles.read(appContext).mode, managerResult)
            val previous = cached
            cached = result
            synchronized(this) { inFlight = false }
            if (result != previous) {
                Log.i(TAG, "[BIL] NPatch 作用域探测: ${result.name}")
                onChanged(result)
            }
        }
    }

    private fun probeWithTimeout(context: Context): ScopeState {
        val call = try {
            queryExecutor.submit<ScopeState> { queryScope(context) }
        } catch (_: Throwable) {
            return ScopeState.UNKNOWN
        }
        return try {
            call.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            call.cancel(true)
            ScopeState.UNKNOWN
        } catch (_: ExecutionException) {
            ScopeState.UNKNOWN
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ScopeState.UNKNOWN
        }
    }

    private fun queryScope(context: Context): ScopeState {
        val client = try {
            context.contentResolver.acquireUnstableContentProviderClient(MANAGER_AUTHORITY)
        } catch (throwable: Throwable) {
            Log.d(TAG, "[BIL] NPatch 作用域 Provider 不可达: ${throwable.javaClass.simpleName}")
            null
        } ?: return ScopeState.UNKNOWN
        return try {
            val cursor = client.query(
                "content://$MANAGER_AUTHORITY?package=${NoRootSupportState.TARGET_PACKAGE}".toUri(),
                arrayOf(COLUMN_PACKAGE),
                null,
                null,
                null
            ) ?: return ScopeState.UNKNOWN
            cursor.use {
                val index = it.getColumnIndex(COLUMN_PACKAGE)
                if (index < 0) return ScopeState.UNKNOWN
                val packages = ArrayList<String>(4)
                while (it.moveToNext()) {
                    packages += it.getString(index).orEmpty()
                }
                classify(packages, BuildConfig.APPLICATION_ID)
            }
        } catch (throwable: Throwable) {
            Log.d(TAG, "[BIL] NPatch 作用域查询失败: ${throwable.javaClass.simpleName}")
            ScopeState.UNKNOWN
        } finally {
            runCatching { client.close() }
        }
    }

    /** 空结果 = Manager 明确不再把本模块列入该目标；只有读不到结果才算未知。 */
    internal fun classify(packages: List<String>, modulePackage: String): ScopeState = when {
        packages.isEmpty() -> ScopeState.OUT_OF_SCOPE
        modulePackage in packages -> ScopeState.IN_SCOPE
        else -> ScopeState.OUT_OF_SCOPE
    }

    private fun boundedExecutor(threadName: String) = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
}
