package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostQueryState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticsCollector
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateChannelStore
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class TelemetryActionStatus {
    SUCCESS,
    DISABLED,
    NOT_DUE,
    MANUAL_LIMIT_REACHED,
    BUSY,
    HOST_UNAVAILABLE,
    IDENTITY_UNAVAILABLE,
    RETIRED,
    RATE_LIMITED,
    REJECTED,
    FAILED,
    NOTHING_TO_DELETE
}

internal data class TelemetryActionResult(
    val status: TelemetryActionStatus,
    val preview: String? = null
)

/**
 * 遥测的唯一编排入口。宿主回执沿用现有签名权限广播；编码与网络只在模块 App
 * 进程的单线程执行器运行，Hook/绑定/滚动/绘制路径均不增加职责。
 */
internal object TelemetryCoordinator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "module-telemetry").apply { isDaemon = true }
    }
    private val uploadInFlight = AtomicBoolean(false)
    private val pendingVersionUpload = PendingVersionUpload()
    private val previewInFlight = AtomicBoolean(false)
    private val purgeInFlight = AtomicBoolean(false)

    fun maybeUpload(
        context: Context,
        manual: Boolean = false,
        versionChange: Boolean = false,
        callback: ((TelemetryActionResult) -> Unit)? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { maybeUpload(context, manual, versionChange, callback) }
            return
        }
        val appContext = context.applicationContext ?: context
        val endpointKey = TelemetryEndpoint.endpointKey()
        val decision = if (versionChange) {
            if (TelemetryStore.isEnabledForUpload(appContext)) TelemetryAttemptDecision.ALLOWED
            else TelemetryAttemptDecision.DISABLED
        } else TelemetryStore.attemptDecision(appContext, endpointKey, manual)
        if (decision != TelemetryAttemptDecision.ALLOWED) {
            deliver(callback, decision.toActionResult())
            return
        }
        if (!uploadInFlight.compareAndSet(false, true)) {
            if (versionChange && pendingVersionUpload.offer {
                    maybeUpload(appContext, versionChange = true, callback = callback)
                }) return
            deliver(callback, TelemetryActionResult(TelemetryActionStatus.BUSY))
            return
        }

        HostRuntimeDiagnosticsQueryClient.query(appContext) query@{ receipt ->
            val hostRuntime = receipt.snapshot.takeIf {
                receipt.status == HostRuntimeDiagnosticsQueryClient.Status.READY
            }
            if (hostRuntime == null) {
                if (!versionChange) TelemetryStore.recordCollectionUnavailable(appContext, manual)
                finishUpload(callback, TelemetryActionResult(TelemetryActionStatus.HOST_UNAVAILABLE))
                return@query
            }
            val versionKey = receipt.source?.let {
                TelemetryVersionPolicy.key(it.moduleVersionCode, it.targetVersionCode)
            }
            if (versionChange && (versionKey == null || hostRuntime.bootstrap.installChainState !=
                    com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState.COMPLETED)) {
                finishUpload(callback, TelemetryActionResult(TelemetryActionStatus.HOST_UNAVAILABLE))
                return@query
            }
            worker.execute {
                var networkAttemptStarted = false
                val result = runCatching {
                    if (!TelemetryStore.isEnabledForUpload(appContext)) {
                        return@runCatching TelemetryActionResult(TelemetryActionStatus.DISABLED)
                    }
                    if (versionChange && !TelemetryStore.versionDecision(appContext, endpointKey, versionKey!!)) {
                        return@runCatching TelemetryActionResult(TelemetryActionStatus.NOT_DUE)
                    }
                    val payload = collectPayload(appContext, hostRuntime, manual, versionChange)
                        ?: return@runCatching TelemetryActionResult(
                            TelemetryActionStatus.IDENTITY_UNAVAILABLE
                        )
                    if (!TelemetryStore.isEnabledForUpload(appContext)) {
                        return@runCatching TelemetryActionResult(TelemetryActionStatus.DISABLED)
                    }
                    if (versionChange) {
                        val encoded = org.json.JSONObject(String(payload, Charsets.UTF_8))
                        if (TelemetryVersionPolicy.key(encoded.getJSONObject("module").getLong("version_code"),
                                encoded.getJSONObject("host").getLong("version_code")) != versionKey) {
                            return@runCatching TelemetryActionResult(TelemetryActionStatus.HOST_UNAVAILABLE)
                        }
                    }
                    val reserved = if (versionChange) {
                        TelemetryStore.beginVersionAttempt(appContext, endpointKey, versionKey!!)
                    } else TelemetryStore.beginNetworkAttempt(appContext, endpointKey, manual)
                    if (!reserved) {
                        return@runCatching TelemetryActionResult(
                            TelemetryActionStatus.IDENTITY_UNAVAILABLE
                        )
                    }
                    networkAttemptStarted = true
                    val transportResult = TelemetryHttpTransport.upload(
                        TelemetryEndpoint.baseUrl(),
                        payload
                    )
                    if (versionChange) TelemetryStore.recordVersionResult(appContext, endpointKey, versionKey!!, transportResult)
                    else TelemetryStore.recordTransportResult(appContext, endpointKey, transportResult, manual)
                    TelemetryActionResult(transportResult.outcome.toActionStatus())
                }.getOrElse {
                    if (versionChange) {
                        // Reservation already persisted a bounded retry delay and consumed one slot.
                    } else if (networkAttemptStarted) {
                        TelemetryStore.recordTransportResult(
                            appContext,
                            endpointKey,
                            TelemetryTransportResult(TelemetryHttpOutcome.RETRYABLE_FAILURE),
                            manual
                        )
                    } else {
                        TelemetryStore.recordCollectionUnavailable(appContext, manual)
                    }
                    TelemetryActionResult(TelemetryActionStatus.FAILED)
                }
                finishUpload(callback, result)
            }
        }
    }

    fun preview(context: Context, callback: (TelemetryActionResult) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { preview(context, callback) }
            return
        }
        if (!previewInFlight.compareAndSet(false, true)) {
            deliver(callback, TelemetryActionResult(TelemetryActionStatus.BUSY))
            return
        }
        val appContext = context.applicationContext ?: context
        queryHostRuntime(appContext) { hostRuntime ->
            if (hostRuntime == null) {
                finishPreview(callback, TelemetryActionResult(TelemetryActionStatus.HOST_UNAVAILABLE))
                return@queryHostRuntime
            }
            worker.execute {
                val result = runCatching {
                    val payload = collectPayload(appContext, hostRuntime, manual = true)
                        ?: return@runCatching TelemetryActionResult(
                            TelemetryActionStatus.IDENTITY_UNAVAILABLE
                        )
                    TelemetryActionResult(
                        TelemetryActionStatus.SUCCESS,
                        TelemetryPayloadCodec.prettyPrint(payload)
                    )
                }.getOrElse { TelemetryActionResult(TelemetryActionStatus.FAILED) }
                finishPreview(callback, result)
            }
        }
    }

    fun purge(context: Context, callback: (TelemetryActionResult) -> Unit) {
        val appContext = context.applicationContext ?: context
        if (!purgeInFlight.compareAndSet(false, true)) {
            deliver(callback, TelemetryActionResult(TelemetryActionStatus.BUSY))
            return
        }
        val tokens = TelemetryStore.purgeTokens(appContext)
        if (tokens.isEmpty()) {
            purgeInFlight.set(false)
            deliver(callback, TelemetryActionResult(TelemetryActionStatus.NOTHING_TO_DELETE))
            return
        }
        worker.execute {
            val finalStatus = runCatching {
                var status = TelemetryActionStatus.SUCCESS
                for (token in tokens) {
                    val result = TelemetryHttpTransport.purge(TelemetryEndpoint.baseUrl(), token)
                    val current = result.outcome.toActionStatus()
                    if (current != TelemetryActionStatus.SUCCESS) {
                        status = current
                        break
                    }
                }
                status
            }.getOrDefault(TelemetryActionStatus.FAILED)
            if (finalStatus == TelemetryActionStatus.SUCCESS) {
                TelemetryStore.recordPurgeSuccess(appContext)
            }
            purgeInFlight.set(false)
            deliver(callback, TelemetryActionResult(finalStatus))
        }
    }

    private fun queryHostRuntime(
        context: Context,
        callback: (HostRuntimeDiagnosticsSnapshot?) -> Unit
    ) {
        HostRuntimeDiagnosticsQueryClient.query(context) { result ->
            callback(
                result.snapshot.takeIf {
                    result.status == HostRuntimeDiagnosticsQueryClient.Status.READY
                }
            )
        }
    }

    private fun collectPayload(
        context: Context,
        hostRuntime: HostRuntimeDiagnosticsSnapshot,
        manual: Boolean,
        versionChange: Boolean = false
    ): ByteArray? {
        val identity = TelemetryStore.getOrCreateIdentity(context) ?: return null
        val snapshot = ModuleDiagnosticsCollector.collect(
            context = context,
            skin = null,
            hostRuntime = hostRuntime,
            hostQueryState = DiagnosticHostQueryState.READY
        )
        val updateChannel = UpdateChannelStore.read(context)
        return TelemetryPayloadCodec.encode(
            snapshot = snapshot,
            identity = identity,
            environment = TelemetryEncodingEnvironment(
                reportId = UUID.randomUUID().toString(),
                manual = manual,
                versionChange = versionChange,
                device = TelemetryDeviceCollector.collect(),
                moduleChannel = if (updateChannel == GitHubReleaseChecker.UpdateChannel.PREVIEW) {
                    "alpha"
                } else {
                    "stable"
                },
                androidSdk = AndroidVersion.code,
                abi = supportedAbi(),
                adapterSchemaVersion = VersionAdapterContract.SCHEMA_VERSION,
                adapterRuleVersion = VersionAdapterContract.RULE_VERSION
            )
        )
    }

    private fun supportedAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { abi ->
        abi == "arm64-v8a" || abi == "armeabi-v7a"
    } ?: "unknown"

    private fun finishUpload(
        callback: ((TelemetryActionResult) -> Unit)?,
        result: TelemetryActionResult
    ) {
        // 入口及释放统一在主线程，避免刚清除单飞标记就被其他请求抢走待处理通知。
        mainHandler.post {
            uploadInFlight.set(false)
            val pending = pendingVersionUpload.take()
            deliver(callback, result)
            pending?.invoke()
        }
    }

    private fun finishPreview(
        callback: (TelemetryActionResult) -> Unit,
        result: TelemetryActionResult
    ) {
        previewInFlight.set(false)
        deliver(callback, result)
    }

    private fun deliver(
        callback: ((TelemetryActionResult) -> Unit)?,
        result: TelemetryActionResult
    ) {
        if (callback == null) return
        mainHandler.post { callback(result) }
    }

    private fun TelemetryAttemptDecision.toActionResult(): TelemetryActionResult =
        TelemetryActionResult(
            when (this) {
                TelemetryAttemptDecision.ALLOWED -> TelemetryActionStatus.FAILED
                TelemetryAttemptDecision.DISABLED -> TelemetryActionStatus.DISABLED
                TelemetryAttemptDecision.RETIRED -> TelemetryActionStatus.RETIRED
                TelemetryAttemptDecision.NOT_DUE -> TelemetryActionStatus.NOT_DUE
                TelemetryAttemptDecision.MANUAL_LIMIT_REACHED -> TelemetryActionStatus.MANUAL_LIMIT_REACHED
            }
        )

    private fun TelemetryHttpOutcome.toActionStatus(): TelemetryActionStatus = when (this) {
        TelemetryHttpOutcome.SUCCESS -> TelemetryActionStatus.SUCCESS
        TelemetryHttpOutcome.RETIRED -> TelemetryActionStatus.RETIRED
        TelemetryHttpOutcome.RATE_LIMITED -> TelemetryActionStatus.RATE_LIMITED
        TelemetryHttpOutcome.REJECTED -> TelemetryActionStatus.REJECTED
        TelemetryHttpOutcome.RETRYABLE_FAILURE -> TelemetryActionStatus.FAILED
    }
}
