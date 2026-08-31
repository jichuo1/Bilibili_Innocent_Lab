package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.provider.RoamingCompatProvider
import com.Bilibili_Innocent_Lab.xposedmodule.receiver.RoamingOpenReceiver
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** 宿主侧异步上报；Provider 不可见时回退到有 caller proof 的显式广播。 */
internal object MineComponentSnapshotReporter {
    private const val PROOF_REQUEST_CODE = 0x4d49
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bil-mine-snapshot").apply { isDaemon = true }
    }
    private val lastAcceptedPayload = AtomicReference<String?>(null)

    fun report(context: Context, payload: String) {
        if (payload.isBlank() || payload == lastAcceptedPayload.get()) return
        val appContext = context.applicationContext ?: context
        executor.execute {
            if (payload == lastAcceptedPayload.get()) return@execute
            if (MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false) == null) {
                return@execute
            }
            if (reportViaProvider(appContext, payload) || reportViaBroadcast(appContext, payload)) {
                lastAcceptedPayload.set(payload)
            }
        }
    }

    private fun reportViaProvider(context: Context, payload: String): Boolean = runCatching {
        context.contentResolver.call(
            RoamingCompatProvider.CONTENT_URI,
            RoamingCompatProvider.METHOD_REPORT_MINE_COMPONENT_SNAPSHOT,
            null,
            Bundle().apply {
                putString(RoamingCompatProvider.EXTRA_MINE_COMPONENT_SNAPSHOT, payload)
            }
        )?.getBoolean(RoamingCompatProvider.RESULT_MINE_COMPONENT_ACCEPTED, false) == true
    }.getOrDefault(false)

    private fun reportViaBroadcast(context: Context, payload: String): Boolean = runCatching {
        val proof = PendingIntent.getBroadcast(
            context,
            PROOF_REQUEST_CODE,
            Intent("${context.packageName}.INNOCENT_LAB_MINE_SNAPSHOT_PROOF")
                .setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val report = Intent(RoamingOpenReceiver.ACTION_REPORT_MINE_COMPONENT_SNAPSHOT)
            .setComponent(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    "${BuildConfig.APPLICATION_ID}.receiver.RoamingOpenReceiver"
                )
            )
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(RoamingOpenReceiver.EXTRA_MINE_SNAPSHOT_CALLER_PROOF, proof)
            .putExtra(RoamingCompatProvider.EXTRA_MINE_COMPONENT_SNAPSHOT, payload)
        CrossAppBroadcastCompat.sendBroadcast(context, report)
        true
    }.getOrDefault(false)
}
