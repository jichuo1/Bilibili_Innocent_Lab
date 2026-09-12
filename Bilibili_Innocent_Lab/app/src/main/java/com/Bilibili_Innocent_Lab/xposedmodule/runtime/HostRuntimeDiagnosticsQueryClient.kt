package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig

/** 有界实时查询；Binder 与广播共享校验。持久化历史不参与在线判定。 */
internal object HostRuntimeDiagnosticsQueryClient {
    enum class Status { READY, TARGET_UNAVAILABLE, INVALID_RESPONSE }
    data class Result(
        val status: Status,
        val snapshot: HostRuntimeDiagnosticsSnapshot? = null,
        val source: HostRuntimeDiagnosticsSource? = null,
        val failure: ReceiptQueryFailure = ReceiptQueryFailure.NONE
    )
    private val validationExecutor = HostReceiptWire.executor("bil-diagnostics-validate")

    fun query(context: Context, callback: (Result) -> Unit) {
        val app = context.applicationContext ?: context
        val main = Handler(Looper.getMainLooper())
        fun deliver(result: Result) {
            ReceiptQueryLog.failure("diagnostics", result.failure)
            main.post { callback(result) }
        }
        ReceiptQueryTransport.query(app, HostReceiptWire.DIAGNOSTICS) { reply ->
            if (reply.failure != ReceiptQueryFailure.NONE) {
                val unavailable = ReceiptQueryPolicy.isUnavailable(reply.failure)
                deliver(Result(if (unavailable) Status.TARGET_UNAVAILABLE else Status.INVALID_RESPONSE, failure = reply.failure))
            } else {
                runCatching { validationExecutor.execute {
                    val result = runCatching {
                        val extras = reply.extras ?: return@runCatching Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
                        if (extras.getString(HostRuntimeDiagnosticsQueryContract.EXTRA_REQUEST_NONCE) != reply.nonce)
                            return@runCatching Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.NONCE_MISMATCH)
                        if (!extras.getBoolean(HostRuntimeDiagnosticsQueryContract.EXTRA_HANDLED, false))
                            return@runCatching Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
                        when (extras.getString(HostRuntimeDiagnosticsQueryContract.EXTRA_STATUS)) {
                            HostRuntimeDiagnosticsQueryContract.STATUS_READY -> validate(app, extras)
                            HostRuntimeDiagnosticsQueryContract.STATUS_UNSUPPORTED ->
                                Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.UNSUPPORTED_PROTOCOL)
                            else -> Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
                        }
                    }.getOrElse { Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE) }
                    deliver(result)
                } }.onFailure { deliver(Result(Status.TARGET_UNAVAILABLE, failure = ReceiptQueryFailure.SEND_FAILED)) }
            }
        }
    }

    private fun validate(context: Context, extras: android.os.Bundle): Result {
        val payload = extras.getString(
            HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD
        ).orEmpty()
        if (payload.length > HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS) {
            return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
        }
        val digest = extras.getString(
            HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD_SHA256
        ).orEmpty()
        if (!HostRuntimeDiagnosticsQueryContract.digestMatches(payload, digest)) {
            return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.DIGEST_MISMATCH)
        }
        val snapshot = HostRuntimeDiagnosticsCodec.decodeOrNull(payload)
            ?: return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
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
            return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.SOURCE_MISMATCH)
        }
        val current = currentTargetSource(context) ?: return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.SOURCE_MISMATCH)
        if (source.targetVersionCode != current.targetVersionCode ||
            source.targetUpdateTime != current.targetUpdateTime
        ) return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.SOURCE_MISMATCH)
        return Result(Status.READY, snapshot, source)
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
