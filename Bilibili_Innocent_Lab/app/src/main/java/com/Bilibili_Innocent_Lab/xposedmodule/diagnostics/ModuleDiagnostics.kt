package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

internal enum class DiagnosticSeverity {
    OK,
    INFO,
    ATTENTION,
    ACTION_REQUIRED,
    UNKNOWN
}

internal enum class DiagnosticEvidence {
    CONFIGURED,
    PUBLISHED,
    ADAPTED,
    OBSERVED,
    APPLIED,
    NOT_AVAILABLE
}

internal enum class DiagnosticItemId {
    MODULE_BUILD,
    TARGET_APP,
    FRAMEWORK_SERVICE,
    REMOTE_CONFIG,
    ACTIVATION,
    NO_ROOT,
    HOST_ADAPTATION,
    INTERFACE_SKIN,
    SETTINGS_CATALOG,
    LOGGING
}

internal enum class DiagnosticActivationState {
    CHECKING,
    ACTIVE_LSPOSED,
    ACTIVE_NPATCH,
    UNAVAILABLE
}

internal enum class DiagnosticNoRootState {
    UNSUPPORTED_OS,
    DISABLED,
    CHECKING,
    MANAGER_MISSING,
    MODULE_NOT_REGISTERED,
    SYNCING,
    RESTART_REQUIRED,
    DISABLE_RESTART_REQUIRED,
    DISABLE_RESTART_REQUIRED_ACTIVE,
    ACTIVE,
    CONNECTION_TIMEOUT,
    ERROR
}

internal enum class DiagnosticRemotePublishState {
    NOT_INITIALIZED,
    WAITING_FOR_SERVICE,
    PUBLISHING,
    READY,
    FAILED
}

internal data class ModuleDiagnosticInputs(
    val collectedAtEpochMs: Long,
    val moduleVersionName: String,
    val moduleVersionCode: Long,
    val debugBuild: Boolean,
    val targetInstalled: Boolean,
    val targetVersionName: String?,
    val targetVersionCode: Long,
    val targetLastUpdateAtEpochMs: Long,
    val frameworkConnected: Boolean,
    val frameworkCapable: Boolean,
    val frameworkName: String,
    val frameworkApiVersion: Int,
    val remotePublishState: DiagnosticRemotePublishState,
    val remoteLastAttemptAtEpochMs: Long,
    val remoteLastSuccessAtEpochMs: Long,
    val remoteGeneration: Long,
    val remoteFailureCode: String?,
    val remotePublishPending: Boolean,
    val activationState: DiagnosticActivationState,
    val noRootDesiredEnabled: Boolean,
    val noRootState: DiagnosticNoRootState,
    val requestedSkin: String,
    val effectiveSkin: String,
    val skinFallbackCode: String?,
    val liquidBackendName: String?,
    val settingsCatalogVersion: Int,
    val settingsTotalCount: Int,
    val settingsAutomaticCount: Int,
    val settingsManualCount: Int,
    val loggingEnabled: Boolean,
    val verboseLogging: Boolean,
    val hostRuntimeReceiptAvailable: Boolean = false,
    val hostRuntimeCapturedAtEpochMs: Long = 0L,
    val hostAdaptedFeatureCount: Int = 0,
    val hostObservedFeatureCount: Int = 0,
    val hostAppliedFeatureCount: Int = 0,
    val hostFeatures: List<DiagnosticHostFeature> = emptyList()
)

internal data class DiagnosticItem(
    val id: DiagnosticItemId,
    val severity: DiagnosticSeverity,
    val evidence: DiagnosticEvidence
)

internal data class DiagnosticHostFeature(
    val featureId: String,
    val evidence: DiagnosticEvidence
)

internal data class ModuleDiagnosticSnapshot(
    val inputs: ModuleDiagnosticInputs,
    val overallSeverity: DiagnosticSeverity,
    val items: List<DiagnosticItem>
)

/**
 * 纯状态归并器。UNKNOWN 是信息边界，不参与把整体状态升级为故障；只有可行动的问题才
 * 进入 ATTENTION/ACTION_REQUIRED。
 */
internal object ModuleHealthEvaluator {
    fun evaluate(inputs: ModuleDiagnosticInputs): ModuleDiagnosticSnapshot {
        val items = listOf(
            DiagnosticItem(
                DiagnosticItemId.MODULE_BUILD,
                DiagnosticSeverity.OK,
                DiagnosticEvidence.OBSERVED
            ),
            DiagnosticItem(
                DiagnosticItemId.TARGET_APP,
                if (inputs.targetInstalled) DiagnosticSeverity.OK
                else DiagnosticSeverity.ACTION_REQUIRED,
                DiagnosticEvidence.OBSERVED
            ),
            DiagnosticItem(
                DiagnosticItemId.FRAMEWORK_SERVICE,
                when {
                    inputs.frameworkCapable -> DiagnosticSeverity.OK
                    inputs.activationState == DiagnosticActivationState.CHECKING ->
                        DiagnosticSeverity.INFO
                    else -> DiagnosticSeverity.INFO
                },
                DiagnosticEvidence.OBSERVED
            ),
            DiagnosticItem(
                DiagnosticItemId.REMOTE_CONFIG,
                remoteSeverity(inputs),
                if (inputs.remotePublishState == DiagnosticRemotePublishState.READY) {
                    DiagnosticEvidence.PUBLISHED
                } else {
                    DiagnosticEvidence.CONFIGURED
                }
            ),
            DiagnosticItem(
                DiagnosticItemId.ACTIVATION,
                when (inputs.activationState) {
                    DiagnosticActivationState.ACTIVE_LSPOSED,
                    DiagnosticActivationState.ACTIVE_NPATCH -> DiagnosticSeverity.OK
                    DiagnosticActivationState.CHECKING -> DiagnosticSeverity.INFO
                    DiagnosticActivationState.UNAVAILABLE ->
                        DiagnosticSeverity.ACTION_REQUIRED
                },
                when (inputs.activationState) {
                    DiagnosticActivationState.ACTIVE_NPATCH -> DiagnosticEvidence.OBSERVED
                    DiagnosticActivationState.ACTIVE_LSPOSED -> DiagnosticEvidence.OBSERVED
                    else -> DiagnosticEvidence.NOT_AVAILABLE
                }
            ),
            DiagnosticItem(
                DiagnosticItemId.NO_ROOT,
                noRootSeverity(inputs),
                if (inputs.noRootState == DiagnosticNoRootState.ACTIVE ||
                    inputs.noRootState == DiagnosticNoRootState.DISABLE_RESTART_REQUIRED_ACTIVE
                ) {
                    DiagnosticEvidence.OBSERVED
                } else {
                    DiagnosticEvidence.CONFIGURED
                }
            ),
            DiagnosticItem(
                DiagnosticItemId.HOST_ADAPTATION,
                when {
                    !inputs.hostRuntimeReceiptAvailable -> DiagnosticSeverity.UNKNOWN
                    inputs.hostAdaptedFeatureCount > 0 -> DiagnosticSeverity.OK
                    else -> DiagnosticSeverity.INFO
                },
                when {
                    !inputs.hostRuntimeReceiptAvailable -> DiagnosticEvidence.NOT_AVAILABLE
                    inputs.hostAppliedFeatureCount > 0 -> DiagnosticEvidence.APPLIED
                    inputs.hostObservedFeatureCount > 0 -> DiagnosticEvidence.OBSERVED
                    inputs.hostAdaptedFeatureCount > 0 -> DiagnosticEvidence.ADAPTED
                    else -> DiagnosticEvidence.NOT_AVAILABLE
                }
            ),
            DiagnosticItem(
                DiagnosticItemId.INTERFACE_SKIN,
                if (inputs.skinFallbackCode == null) DiagnosticSeverity.OK
                else DiagnosticSeverity.ATTENTION,
                DiagnosticEvidence.OBSERVED
            ),
            DiagnosticItem(
                DiagnosticItemId.SETTINGS_CATALOG,
                DiagnosticSeverity.OK,
                DiagnosticEvidence.CONFIGURED
            ),
            DiagnosticItem(
                DiagnosticItemId.LOGGING,
                if (inputs.loggingEnabled) DiagnosticSeverity.OK else DiagnosticSeverity.INFO,
                DiagnosticEvidence.CONFIGURED
            )
        )
        val overall = when {
            items.any { it.severity == DiagnosticSeverity.ACTION_REQUIRED } ->
                DiagnosticSeverity.ACTION_REQUIRED
            items.any { it.severity == DiagnosticSeverity.ATTENTION } ->
                DiagnosticSeverity.ATTENTION
            else -> DiagnosticSeverity.OK
        }
        return ModuleDiagnosticSnapshot(inputs, overall, items)
    }

    private fun remoteSeverity(inputs: ModuleDiagnosticInputs): DiagnosticSeverity = when {
        inputs.remotePublishState == DiagnosticRemotePublishState.FAILED &&
            inputs.frameworkConnected -> DiagnosticSeverity.ATTENTION
        inputs.remotePublishState == DiagnosticRemotePublishState.PUBLISHING ||
            inputs.remotePublishPending -> DiagnosticSeverity.INFO
        inputs.remotePublishState == DiagnosticRemotePublishState.READY -> DiagnosticSeverity.OK
        else -> DiagnosticSeverity.INFO
    }

    private fun noRootSeverity(inputs: ModuleDiagnosticInputs): DiagnosticSeverity {
        if (!inputs.noRootDesiredEnabled &&
            inputs.noRootState != DiagnosticNoRootState.DISABLE_RESTART_REQUIRED_ACTIVE &&
            inputs.noRootState != DiagnosticNoRootState.DISABLE_RESTART_REQUIRED
        ) return DiagnosticSeverity.INFO
        return when (inputs.noRootState) {
            DiagnosticNoRootState.ACTIVE -> DiagnosticSeverity.OK
            DiagnosticNoRootState.DISABLE_RESTART_REQUIRED_ACTIVE,
            DiagnosticNoRootState.DISABLE_RESTART_REQUIRED,
            DiagnosticNoRootState.RESTART_REQUIRED,
            DiagnosticNoRootState.MANAGER_MISSING,
            DiagnosticNoRootState.MODULE_NOT_REGISTERED,
            DiagnosticNoRootState.CONNECTION_TIMEOUT,
            DiagnosticNoRootState.ERROR -> DiagnosticSeverity.ATTENTION
            else -> DiagnosticSeverity.INFO
        }
    }
}
