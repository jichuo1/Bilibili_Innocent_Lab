package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 模块诊断页发起的一次性、有界宿主回执查询。 */
internal object HostRuntimeDiagnosticsQueryClient {
    private const val QUERY_TIMEOUT_MS = 1_500L

    enum class Status { READY, TARGET_UNAVAILABLE, INVALID_RESPONSE }

    data class Result(
        val status: Status,
        val snapshot: HostRuntimeDiagnosticsSnapshot? = null
    )

    private val validationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bil-host-diagnostics-query").apply { isDaemon = true }
    }

    fun query(context: Context, callback: (Result) -> Unit) {
        val appContext = context.applicationContext ?: context
        val mainHandler = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        val nonce = UUID.randomUUID().toString()

        fun deliver(result: Result) {
            if (Looper.myLooper() == Looper.getMainLooper()) callback(result)
            else mainHandler.post { callback(result) }
        }

        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                deliver(Result(Status.TARGET_UNAVAILABLE))
            }
        }
        mainHandler.postDelayed(timeout, QUERY_TIMEOUT_MS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!completed.compareAndSet(false, true)) return
                mainHandler.removeCallbacks(timeout)
                val extras = getResultExtras(false)
                if (resultCode != HostRuntimeDiagnosticsQueryContract.RESULT_CODE_HANDLED ||
                    extras == null ||
                    !extras.getBoolean(HostRuntimeDiagnosticsQueryContract.EXTRA_HANDLED, false) ||
                    extras.getString(HostRuntimeDiagnosticsQueryContract.EXTRA_REQUEST_NONCE) != nonce ||
                    extras.getString(HostRuntimeDiagnosticsQueryContract.EXTRA_STATUS) !=
                    HostRuntimeDiagnosticsQueryContract.STATUS_READY
                ) {
                    deliver(Result(Status.INVALID_RESPONSE))
                    return
                }
                validationExecutor.execute { deliver(validate(appContext, extras)) }
            }
        }
        val request = Intent(HostRuntimeDiagnosticsQueryContract.ACTION_QUERY)
            .setPackage(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE)
            .putExtra(
                HostRuntimeDiagnosticsQueryContract.EXTRA_PROTOCOL_VERSION,
                HostRuntimeDiagnosticsQueryContract.PROTOCOL_VERSION
            )
            .putExtra(HostRuntimeDiagnosticsQueryContract.EXTRA_REQUEST_NONCE, nonce)
        runCatching {
            CrossAppBroadcastCompat.sendOrderedBroadcast(
                context = appContext,
                intent = request,
                resultReceiver = receiver,
                scheduler = mainHandler,
                initialCode = HostRuntimeDiagnosticsQueryContract.RESULT_CODE_UNHANDLED
            )
        }.onFailure {
            if (completed.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                deliver(Result(Status.TARGET_UNAVAILABLE))
            }
        }
    }

    private fun validate(context: Context, extras: android.os.Bundle): Result {
        val payload = extras.getString(
            HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD
        ).orEmpty()
        val digest = extras.getString(
            HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD_SHA256
        ).orEmpty()
        if (!HostRuntimeDiagnosticsQueryContract.digestMatches(payload, digest)) {
            return Result(Status.INVALID_RESPONSE)
        }
        val snapshot = HostRuntimeDiagnosticsCodec.decodeOrNull(payload)
            ?: return Result(Status.INVALID_RESPONSE)
        val source = HostRuntimeDiagnosticsSource(
            targetVersionCode = extras.getLong(
                HostRuntimeDiagnosticsQueryContract.EXTRA_TARGET_VERSION, 0L
            ),
            targetUpdateTime = extras.getLong(
                HostRuntimeDiagnosticsQueryContract.EXTRA_TARGET_UPDATE_TIME, 0L
            ),
            moduleVersionCode = extras.getLong(
                HostRuntimeDiagnosticsQueryContract.EXTRA_MODULE_VERSION, 0L
            )
        )
        if (!source.isComplete || source.moduleVersionCode != BuildConfig.VERSION_CODE.toLong()) {
            return Result(Status.INVALID_RESPONSE)
        }
        val current = currentTargetSource(context) ?: return Result(Status.INVALID_RESPONSE)
        if (source.targetVersionCode != current.targetVersionCode ||
            source.targetUpdateTime != current.targetUpdateTime
        ) return Result(Status.INVALID_RESPONSE)
        return Result(Status.READY, snapshot)
    }

    private fun currentTargetSource(context: Context): HostRuntimeDiagnosticsSource? = runCatching {
        val info = context.packageManager.getPackageInfo(
            HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE, 0
        )
        HostRuntimeDiagnosticsSource(
            targetVersionCode = info.versionCodeCompat(),
            targetUpdateTime = info.lastUpdateTime,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
        ).takeIf { it.isComplete }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
