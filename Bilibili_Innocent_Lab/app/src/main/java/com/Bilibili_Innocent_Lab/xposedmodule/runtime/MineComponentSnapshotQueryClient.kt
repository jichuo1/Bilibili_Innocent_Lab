package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 模块设置页发起的一次性、有界扫描结果查询。 */
internal object MineComponentSnapshotQueryClient {
    private const val QUERY_TIMEOUT_MS = 1_500L

    enum class Status {
        READY,
        WAITING_PAGE,
        TARGET_UNAVAILABLE,
        INVALID_RESPONSE,
        STORE_FAILED
    }

    data class Result(
        val status: Status,
        val snapshot: MineComponentSnapshot? = null
    )

    private val validationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bil-mine-query-validate").apply { isDaemon = true }
    }

    fun query(context: Context, callback: (Result) -> Unit) {
        val appContext = context.applicationContext ?: context
        val mainHandler = Handler(Looper.getMainLooper())
        val transportCompleted = AtomicBoolean(false)
        val nonce = UUID.randomUUID().toString()

        fun deliver(result: Result) {
            if (Looper.myLooper() == Looper.getMainLooper()) callback(result)
            else mainHandler.post { callback(result) }
        }

        val timeout = Runnable {
            if (transportCompleted.compareAndSet(false, true)) {
                deliver(Result(Status.TARGET_UNAVAILABLE))
            }
        }
        mainHandler.postDelayed(timeout, QUERY_TIMEOUT_MS)

        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!transportCompleted.compareAndSet(false, true)) return
                mainHandler.removeCallbacks(timeout)
                val extras = getResultExtras(false)
                if (resultCode != MineComponentSnapshotQueryContract.RESULT_CODE_HANDLED ||
                    extras == null ||
                    !extras.getBoolean(MineComponentSnapshotQueryContract.EXTRA_HANDLED, false)
                ) {
                    deliver(Result(Status.TARGET_UNAVAILABLE))
                    return
                }
                if (extras.getString(MineComponentSnapshotQueryContract.EXTRA_REQUEST_NONCE) != nonce) {
                    deliver(Result(Status.INVALID_RESPONSE))
                    return
                }
                when (extras.getString(MineComponentSnapshotQueryContract.EXTRA_STATUS)) {
                    MineComponentSnapshotQueryContract.STATUS_WAITING_PAGE ->
                        deliver(Result(Status.WAITING_PAGE))

                    MineComponentSnapshotQueryContract.STATUS_READY -> validationExecutor.execute {
                        deliver(validateAndStore(appContext, extras))
                    }

                    else -> deliver(Result(Status.INVALID_RESPONSE))
                }
            }
        }

        val request = Intent(MineComponentSnapshotQueryContract.ACTION_QUERY)
            .setPackage(MineComponentSnapshotQueryContract.TARGET_PACKAGE)
            .putExtra(
                MineComponentSnapshotQueryContract.EXTRA_PROTOCOL_VERSION,
                MineComponentSnapshotQueryContract.PROTOCOL_VERSION
            )
            .putExtra(MineComponentSnapshotQueryContract.EXTRA_REQUEST_NONCE, nonce)
        runCatching {
            CrossAppBroadcastCompat.sendOrderedBroadcast(
                context = appContext,
                intent = request,
                resultReceiver = resultReceiver,
                scheduler = mainHandler,
                initialCode = MineComponentSnapshotQueryContract.RESULT_CODE_UNHANDLED
            )
        }.onFailure {
            if (transportCompleted.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                deliver(Result(Status.TARGET_UNAVAILABLE))
            }
        }
    }

    private fun validateAndStore(context: Context, extras: android.os.Bundle): Result {
        val payload = extras.getString(MineComponentSnapshotQueryContract.EXTRA_PAYLOAD).orEmpty()
        val digest = extras.getString(
            MineComponentSnapshotQueryContract.EXTRA_PAYLOAD_SHA256
        ).orEmpty()
        if (!MineComponentSnapshotQueryContract.digestMatches(payload, digest)) {
            return Result(Status.INVALID_RESPONSE)
        }
        val snapshot = MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)
            ?: return Result(Status.INVALID_RESPONSE)
        if (snapshot.processName != MineComponentSnapshotQueryContract.TARGET_PACKAGE ||
            snapshot.entries.isEmpty()
        ) return Result(Status.INVALID_RESPONSE)

        val source = MineComponentSnapshotSource(
            targetVersionCode = extras.getLong(
                MineComponentSnapshotQueryContract.EXTRA_TARGET_VERSION,
                0L
            ),
            targetUpdateTime = extras.getLong(
                MineComponentSnapshotQueryContract.EXTRA_TARGET_UPDATE_TIME,
                0L
            ),
            moduleVersionCode = extras.getLong(
                MineComponentSnapshotQueryContract.EXTRA_MODULE_VERSION,
                0L
            )
        )
        if (!source.isComplete || source.moduleVersionCode != BuildConfig.VERSION_CODE.toLong()) {
            return Result(Status.INVALID_RESPONSE)
        }
        val installedTarget = currentTargetSource(context) ?: return Result(Status.INVALID_RESPONSE)
        if (source.targetVersionCode != installedTarget.targetVersionCode ||
            source.targetUpdateTime != installedTarget.targetUpdateTime
        ) return Result(Status.INVALID_RESPONSE)

        val stored = MineComponentSnapshotStore.write(context, payload, source)
        return Result(if (stored) Status.READY else Status.STORE_FAILED, snapshot)
    }

    private fun currentTargetSource(context: Context): MineComponentSnapshotSource? = runCatching {
        val info = context.packageManager.getPackageInfo(
            MineComponentSnapshotQueryContract.TARGET_PACKAGE,
            0
        )
        MineComponentSnapshotSource(
            targetVersionCode = info.versionCodeCompat(),
            targetUpdateTime = info.lastUpdateTime,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
        ).takeIf { it.isComplete }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
