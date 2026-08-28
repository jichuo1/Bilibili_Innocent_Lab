package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertEquals
import org.junit.Test

class NoRootSupportStateTest {

    @Test
    fun `unsupported OS and disabled intent take precedence`() {
        assertEquals(
            NoRootDisplayState.UNSUPPORTED_OS,
            NoRootSupportState.displayState(
                sdkInt = NoRootSupportState.MIN_SUPPORTED_SDK - 1,
                status = status(desired = true, state = NoRootSupportStore.SyncState.ACTIVE),
                currentSnapshot = snapshot(),
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.DISABLED,
            NoRootSupportState.displayState(
                sdkInt = NoRootSupportState.MIN_SUPPORTED_SDK,
                status = status(desired = false, state = NoRootSupportStore.SyncState.ACTIVE),
                currentSnapshot = snapshot(),
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME
            )
        )
    }

    @Test
    fun `active requires a heartbeat matching the current snapshot`() {
        val snapshot = snapshot()
        val matching = status(
            desired = true,
            state = NoRootSupportStore.SyncState.ACTIVE,
            heartbeatRevision = snapshot.revision,
            heartbeatModuleVersion = snapshot.moduleVersionCode,
            heartbeatTargetVersion = TARGET_VERSION,
            heartbeatTargetUpdateTime = TARGET_UPDATE_TIME,
            heartbeatTargetPackage = NoRootSupportState.TARGET_PACKAGE,
            heartbeatReceivedAt = 1_700_000_000_000L
        )

        assertEquals(
            NoRootDisplayState.ACTIVE,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching.copy(heartbeatRevision = snapshot.revision - 1L),
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                currentSnapshot = null,
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                currentTargetVersionCode = TARGET_VERSION + 1L,
                currentTargetUpdateTime = TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME + 1L
            )
        )
    }

    @Test
    fun `transport states map without claiming activation`() {
        val expected = mapOf(
            NoRootSupportStore.SyncState.DISABLED to NoRootDisplayState.CHECKING,
            NoRootSupportStore.SyncState.CHECKING to NoRootDisplayState.CHECKING,
            NoRootSupportStore.SyncState.MANAGER_MISSING to NoRootDisplayState.MANAGER_MISSING,
            NoRootSupportStore.SyncState.MODULE_NOT_REGISTERED to
                NoRootDisplayState.MODULE_NOT_REGISTERED,
            NoRootSupportStore.SyncState.SYNCING to NoRootDisplayState.SYNCING,
            NoRootSupportStore.SyncState.RESTART_REQUIRED to
                NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED to
                NoRootDisplayState.DISABLE_RESTART_REQUIRED,
            NoRootSupportStore.SyncState.CONNECTION_TIMEOUT to
                NoRootDisplayState.CONNECTION_TIMEOUT,
            NoRootSupportStore.SyncState.ERROR to NoRootDisplayState.ERROR
        )

        expected.forEach { (syncState, displayState) ->
            assertEquals(
                displayState,
                NoRootSupportState.displayState(
                    sdkInt = 28,
                    status = status(desired = true, state = syncState),
                    currentSnapshot = snapshot(),
                    currentTargetVersionCode = TARGET_VERSION,
                    currentTargetUpdateTime = TARGET_UPDATE_TIME
                )
            )
        }
    }

    @Test
    fun `disable pending only preserves activation after a verified heartbeat`() {
        val pending = status(
            desired = false,
            state = NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED
        )
        assertEquals(
            NoRootDisplayState.DISABLE_RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                pending,
                snapshot(),
                TARGET_VERSION,
                TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE,
            NoRootSupportState.displayState(
                28,
                pending.copy(disableWasActive = true),
                snapshot(),
                TARGET_VERSION,
                TARGET_UPDATE_TIME
            )
        )
    }

    @Test
    fun `root activation wins and no-root activates only after heartbeat`() {
        assertEquals(
            ActivationDecision(activated = true, byNoRoot = false),
            NoRootSupportState.activationDecision(
                rootActive = true,
                displayState = NoRootDisplayState.ACTIVE
            )
        )
        assertEquals(
            ActivationDecision(activated = true, byNoRoot = true),
            NoRootSupportState.activationDecision(
                rootActive = false,
                displayState = NoRootDisplayState.ACTIVE
            )
        )
        assertEquals(
            ActivationDecision(activated = true, byNoRoot = true),
            NoRootSupportState.activationDecision(
                rootActive = false,
                displayState = NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE
            )
        )
        assertEquals(
            ActivationDecision(activated = false, byNoRoot = false),
            NoRootSupportState.activationDecision(
                rootActive = false,
                displayState = NoRootDisplayState.DISABLE_RESTART_REQUIRED
            )
        )
        assertEquals(
            ActivationDecision(activated = false, byNoRoot = false),
            NoRootSupportState.activationDecision(
                rootActive = false,
                displayState = NoRootDisplayState.RESTART_REQUIRED
            )
        )
    }

    private fun snapshot() = NoRootConfigSnapshot(
        schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
        catalogVersion = 3,
        modulePackage = "com.Bilibili_Innocent_Lab.xposedmodule",
        moduleVersionCode = 8L,
        revision = 42L,
        adapterResetRevision = 0L,
        enabled = true,
        values = mapOf("feature.enabled" to true)
    )

    private fun status(
        desired: Boolean,
        state: NoRootSupportStore.SyncState,
        heartbeatRevision: Long = 0L,
        heartbeatModuleVersion: Long = 0L,
        heartbeatTargetVersion: Long = 0L,
        heartbeatTargetUpdateTime: Long = 0L,
        heartbeatTargetPackage: String? = null,
        heartbeatReceivedAt: Long = 0L,
        disableWasActive: Boolean = false
    ) = NoRootSupportStore.Status(
        desiredEnabled = desired,
        syncState = state,
        detail = null,
        syncRevision = 0L,
        heartbeatRevision = heartbeatRevision,
        heartbeatModuleVersion = heartbeatModuleVersion,
        heartbeatTargetVersion = heartbeatTargetVersion,
        heartbeatTargetUpdateTime = heartbeatTargetUpdateTime,
        heartbeatTargetPackage = heartbeatTargetPackage,
        heartbeatReceivedAt = heartbeatReceivedAt,
        disableWasActive = disableWasActive
    )

    private companion object {
        const val TARGET_VERSION = 9_090_300L
        const val TARGET_UPDATE_TIME = 1_700_000_000_000L
    }
}
