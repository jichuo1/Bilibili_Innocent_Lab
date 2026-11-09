package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleHealthEvaluatorTest {
    @Test
    fun `verified no-root heartbeat produces healthy overall state`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.ACTIVE_NPATCH,
                noRootDesiredEnabled = true,
                noRootState = DiagnosticNoRootState.ACTIVE,
                remotePublishState = DiagnosticRemotePublishState.WAITING_FOR_SERVICE
            )
        )

        assertEquals(DiagnosticSeverity.OK, snapshot.overallSeverity)
        assertEquals(
            DiagnosticEvidence.OBSERVED,
            snapshot.item(DiagnosticItemId.ACTIVATION).evidence
        )
        assertEquals(
            DiagnosticSeverity.UNKNOWN,
            snapshot.item(DiagnosticItemId.HOST_ADAPTATION).severity
        )
    }

    @Test
    fun `missing activation evidence requires action`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.UNAVAILABLE,
                frameworkConnected = false,
                frameworkCapable = false
            )
        )

        assertEquals(DiagnosticSeverity.ACTION_REQUIRED, snapshot.overallSeverity)
        assertEquals(
            DiagnosticEvidence.NOT_AVAILABLE,
            snapshot.item(DiagnosticItemId.ACTIVATION).evidence
        )
    }

    @Test
    fun `missing NPatch scope requires action even with a stored heartbeat`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.UNAVAILABLE,
                frameworkConnected = true,
                frameworkCapable = true,
                noRootDesiredEnabled = true,
                noRootState = DiagnosticNoRootState.SCOPE_MISSING
            )
        )

        assertEquals(DiagnosticSeverity.ACTION_REQUIRED, snapshot.overallSeverity)
        assertEquals(
            DiagnosticSeverity.ATTENTION,
            snapshot.item(DiagnosticItemId.NO_ROOT).severity
        )
    }

    @Test
    fun `a silently unloaded module escalates to action required`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.UNAVAILABLE,
                frameworkConnected = true,
                frameworkCapable = true,
                noRootDesiredEnabled = true,
                noRootState = DiagnosticNoRootState.NOT_RUNNING
            )
        )

        assertEquals(DiagnosticSeverity.ACTION_REQUIRED, snapshot.overallSeverity)
        assertEquals(
            DiagnosticSeverity.ACTION_REQUIRED,
            snapshot.item(DiagnosticItemId.NO_ROOT).severity
        )
    }

    @Test
    fun `an unconfirmed no-root run asks for verification instead of claiming health`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.CHECKING,
                frameworkConnected = true,
                frameworkCapable = true,
                noRootDesiredEnabled = true,
                noRootState = DiagnosticNoRootState.UNCONFIRMED
            )
        )

        assertEquals(DiagnosticSeverity.ATTENTION, snapshot.overallSeverity)
        assertEquals(
            DiagnosticSeverity.ATTENTION,
            snapshot.item(DiagnosticItemId.NO_ROOT).severity
        )
    }

    @Test
    fun `remote read-back failure remains visible even while activated`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.ACTIVE_LSPOSED,
                frameworkConnected = true,
                frameworkCapable = true,
                remotePublishState = DiagnosticRemotePublishState.FAILED,
                remoteFailureCode = "publish_failed"
            )
        )

        assertEquals(DiagnosticSeverity.ATTENTION, snapshot.overallSeverity)
        assertEquals(
            DiagnosticSeverity.ATTENTION,
            snapshot.item(DiagnosticItemId.REMOTE_CONFIG).severity
        )
    }

    @Test
    fun `explicit bypass state keeps a healthy NPatch report ready`() {
        val selected = ModuleHealthEvaluator.evaluate(inputs(
            activationState = DiagnosticActivationState.ACTIVE_NPATCH,
            frameworkConnected = true, frameworkCapable = true,
            remotePublishState = DiagnosticRemotePublishState.BYPASSED,
            noRootDesiredEnabled = true, noRootState = DiagnosticNoRootState.ACTIVE))
        assertEquals(DiagnosticSeverity.INFO, selected.item(DiagnosticItemId.REMOTE_CONFIG).severity)
        assertEquals(DiagnosticSeverity.OK, selected.overallSeverity)
    }

    @Test
    fun `bypassed standard publisher is informational once NPatch is selected`() {
        // 选中 NPatch 时 RemoteHookConfigStore 故意返回 "NPatch selected"（FAILED/publish_failed）；
        // 那是设计内状态，不该把整份诊断染成“需要留意”。
        val selected = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.ACTIVE_NPATCH,
                frameworkConnected = true,
                frameworkCapable = true,
                remotePublishState = DiagnosticRemotePublishState.FAILED,
                remoteFailureCode = "publish_failed",
                noRootDesiredEnabled = true,
                noRootState = DiagnosticNoRootState.ACTIVE
            )
        )
        assertEquals(DiagnosticSeverity.INFO, selected.item(DiagnosticItemId.REMOTE_CONFIG).severity)
        assertEquals(DiagnosticSeverity.OK, selected.overallSeverity)

        // 未选 NPatch 时同样的 FAILED 仍是 ATTENTION（上一条用例已钉住整体结果）。
        val notSelected = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.ACTIVE_LSPOSED,
                frameworkConnected = true,
                frameworkCapable = true,
                remotePublishState = DiagnosticRemotePublishState.FAILED,
                remoteFailureCode = "publish_failed",
                noRootDesiredEnabled = false
            )
        )
        assertEquals(
            DiagnosticSeverity.ATTENTION,
            notSelected.item(DiagnosticItemId.REMOTE_CONFIG).severity
        )
    }

    @Test
    fun `skin fallback is attention and unknown adaptation is not a failure`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                activationState = DiagnosticActivationState.ACTIVE_LSPOSED,
                frameworkConnected = true,
                frameworkCapable = true,
                remotePublishState = DiagnosticRemotePublishState.READY,
                skinFallbackCode = "liquid_renderer_initialization_failed"
            )
        )

        assertEquals(DiagnosticSeverity.ATTENTION, snapshot.overallSeverity)
        assertTrue(snapshot.items.any { it.severity == DiagnosticSeverity.UNKNOWN })
    }

    @Test
    fun `ready generation with a queued update is still shown as pending`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(remotePublishPending = true)
        )

        assertEquals(
            DiagnosticSeverity.INFO,
            snapshot.item(DiagnosticItemId.REMOTE_CONFIG).severity
        )
    }

    @Test
    fun `host receipt distinguishes adapted observed and applied evidence`() {
        val adapted = ModuleHealthEvaluator.evaluate(
            inputs(
                hostRuntimeReceiptAvailable = true,
                hostAdaptedFeatureCount = 2
            )
        ).item(DiagnosticItemId.HOST_ADAPTATION)
        val applied = ModuleHealthEvaluator.evaluate(
            inputs(
                hostRuntimeReceiptAvailable = true,
                hostAdaptedFeatureCount = 2,
                hostObservedFeatureCount = 1,
                hostAppliedFeatureCount = 1
            )
        ).item(DiagnosticItemId.HOST_ADAPTATION)

        assertEquals(DiagnosticSeverity.OK, adapted.severity)
        assertEquals(DiagnosticEvidence.ADAPTED, adapted.evidence)
        assertEquals(DiagnosticEvidence.APPLIED, applied.evidence)
    }

    @Test
    fun `rejected host config identifies the authorization chain as actionable`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                hostRuntimeReceiptAvailable = true,
                hostQueryState = DiagnosticHostQueryState.READY,
                hostConfigState = DiagnosticHostConfigState.REJECTED,
                hostConfigReasonCode = "remote_digest_invalid"
            )
        )

        assertEquals(
            DiagnosticSeverity.ACTION_REQUIRED,
            snapshot.item(DiagnosticItemId.HOST_BOOTSTRAP).severity
        )
        assertEquals(DiagnosticSeverity.ACTION_REQUIRED, snapshot.overallSeverity)
    }

    @Test
    fun `isolated feature registration failure is attention without claiming runtime failure`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                hostRuntimeReceiptAvailable = true,
                hostInstalledFeatureCount = 12,
                hostFailedFeatureCount = 1
            )
        )

        assertEquals(
            DiagnosticSeverity.ATTENTION,
            snapshot.item(DiagnosticItemId.FEATURE_COVERAGE).severity
        )
        assertEquals(
            DiagnosticSeverity.INFO,
            snapshot.item(DiagnosticItemId.HOST_ADAPTATION).severity
        )
        assertEquals(
            DiagnosticEvidence.NOT_AVAILABLE,
            snapshot.item(DiagnosticItemId.HOST_ADAPTATION).evidence
        )
    }

    private fun ModuleDiagnosticSnapshot.item(id: DiagnosticItemId): DiagnosticItem =
        requireNotNull(items.firstOrNull { it.id == id })
    @Test
    fun `bypassed-publisher predicate covers exactly the states the bypass leaves idle`() {
        val expectedIdle = setOf(
            DiagnosticRemotePublishState.BYPASSED,
            DiagnosticRemotePublishState.FAILED,
            DiagnosticRemotePublishState.WAITING_FOR_SERVICE,
            DiagnosticRemotePublishState.NOT_INITIALIZED
        )
        DiagnosticRemotePublishState.entries.forEach { state ->
            assertEquals(
                "selected state=$state",
                state in expectedIdle,
                inputs(noRootDesiredEnabled = true, remotePublishState = state)
                    .isIdleUnderStandardPublisherBypass()
            )
            // 没选 NPatch：任何状态都不是"旁路空闲"，FAILED 仍是真故障。
            assertFalse(
                "not selected state=$state",
                inputs(noRootDesiredEnabled = false, remotePublishState = state)
                    .isIdleUnderStandardPublisherBypass()
            )
        }
        assertTrue(inputs(noRootDesiredEnabled = true).standardPublisherBypassed)
        assertFalse(inputs(noRootDesiredEnabled = false).standardPublisherBypassed)
    }
}

internal fun inputs(
    activationState: DiagnosticActivationState = DiagnosticActivationState.ACTIVE_LSPOSED,
    frameworkConnected: Boolean = true,
    frameworkCapable: Boolean = true,
    remotePublishState: DiagnosticRemotePublishState = DiagnosticRemotePublishState.READY,
    remoteFailureCode: String? = null,
    remotePublishPending: Boolean = false,
    noRootDesiredEnabled: Boolean = false,
    noRootState: DiagnosticNoRootState = DiagnosticNoRootState.DISABLED,
    skinFallbackCode: String? = null,
    hostRuntimeReceiptAvailable: Boolean = false,
    hostAdaptedFeatureCount: Int = 0,
    hostObservedFeatureCount: Int = 0,
    hostAppliedFeatureCount: Int = 0,
    hostFeatures: List<DiagnosticHostFeature> = emptyList(),
    hostQueryState: DiagnosticHostQueryState = DiagnosticHostQueryState.TARGET_UNAVAILABLE,
    hostConfigState: DiagnosticHostConfigState = DiagnosticHostConfigState.NOT_CHECKED,
    hostConfigReasonCode: String? = null,
    hostInstalledFeatureCount: Int = 0,
    hostFailedFeatureCount: Int = 0
) = ModuleDiagnosticInputs(
    collectedAtEpochMs = 1_800_000_000_000L,
    moduleVersionName = "1.1.0",
    moduleVersionCode = 110L,
    debugBuild = false,
    targetInstalled = true,
    targetVersionName = "8.60.0",
    targetVersionCode = 8_600_000L,
    targetLastUpdateAtEpochMs = 1_799_000_000_000L,
    frameworkConnected = frameworkConnected,
    frameworkCapable = frameworkCapable,
    frameworkName = "LSPosed",
    frameworkApiVersion = 102,
    remotePublishState = remotePublishState,
    remoteLastAttemptAtEpochMs = 1_799_999_000_000L,
    remoteLastSuccessAtEpochMs = 1_799_999_000_001L,
    remoteGeneration = 42L,
    remoteFailureCode = remoteFailureCode,
    remotePublishPending = remotePublishPending,
    activationState = activationState,
    noRootDesiredEnabled = noRootDesiredEnabled,
    noRootState = noRootState,
    requestedSkin = "LIQUID",
    effectiveSkin = if (skinFallbackCode == null) "LIQUID" else "MATERIAL_YOU",
    skinFallbackCode = skinFallbackCode,
    liquidBackendName = if (skinFallbackCode == null) "BLUR" else null,
    settingsCatalogVersion = 2,
    settingsTotalCount = 74,
    settingsAutomaticCount = 73,
    settingsManualCount = 1,
    loggingEnabled = true,
    verboseLogging = false,
    hostRuntimeReceiptAvailable = hostRuntimeReceiptAvailable,
    hostAdaptedFeatureCount = hostAdaptedFeatureCount,
    hostObservedFeatureCount = hostObservedFeatureCount,
    hostAppliedFeatureCount = hostAppliedFeatureCount,
    hostFeatures = hostFeatures,
    hostQueryState = hostQueryState,
    hostConfigState = hostConfigState,
    hostConfigReasonCode = hostConfigReasonCode,
    hostInstalledFeatureCount = hostInstalledFeatureCount,
    hostFailedFeatureCount = hostFailedFeatureCount
)
