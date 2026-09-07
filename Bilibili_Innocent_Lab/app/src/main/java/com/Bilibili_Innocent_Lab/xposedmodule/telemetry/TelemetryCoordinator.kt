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
    private val previewInFlight = AtomicBoolean(false)
    private val purgeInFlight = AtomicBoolean(false)

    fun maybeUpload(
        context: Context,
        manual: Boolean = false,
        callback: ((TelemetryActionResult) -> Unit)? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { maybeUpload(context, manual, callback) }
            return
        }
        val appContext = context.applicationContext ?: context
        val endpointKey = TelemetryEndpoint.endpointKey()
        val decision = TelemetryStore.attemptDecision(appContext, endpointKey, manual)
        if (decision != TelemetryAttemptDecision.ALLOWED) {
            deliver(callback, decision.toActionResult())
            return
        }
        if (!uploadInFlight.compareAndSet(false, true)) {
            deliver(callback, TelemetryActionResult(TelemetryActionStatus.BUSY))
            return
        }

        queryHostRuntime(appContext) { hostRuntime ->
            if (hostRuntime == null) {
                TelemetryStore.recordCollectionUnavailable(appContext, manual)
                finishUpload(callback, TelemetryActionResult(TelemetryActionStatus.HOST_UNAVAILABLE))
                return@queryHostRuntime
            }
            worker.execute {
                var networkAttemptStarted = false
                val result = runCatching {
                    if (!TelemetryStore.isEnabledForUpload(appContext)) {
                        return@runCatching TelemetryActionResult(TelemetryActionStatus.DISABLED)
                    }
                    val payload = collectPayload(appContext, hostRuntime, manual)
                        ?: return@runCatching TelemetryActionResult(
                            TelemetryActionStatus.IDENTITY_UNAVAILABLE
                        )
                    if (!TelemetryStore.isEnabledForUpload(appContext)) {
                        return@runCatching TelemetryActionResult(TelemetryActionStatus.DISABLED)
                    }
                    if (!TelemetryStore.beginNetworkAttempt(appContext, endpointKey, manual)) {
                        return@runCatching TelemetryActionResult(
                            TelemetryActionStatus.IDENTITY_UNAVAILABLE
                        )
                    }
                    networkAttemptStarted = true
                    val transportResult = TelemetryHttpTransport.upload(
                        TelemetryEndpoint.baseUrl(),
                        payload
                    )
                    TelemetryStore.recordTransportResult(appContext, endpointKey, transportResult, manual)
                    TelemetryActionResult(transportResult.outcome.toActionStatus())
                }.getOrElse {
                    if (networkAttemptStarted) {
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
        manual: Boolean
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
        uploadInFlight.set(false)
        deliver(callback, result)
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
