package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅由已校验 Binder UID 的 Provider 调用；不接受调用方提供的版本或遥测载荷。 */
internal object TelemetryVersionTrigger {
    const val METHOD = "telemetry_host_ready"
    private val lock = Any()
    private var lastSignalElapsed = -60_000L

    fun handle(context: Context): Boolean {
        // 不允许同进程主线程调用等待自身的回执回调。
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSignalElapsed < 60_000L) return false
            lastSignalElapsed = now
        }
        if (!TelemetryStore.isEnabledForUpload(context)) return false
        val finished = CountDownLatch(1)
        // Provider 客户端引用保持到本次有界工作完成；不拉起 Activity 或常驻服务。
        TelemetryCoordinator.maybeUpload(context, versionChange = true) { finished.countDown() }
        return runCatching { finished.await(20, TimeUnit.SECONDS) }.getOrDefault(false)
    }
}
