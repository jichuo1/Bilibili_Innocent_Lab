package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat

/** 有界实时查询；Binder 与广播共享校验。持久化历史不参与在线判定。 */
internal object MineComponentSnapshotQueryClient {
    enum class Status { READY, WAITING_PAGE, TARGET_UNAVAILABLE, INVALID_RESPONSE, STORE_FAILED }
    data class Result(
        val status: Status,
        val snapshot: MineComponentSnapshot? = null,
        val failure: ReceiptQueryFailure = ReceiptQueryFailure.NONE
    )
    private val validationExecutor = HostReceiptWire.executor("bil-scan-validate")

    /**
     * @param wakeHost 取不到时是否允许把宿主主进程拉起来再试一次。
     *   用户点开面板的路径保持默认 true；**没有用户动作的静默拉取必须显式传 false**
     *   （当前是"自动确认新增屏蔽标签"在模块前台时的那次后台合并），
     *   否则打开模块 App 就会顺手启动哔哩哔哩。
     */
    fun query(
        context: Context,
        surface: String = MineComponentSnapshotCodec.SURFACE_MINE,
        wakeHost: Boolean = true,
        callback: (Result) -> Unit
    ) {
        val app = context.applicationContext ?: context
        val main = Handler(Looper.getMainLooper())
        fun deliver(result: Result) {
            ReceiptQueryLog.failure("scan", result.failure)
            main.post { callback(result) }
        }
        // 扫描快照面的两个调用方（四个勾选面板、管理推荐屏蔽）都是用户点开面板才触发的，
        // 所以允许用最后那段保底把宿主主进程拉起来；遥测与激活卡检查走的是诊断通道，
        // 那条默认不唤起，见 ReceiptQueryTransport 的类注释。
        ReceiptQueryTransport.query(app, surface, allowWake = wakeHost) { reply ->
            if (reply.failure != ReceiptQueryFailure.NONE) {
                val unavailable = ReceiptQueryPolicy.isUnavailable(reply.failure)
                deliver(Result(if (unavailable) Status.TARGET_UNAVAILABLE else Status.INVALID_RESPONSE, failure = reply.failure))
            } else {
                runCatching { validationExecutor.execute {
                    val result = runCatching {
                        val extras = reply.extras ?: return@runCatching Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
                        if (extras.getString(MineComponentSnapshotQueryContract.EXTRA_REQUEST_NONCE) != reply.nonce)
                            return@runCatching Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.NONCE_MISMATCH)
                        if (!extras.getBoolean(MineComponentSnapshotQueryContract.EXTRA_HANDLED, false))
                            return@runCatching Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
                        when (extras.getString(MineComponentSnapshotQueryContract.EXTRA_STATUS)) {
                            MineComponentSnapshotQueryContract.STATUS_READY -> validateAndStore(app, extras, surface)
                            MineComponentSnapshotQueryContract.STATUS_WAITING_PAGE -> Result(Status.WAITING_PAGE)
                            MineComponentSnapshotQueryContract.STATUS_UNSUPPORTED ->
                                Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.UNSUPPORTED_PROTOCOL)
                            else -> Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
                        }
                    }.getOrElse { Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE) }
                    deliver(result)
                } }.onFailure { deliver(Result(Status.TARGET_UNAVAILABLE, failure = ReceiptQueryFailure.SEND_FAILED)) }
            }
        }
    }

    private fun validateAndStore(
        context: Context,
        extras: android.os.Bundle,
        requestedSurface: String
    ): Result {
        val payload = extras.getString(MineComponentSnapshotQueryContract.EXTRA_PAYLOAD).orEmpty()
        if (payload.length > MineComponentSnapshotCodec.MAX_PAYLOAD_BYTES) {
            return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
        }
        val digest = extras.getString(
            MineComponentSnapshotQueryContract.EXTRA_PAYLOAD_SHA256
        ).orEmpty()
        if (!MineComponentSnapshotQueryContract.digestMatches(payload, digest)) {
            return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.DIGEST_MISMATCH)
        }
        val snapshot = MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)
            ?: return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
        if (snapshot.surface != requestedSurface ||
            snapshot.processName != MineComponentSnapshotQueryContract.TARGET_PACKAGE
        ) return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.SURFACE_MISMATCH)
        if (snapshot.entries.isEmpty()) return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.MALFORMED_RESPONSE)

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
            return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.SOURCE_MISMATCH)
        }
        val installedTarget = currentTargetSource(context) ?: return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.SOURCE_MISMATCH)
        if (source.targetVersionCode != installedTarget.targetVersionCode ||
            source.targetUpdateTime != installedTarget.targetUpdateTime
        ) return Result(Status.INVALID_RESPONSE, failure = ReceiptQueryFailure.SOURCE_MISMATCH)

        val stored = MineComponentSnapshotStore.write(context, payload, source)
        return Result(if (stored) Status.READY else Status.STORE_FAILED, snapshot,
            if (stored) ReceiptQueryFailure.NONE else ReceiptQueryFailure.STORE_FAILED)
    }

    private fun currentTargetSource(context: Context): MineComponentSnapshotSource? = runCatching {
        val info = context.packageManager.getPackageInfo(
            MineComponentSnapshotQueryContract.TARGET_PACKAGE,
            0
        )
        MineComponentSnapshotSource(
            targetVersionCode = info.versionCodeCompat,
            targetUpdateTime = info.lastUpdateTime,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
        ).takeIf { it.isComplete }
    }.getOrNull()
}
