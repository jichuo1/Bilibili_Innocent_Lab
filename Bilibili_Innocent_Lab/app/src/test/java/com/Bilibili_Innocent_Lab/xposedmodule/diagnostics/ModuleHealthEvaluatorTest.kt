package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import org.junit.Assert.assertEquals
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

    private fun ModuleDiagnosticSnapshot.item(id: DiagnosticItemId): DiagnosticItem =
        requireNotNull(items.firstOrNull { it.id == id })
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
    skinFallbackCode: String? = null
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
    verboseLogging = false
)
