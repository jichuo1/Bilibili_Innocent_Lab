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
    HOST_BOOTSTRAP,
    FEATURE_COVERAGE,
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

internal enum class DiagnosticHostQueryState {
    READY,
    TARGET_UNAVAILABLE,
    INVALID_RESPONSE
}

internal enum class DiagnosticHostConfigState {
    NOT_CHECKED,
    ACCEPTED,
    REJECTED,
    NOT_AUTHORIZED
}

internal enum class DiagnosticHostInstallChainState {
    NOT_STARTED,
    STARTED,
    COMPLETED,
    FAILED
}

internal enum class DiagnosticFeatureInstallState {
    NOT_REPORTED,
    DISABLED,
    NOT_APPLICABLE,
    INSTALLED,
    SKIPPED,
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
    /**
     * 高阶 Liquid 后端被 GPU 驱动拒绝时的有界原因（异常类型 + 截断 message）。
     *
     * **只在本机诊断界面展示，不进入可导出报告**：它是驱动侧的自由文本，无法像枚举 code 那样
     * 事先穷举与脱敏，因此不放进 `DiagnosticReportCodec` 的白名单字段。
     */
    val liquidBackendDegradeReason: String? = null,
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
    val hostFeatures: List<DiagnosticHostFeature> = emptyList(),
    val hostQueryState: DiagnosticHostQueryState = DiagnosticHostQueryState.TARGET_UNAVAILABLE,
    val hostBootstrapReached: Boolean = false,
    val hostConfigState: DiagnosticHostConfigState = DiagnosticHostConfigState.NOT_CHECKED,
    val hostConfigGeneration: Long = 0L,
    val hostConfigReasonCode: String? = null,
    val hostInstallChainState: DiagnosticHostInstallChainState =
        DiagnosticHostInstallChainState.NOT_STARTED,
    val hostHookPointResolvedCount: Int = 0,
    val hostHookPointInstalledCount: Int = 0,
    val hostHookPointMissingCount: Int = 0,
    val hostHookPointFailedCount: Int = 0,
    val hostInstalledFeatureCount: Int = 0,
    val hostFailedFeatureCount: Int = 0,
    /**
     * 模块进程所属的 Android userId（0 = 主用户）。
     *
     * 只用于把"框架没装"和"当前用户下没启用模块"这两种同样表现为"收不到 libxposed 服务"的
     * 情况在界面上区分开，**不参与健康度评估**：分身用户本身不是故障，`ModuleHealthEvaluator`
     * 的 severity 不因它改变。也不进入 `DiagnosticReportCodec` 的导出白名单。
     */
    val moduleUserId: Int = 0,
    /** 当前用户下目标 App 的 userId；不可见或未安装时为 null。 */
    val targetUserId: Int? = null,
    /** 模块与目标是否属于同一 Android 用户；目标不可见时为 null，不做猜测。 */
    val sameAndroidUser: Boolean? = null
)

internal data class DiagnosticItem(
    val id: DiagnosticItemId,
    val severity: DiagnosticSeverity,
    val evidence: DiagnosticEvidence
)

internal data class DiagnosticHostFeature(
    val featureId: String,
    val evidence: DiagnosticEvidence,
    val installState: DiagnosticFeatureInstallState = DiagnosticFeatureInstallState.NOT_REPORTED,
    val installedHookCount: Int = 0,
    val installReasonCode: String? = null,
    val runtimeEvidenceExpected: Boolean = false
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
                DiagnosticItemId.HOST_BOOTSTRAP,
                hostBootstrapSeverity(inputs),
                if (inputs.hostRuntimeReceiptAvailable) DiagnosticEvidence.OBSERVED
                else DiagnosticEvidence.NOT_AVAILABLE
            ),
            DiagnosticItem(
                DiagnosticItemId.FEATURE_COVERAGE,
                when {
                    !inputs.hostRuntimeReceiptAvailable -> DiagnosticSeverity.UNKNOWN
                    inputs.hostFailedFeatureCount > 0 -> DiagnosticSeverity.ATTENTION
                    inputs.hostInstalledFeatureCount > 0 -> DiagnosticSeverity.OK
                    else -> DiagnosticSeverity.INFO
                },
                if (inputs.hostRuntimeReceiptAvailable) DiagnosticEvidence.OBSERVED
                else DiagnosticEvidence.NOT_AVAILABLE
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

    private fun hostBootstrapSeverity(inputs: ModuleDiagnosticInputs): DiagnosticSeverity = when {
        !inputs.hostRuntimeReceiptAvailable &&
            inputs.hostQueryState == DiagnosticHostQueryState.INVALID_RESPONSE ->
            DiagnosticSeverity.ATTENTION
        !inputs.hostRuntimeReceiptAvailable -> DiagnosticSeverity.UNKNOWN
        inputs.hostConfigState == DiagnosticHostConfigState.REJECTED ||
            inputs.hostConfigState == DiagnosticHostConfigState.NOT_AUTHORIZED ||
            inputs.hostInstallChainState == DiagnosticHostInstallChainState.FAILED ->
            DiagnosticSeverity.ACTION_REQUIRED
        inputs.hostConfigState == DiagnosticHostConfigState.ACCEPTED &&
            inputs.hostInstallChainState == DiagnosticHostInstallChainState.COMPLETED ->
            DiagnosticSeverity.OK
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
