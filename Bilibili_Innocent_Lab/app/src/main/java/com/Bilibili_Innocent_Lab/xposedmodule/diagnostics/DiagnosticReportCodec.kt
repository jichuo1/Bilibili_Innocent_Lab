package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsCodec
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class DiagnosticReportMetadata(
    val collectedAtEpochMs: Long,
    val overallSeverity: DiagnosticSeverity,
    val itemCount: Int
)

/** 只导出固定白名单字段的本地诊断报告；不接受设置值、日志正文和任意异常文本。 */
internal object DiagnosticReportCodec {
    const val FORMAT_NAME = "bilab-diagnostics"
    const val CURRENT_FORMAT_VERSION = 3
    const val PRODUCT_ID = "bilibili-innocent-lab"
    const val MAX_FILE_BYTES = 256 * 1024

    private val allowedRemoteFailureCodes = setOf(
        "service_not_connected",
        "remote_preferences_unsupported",
        "publish_failed"
    )
    private val allowedSkinFallbackCodes = setOf(
        "liquid_renderer_initialization_failed"
    )

    val excludedCategories = listOf(
        "preference_values",
        "custom_rules",
        "file_paths",
        "logs",
        "exceptions",
        "host_member_names"
    )

    fun encode(snapshot: ModuleDiagnosticSnapshot): ByteArray {
        val input = snapshot.inputs
        val root = JSONObject()
            .put("format", FORMAT_NAME)
            .put("formatVersion", CURRENT_FORMAT_VERSION)
            .put("productId", PRODUCT_ID)
            .put("collectedAtEpochMs", input.collectedAtEpochMs)
            .put(
                "module",
                JSONObject()
                    .put("versionName", input.moduleVersionName)
                    .put("versionCode", input.moduleVersionCode)
                    .put("debugBuild", input.debugBuild)
            )
            .put(
                "target",
                JSONObject()
                    .put("installed", input.targetInstalled)
                    .put("versionName", input.targetVersionName ?: JSONObject.NULL)
                    .put("versionCode", input.targetVersionCode)
                    .put("lastUpdateAtEpochMs", input.targetLastUpdateAtEpochMs)
            )
            .put(
                "framework",
                JSONObject()
                    .put("connected", input.frameworkConnected)
                    .put("capable", input.frameworkCapable)
                    .put("name", input.frameworkName.take(128))
                    .put("apiVersion", input.frameworkApiVersion)
            )
            .put(
                "remoteConfig",
                JSONObject()
                    .put("state", input.remotePublishState.name)
                    .put("lastAttemptAtEpochMs", input.remoteLastAttemptAtEpochMs)
                    .put("lastSuccessAtEpochMs", input.remoteLastSuccessAtEpochMs)
                    .put("generation", input.remoteGeneration)
                    .put(
                        "failureCode",
                        input.remoteFailureCode.boundedCode(allowedRemoteFailureCodes)
                            ?: JSONObject.NULL
                    )
                    .put("publishPending", input.remotePublishPending)
            )
            .put(
                "runtime",
                JSONObject()
                    .put("activation", input.activationState.name)
                    .put("noRootDesiredEnabled", input.noRootDesiredEnabled)
                    .put("noRootState", input.noRootState.name)
                    .put("hostAdaptationReceiptAvailable", input.hostRuntimeReceiptAvailable)
                    .put("hostRuntimeCapturedAtEpochMs", input.hostRuntimeCapturedAtEpochMs)
                    .put("hostAdaptedFeatureCount", input.hostAdaptedFeatureCount)
                    .put("hostObservedFeatureCount", input.hostObservedFeatureCount)
                    .put("hostAppliedFeatureCount", input.hostAppliedFeatureCount)
                    .put("hostQueryState", input.hostQueryState.name)
                    .put(
                        "hostBootstrap",
                        JSONObject()
                            .put("reached", input.hostBootstrapReached)
                            .put("configState", input.hostConfigState.name)
                            .put("configGeneration", input.hostConfigGeneration)
                            .put(
                                "configReasonCode",
                                input.hostConfigReasonCode.boundedCode(
                                    HostRuntimeDiagnosticsCodec.allowedConfigReasonCodes
                                ) ?: JSONObject.NULL
                            )
                            .put("installChainState", input.hostInstallChainState.name)
                            .put("hookResolvedCount", input.hostHookPointResolvedCount)
                            .put("hookInstalledCount", input.hostHookPointInstalledCount)
                            .put("hookMissingCount", input.hostHookPointMissingCount)
                            .put("hookFailedCount", input.hostHookPointFailedCount)
                    )
                    .put("hostInstalledFeatureCount", input.hostInstalledFeatureCount)
                    .put("hostFailedFeatureCount", input.hostFailedFeatureCount)
                    .put("hostFeatures", JSONArray().apply {
                        input.hostFeatures.forEach { feature ->
                            put(
                                JSONObject()
                                    .put("id", feature.featureId)
                                    .put("evidence", feature.evidence.name)
                                    .put("installState", feature.installState.name)
                                    .put("installedHookCount", feature.installedHookCount)
                                    .put(
                                        "installReasonCode",
                                        feature.installReasonCode.boundedCode(
                                            HostRuntimeDiagnosticsCodec.allowedInstallReasonCodes
                                        ) ?: JSONObject.NULL
                                    )
                                    .put("runtimeEvidenceExpected", feature.runtimeEvidenceExpected)
                            )
                        }
                    })
            )
            .put(
                "interface",
                JSONObject()
                    .put("requestedSkin", input.requestedSkin)
                    .put("effectiveSkin", input.effectiveSkin)
                    .put(
                        "fallbackCode",
                        input.skinFallbackCode.boundedCode(allowedSkinFallbackCodes)
                            ?: JSONObject.NULL
                    )
                    .put("liquidBackend", input.liquidBackendName ?: JSONObject.NULL)
            )
            .put(
                "settings",
                JSONObject()
                    .put("catalogVersion", input.settingsCatalogVersion)
                    .put("totalCount", input.settingsTotalCount)
                    .put("automaticCount", input.settingsAutomaticCount)
                    .put("manualCount", input.settingsManualCount)
                    .put("loggingEnabled", input.loggingEnabled)
                    .put("verboseLogging", input.verboseLogging)
            )
            .put(
                "assessment",
                JSONObject()
                    .put("overall", snapshot.overallSeverity.name)
                    .put(
                        "items",
                        JSONArray().apply {
                            snapshot.items.forEach { item ->
                                put(
                                    JSONObject()
                                        .put("id", item.id.name)
                                        .put("severity", item.severity.name)
                                        .put("evidence", item.evidence.name)
                                )
                            }
                        }
                    )
            )
            .put("excluded", JSONArray(excludedCategories))
        return root.toString(2).toByteArray(StandardCharsets.UTF_8).also {
            require(it.size in 1..MAX_FILE_BYTES) { "Diagnostic report exceeds size limit" }
        }
    }

    fun validate(bytes: ByteArray): DiagnosticReportMetadata {
        require(bytes.size in 1..MAX_FILE_BYTES) { "Invalid diagnostic report size" }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject
            ?: throw IllegalArgumentException("Diagnostic report root must be an object")
        require(tokener.nextClean() == '\u0000') { "Trailing diagnostic report data" }
        require(root.getString("format") == FORMAT_NAME) { "Unknown diagnostic format" }
        require(root.getInt("formatVersion") == CURRENT_FORMAT_VERSION) {
            "Unsupported diagnostic format version"
        }
        require(root.getString("productId") == PRODUCT_ID) { "Wrong diagnostic product" }
        val assessment = root.getJSONObject("assessment")
        val severity = DiagnosticSeverity.valueOf(assessment.getString("overall"))
        val items = assessment.getJSONArray("items")
        require(items.length() == DiagnosticItemId.entries.size) {
            "Incomplete diagnostic assessment"
        }
        val runtime = root.getJSONObject("runtime")
        DiagnosticHostQueryState.valueOf(runtime.getString("hostQueryState"))
        val bootstrap = runtime.getJSONObject("hostBootstrap")
        require(bootstrap.length() == 9) { "Invalid host bootstrap assessment" }
        DiagnosticHostConfigState.valueOf(bootstrap.getString("configState"))
        DiagnosticHostInstallChainState.valueOf(bootstrap.getString("installChainState"))
        require(bootstrap.getLong("configGeneration") >= 0L) {
            "Invalid host bootstrap assessment"
        }
        listOf(
            "hookResolvedCount",
            "hookInstalledCount",
            "hookMissingCount",
            "hookFailedCount"
        ).forEach { key ->
            require(bootstrap.getInt(key) in 0..HostRuntimeDiagnosticsCodec.MAX_HOOK_COUNT) {
                "Invalid host bootstrap assessment"
            }
        }
        if (!bootstrap.isNull("configReasonCode")) {
            require(
                bootstrap.getString("configReasonCode") in
                    HostRuntimeDiagnosticsCodec.allowedConfigReasonCodes
            ) { "Invalid host config reason" }
        }
        val hostFeatures = runtime.getJSONArray("hostFeatures")
        require(hostFeatures.length() <= HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT) {
            "Invalid host feature assessment"
        }
        val hostFeatureIds = HashSet<String>()
        for (index in 0 until hostFeatures.length()) {
            val feature = hostFeatures.getJSONObject(index)
            require(feature.length() == 6) { "Invalid host feature assessment" }
            val id = feature.getString("id")
            require(id in HostRuntimeDiagnosticsCodec.allowedFeatureIds && hostFeatureIds.add(id)) {
                "Invalid host feature assessment"
            }
            require(
                DiagnosticEvidence.valueOf(feature.getString("evidence")) in setOf(
                    DiagnosticEvidence.ADAPTED,
                    DiagnosticEvidence.OBSERVED,
                    DiagnosticEvidence.APPLIED,
                    DiagnosticEvidence.NOT_AVAILABLE
                )
            ) { "Invalid host feature evidence" }
            val installState = DiagnosticFeatureInstallState.valueOf(
                feature.getString("installState")
            )
            val hookCount = feature.getInt("installedHookCount")
            require(hookCount in 0..HostRuntimeDiagnosticsCodec.MAX_HOOK_COUNT) {
                "Invalid host feature hook count"
            }
            val reasonCode = if (feature.isNull("installReasonCode")) null
            else feature.getString("installReasonCode")
            require(
                reasonCode == null ||
                    reasonCode in HostRuntimeDiagnosticsCodec.allowedInstallReasonCodes
            ) { "Invalid host feature install reason" }
            require(
                when (installState) {
                    DiagnosticFeatureInstallState.INSTALLED -> hookCount > 0 && reasonCode == null
                    DiagnosticFeatureInstallState.NOT_REPORTED -> hookCount == 0 && reasonCode == null
                    else -> hookCount == 0 && reasonCode != null
                }
            ) { "Invalid host feature install state" }
            feature.getBoolean("runtimeEvidenceExpected")
        }
        val excluded = root.getJSONArray("excluded")
        require(excluded.length() == excludedCategories.size) { "Invalid privacy declaration" }
        excludedCategories.forEachIndexed { index, expected ->
            require(excluded.getString(index) == expected) { "Invalid privacy declaration" }
        }
        return DiagnosticReportMetadata(
            collectedAtEpochMs = root.getLong("collectedAtEpochMs"),
            overallSeverity = severity,
            itemCount = items.length()
        )
    }

    private fun String?.boundedCode(allowed: Set<String>): String? = when {
        this == null -> null
        this in allowed -> this
        else -> "unknown"
    }
}
