package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.frameworkHasSystemCapability
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isNpatchConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isRootFramework
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import io.github.libxposed.service.XposedService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootSupportStateTest {

    @Test fun `embedded gate takes precedence over matching historical activation evidence`() {
        val snapshot = snapshot()
        val matching = status(desired = true, state = NoRootSupportStore.SyncState.ACTIVE,
            heartbeatRevision = snapshot.revision, heartbeatModuleVersion = snapshot.moduleVersionCode,
            heartbeatTargetVersion = TARGET_VERSION, heartbeatTargetUpdateTime = TARGET_UPDATE_TIME,
            heartbeatTargetPackage = NoRootSupportState.TARGET_PACKAGE, heartbeatReceivedAt = 1_700_000_000_000L)
        val blocked = NoRootSupportState.displayState(28, matching, snapshot, TARGET_VERSION,
            TARGET_UPDATE_TIME, probe = HostRuntimeProbeStatus.CONFIRMED, embeddedModeBlocked = true)
        assertEquals(NoRootDisplayState.EMBEDDED_UNSUPPORTED, blocked)
        for (npatch in listOf(false, true)) {
            assertEquals(ActivationDisplayState.EMBEDDED_UNSUPPORTED,
                NoRootSupportState.activationDisplayState(rootActive = true, frameworkCheckPending = false,
                    displayState = blocked, lspatchFramework = !npatch, nPatchFramework = npatch,
                    lspatchHostState = LspatchHostReceiptState.CONFIRMED))
        }
        assertEquals(ActivationDisplayState.ACTIVE_LSPOSED,
            NoRootSupportState.activationDisplayState(rootActive = true, frameworkCheckPending = false,
                displayState = blocked))
    }

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
                TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching.copy(heartbeatRevision = snapshot.revision - 1L),
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                currentSnapshot = null,
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                currentTargetVersionCode = TARGET_VERSION + 1L,
                currentTargetUpdateTime = TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME + 1L,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
    }

    @Test
    fun `scope removal overrides a matching heartbeat but unknown keeps it`() {
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
            NoRootDisplayState.SCOPE_MISSING,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                scope = NPatchScopeProbe.ScopeState.OUT_OF_SCOPE
            )
        )
        // Provider 读不到只能算未知；不能让一次临时查询失败把已激活降级。
        assertEquals(
            NoRootDisplayState.ACTIVE,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                scope = NPatchScopeProbe.ScopeState.UNKNOWN,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
        assertEquals(
            NoRootDisplayState.ACTIVE,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                scope = NPatchScopeProbe.ScopeState.IN_SCOPE,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
        // 开关已关闭时作用域不再影响状态文案。
        assertEquals(
            NoRootDisplayState.DISABLED,
            NoRootSupportState.displayState(
                28,
                matching.copy(desiredEnabled = false),
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                scope = NPatchScopeProbe.ScopeState.OUT_OF_SCOPE
            )
        )
    }

    @Test
    fun `a live probe is required before claiming activation`() {
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
                TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
        assertEquals(
            NoRootDisplayState.CONFIRMING,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.PROBING
            )
        )
        assertEquals(
            NoRootDisplayState.UNCONFIRMED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.IDLE
            )
        )
        assertEquals(
            NoRootDisplayState.UNCONFIRMED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.UNCONFIRMED
            )
        )
        assertEquals(
            NoRootDisplayState.NOT_RUNNING,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.NOT_RUNNING
            )
        )
    }

    @Test
    fun `a live probe never overrides a stale config heartbeat`() {
        val snapshot = snapshot()
        val matching = status(
            desired = true,
            state = NoRootSupportStore.SyncState.ACTIVE,
            heartbeatRevision = snapshot.revision - 1L,
            heartbeatModuleVersion = snapshot.moduleVersionCode,
            heartbeatTargetVersion = TARGET_VERSION,
            heartbeatTargetUpdateTime = TARGET_UPDATE_TIME,
            heartbeatTargetPackage = NoRootSupportState.TARGET_PACKAGE,
            heartbeatReceivedAt = 1_700_000_000_000L
        )

        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
    }

    @Test
    fun `scope removal wins over a live probe`() {
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
            NoRootDisplayState.SCOPE_MISSING,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME,
                scope = NPatchScopeProbe.ScopeState.OUT_OF_SCOPE,
                probe = HostRuntimeProbeStatus.CONFIRMED
            )
        )
    }

    @Test
    fun `runtime states win over a modern framework activation claim`() {
        for (display in listOf(
            NoRootDisplayState.NOT_RUNNING,
            NoRootDisplayState.UNCONFIRMED,
            NoRootDisplayState.CONFIRMING
        )) {
            val expected = when (display) {
                NoRootDisplayState.NOT_RUNNING -> ActivationDisplayState.NOT_RUNNING
                NoRootDisplayState.UNCONFIRMED -> ActivationDisplayState.UNCONFIRMED
                else -> ActivationDisplayState.CONFIRMING
            }
            assertEquals(
                expected,
                NoRootSupportState.activationDisplayState(
                    rootActive = true,
                    frameworkCheckPending = false,
                    displayState = display,
                    lspatchFramework = true,
                    lspatchHostState = LspatchHostReceiptState.CONFIRMED
                )
            )
        }
    }

    @Test
    fun `scope removal wins over a confirmed LSPatch receipt`() {
        assertEquals(
            ActivationDisplayState.SCOPE_MISSING,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.SCOPE_MISSING,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.CONFIRMED
            )
        )
    }

    @Test
    fun `root activation ignores stale NPatch scope and runtime states`() {
        val rootFlags = XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM
        for (name in listOf("LSPosed", "Vector", "Irena")) {
            for (properties in listOf(rootFlags, null)) {
                val framework = ModernFrameworkStatus(
                    connected = true, capable = true, name = name,
                    apiVersion = 102, properties = properties
                )
                for (state in listOf(
                    NoRootDisplayState.SCOPE_MISSING,
                    NoRootDisplayState.CONFIRMING,
                    NoRootDisplayState.UNCONFIRMED,
                    NoRootDisplayState.NOT_RUNNING
                )) {
                    assertEquals(
                        "$name properties=$properties state=$state",
                        ActivationDisplayState.ACTIVE_LSPOSED,
                        NoRootSupportState.activationDisplayState(
                            rootActive = framework.capable,
                            frameworkCheckPending = false,
                            displayState = state,
                            nPatchFramework = isNpatchConfigChannel(framework)
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `NPatch modern services cannot override scope and runtime failures`() {
        val expectedStates = mapOf(
            NoRootDisplayState.SCOPE_MISSING to ActivationDisplayState.SCOPE_MISSING,
            NoRootDisplayState.CONFIRMING to ActivationDisplayState.CONFIRMING,
            NoRootDisplayState.UNCONFIRMED to ActivationDisplayState.UNCONFIRMED,
            NoRootDisplayState.NOT_RUNNING to ActivationDisplayState.NOT_RUNNING
        )
        for (name in listOf("NPatch", "RenamedPatch")) {
            val framework = ModernFrameworkStatus(
                connected = true, capable = true, name = name,
                apiVersion = 102, properties = XposedService.PROP_CAP_REMOTE
            )
            for ((state, expected) in expectedStates) {
                assertEquals(
                    "$name state=$state",
                    expected,
                    NoRootSupportState.activationDisplayState(
                        rootActive = framework.capable,
                        frameworkCheckPending = false,
                        displayState = state,
                        nPatchFramework = isNpatchConfigChannel(framework)
                    )
                )
            }
        }
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

    @Test
    fun `activation card distinguishes framework settling from unavailable`() {
        assertEquals(
            ActivationDisplayState.ACTIVE_LSPOSED,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = true,
                displayState = NoRootDisplayState.ACTIVE
            )
        )
        assertEquals(
            ActivationDisplayState.ACTIVE_NPATCH,
            NoRootSupportState.activationDisplayState(
                rootActive = false,
                frameworkCheckPending = true,
                displayState = NoRootDisplayState.ACTIVE
            )
        )
        assertEquals(
            ActivationDisplayState.CHECKING,
            NoRootSupportState.activationDisplayState(
                rootActive = false,
                frameworkCheckPending = true,
                displayState = NoRootDisplayState.DISABLED
            )
        )
        assertEquals(
            ActivationDisplayState.UNAVAILABLE,
            NoRootSupportState.activationDisplayState(
                rootActive = false,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.DISABLED
            )
        )
    }

    @Test
    fun `LSPatch state requires a receipt and does not override a valid NPatch heartbeat`() {
        assertEquals(
            ActivationDisplayState.LSPATCH_WAITING_FOR_HOST,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.DISABLED,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.WAITING
            )
        )
        assertEquals(
            ActivationDisplayState.ACTIVE_LSPATCH,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.DISABLED,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.CONFIRMED
            )
        )
        assertEquals(
            ActivationDisplayState.LSPATCH_HOST_FAILED,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.DISABLED,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.FAILED
            )
        )
        assertEquals(
            ActivationDisplayState.ACTIVE_NPATCH,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.ACTIVE,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.WAITING
            )
        )
    }

    @Test
    fun `LSPatch receipt state is bounded by config and install evidence`() {
        assertEquals(
            LspatchHostReceiptState.CONFIRMED,
            NoRootSupportState.lspatchHostReceiptState(
                HostConfigState.ACCEPTED,
                HostInstallChainState.COMPLETED
            )
        )
        assertEquals(
            LspatchHostReceiptState.FAILED,
            NoRootSupportState.lspatchHostReceiptState(
                HostConfigState.REJECTED,
                HostInstallChainState.STARTED
            )
        )
        assertEquals(
            LspatchHostReceiptState.FAILED,
            NoRootSupportState.lspatchHostReceiptState(
                HostConfigState.ACCEPTED,
                HostInstallChainState.FAILED
            )
        )
        assertEquals(
            LspatchHostReceiptState.WAITING,
            NoRootSupportState.lspatchHostReceiptState(
                HostConfigState.ACCEPTED,
                HostInstallChainState.STARTED
            )
        )
    }

    @Test fun lspatchEmbeddedWithoutManagerStillRequiresARealHostReceipt() {
        for (receipt in LspatchHostReceiptState.entries) {
            assertEquals(when (receipt) {
                LspatchHostReceiptState.CONFIRMED -> ActivationDisplayState.ACTIVE_LSPATCH
                LspatchHostReceiptState.FAILED -> ActivationDisplayState.LSPATCH_HOST_FAILED
                LspatchHostReceiptState.WAITING -> ActivationDisplayState.LSPATCH_WAITING_FOR_HOST
            }, NoRootSupportState.activationDisplayState(
                rootActive = false, frameworkCheckPending = true, displayState = NoRootDisplayState.DISABLED,
                lspatchFramework = true, lspatchHostState = receipt))
        }
    }

    @Test
    fun `root framework truth table combines capability name and system bit`() {
        val system = XposedService.PROP_CAP_SYSTEM or XposedService.PROP_CAP_REMOTE
        val remoteOnly = XposedService.PROP_CAP_REMOTE
        // (capable, name, properties) -> isRootFramework
        assertTrue(isRootFramework(true, "LSPosed", system))
        assertTrue(isRootFramework(true, "LSPosed", null))
        assertFalse(isRootFramework(false, "LSPosed", system))
        assertFalse(isRootFramework(true, "NPatch", system))
        assertFalse(isRootFramework(true, "NPatch", null))
        assertFalse(isRootFramework(true, "SomethingElse", remoteOnly))
        assertNull(frameworkHasSystemCapability(null))
        assertEquals(true, frameworkHasSystemCapability(system))
        assertEquals(false, frameworkHasSystemCapability(remoteOnly))
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
