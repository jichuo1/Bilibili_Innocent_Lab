package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.CrossAppBroadcastCompat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 模块设置进程侧的存活探测客户端（B12）。
 *
 * 一次探测窗口内重复投递同一个 nonce：宿主可能还没完成启动（模块入口晚于广播到达），
 * 重发才有机会拿到回执；窗口结束后按 [HostRuntimeProbeTracker] 的语义结算。
 * 结算回调统一抛回主线程，调用方只负责重新渲染界面。
 */
internal object HostRuntimeProbeClient {
    private const val RESEND_MS = 1_000L
    internal const val RESUME_TIMEOUT_MS = 1_500L
    internal const val VERIFY_TIMEOUT_MS = 15_000L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = HostRuntimeProbeSession(SystemClock::elapsedRealtime) { mainHandler.post(it) }
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "BIL-HostRuntimeProbe").apply { isDaemon = true }
        }

    private var timeoutTask: ScheduledFuture<*>? = null

    fun status(): HostRuntimeProbeStatus = synchronized(lock) { session.status }

    /**
     * 发起一次探测。`verify=true` 表示用户主动验证：超时后结算为
     * [HostRuntimeProbeStatus.NOT_RUNNING]，并由调用方展示修复引导。
     */
    fun probeAsync(
        context: Context,
        verify: Boolean,
        onSettled: (HostRuntimeProbeStatus) -> Unit
    ) {
        val appContext = context.applicationContext ?: context
        val timeoutMs = if (verify) VERIFY_TIMEOUT_MS else RESUME_TIMEOUT_MS
        val nonce = UUID.randomUUID().toString()
        synchronized(lock) {
            val request = session.request(nonce, verify, timeoutMs, onSettled)
            if (request.scheduleTimeout) {
                timeoutTask?.cancel(false)
                timeoutTask = scheduler.schedule(
                    { settleTimeout(request.nonce) }, timeoutMs, TimeUnit.MILLISECONDS
                )
            }
            if (!request.newWindow) return
        }
        dispatch(appContext, nonce)
        scheduler.schedule({ resend(appContext, nonce) }, RESEND_MS, TimeUnit.MILLISECONDS)
    }

    /** 宿主回执入口；nonce 不属于当前窗口时直接丢弃，返回 false 供调用方决定是否记日志。 */
    fun onHostReply(nonce: String, hooksInstalled: Boolean): Boolean {
        synchronized(lock) {
            when (session.onReply(nonce, hooksInstalled)) {
                HostRuntimeProbeTracker.ReplyResult.REJECTED -> {
                    // 过期回执已结算窗口；旧 nonce 被拒时，不能取消另一个仍有效窗口的计时。
                    if (!session.hasPendingRequest) cancelTimeoutLocked()
                    return false
                }
                HostRuntimeProbeTracker.ReplyResult.PENDING -> Unit
                HostRuntimeProbeTracker.ReplyResult.CONFIRMED ->
                    cancelTimeoutLocked()
            }
        }
        return true
    }

    /** 心跳（宿主装完 Hook 后上报）是更强的证据，随时可以确认并结束窗口。 */
    fun markHostAlive() {
        synchronized(lock) {
            session.onHeartbeat()
            cancelTimeoutLocked()
        }
    }

    private fun resend(context: Context, nonce: String) {
        if (!synchronized(lock) { session.isPending(nonce) }) return
        dispatch(context, nonce)
        scheduler.schedule({ resend(context, nonce) }, RESEND_MS, TimeUnit.MILLISECONDS)
    }

    private fun settleTimeout(nonce: String) {
        synchronized(lock) {
            if (session.onTimeout(nonce)) cancelTimeoutLocked()
        }
    }

    /** 与窗口状态共用一把锁，避免旧结算清掉新窗口的任务或回调。 */
    private fun cancelTimeoutLocked() {
        timeoutTask?.cancel(false)
        timeoutTask = null
    }

    private fun dispatch(context: Context, nonce: String) {
        val intent = Intent(HostRuntimeProbeContract.ACTION_PROBE)
            .setPackage(HostRuntimeProbeContract.TARGET_PACKAGE)
            .putExtra(HostRuntimeProbeContract.EXTRA_NONCE, nonce)
        // 必须走 CrossAppBroadcastCompat：API 34+ 的 sentFromPackage 要求发送方显式共享身份。
        runCatching { CrossAppBroadcastCompat.sendBroadcast(context, intent) }
    }
}
