package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticFeatureRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureSkipReason
import org.json.JSONArray
import org.json.JSONObject

internal enum class HostConfigState {
    NOT_CHECKED,
    ACCEPTED,
    REJECTED,
    NOT_AUTHORIZED
}

internal enum class HostInstallChainState {
    NOT_STARTED,
    STARTED,
    COMPLETED,
    FAILED
}

internal enum class HostFeatureInstallState {
    NOT_REPORTED,
    DISABLED,
    NOT_APPLICABLE,
    INSTALLED,
    PARTIAL,
    UNKNOWN,
    SKIPPED,
    FAILED
}

internal data class HostRuntimeBootstrapEvidence(
    val bootstrapReached: Boolean = false,
    val configState: HostConfigState = HostConfigState.NOT_CHECKED,
    val configGeneration: Long = 0L,
    val configReasonCode: String? = null,
    val installChainState: HostInstallChainState = HostInstallChainState.NOT_STARTED,
    val hookPointResolvedCount: Int = 0,
    val hookPointInstalledCount: Int = 0,
    val hookPointMissingCount: Int = 0,
    val hookPointFailedCount: Int = 0,
    val configSource: String = "manager",
    val admission: com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity? = null
)

internal data class HostRuntimeFeatureEvidence(
    val featureId: String,
    val adaptedCount: Long,
    val observedCount: Long,
    val appliedCount: Long,
    val installState: HostFeatureInstallState = HostFeatureInstallState.NOT_REPORTED,
    val installedHookCount: Int = 0,
    val installReasonCode: String? = null,
    val runtimeError: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", featureId)
        .put("adapted", adaptedCount)
        .put("observed", observedCount)
        .put("applied", appliedCount)
        .put("install", installState.name)
        .put("hooks", installedHookCount)
        .put("reason", installReasonCode ?: JSONObject.NULL)
        .put("runtime_error", runtimeError)
}

internal data class HostRuntimeDiagnosticsSnapshot(
    val capturedAtEpochMs: Long,
    val processName: String,
    val features: List<HostRuntimeFeatureEvidence>,
    val bootstrap: HostRuntimeBootstrapEvidence = HostRuntimeBootstrapEvidence()
) {
    val adaptedFeatureCount: Int
        get() = features.count { !DiagnosticFeatureRegistry.isAggregate(it.featureId) && it.adaptedCount > 0L }
    val observedFeatureCount: Int
        get() = features.count { !DiagnosticFeatureRegistry.isAggregate(it.featureId) && it.observedCount > 0L }
    val appliedFeatureCount: Int
        get() = features.count { !DiagnosticFeatureRegistry.isAggregate(it.featureId) && it.appliedCount > 0L }
    val installedFeatureCount: Int
        get() = features.count { !DiagnosticFeatureRegistry.isAggregate(it.featureId) && it.installState == HostFeatureInstallState.INSTALLED }
    val failedFeatureCount: Int
        get() = features.count {
            !DiagnosticFeatureRegistry.isAggregate(it.featureId) && (it.installState == HostFeatureInstallState.FAILED ||
                it.installState == HostFeatureInstallState.PARTIAL ||
                it.installState == HostFeatureInstallState.SKIPPED &&
                it.installReasonCode !in NON_ACTIONABLE_SKIP_CODES)
        }

    private companion object {
        val NON_ACTIONABLE_SKIP_CODES = setOf(
            FeatureSkipReason.DISABLED.name,
            FeatureSkipReason.NOT_APPLICABLE_PROCESS.name,
            FeatureSkipReason.NOT_APPLICABLE_HOST.name
        )
    }
}

/** 固定白名单诊断协议；拒绝任意键、文本、成员名和宿主业务数据。 */
internal object HostRuntimeDiagnosticsCodec {
    const val CURRENT_SCHEMA_VERSION = 3
    const val TARGET_PACKAGE = "tv.danmaku.bili"
    const val MAX_PAYLOAD_CHARS = 64 * 1024
    const val MAX_FEATURE_COUNT = 256
    const val MAX_COUNTER = 1L
    const val MAX_HOOK_COUNT = 256

    val allowedFeatureIds: Set<String> = DiagnosticFeatureRegistry.ids

    val allowedConfigReasonCodes: Set<String> = setOf(
        "admission_unavailable", "admission_denied", "admission_timeout",
        "remote_group_missing", "remote_stale_protocol", "remote_auth_rejected",
        "remote_group_unavailable",
        "remote_not_ready",
        "remote_key_set_mismatch",
        "remote_schema_mismatch",
        "remote_catalog_mismatch",
        "remote_terms_version_mismatch",
        "remote_terms_decision_invalid",
        "remote_generation_invalid",
        "remote_missing_key",
        "remote_setting_type_invalid",
        "remote_setting_value_invalid",
        "remote_runtime_type_invalid",
        "remote_runtime_value_invalid",
        "remote_digest_invalid",
        "remote_read_exception",
        "unknown"
    )

    val allowedInstallReasonCodes: Set<String> =
        FeatureSkipReason.entries.mapTo(linkedSetOf()) { it.name } +
            setOf("INSTALLER_EXCEPTION", "PARTIAL_COVERAGE", "CAPABILITY_UNVERIFIED")

    fun encode(snapshot: HostRuntimeDiagnosticsSnapshot): String = JSONObject()
        .put("schema", CURRENT_SCHEMA_VERSION)
        .put("captured_at", snapshot.capturedAtEpochMs.coerceAtLeast(1L))
        .put("process", TARGET_PACKAGE)
        .put("bootstrap", snapshot.bootstrap.sanitized().toJson())
        .put("features", JSONArray().apply {
            require(snapshot.features.size <= MAX_FEATURE_COUNT) { "Diagnostic capability overflow" }
            snapshot.features
                .asSequence()
                .filter { it.featureId in allowedFeatureIds }
                .distinctBy(HostRuntimeFeatureEvidence::featureId)
                .forEach { put(it.sanitized().toJson()) }
        })
        .toString()
        .also { require(it.length <= MAX_PAYLOAD_CHARS) }

    fun decodeOrNull(raw: String): HostRuntimeDiagnosticsSnapshot? = runCatching {
        if (raw.isBlank() || raw.length > MAX_PAYLOAD_CHARS) return@runCatching null
        val root = JSONObject(raw)
        if (root.optInt("schema") != CURRENT_SCHEMA_VERSION) return@runCatching null
        if (root.optString("process") != TARGET_PACKAGE) return@runCatching null
        val capturedAt = root.optLong("captured_at", 0L)
        if (capturedAt <= 0L) return@runCatching null
        val bootstrap = decodeBootstrap(root.optJSONObject("bootstrap") ?: return@runCatching null)
            ?: return@runCatching null
        val array = root.optJSONArray("features") ?: return@runCatching null
        if (array.length() > MAX_FEATURE_COUNT) return@runCatching null
        val features = ArrayList<HostRuntimeFeatureEvidence>(array.length())
        val ids = HashSet<String>()
        for (index in 0 until array.length()) {
            val value = array.getJSONObject(index)
            val id = value.optString("id")
            if (id !in allowedFeatureIds || !ids.add(id)) return@runCatching null
            if (value.opt("runtime_error") !is Boolean) return@runCatching null
            val evidence = HostRuntimeFeatureEvidence(
                featureId = id,
                adaptedCount = value.optLong("adapted", -1L),
                observedCount = value.optLong("observed", -1L),
                appliedCount = value.optLong("applied", -1L),
                installState = runCatching {
                    HostFeatureInstallState.valueOf(value.getString("install"))
                }.getOrNull() ?: return@runCatching null,
                installedHookCount = value.optInt("hooks", -1),
                installReasonCode = value.optString("reason")
                    .takeIf { it.isNotBlank() && it != "null" },
                runtimeError = value.getBoolean("runtime_error")
            )
            if (!evidence.isValid()) return@runCatching null
            features += evidence
        }
        HostRuntimeDiagnosticsSnapshot(capturedAt, TARGET_PACKAGE, features, bootstrap)
    }.getOrNull()

    fun increment(
        current: HostRuntimeFeatureEvidence?,
        featureId: String,
        stage: FeatureRuntimeStage,
        delta: Int
    ): HostRuntimeFeatureEvidence? {
        if (featureId !in allowedFeatureIds || delta <= 0) return current
        val base = current ?: HostRuntimeFeatureEvidence(featureId, 0L, 0L, 0L)
        return when (stage) {
            FeatureRuntimeStage.ADAPTED -> base.copy(adaptedCount = 1L)
            FeatureRuntimeStage.OBSERVED -> base.copy(observedCount = 1L)
            FeatureRuntimeStage.APPLIED -> base.copy(appliedCount = 1L)
            FeatureRuntimeStage.ERROR -> base.copy(runtimeError = true)
        }
    }

    fun withInstallOutcome(
        current: HostRuntimeFeatureEvidence?,
        featureId: String,
        state: HostFeatureInstallState,
        hookCount: Int,
        reasonCode: String?
    ): HostRuntimeFeatureEvidence? {
        if (featureId !in allowedFeatureIds) return current
        val boundedReason = reasonCode?.takeIf { it in allowedInstallReasonCodes }
        val updated = (current ?: HostRuntimeFeatureEvidence(featureId, 0L, 0L, 0L)).copy(
            installState = state,
            installedHookCount = hookCount.coerceIn(0, MAX_HOOK_COUNT),
            installReasonCode = boundedReason
        )
        return updated.takeIf { it.isValid() }
    }

    private fun decodeBootstrap(value: JSONObject): HostRuntimeBootstrapEvidence? {
        val configState = runCatching {
            HostConfigState.valueOf(value.getString("config"))
        }.getOrNull() ?: return null
        val installState = runCatching {
            HostInstallChainState.valueOf(value.getString("install_chain"))
        }.getOrNull() ?: return null
        val result = HostRuntimeBootstrapEvidence(
            bootstrapReached = value.optBoolean("reached", false),
            configState = configState,
            configGeneration = value.optLong("generation", -1L),
            configReasonCode = value.optString("config_reason")
                .takeIf { it.isNotBlank() && it != "null" },
            installChainState = installState,
            hookPointResolvedCount = value.optInt("hook_resolved", -1),
            hookPointInstalledCount = value.optInt("hook_installed", -1),
            hookPointMissingCount = value.optInt("hook_missing", -1),
            hookPointFailedCount = value.optInt("hook_failed", -1),
            configSource = value.optString("config_source", "manager"),
            admission = if (value.has("admission") && !value.isNull("admission")) {
                HostAdmissionContract.identityJson(value.optJSONObject("admission") ?: return null) ?: return null
            } else null
        )
        return result.takeIf { it.isValid() }
    }

    private fun HostRuntimeBootstrapEvidence.toJson(): JSONObject = JSONObject()
        .put("reached", bootstrapReached)
        .put("config", configState.name)
        .put("generation", configGeneration)
        .put("config_reason", configReasonCode ?: JSONObject.NULL)
        .put("install_chain", installChainState.name)
        .put("hook_resolved", hookPointResolvedCount)
        .put("hook_installed", hookPointInstalledCount)
        .put("hook_missing", hookPointMissingCount)
        .put("hook_failed", hookPointFailedCount)
        .put("config_source", configSource)
        .put("admission", admission?.let(HostAdmissionContract::identityJson) ?: JSONObject.NULL)

    private fun HostRuntimeBootstrapEvidence.isValid(): Boolean =
        configSource in setOf("manager", "module_direct") &&
            (configSource != "module_direct" || admission != null) &&
            configGeneration >= 0L &&
            hookPointResolvedCount in 0..MAX_HOOK_COUNT &&
            hookPointInstalledCount in 0..MAX_HOOK_COUNT &&
            hookPointMissingCount in 0..MAX_HOOK_COUNT &&
            hookPointFailedCount in 0..MAX_HOOK_COUNT &&
            (configReasonCode == null || configReasonCode in allowedConfigReasonCodes) &&
            when (configState) {
                HostConfigState.ACCEPTED -> configGeneration > 0L && configReasonCode == null
                HostConfigState.REJECTED -> configGeneration == 0L && configReasonCode != null
                HostConfigState.NOT_AUTHORIZED -> configGeneration > 0L && configReasonCode == null
                HostConfigState.NOT_CHECKED -> configGeneration == 0L && configReasonCode == null
            }

    private fun HostRuntimeBootstrapEvidence.sanitized(): HostRuntimeBootstrapEvidence = copy(
        configGeneration = configGeneration.coerceAtLeast(0L),
        configReasonCode = configReasonCode?.takeIf { it in allowedConfigReasonCodes }
            ?: configReasonCode?.let { "unknown" },
        hookPointResolvedCount = hookPointResolvedCount.coerceIn(0, MAX_HOOK_COUNT),
        hookPointInstalledCount = hookPointInstalledCount.coerceIn(0, MAX_HOOK_COUNT),
        hookPointMissingCount = hookPointMissingCount.coerceIn(0, MAX_HOOK_COUNT),
        hookPointFailedCount = hookPointFailedCount.coerceIn(0, MAX_HOOK_COUNT)
    )

    private fun HostRuntimeFeatureEvidence.isValid(): Boolean =
        featureId in allowedFeatureIds &&
            adaptedCount in 0L..1L && observedCount in 0L..MAX_COUNTER &&
            appliedCount in 0L..MAX_COUNTER && installedHookCount in 0..MAX_HOOK_COUNT &&
            (installReasonCode == null || installReasonCode in allowedInstallReasonCodes) &&
            when (installState) {
                HostFeatureInstallState.NOT_REPORTED ->
                    installedHookCount == 0 && installReasonCode == null
                HostFeatureInstallState.INSTALLED ->
                    installedHookCount > 0 && installReasonCode == null
                HostFeatureInstallState.PARTIAL ->
                    installedHookCount > 0 && installReasonCode == "PARTIAL_COVERAGE"
                HostFeatureInstallState.UNKNOWN ->
                    installedHookCount == 0 && installReasonCode == "CAPABILITY_UNVERIFIED"
                HostFeatureInstallState.DISABLED,
                HostFeatureInstallState.NOT_APPLICABLE,
                HostFeatureInstallState.SKIPPED,
                HostFeatureInstallState.FAILED ->
                    installedHookCount == 0 && installReasonCode != null
            }

    private fun HostRuntimeFeatureEvidence.sanitized(): HostRuntimeFeatureEvidence {
        val hooks = if (installState == HostFeatureInstallState.INSTALLED || installState == HostFeatureInstallState.PARTIAL) {
            installedHookCount.coerceIn(1, MAX_HOOK_COUNT)
        } else 0
        val reason = when (installState) {
            HostFeatureInstallState.PARTIAL -> "PARTIAL_COVERAGE"
            HostFeatureInstallState.UNKNOWN -> "CAPABILITY_UNVERIFIED"
            HostFeatureInstallState.NOT_REPORTED,
            HostFeatureInstallState.INSTALLED -> null
            else -> installReasonCode?.takeIf { it in allowedInstallReasonCodes } ?: "OTHER"
        }
        return copy(
            adaptedCount = adaptedCount.coerceIn(0L, 1L),
            observedCount = observedCount.coerceIn(0L, MAX_COUNTER),
            appliedCount = appliedCount.coerceIn(0L, MAX_COUNTER),
            installedHookCount = hooks,
            installReasonCode = reason
        )
    }
}
