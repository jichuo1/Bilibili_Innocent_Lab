package com.Bilibili_Innocent_Lab.xposedmodule.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootUpgradeRecoveryCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isStandardPublisherBypass
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 覆盖安装后唤起模块进程。条款迁移、授权镜像以及免 Root 快照自愈统一由
 * DefaultApplication.onCreate 启动；接收器在后台有界等待免 Root 恢复与
 * 标准框架重发布（最长 3 秒），不阻塞主线程、不泄漏监听也不启动界面。
 */
class TermsMirrorRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        val pendingResult = goAsync()
        val coordinator = TermsMirrorFlushCoordinator(
            isNoRootEnabled = { NoRootSupportStore.isDesiredEnabled(appContext) },
            startNoRootRecovery = NoRootUpgradeRecoveryCoordinator::startPendingAttempt,
            awaitNoRootRecovery = NoRootUpgradeRecoveryCoordinator::awaitCurrentAttempt,
            addPublishListener = RemoteHookConfigStore::addPublishListener,
            removePublishListener = RemoteHookConfigStore::removePublishListener,
            requestStandardFlush = { RemoteHookConfigStore.requestAuthorityRefresh(appContext) },
            readDiagnostics = RemoteHookConfigStore::diagnostics,
            scheduleTimeout = { delayMs, onTimeout ->
                val future = timeoutScheduler.schedule(onTimeout, delayMs, TimeUnit.MILLISECONDS)
                val cancel: () -> Unit = { future.cancel(false) }
                cancel
            },
            runAsync = flushExecutor::execute,
            onFinish = { runCatching { pendingResult.finish() } }
        )
        runCatching { coordinator.start() }.onFailure { coordinator.forceFinish() }
    }

    private companion object {
        val flushExecutor = HostReceiptWire.executor("bil-terms-refresh-flush")
        val timeoutScheduler: ScheduledThreadPoolExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "BIL-TermsRefresh-Timeout").apply { isDaemon = true }
            }.apply {
                removeOnCancelPolicy = true
                setKeepAliveTime(5L, TimeUnit.SECONDS)
                allowCoreThreadTimeOut(true)
            }
        }
    }
}

internal class TermsMirrorFlushCoordinator(
    private val isNoRootEnabled: () -> Boolean,
    private val startNoRootRecovery: () -> Unit,
    private val awaitNoRootRecovery: (() -> Unit) -> Unit,
    private val addPublishListener: (RemoteHookConfigPublishListener) -> Unit,
    private val removePublishListener: (RemoteHookConfigPublishListener) -> Unit,
    private val requestStandardFlush: () -> Unit,
    private val readDiagnostics: () -> RemoteHookConfigDiagnostics,
    private val scheduleTimeout: (delayMs: Long, onTimeout: () -> Unit) -> () -> Unit,
    private val runAsync: (Runnable) -> Unit,
    private val onFinish: () -> Unit
) {
    private val finished = AtomicBoolean(false)
    private val noRootDone = AtomicBoolean(false)
    private val standardDone = AtomicBoolean(false)
    private val cancelTimeoutRef = AtomicReference<(() -> Unit)?>(null)
    private val listenerRef = AtomicReference<RemoteHookConfigPublishListener?>(null)

    fun start() {
        runCatching { startNoRootRecovery() }
        val noRootCallbackScheduled = runCatching {
            awaitNoRootRecovery { completeNoRoot() }
        }.isSuccess
        if (!noRootCallbackScheduled) {
            completeNoRoot()
        }

        val noRootSelected = runCatching { isNoRootEnabled() }.getOrDefault(false)
        if (noRootSelected) {
            completeStandard()
            return
        }

        val listener = RemoteHookConfigPublishListener { event ->
            val result = event.result
            val terminal = result is RemoteHookConfigPublishResult.Success ||
                (result is RemoteHookConfigPublishResult.Failure && result.isStandardPublisherBypass)
            if (terminal) {
                completeStandard()
            }
        }
        listenerRef.set(listener)
        addPublishListener(listener)

        val cancelHandle = runCatching {
            scheduleTimeout(STANDARD_FLUSH_TIMEOUT_MS) { completeStandard() }
        }.getOrNull()
        if (cancelHandle == null) {
            completeStandard()
            return
        }
        if (standardDone.get()) {
            cancelHandle()
            return
        }
        cancelTimeoutRef.set(cancelHandle)
        if (standardDone.get()) {
            cancelTimeoutRef.getAndSet(null)?.invoke()
            return
        }

        val submitted = runCatching {
            runAsync(Runnable {
                runCatching { requestStandardFlush() }
                val diag = runCatching { readDiagnostics() }.getOrNull()
                if (diag != null && !diag.publishPending &&
                    (diag.state == RemoteHookConfigPublishState.READY ||
                        diag.state == RemoteHookConfigPublishState.BYPASSED)
                ) {
                    completeStandard()
                }
            })
        }.isSuccess
        if (!submitted) {
            completeStandard()
        }
    }

    fun forceFinish() {
        completeStandard()
        completeNoRoot()
    }

    private fun completeNoRoot() {
        if (noRootDone.compareAndSet(false, true)) {
            maybeFinish()
        }
    }

    private fun completeStandard() {
        if (standardDone.compareAndSet(false, true)) {
            listenerRef.getAndSet(null)?.let { runCatching { removePublishListener(it) } }
            cancelTimeoutRef.getAndSet(null)?.invoke()
            maybeFinish()
        }
    }

    private fun maybeFinish() {
        if (noRootDone.get() && standardDone.get() && finished.compareAndSet(false, true)) {
            runCatching { onFinish() }
        }
    }

    companion object {
        internal const val STANDARD_FLUSH_TIMEOUT_MS = 3_000L
    }
}
