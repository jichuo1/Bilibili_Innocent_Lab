package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import com.highcapable.betterandroid.system.extension.component.versionCodeCompat
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 模块设置进程的单飞协调器；无定时轮询，不持有 Activity 或 View。 */
internal object NoRootSupportController {
    private const val DEFAULT_RESTART_FLUSH_TIMEOUT_MS = 4_000L
    private const val FLIGHT_WATCHDOG_TIMEOUT_MS = 7_000L

    enum class FlushResult {
        SUCCESS,
        FAILED,
        TIMED_OUT
    }

    private data class EnabledSyncCompletion(
        val revision: Long,
        val remoteResult: NPatchRemoteGateway.SyncResult?
    )

    private val requestGeneration = AtomicLong(0L)
    private val syncFlights =
        NoRootSyncFlightRegistry<NPatchRemoteGateway.SyncResult>()
    private val flightWatchdogs = ConcurrentHashMap<
        NoRootSyncFlightRegistry.Token,
        ScheduledFuture<*>
        >()
    private val watchdogExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ScheduledThreadPoolExecutor(
            1
        ) { runnable ->
            Thread(runnable, "BIL-NPatch-Watchdog").apply { isDaemon = true }
        }.apply {
            setRemoveOnCancelPolicy(true)
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }

    /**
     * 只提交用户意图并使旧同步回调失效；开启后的快照构造与 Binder 同步由调用方
     * 随后放到后台执行。这样快速开关时，最后一次 UI 操作始终拥有最终决定权。
     */
    fun setDesiredEnabled(
        context: Context,
        enabled: Boolean
    ): Boolean {
        val appContext = context.applicationContext
        if (enabled && AndroidVersion.isLessThan(AndroidVersion.P)) {
            return false
        }
        val intentGeneration = requestGeneration.incrementAndGet()
        val canceledListeners = syncFlights.cancelAll()
        cancelAllWatchdogs()
        if (!enabled) {
            val disabled = NoRootSupportStore.disable(appContext)
            if (!disabled) {
                NoRootSupportStore.updateSyncState(
                    appContext,
                    NoRootSupportStore.SyncState.ERROR,
                    detail = "disable_snapshot_write_failed",
                    stillCurrent = { requestGeneration.get() == intentGeneration }
                )
            } else {
                // 目标启动权威通道已经使用本地 tombstone；这里再尽力清理 NPatch
                // Remote Store，避免未来版本读取到历史 enabled=true。
                NoRootSupportStore.readSnapshot(appContext)
                    ?.takeUnless { it.enabled }
                    ?.let { tombstone ->
                        enqueueRemoteSync(
                            context = appContext,
                            snapshot = tombstone,
                            generation = intentGeneration,
                            resultHandler = {},
                            listener = null
                        )
                    }
            }
            notifyListeners(canceledListeners, result = null)
            return disabled
        }
        if (!NoRootSupportStore.setDesiredEnabled(appContext, enabled = true)) {
            notifyListeners(canceledListeners, result = null)
            return false
        }
        NoRootSupportStore.updateSyncState(
            appContext,
            NoRootSupportStore.SyncState.CHECKING,
            stillCurrent = { requestGeneration.get() == intentGeneration }
        )
        notifyListeners(canceledListeners, result = null)
        return true
    }

    /**
     * 同步读取当前用户意图代次。生命周期刷新不会生成新代次，避免相同内容的
     * onResume/onPause 调用互相作废；只有用户切换免 Root 意图才递增代次。
     */
    fun beginSynchronization(context: Context): Long? {
        val appContext = context.applicationContext
        if (!NoRootSupportStore.isDesiredEnabled(appContext) ||
            AndroidVersion.isLessThan(AndroidVersion.P)
        ) return null
        val generation = requestGeneration.get()
        val currentState = NoRootSupportStore.readStatus(appContext).syncState
        if (currentState != NoRootSupportStore.SyncState.SYNCING &&
            currentState != NoRootSupportStore.SyncState.ACTIVE &&
            currentState != NoRootSupportStore.SyncState.RESTART_REQUIRED
        ) {
            NoRootSupportStore.updateSyncState(
                appContext,
                NoRootSupportStore.SyncState.CHECKING,
                stillCurrent = { requestGeneration.get() == generation }
            )
        }
        return generation
    }

    fun synchronize(
        context: Context,
        bridge: YukiHookPrefsBridge,
        generation: Long? = null,
        onFinished: (() -> Unit)? = null
    ) {
        synchronizeWithResult(context, bridge, generation) { onFinished?.invoke() }
    }

    private fun synchronizeWithResult(
        context: Context,
        bridge: YukiHookPrefsBridge,
        generation: Long? = null,
        onFinished: (EnabledSyncCompletion) -> Unit
    ) {
        val appContext = context.applicationContext
        if (!NoRootSupportStore.isDesiredEnabled(appContext) ||
            AndroidVersion.isLessThan(AndroidVersion.P)
        ) {
            onFinished(EnabledSyncCompletion(0L, null))
            return
        }
        val activeGeneration = generation ?: beginSynchronization(appContext) ?: run {
            onFinished(EnabledSyncCompletion(0L, null))
            return
        }
        if (requestGeneration.get() != activeGeneration) {
            onFinished(EnabledSyncCompletion(0L, null))
            return
        }
        val snapshot = runCatching {
            NoRootSupportStore.upsertEnabledSnapshot(appContext, bridge)
        }.getOrNull()
        if (snapshot == null) {
            if (requestGeneration.get() != activeGeneration ||
                !NoRootSupportStore.isDesiredEnabled(appContext)
            ) {
                onFinished(EnabledSyncCompletion(0L, null))
                return
            }
            NoRootSupportStore.updateSyncState(
                appContext,
                NoRootSupportStore.SyncState.ERROR,
                detail = "snapshot_write_failed",
                stillCurrent = { requestGeneration.get() == activeGeneration }
            )
            onFinished(EnabledSyncCompletion(0L, null))
            return
        }
        if (requestGeneration.get() != activeGeneration ||
            !NoRootSupportStore.isDesiredEnabled(appContext)
        ) {
            onFinished(EnabledSyncCompletion(snapshot.revision, null))
            return
        }
        NoRootSupportStore.updateSyncState(
            appContext,
            NoRootSupportStore.SyncState.SYNCING,
            revision = snapshot.revision,
            stillCurrent = { requestGeneration.get() == activeGeneration }
        )
        enqueueRemoteSync(
            context = appContext,
            snapshot = snapshot,
            generation = activeGeneration,
            resultHandler = { result ->
                applyEnabledSyncResult(appContext, snapshot, activeGeneration, result)
            },
            listener = { result ->
                onFinished(EnabledSyncCompletion(snapshot.revision, result))
            }
        )
    }

    /**
     * 打开宿主应用详情前的有界落盘屏障。开启态确保当前 revision 已写入 NPatch；
     * 关闭态确保更高 revision 的 tombstone 已写入。失败或超时也会明确回调，绝不阻塞 UI。
     */
    fun flushBeforeRestart(
        context: Context,
        bridge: YukiHookPrefsBridge,
        timeoutMillis: Long = DEFAULT_RESTART_FLUSH_TIMEOUT_MS,
        onFinished: (FlushResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val boundedTimeout = timeoutMillis.coerceIn(1L, 30_000L)
        Thread({
            val generation = requestGeneration.get()
            val expectedEnabled = NoRootSupportStore.isDesiredEnabled(appContext)
            val deadlineNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(boundedTimeout)
            val result = if (expectedEnabled) {
                flushEnabledUntilStable(appContext, bridge, generation, deadlineNanos)
            } else {
                flushDisabledSnapshot(appContext, generation, deadlineNanos)
            }
            runCatching { onFinished(result) }
        }, "InnocentLab-NoRootFlush").apply { isDaemon = true }.start()
    }

    /** 设置在等待期间继续变化时，使用同一总截止时间同步新 revision，直到源 prefs 稳定。 */
    private fun flushEnabledUntilStable(
        appContext: Context,
        bridge: YukiHookPrefsBridge,
        generation: Long,
        deadlineNanos: Long
    ): FlushResult {
        if (AndroidVersion.isLessThan(AndroidVersion.P)) return FlushResult.FAILED
        while (remainingNanos(deadlineNanos) > 0L) {
            if (requestGeneration.get() != generation ||
                !NoRootSupportStore.isDesiredEnabled(appContext)
            ) return FlushResult.FAILED

            val completed = CountDownLatch(1)
            val completionRef = AtomicReference<EnabledSyncCompletion?>()
            synchronizeWithResult(appContext, bridge, generation) { completion ->
                completionRef.set(completion)
                completed.countDown()
            }
            if (!awaitUntil(completed, deadlineNanos)) return FlushResult.TIMED_OUT

            val completion = completionRef.get() ?: return FlushResult.FAILED
            if (requestGeneration.get() != generation ||
                !NoRootSupportStore.isDesiredEnabled(appContext)
            ) return FlushResult.FAILED
            when (completion.remoteResult) {
                NPatchRemoteGateway.SyncResult.ConnectionTimeout ->
                    return FlushResult.TIMED_OUT
                NPatchRemoteGateway.SyncResult.Success -> {
                    val refreshedSnapshot = runCatching {
                        NoRootSupportStore.upsertEnabledSnapshot(appContext, bridge)
                    }.getOrNull() ?: return FlushResult.FAILED
                    if (refreshedSnapshot.revision != completion.revision) {
                        // 等待期间设置已变化；新 revision 已落盘，继续使用剩余时间同步。
                        continue
                    }
                    return if (acceptsFlushCompletion(
                            appContext = appContext,
                            generation = generation,
                            expectedEnabled = true,
                            expectedRevision = completion.revision,
                            remoteWriteConfirmed = NoRootSupportStore.isRemoteSynced(
                                appContext,
                                completion.revision
                            )
                        )
                    ) FlushResult.SUCCESS else FlushResult.FAILED
                }
                null -> {
                    val currentRevision = NoRootSupportStore.readSnapshot(appContext)?.revision
                        ?: return FlushResult.FAILED
                    if (completion.revision > 0L &&
                        currentRevision != completion.revision
                    ) continue
                    return FlushResult.FAILED
                }
                else -> return FlushResult.FAILED
            }
        }
        return FlushResult.TIMED_OUT
    }

    private fun flushDisabledSnapshot(
        appContext: Context,
        generation: Long,
        deadlineNanos: Long
    ): FlushResult {
        val tombstone = NoRootSupportStore.readSnapshot(appContext)
            ?.takeUnless { it.enabled }
            ?: return FlushResult.FAILED
        val completed = CountDownLatch(1)
        val resultRef = AtomicReference<NPatchRemoteGateway.SyncResult?>()
        enqueueRemoteSync(
            context = appContext,
            snapshot = tombstone,
            generation = generation,
            resultHandler = {},
            listener = { result ->
                resultRef.set(result)
                completed.countDown()
            }
        )
        if (!awaitUntil(completed, deadlineNanos)) return FlushResult.TIMED_OUT
        val remoteResult = resultRef.get()
        if (remoteResult == NPatchRemoteGateway.SyncResult.ConnectionTimeout) {
            return FlushResult.TIMED_OUT
        }
        return if (acceptsFlushCompletion(
                appContext = appContext,
                generation = generation,
                expectedEnabled = false,
                expectedRevision = tombstone.revision,
                remoteWriteConfirmed = remoteResult == NPatchRemoteGateway.SyncResult.Success
            )
        ) FlushResult.SUCCESS else FlushResult.FAILED
    }

    private fun awaitUntil(latch: CountDownLatch, deadlineNanos: Long): Boolean {
        val remaining = remainingNanos(deadlineNanos)
        if (remaining <= 0L) return false
        return try {
            latch.await(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private fun acceptsFlushCompletion(
        appContext: Context,
        generation: Long,
        expectedEnabled: Boolean,
        expectedRevision: Long,
        remoteWriteConfirmed: Boolean
    ): Boolean {
        val snapshot = NoRootSupportStore.readSnapshot(appContext)
        return NoRootRestartFlushGuard.accepts(
            generationMatches = requestGeneration.get() == generation,
            desiredEnabled = NoRootSupportStore.isDesiredEnabled(appContext),
            expectedEnabled = expectedEnabled,
            snapshotEnabled = snapshot?.enabled,
            snapshotRevision = snapshot?.revision ?: 0L,
            expectedRevision = expectedRevision,
            remoteWriteConfirmed = remoteWriteConfirmed
        )
    }

    private fun enqueueRemoteSync(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        generation: Long,
        resultHandler: (NPatchRemoteGateway.SyncResult) -> Unit,
        listener: ((NPatchRemoteGateway.SyncResult?) -> Unit)?
    ) {
        val key = NoRootSyncFlightRegistry.Key(
            intentGeneration = generation,
            snapshotRevision = snapshot.revision,
            enabled = snapshot.enabled
        )
        val stillCurrent = {
            requestGeneration.get() == generation &&
                NoRootSupportStore.isDesiredEnabled(context) == snapshot.enabled &&
                NoRootSupportStore.readSnapshot(context)?.let { current ->
                    current.enabled == snapshot.enabled && current.revision == snapshot.revision
                } == true
        }
        if (!runCatching(stillCurrent).getOrDefault(false)) {
            listener?.let { notifyListeners(listOf(it), result = null) }
            return
        }

        val registration = syncFlights.register(key, resultHandler, listener)
        notifyListeners(registration.displacedListeners, result = null)
        if (!registration.startsFlight) return
        val token = registration.token
        registration.displacedToken?.let(::cancelWatchdog)
        flightWatchdogs[token] = watchdogExecutor.schedule(
            { completeFlight(token, NPatchRemoteGateway.SyncResult.ConnectionTimeout) },
            FLIGHT_WATCHDOG_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
        if (!syncFlights.isCurrent(token) ||
            !runCatching(stillCurrent).getOrDefault(false)
        ) {
            cancelWatchdog(token)
            notifyListeners(syncFlights.cancel(token), result = null)
            return
        }

        runCatching {
            NPatchRemoteGateway.syncAsync(
                context = context,
                snapshot = snapshot,
                stillCurrent = {
                    syncFlights.isCurrent(token) &&
                        runCatching(stillCurrent).getOrDefault(false)
                }
            ) { result ->
                completeFlight(token, result)
            }
        }.onFailure { throwable ->
            completeFlight(
                token,
                NPatchRemoteGateway.SyncResult.Failure(
                    throwable.javaClass.simpleName.ifBlank { "Unknown" }
                )
            )
        }
    }

    private fun completeFlight(
        token: NoRootSyncFlightRegistry.Token,
        result: NPatchRemoteGateway.SyncResult
    ) {
        cancelWatchdog(token)
        val completion = syncFlights.takeCompletion(token) ?: return
        runCatching { completion.resultHandler(result) }
        notifyListeners(completion.listeners, result)
    }

    private fun notifyListeners(
        listeners: List<(NPatchRemoteGateway.SyncResult?) -> Unit>,
        result: NPatchRemoteGateway.SyncResult?
    ) {
        listeners.forEach { listener -> runCatching { listener(result) } }
    }

    private fun cancelWatchdog(token: NoRootSyncFlightRegistry.Token) {
        flightWatchdogs.remove(token)?.cancel(false)
    }

    private fun cancelAllWatchdogs() {
        flightWatchdogs.values.forEach { future -> future.cancel(false) }
        flightWatchdogs.clear()
    }

    private fun applyEnabledSyncResult(
        appContext: Context,
        snapshot: NoRootConfigSnapshot,
        activeGeneration: Long,
        result: NPatchRemoteGateway.SyncResult
    ) {
        if (requestGeneration.get() != activeGeneration ||
            !NoRootSupportStore.isDesiredEnabled(appContext)
        ) return
        val state = when (result) {
            NPatchRemoteGateway.SyncResult.Success -> {
                if (!NoRootSupportStore.markRemoteSynced(
                        appContext,
                        snapshot.revision,
                        stillCurrent = { requestGeneration.get() == activeGeneration }
                    )
                ) {
                    NoRootSupportStore.SyncState.ERROR
                } else {
                    successfulSyncState(appContext, snapshot)
                }
            }
            NPatchRemoteGateway.SyncResult.ManagerMissing ->
                NoRootSupportStore.SyncState.MANAGER_MISSING
            NPatchRemoteGateway.SyncResult.ModuleNotRegistered ->
                NoRootSupportStore.SyncState.MODULE_NOT_REGISTERED
            NPatchRemoteGateway.SyncResult.ConnectionTimeout ->
                NoRootSupportStore.SyncState.CONNECTION_TIMEOUT
            is NPatchRemoteGateway.SyncResult.Failure ->
                NoRootSupportStore.SyncState.ERROR
        }
        val detail = (result as? NPatchRemoteGateway.SyncResult.Failure)?.errorClass
        NoRootSupportStore.updateSyncState(
            appContext,
            state,
            revision = snapshot.revision,
            detail = detail,
            stillCurrent = { requestGeneration.get() == activeGeneration }
        )
    }

    private fun successfulSyncState(
        appContext: Context,
        snapshot: NoRootConfigSnapshot
    ): NoRootSupportStore.SyncState {
        val status = NoRootSupportStore.readStatus(appContext)
        val targetInfo = runCatching {
            appContext.packageManager.getPackageInfo(NoRootSupportState.TARGET_PACKAGE, 0)
        }.getOrNull()
        val currentTargetVersionCode = targetInfo?.versionCodeCompat ?: 0L
        val heartbeatMatches =
            status.heartbeatRevision == snapshot.revision &&
                status.heartbeatModuleVersion == snapshot.moduleVersionCode &&
                status.heartbeatTargetPackage == NoRootSupportState.TARGET_PACKAGE &&
                currentTargetVersionCode > 0L &&
                status.heartbeatTargetVersion == currentTargetVersionCode &&
                status.heartbeatTargetUpdateTime == (targetInfo?.lastUpdateTime ?: 0L)
        return if (heartbeatMatches) NoRootSupportStore.SyncState.ACTIVE
        else NoRootSupportStore.SyncState.RESTART_REQUIRED
    }
}
