package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 模块冷启动时的有界免 Root 快照自愈。
 *
 * 当前版本快照仍由 [NoRootSupportStore] 严格校验；这里只在用户意图保持开启，且快照因版本失效
 * 或尚未成功同步到 Remote Store 时，通过现有 Controller 重建/重试。每个模块进程最多尝试两次，
 * 第二次位于 NPatch 15 秒连接熔断窗口之后，不做定时轮询，也不持有 Activity 或 View。
 */
internal object NoRootUpgradeRecoveryCoordinator {
    private const val TAG = "BilibiliInnocentLab"
    private const val RECEIVER_SAFE_TIMEOUT_MS = 8_000L
    private const val ATTEMPT_RESULT_TIMEOUT_MS = 12_000L
    private const val RETRY_DELAY_MS = 16_000L
    internal const val MAX_ATTEMPTS_PER_PROCESS = 2

    internal enum class RecoveryPhase {
        NOT_STARTED,
        RUNNING,
        READY,
        RETRY_WAIT,
        EXHAUSTED
    }

    private data class Attempt(
        val id: Long,
        val context: Context,
        val bridge: SharedPreferences,
        val authorized: Boolean
    )

    private val stateLock = Any()
    private var phase = RecoveryPhase.NOT_STARTED
    private var attemptsStarted = 0
    private var activeAttemptId = 0L
    private var retryNotBeforeElapsedMs = 0L
    private var recoveryContext: Context? = null
    private var recoveryBridge: SharedPreferences? = null
    private var recoveryAuthorized = false
    private var startRequested = false
    private var nextWaiterId = 0L
    private val attemptWaiters = linkedMapOf<Long, () -> Unit>()

    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }
    private val settlementExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_ATTEMPTS_PER_PROCESS * 2),
            { runnable ->
                Thread(runnable, "BIL-NPatch-Upgrade-Settlement").apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy()
        ).apply { allowCoreThreadTimeOut(true) }
    }
    private val retryAction = Runnable { pokeIfRetryable() }

    /**
     * 只登记恢复所需的进程级依赖，不在 Application.onCreate 中抢先读取或写入快照。
     * 真正启动由覆盖升级 Receiver 或已经完成当前 envelope 构造的宿主查询触发。
     */
    fun initialize(
        context: Context,
        bridge: SharedPreferences,
        authorized: Boolean
    ) {
        val pendingAttempt = synchronized(stateLock) {
            if (recoveryContext != null || phase != RecoveryPhase.NOT_STARTED) return
            recoveryContext = context.applicationContext
            recoveryBridge = bridge
            recoveryAuthorized = authorized
            if (startRequested) beginAttemptLocked() else null
        }
        if (pendingAttempt != null) postAttemptLaunch(pendingAttempt)
    }

    /** 覆盖升级广播没有宿主 800ms 查询窗口，可在登记 waiter 前直接开始首次恢复。 */
    fun startPendingAttempt() {
        val attempt = synchronized(stateLock) {
            if (phase != RecoveryPhase.NOT_STARTED || attemptsStarted != 0) return
            startRequested = true
            beginAttemptLocked()
        } ?: return
        launchAttempt(attempt)
    }

    /**
     * Provider/广播查询路径的非阻塞触发器。当前 envelope 已由调用方先行构造；首次恢复或
     * 唯一一次到期重试都只投递到主队列，任务运行中、已成功或达到上限时立即返回。
     */
    fun pokeIfRetryable() {
        val now = SystemClock.elapsedRealtime()
        val attempt = synchronized(stateLock) {
            if (!shouldStartAttempt(
                    phase = phase,
                    attemptsStarted = attemptsStarted,
                    nowElapsedMs = now,
                    retryNotBeforeElapsedMs = retryNotBeforeElapsedMs
                )
            ) return
            startRequested = true
            beginAttemptLocked()
        } ?: return
        postAttemptLaunch(attempt)
    }

    /**
     * 只等待当前尝试结束，供 [android.content.BroadcastReceiver.goAsync] 保活。
     * 8 秒超时只移除并通知该 PendingResult 对应的 listener，不改变恢复阶段或重试资格。
     */
    fun awaitCurrentAttempt(listener: () -> Unit) {
        val waiterId = synchronized(stateLock) {
            if (phase != RecoveryPhase.RUNNING) {
                null
            } else {
                (++nextWaiterId).also { attemptWaiters[it] = listener }
            }
        }
        if (waiterId == null) {
            runCatching(listener)
            return
        }
        mainHandler.postDelayed(
            { releaseWaiter(waiterId) },
            RECEIVER_SAFE_TIMEOUT_MS
        )
    }

    private fun beginAttemptLocked(): Attempt? {
        if (attemptsStarted >= MAX_ATTEMPTS_PER_PROCESS) return null
        val context = recoveryContext ?: return null
        val bridge = recoveryBridge ?: return null
        attemptsStarted += 1
        activeAttemptId += 1L
        phase = RecoveryPhase.RUNNING
        startRequested = false
        retryNotBeforeElapsedMs = 0L
        return Attempt(activeAttemptId, context, bridge, recoveryAuthorized)
    }

    /** Provider 查询线程和 initialize 补发路径都只投递，不直接竞争 Store ioLock。 */
    private fun postAttemptLaunch(attempt: Attempt) {
        mainHandler.removeCallbacks(retryAction)
        if (!mainHandler.post { launchAttempt(attempt) }) {
            settleAttemptAsFailure(attempt.id)
        }
    }

    private fun launchAttempt(attempt: Attempt) {
        // 与 Receiver 的 8 秒 PendingResult 释放窗口相互独立；即使底层单飞回调丢失，
        // 也只会按当前严格 exportState 把本次尝试结算为可重试失败，不会误标成功。
        mainHandler.postDelayed(
            { requestSettlement(attempt.id) },
            ATTEMPT_RESULT_TIMEOUT_MS
        )
        runCatching {
            Thread({
                try {
                    if (!stillNeedsRecovery(attempt)) {
                        requestSettlement(attempt.id)
                        return@Thread
                    }
                    val generation = NoRootSupportController.beginSynchronization(
                        attempt.context
                    )
                    if (generation == null) {
                        requestSettlement(attempt.id)
                        return@Thread
                    }
                    NoRootSupportController.synchronize(
                        context = attempt.context,
                        bridge = attempt.bridge,
                        generation = generation,
                        onFinished = { requestSettlement(attempt.id) }
                    )
                } catch (throwable: Throwable) {
                    Log.w(TAG, "recover no-root snapshot after module upgrade failed", throwable)
                    requestSettlement(attempt.id)
                }
            }, "BIL-NPatch-Upgrade-Recovery-${attempt.id}").apply {
                isDaemon = true
            }.start()
        }.onFailure { throwable ->
            Log.w(TAG, "schedule no-root upgrade recovery failed", throwable)
            requestSettlement(attempt.id)
        }
    }

    /** 所有可能读取 AtomicFile 的尝试结算都离开主线程；任务总数受两次尝试严格限制。 */
    private fun requestSettlement(attemptId: Long) {
        try {
            settlementExecutor.execute { settleAttempt(attemptId) }
        } catch (rejected: RejectedExecutionException) {
            Log.w(TAG, "schedule no-root recovery settlement failed", rejected)
            settleAttemptAsFailure(attemptId)
        }
    }

    private fun settleAttempt(attemptId: Long) {
        val attempt = synchronized(stateLock) {
            if (phase != RecoveryPhase.RUNNING || activeAttemptId != attemptId) return
            val context = recoveryContext ?: return
            Attempt(attemptId, context, recoveryBridge ?: return, recoveryAuthorized)
        }
        val stillNeedsRecovery = runCatching { stillNeedsRecovery(attempt) }
            .getOrDefault(true)
        completeAttempt(attemptId, stillNeedsRecovery)
    }

    /** 执行器拒绝任务时只按失败推进有界状态机，不在调用线程读取磁盘。 */
    private fun settleAttemptAsFailure(attemptId: Long) {
        completeAttempt(attemptId, stillNeedsRecovery = true)
    }

    private fun completeAttempt(attemptId: Long, stillNeedsRecovery: Boolean) {
        val completion = synchronized(stateLock) {
            if (phase != RecoveryPhase.RUNNING || activeAttemptId != attemptId) return
            val transition = transitionAfterAttempt(
                stillNeedsRecovery = stillNeedsRecovery,
                attemptsStarted = attemptsStarted
            )
            phase = transition
            if (transition == RecoveryPhase.RETRY_WAIT) {
                retryNotBeforeElapsedMs = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
            } else {
                retryNotBeforeElapsedMs = 0L
            }
            transition to attemptWaiters.values.toList().also { attemptWaiters.clear() }
        }
        val (transition, listeners) = completion
        listeners.forEach { listener -> runCatching(listener) }
        if (transition == RecoveryPhase.RETRY_WAIT) {
            mainHandler.removeCallbacks(retryAction)
            mainHandler.postDelayed(retryAction, RETRY_DELAY_MS)
        } else {
            mainHandler.removeCallbacks(retryAction)
        }
    }

    private fun stillNeedsRecovery(attempt: Attempt): Boolean {
        val desiredEnabled = NoRootSupportStore.isDesiredEnabled(attempt.context)
        val exported = NoRootSupportStore.exportState(
            attempt.context,
            attempt.authorized
        )
        // Remote Store 标记只决定升级自愈是否需要重试，不改变 Provider/广播对宿主返回的
        // 授权结果；当前 enabled revision 必须确认写入远端后才结束本协调器。
        val remoteSynced = exported.enabled && exported.revision > 0L &&
            NoRootSupportStore.isRemoteSynced(attempt.context, exported.revision)
        val hasReadySnapshot = isRecoveryExportReady(
            exportValid = exported.valid,
            exportEnabled = exported.enabled,
            revision = exported.revision,
            remoteSynced = remoteSynced
        )
        return shouldRecoverSnapshot(
            authorized = attempt.authorized,
            desiredEnabled = desiredEnabled,
            hasReadySnapshot = hasReadySnapshot
        )
    }

    private fun releaseWaiter(waiterId: Long) {
        val listener = synchronized(stateLock) { attemptWaiters.remove(waiterId) }
        if (listener != null) runCatching(listener)
    }

    internal fun shouldRecoverSnapshot(
        authorized: Boolean,
        desiredEnabled: Boolean,
        hasReadySnapshot: Boolean
    ): Boolean = authorized && desiredEnabled && !hasReadySnapshot

    internal fun isRecoveryExportReady(
        exportValid: Boolean,
        exportEnabled: Boolean,
        revision: Long,
        remoteSynced: Boolean
    ): Boolean = exportValid &&
        (!exportEnabled || (revision > 0L && remoteSynced))

    internal fun transitionAfterAttempt(
        stillNeedsRecovery: Boolean,
        attemptsStarted: Int
    ): RecoveryPhase = when {
        !stillNeedsRecovery -> RecoveryPhase.READY
        attemptsStarted < MAX_ATTEMPTS_PER_PROCESS -> RecoveryPhase.RETRY_WAIT
        else -> RecoveryPhase.EXHAUSTED
    }

    internal fun shouldStartRetry(
        phase: RecoveryPhase,
        attemptsStarted: Int,
        nowElapsedMs: Long,
        retryNotBeforeElapsedMs: Long
    ): Boolean = phase == RecoveryPhase.RETRY_WAIT &&
        attemptsStarted < MAX_ATTEMPTS_PER_PROCESS &&
        retryNotBeforeElapsedMs > 0L &&
        nowElapsedMs >= retryNotBeforeElapsedMs

    internal fun shouldStartAttempt(
        phase: RecoveryPhase,
        attemptsStarted: Int,
        nowElapsedMs: Long,
        retryNotBeforeElapsedMs: Long
    ): Boolean = (phase == RecoveryPhase.NOT_STARTED && attemptsStarted == 0) ||
        shouldStartRetry(
            phase = phase,
            attemptsStarted = attemptsStarted,
            nowElapsedMs = nowElapsedMs,
            retryNotBeforeElapsedMs = retryNotBeforeElapsedMs
        )
}
