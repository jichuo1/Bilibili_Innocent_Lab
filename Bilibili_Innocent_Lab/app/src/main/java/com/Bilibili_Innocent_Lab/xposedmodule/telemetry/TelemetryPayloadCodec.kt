package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticActivationState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticFeatureInstallState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostFeature
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticFeatureRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class TelemetryEncodingEnvironment(
    val reportId: String,
    val moduleChannel: String,
    val androidSdk: Int,
    val abi: String,
    val adapterSchemaVersion: Int,
    val adapterRuleVersion: Int,
    val manual: Boolean = false,
    val versionChange: Boolean = false,
    val device: TelemetryDeviceProfile = TelemetryDeviceProfile("unknown", "unknown", "unknown")
)

/** 白名单投影器：输入沿用诊断快照，输出严格匹配服务端 schema v1。 */
internal object TelemetryPayloadCodec {
    const val SCHEMA_VERSION = 2
    const val MAX_PAYLOAD_BYTES = 32 * 1024
    private val reasonCodePattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")

    fun encode(
        snapshot: ModuleDiagnosticSnapshot,
        identity: TelemetryIdentity,
        environment: TelemetryEncodingEnvironment
    ): ByteArray {
        val input = snapshot.inputs
        require(input.hostRuntimeReceiptAvailable) { "host_runtime_unavailable" }
        require(!environment.versionChange || (!environment.manual && input.hostInstallChainState ==
            com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostInstallChainState.COMPLETED)) {
            "version_change_receipt_required"
        }
        require(input.moduleVersionCode in 1..Int.MAX_VALUE.toLong()) { "module_version_invalid" }
        require(input.targetVersionCode in 1..Int.MAX_VALUE.toLong()) { "target_version_invalid" }
        require(environment.androidSdk in 27..100) { "android_sdk_invalid" }
        require(environment.moduleChannel == "stable" || environment.moduleChannel == "alpha") {
            "module_channel_invalid"
        }
        require(environment.abi in setOf("arm64-v8a", "armeabi-v7a", "unknown")) {
            "abi_invalid"
        }

        val frameworkCode = frameworkCode(input.frameworkName)
        val features = input.hostFeatures.asSequence()
            .filterNot { feature ->
                DiagnosticFeatureRegistry.isAggregate(feature.featureId) ||
                    feature.installState == DiagnosticFeatureInstallState.NOT_REPORTED ||
                    feature.installState == DiagnosticFeatureInstallState.DISABLED ||
                    feature.installReasonCode == "DISABLED"
            }
            .map(::encodeFeature)
            .filterNotNull()
            .sortedBy { it.getString("id") }
            .toList()
        require(features.size <= DiagnosticCapabilityCatalog.leafIds.size) { "capability_overflow" }
        require(features.all { it.getString("id") in DiagnosticCapabilityCatalog.leafIds }) { "unknown_capability" }
        val groups = input.hostFeatures.filter { DiagnosticFeatureRegistry.isAggregate(it.featureId) }
            .mapNotNull(::encodeFeature)
            .onEach { group ->
                val parent = group.getString("id")
                val children = features.filter {
                    DiagnosticCapabilityCatalog.byId[it.getString("id")]?.parentId == parent
                }.map { it.getString("state") }
                val state = groupState(group.getString("state"), children)
                group.put("state", state)
                group.remove("hook_count") // shared registrations must not be counted again at group level
                group.remove("reason_code")
                when (state) {
                    "partial" -> group.put("reason_code", "PARTIAL_COVERAGE")
                    "unknown" -> group.put("reason_code", "CAPABILITY_UNVERIFIED")
                    "failed", "missing" -> group.put("reason_code", "CHILD_CAPABILITY_UNAVAILABLE")
                }
                if (features.any {
                    DiagnosticCapabilityCatalog.byId[it.getString("id")]?.parentId == parent &&
                        it.optInt("observed", 0) > 0
                }) group.put("observed", 1)
            }

        val root = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("feature_catalog_version", DiagnosticCapabilityCatalog.VERSION)
            .put("snapshot_complete", input.hostInstallChainState ==
                com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostInstallChainState.COMPLETED)
            .put("snapshot_state", input.hostInstallChainState.name.lowercase(Locale.ROOT))
            .put("evidence_scope", "current_host_process")
            .put("feature_groups", JSONArray().apply { groups.forEach(::put) })
            .put("disclosure_version", TelemetryPolicy.CURRENT_DISCLOSURE_VERSION)
            .put("device", JSONObject()
                .put("manufacturer", TelemetryDevicePolicy.productLabel(environment.device.manufacturer, lowercase = true))
                .put("model", TelemetryDevicePolicy.productLabel(environment.device.model))
                .put("rom", environment.device.rom.takeIf { it in TelemetryDevicePolicy.romCodes } ?: "unknown"))
            .put("upload_kind", when {
                environment.versionChange -> "version_change"
                environment.manual -> "manual"
                else -> "automatic"
            })
            .put("client_report_id", environment.reportId)
            .put("install_id", identity.installId)
            .put("purge_token", identity.purgeToken)
            .put(
                "module",
                JSONObject()
                    .put("version_code", input.moduleVersionCode)
                    .put("channel", environment.moduleChannel)
            )
            .put(
                "host",
                JSONObject().put("version_code", input.targetVersionCode)
            )
            .put(
                "runtime",
                JSONObject()
                    .put("framework", frameworkCode)
                    .put("framework_api", input.frameworkApiVersion.coerceIn(0, 999))
                    .put("framework_version", TelemetryDevicePolicy.productLabel(
                        input.frameworkVersion.takeIf { input.frameworkConnected }
                    ))
                    .put("framework_version_code", input.frameworkVersionCode?.takeIf {
                        input.frameworkConnected && it in 0..2_147_483_647L
                    } ?: 0L)
                    .put("android_sdk", environment.androidSdk)
                    .put("abi", environment.abi)
                    .put("delivery_channel", deliveryChannel(input.activationState, frameworkCode))
            )
            .put(
                "adaptation",
                JSONObject()
                    .put("schema_version", environment.adapterSchemaVersion.coerceIn(0, 100_000))
                    .put("rule_version", environment.adapterRuleVersion.coerceIn(0, 100_000))
                    .put("cache_status", "unknown")
                    .put("duration_bucket", "unknown")
                    // 当前跨进程回执没有可靠的 DexKit 使用位，明确上报 unknown，不能伪造 false。
                    .put("dex_assist_used", "unknown")
                    .put(
                        "bootstrap",
                        JSONObject()
                            .put("resolved", input.hostHookPointResolvedCount.coerceIn(0, 10_000))
                            .put("installed", input.hostHookPointInstalledCount.coerceIn(0, 10_000))
                            .put("missing", input.hostHookPointMissingCount.coerceIn(0, 10_000))
                            .put("failed", input.hostHookPointFailedCount.coerceIn(0, 10_000))
                    )
            )
            .put("features", JSONArray().apply { features.forEach(::put) })

        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "payload_too_large" }
        return bytes
    }

    fun prettyPrint(bytes: ByteArray): String =
        JSONObject(String(bytes, StandardCharsets.UTF_8)).toString(2)

    internal fun groupState(original: String, children: List<String>): String {
        val working = children.any { it == "installed" || it == "partial" }
        val broken = children.any { it == "partial" || it == "failed" || it == "missing" }
        val derived = when {
            broken && working -> "partial"
            broken -> if (children.all { it == "missing" }) "missing" else "failed"
            children.isEmpty() || children.any { it == "unknown" } -> "unknown"
            working -> "installed"
            children.all { it == "not_applicable" || it == "safe_skip" } -> "not_applicable"
            else -> "unknown"
        }
        return if (original in setOf("partial", "failed", "missing")) {
            if (derived == "installed") "partial" else if (derived == "unknown") original else derived
        } else derived
    }

    internal fun frameworkCode(name: String): String {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return when {
            normalized == "lspatch" -> "lspatch"
            "vector" in normalized -> "vector"
            "irena" in normalized -> "irena"
            "npatch" in normalized -> "npatch"
            "lsposed" in normalized -> "lsposed"
            else -> "unknown"
        }
    }

    private fun deliveryChannel(
        activationState: DiagnosticActivationState,
        frameworkCode: String
    ): String = when {
        activationState == DiagnosticActivationState.ACTIVE_NPATCH -> "npatch"
        frameworkCode == "lspatch" -> "lspatch_manager"
        else -> "standard"
    }

    private fun encodeFeature(feature: DiagnosticHostFeature): JSONObject? {
        val state = when (feature.installState) {
            DiagnosticFeatureInstallState.NOT_REPORTED,
            DiagnosticFeatureInstallState.DISABLED -> return null
            DiagnosticFeatureInstallState.NOT_APPLICABLE -> "not_applicable"
            DiagnosticFeatureInstallState.INSTALLED -> "installed"
            DiagnosticFeatureInstallState.PARTIAL -> "partial"
            DiagnosticFeatureInstallState.UNKNOWN -> "unknown"
            DiagnosticFeatureInstallState.SKIPPED -> {
                if (feature.installReasonCode == "DISABLED") return null
                if (feature.installReasonCode == "NOT_APPLICABLE_PROCESS" ||
                    feature.installReasonCode == "NOT_APPLICABLE_HOST") {
                    "not_applicable"
                } else if (feature.installReasonCode in setOf(
                    "MISSING_ADAPTER_POINT", "MISSING_HOST_STRUCTURE", "AMBIGUOUS_HOST_STRUCTURE", "NO_SAFE_HOOK_POINT"
                )) {
                    "missing"
                } else if (feature.installReasonCode == "OTHER") {
                    "unknown"
                } else {
                    "failed"
                }
            }
            DiagnosticFeatureInstallState.FAILED -> "failed"
        }
        return JSONObject()
            .put("id", feature.featureId)
            .put("state", state)
            .apply {
                if (feature.runtimeError) put("runtime_error", true)
                feature.installReasonCode
                    ?.takeIf(reasonCodePattern::matches)
                    ?.let { put("reason_code", it) }
                put("hook_count", feature.installedHookCount.coerceIn(0, 1_000_000))
                if (DiagnosticCapabilityCatalog.runtimeSupport(feature.featureId) > 0) put(
                    "observed",
                    if (feature.evidence == DiagnosticEvidence.OBSERVED ||
                        feature.evidence == DiagnosticEvidence.APPLIED
                    ) 1 else 0
                )
                if (DiagnosticCapabilityCatalog.runtimeSupport(feature.featureId) == 2) {
                    put("applied", if (feature.evidence == DiagnosticEvidence.APPLIED) 1 else 0)
                }
            }
    }
}
