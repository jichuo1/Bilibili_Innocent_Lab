package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.StandardPublisherPolicy

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
    SCOPE_MISSING,
    CONFIRMING,
    UNCONFIRMED,
    NOT_RUNNING,
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
    BYPASSED,
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
    PARTIAL,
    UNKNOWN,
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
    val hostQueryFailure: com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure =
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure.NONE,
    val hostBootstrapReached: Boolean = false,
    val hostConfigState: DiagnosticHostConfigState = DiagnosticHostConfigState.NOT_CHECKED,
    val hostConfigGeneration: Long = 0L,
    val hostConfigSource: String = "manager",
    val hostAdmissionPresent: Boolean = false,
    val hostAdmissionCurrent: Boolean = false,
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
     * 只在界面提供当前用户空间线索；服务缺失也可能来自框架投递或进程重连异常。
     * **不参与健康度评估**：分身用户本身不是故障，`ModuleHealthEvaluator`
     * 的 severity 不因它改变。也不进入 `DiagnosticReportCodec` 的导出白名单。
     */
    val moduleUserId: Int = 0,
    /** 当前用户下目标 App 的 userId；不可见或未安装时为 null。 */
    val targetUserId: Int? = null,
    /** 模块与目标是否属于同一 Android 用户；目标不可见时为 null，不做猜测。 */
    val sameAndroidUser: Boolean? = null,
    val frameworkVersion: String? = null,
    val frameworkVersionCode: Long? = null,
    val frameworkProperties: Long? = null,
    val frameworkConnectionId: Long = 0L,
    val frameworkFailureCode: String? = null,
    val remoteConnectionId: Long = 0L,
    /** 已安装宿主的补丁档案，仅用于本地详情；不扩展当前导出报告白名单。 */
    val nPatchEnvironment: com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchEnvironment? = null,
    val nPatchEmbeddedBuild: com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchEmbeddedBuildState =
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchEmbeddedBuildState.UNKNOWN,
    val noRootSyncDetail: String? = null,
    /** 本次有界查询中实际响应的 NPatch 进程；支持包独立导出，不进入主报告或激活评估。 */
    val nPatchProcesses: List<com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessSnapshot> = emptyList(),
    val nPatchObservedModuleVersions: List<Long> = emptyList(),
    val nPatchPreviousModuleVersion: Long = 0L,
    val nPatchPreviousModuleAt: Long = 0L,
    val nPatchManagerReachability: com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchManagerReachability =
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchManagerReachability.NOT_APPLICABLE
)

/**
 * 用户选中了 NPatch（免 Root）通道。`RemoteHookConfigStore.publishSnapshot` 在同一判据
 * （`NoRootSupportStore.isDesiredEnabled`）下以 "NPatch selected" 故意短路标准发布，所以标准发布器的
 * 状态、宿主 admission 代次对比在此期间都不代表故障。诊断里所有"因选了 NPatch 而降级"的判断
 * 都必须经过这一个入口，免得发布器的短路条件改了、诊断这边某一处没跟着改。
 */
internal val ModuleDiagnosticInputs.standardPublisherBypassed: Boolean
    get() = StandardPublisherPolicy.isBypassed(noRootDesiredEnabled)

/**
 * 标准发布器旁路期间"永远不会推进"的状态：既不是故障也不是"等待服务后发布"。
 * `READY` 是选中前的历史提交，照常显示；`PUBLISHING` 是进行中的真实发布，也照常显示。
 */
internal fun ModuleDiagnosticInputs.isIdleUnderStandardPublisherBypass(): Boolean =
    standardPublisherBypassed && remotePublishState in BYPASSED_IDLE_PUBLISH_STATES

private val BYPASSED_IDLE_PUBLISH_STATES = setOf(
    DiagnosticRemotePublishState.BYPASSED,
    DiagnosticRemotePublishState.FAILED,
    DiagnosticRemotePublishState.WAITING_FOR_SERVICE,
    DiagnosticRemotePublishState.NOT_INITIALIZED
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
    val runtimeEvidenceExpected: Boolean = false,
    val runtimeError: Boolean = false
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
        // 选中 NPatch 时标准发布器被故意短路，此时的 FAILED 是设计内状态而不是故障；
        // 配置投递另由 NO_ROOT 一项判断。
        inputs.isIdleUnderStandardPublisherBypass() -> DiagnosticSeverity.INFO
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
            DiagnosticNoRootState.NOT_RUNNING -> DiagnosticSeverity.ACTION_REQUIRED
            DiagnosticNoRootState.UNCONFIRMED,
            DiagnosticNoRootState.DISABLE_RESTART_REQUIRED_ACTIVE,
            DiagnosticNoRootState.DISABLE_RESTART_REQUIRED,
            DiagnosticNoRootState.RESTART_REQUIRED,
            DiagnosticNoRootState.MANAGER_MISSING,
            DiagnosticNoRootState.MODULE_NOT_REGISTERED,
            DiagnosticNoRootState.SCOPE_MISSING,
            DiagnosticNoRootState.CONNECTION_TIMEOUT,
            DiagnosticNoRootState.ERROR -> DiagnosticSeverity.ATTENTION
            else -> DiagnosticSeverity.INFO
        }
    }
}
