package com.Bilibili_Innocent_Lab.xposedmodule.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.IntentCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryContract
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryVersionTrigger
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Provider 不可见时的显式通知后备；只接收宿主身份凭据，不接收遥测数据或版本。 */
class TelemetryHostReadyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.TELEMETRY_HOST_READY") return
        val trusted = runCatching {
            val proof = IntentCompat.getParcelableExtra(intent, "proof", PendingIntent::class.java)
                ?: return@runCatching false
            val host = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE
            val uid = context.packageManager.getApplicationInfo(host, 0).uid
            proof.creatorPackage == host && proof.creatorUid == uid &&
                (!AndroidVersion.isAtLeast(AndroidVersion.U) || sentFromUid == uid)
        }.getOrDefault(false)
        if (!trusted || !inFlight.compareAndSet(false, true)) return
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) pending.finish() }
        // Receiver 持有时间严格有界；网络仍有自身连接/读取超时，系统回收时留待下次宿主启动。
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable { finish() }
        handler.postDelayed(timeout, 8_000L)
        worker.execute {
            try { TelemetryVersionTrigger.handle(context.applicationContext) }
            finally {
                inFlight.set(false)
                handler.removeCallbacks(timeout)
                finish()
            }
        }
    }

    private companion object {
        val inFlight = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "module-version-receipt").apply { isDaemon = true }
        }
    }
}
