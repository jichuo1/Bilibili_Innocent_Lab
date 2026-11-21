package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.frameworkHasSystemCapability
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isNpatchConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isRootFramework
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import io.github.libxposed.service.XposedService
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootSnapshotValuesTest {
    fun wrongPreferenceTypesFallBackWithoutLosingTheRestOfTheDocument() {
        val raw = SettingsCatalog.specs.associate { spec ->
            spec.storageKey to when (spec.defaultValue) {
                is SettingValue.Bool -> "old boolean"
                is SettingValue.IntValue -> false
                is SettingValue.Text -> 42
            }
        }
        val actual = snapshot(raw)
        assertEquals(SettingsCatalog.specs.map { it.storageKey }.toSet(), actual.keys)
        assertEquals(snapshot(emptyMap()), actual)
    }

    fun normalizationRetainsLegacyMaterialAndClampsContinuousValues() {
        val raw = mapOf(
            FeaturePreferences.HOST_CARD_ACRYLIC to true,
            FeaturePreferences.HOST_BACKGROUND_BLUR to 100,
            FeaturePreferences.HOST_BACKGROUND_SATURATION to -10,
            FeaturePreferences.HOST_BACKGROUND_PRESET to "unsupported"
        )
        val actual = snapshot(raw)
        assertEquals("acrylic", actual[FeaturePreferences.HOST_CARD_MATERIAL])
        assertEquals(60, actual[FeaturePreferences.HOST_BACKGROUND_BLUR])
        assertEquals(0, actual[FeaturePreferences.HOST_BACKGROUND_SATURATION])
        assertEquals(snapshot(emptyMap())[FeaturePreferences.HOST_BACKGROUND_PRESET],
            actual[FeaturePreferences.HOST_BACKGROUND_PRESET])
        assertEquals("original", snapshot(raw + (FeaturePreferences.HOST_CARD_MATERIAL to 42))[
            FeaturePreferences.HOST_CARD_MATERIAL])
    }

    fun credentialsRuntimeMetadataAndUnknownKeysStayOutOfTheSnapshotFile() {
        val raw = mapOf(
            RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY to "test-only-key",
            RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to 123L,
            RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to 456L,
            "unrelated" to true
        )
        val actual = snapshot(raw)
        assertEquals(snapshot(emptyMap()), actual)
        assertTrue(raw.keys.none(actual::containsKey))
    }

    fun publicationCapturesOnePreferenceMapWithoutTypedReadsOrWrites() {
        var reads = 0
        val preferences = preferences {
            check(++reads == 1) { "Snapshot mixed different settings batches" }
            mapOf(FeaturePreferences.HOST_BACKGROUND_BLUR to 20)
        }
        assertEquals(20, NoRootSupportStore.snapshotValues(preferences)[FeaturePreferences.HOST_BACKGROUND_BLUR])
        assertEquals(1, reads)
    }

    private fun snapshot(raw: Map<String, Any>) = NoRootSupportStore.snapshotValues(preferences { raw })

    private fun preferences(read: () -> Map<String, Any>): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> read()
                else -> error("Snapshot must not use ${method.name}")
            }
        } as SharedPreferences

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (NoRootSnapshotValuesTest) -> Unit) {
            val s = NoRootSnapshotValuesTest()
            block(s)
        }
        runIsolated { s -> s.wrongPreferenceTypesFallBackWithoutLosingTheRestOfTheDocument() }
        runIsolated { s -> s.normalizationRetainsLegacyMaterialAndClampsContinuousValues() }
        runIsolated { s -> s.credentialsRuntimeMetadataAndUnknownKeysStayOutOfTheSnapshotFile() }
        runIsolated { s -> s.publicationCapturesOnePreferenceMapWithoutTypedReadsOrWrites() }
    
        NoRootSupportStateCases().verifyAllCases()
        NoRootSupportStoreGuardCases().verifyAllCases()
        NoRootSyncFlightRegistryCases().verifyAllCases()
        NoRootTargetConfigBridgeCases().verifyAllCases()
        NoRootUpgradeRecoveryCoordinatorCases().verifyAllCases()
    }
}

private class NoRootSupportStateCases {

    fun `embedded gate takes precedence over matching historical activation evidence`() {
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

    fun lspatchEmbeddedWithoutManagerStillRequiresARealHostReceipt() {
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

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootSupportStateCases) -> Unit) {
            val s = NoRootSupportStateCases()
            block(s)
        }
        runIsolated { s -> s.`embedded gate takes precedence over matching historical activation evidence`() }
        runIsolated { s -> s.`unsupported OS and disabled intent take precedence`() }
        runIsolated { s -> s.`active requires a heartbeat matching the current snapshot`() }
        runIsolated { s -> s.`scope removal overrides a matching heartbeat but unknown keeps it`() }
        runIsolated { s -> s.`a live probe is required before claiming activation`() }
        runIsolated { s -> s.`a live probe never overrides a stale config heartbeat`() }
        runIsolated { s -> s.`scope removal wins over a live probe`() }
        runIsolated { s -> s.`runtime states win over a modern framework activation claim`() }
        runIsolated { s -> s.`scope removal wins over a confirmed LSPatch receipt`() }
        runIsolated { s -> s.`root activation ignores stale NPatch scope and runtime states`() }
        runIsolated { s -> s.`NPatch modern services cannot override scope and runtime failures`() }
        runIsolated { s -> s.`transport states map without claiming activation`() }
        runIsolated { s -> s.`disable pending only preserves activation after a verified heartbeat`() }
        runIsolated { s -> s.`root activation wins and no-root activates only after heartbeat`() }
        runIsolated { s -> s.`activation card distinguishes framework settling from unavailable`() }
        runIsolated { s -> s.`LSPatch state requires a receipt and does not override a valid NPatch heartbeat`() }
        runIsolated { s -> s.`LSPatch receipt state is bounded by config and install evidence`() }
        runIsolated { s -> s.lspatchEmbeddedWithoutManagerStillRequiresARealHostReceipt() }
        runIsolated { s -> s.`root framework truth table combines capability name and system bit`() }
    }
}

private class NoRootSupportStoreGuardCases {
    private val enabledSnapshot = NoRootConfigSnapshot(
        schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
        catalogVersion = 1,
        modulePackage = "com.example.module",
        moduleVersionCode = 1L,
        revision = 42L,
        adapterResetRevision = 0L,
        enabled = true,
        values = mapOf("feature" to true)
    )

    fun `disabled ack requires the current tombstone and matching module version`() {
        val disabled = enabledSnapshot.copy(enabled = false)
        assertTrue(NoRootSupportStore.acceptsDisabledAck(disabled, 42L, 1L))
        assertFalse(NoRootSupportStore.acceptsDisabledAck(enabledSnapshot, 42L, 1L))
        assertFalse(NoRootSupportStore.acceptsDisabledAck(disabled, 41L, 1L))
        assertFalse(NoRootSupportStore.acceptsDisabledAck(disabled, 42L, 2L))
    }

    fun `rejects late callback after user disables support`() {
        assertFalse(
            NoRootSupportStore.acceptsEnabledStateWrite(
                desiredEnabled = false,
                currentSnapshot = enabledSnapshot,
                expectedRevision = enabledSnapshot.revision,
                stillCurrent = true
            )
        )
    }

    fun `rejects stale generation even when revision still matches`() {
        assertFalse(
            NoRootSupportStore.acceptsEnabledStateWrite(
                desiredEnabled = true,
                currentSnapshot = enabledSnapshot,
                expectedRevision = enabledSnapshot.revision,
                stillCurrent = false
            )
        )
    }

    fun `rejects state from an older snapshot revision`() {
        assertFalse(
            NoRootSupportStore.acceptsEnabledStateWrite(
                desiredEnabled = true,
                currentSnapshot = enabledSnapshot.copy(revision = 43L),
                expectedRevision = enabledSnapshot.revision,
                stillCurrent = true
            )
        )
    }

    fun `accepts current matching enabled state`() {
        assertTrue(
            NoRootSupportStore.acceptsEnabledStateWrite(
                desiredEnabled = true,
                currentSnapshot = enabledSnapshot,
                expectedRevision = enabledSnapshot.revision,
                stillCurrent = true
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootSupportStoreGuardCases) -> Unit) {
            val s = NoRootSupportStoreGuardCases()
            block(s)
        }
        runIsolated { s -> s.`disabled ack requires the current tombstone and matching module version`() }
        runIsolated { s -> s.`rejects late callback after user disables support`() }
        runIsolated { s -> s.`rejects stale generation even when revision still matches`() }
        runIsolated { s -> s.`rejects state from an older snapshot revision`() }
        runIsolated { s -> s.`accepts current matching enabled state`() }
    }
}

private class NoRootSyncFlightRegistryCases {
    private val firstKey = NoRootSyncFlightRegistry.Key(
        intentGeneration = 1L,
        snapshotRevision = 10L,
        enabled = true,
        termsDecision = UserTermsDecision.ACCEPTED
    )

    fun `same revision joins one active flight`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val handled = mutableListOf<String>()
        val notified = mutableListOf<String?>()

        val first = registry.register(firstKey, handled::add, notified::add)
        val second = registry.register(firstKey, { error("must keep first handler") }) {
            notified += it
        }

        assertTrue(first.startsFlight)
        assertFalse(second.startsFlight)
        assertTrue(second.displacedListeners.isEmpty())
        assertEquals(first.token, second.token)
        val completion = registry.takeCompletion(first.token)!!
        completion.resultHandler("ok")
        completion.listeners.forEach { it("ok") }
        assertEquals(listOf("ok"), handled)
        assertEquals(listOf("ok", "ok"), notified)
    }

    fun `new revision supersedes older listener and becomes current`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val superseded = mutableListOf<String?>()
        registry.register(firstKey, {}, superseded::add)
        val nextKey = firstKey.copy(snapshotRevision = 11L)

        val registration = registry.register(nextKey, {}, null)
        val displacedToken = checkNotNull(registration.displacedToken)

        assertTrue(registration.startsFlight)
        assertEquals(firstKey, displacedToken.key)
        registration.displacedListeners.forEach { it(null) }
        assertEquals(listOf<String?>(null), superseded)
        assertFalse(registry.isCurrent(displacedToken))
        assertTrue(registry.isCurrent(registration.token))
        assertNull(registry.takeCompletion(displacedToken))
    }

    fun `cancel only removes matching current flight`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val notified = mutableListOf<String?>()
        val registration = registry.register(firstKey, {}, notified::add)
        val different = registry.register(firstKey.copy(snapshotRevision = 9L), {}, null)

        assertTrue(registry.cancel(registration.token).isEmpty())
        assertTrue(registry.isCurrent(different.token))
        registry.cancel(different.token).forEach { it(null) }

        assertTrue(notified.isEmpty())
        assertFalse(registry.isCurrent(different.token))
    }

    fun `intent generation and enabled mode are part of flight identity`() {
        val registry = NoRootSyncFlightRegistry<String>()
        registry.register(firstKey, {}, null)

        val disabled = firstKey.copy(intentGeneration = 2L, enabled = false)
        val registration = registry.register(disabled, {}, null)

        assertTrue(registration.startsFlight)
        assertEquals(firstKey, registration.displacedToken?.key)
        assertTrue(registry.isCurrent(registration.token))
    }

    fun `terms decision is part of flight identity`() {
        val registry = NoRootSyncFlightRegistry<String>()
        registry.register(firstKey, {}, null)

        val declined = firstKey.copy(termsDecision = UserTermsDecision.DECLINED)
        val registration = registry.register(declined, {}, null)

        assertTrue(registration.startsFlight)
        assertEquals(firstKey, registration.displacedToken?.key)
        assertTrue(registry.isCurrent(registration.token))
    }

    fun `late completion from retried same key cannot complete new attempt`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val first = registry.register(firstKey, {}, null)
        assertNotNull(registry.takeCompletion(first.token))

        val retry = registry.register(firstKey, {}, null)

        assertFalse(first.token == retry.token)
        assertNull(registry.takeCompletion(first.token))
        assertTrue(registry.isCurrent(retry.token))
        assertNotNull(registry.takeCompletion(retry.token))
    }

    fun `concurrent callers of same revision start exactly one attempt`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val start = CountDownLatch(1)
        val registrations = Collections.synchronizedList(
            mutableListOf<NoRootSyncFlightRegistry.Registration<String>>()
        )
        val threads = List(16) {
            Thread {
                start.await()
                registrations += registry.register(firstKey, {}, {})
            }
        }

        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, registrations.count { it.startsFlight })
        val token = registrations.first().token
        assertTrue(registrations.all { it.token == token })
        assertEquals(16, registry.takeCompletion(token)?.listeners?.size)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootSyncFlightRegistryCases) -> Unit) {
            val s = NoRootSyncFlightRegistryCases()
            block(s)
        }
        runIsolated { s -> s.`same revision joins one active flight`() }
        runIsolated { s -> s.`new revision supersedes older listener and becomes current`() }
        runIsolated { s -> s.`cancel only removes matching current flight`() }
        runIsolated { s -> s.`intent generation and enabled mode are part of flight identity`() }
        runIsolated { s -> s.`terms decision is part of flight identity`() }
        runIsolated { s -> s.`late completion from retried same key cannot complete new attempt`() }
        runIsolated { s -> s.`concurrent callers of same revision start exactly one attempt`() }
    }
}

private class NoRootTargetConfigBridgeCases {

    fun `disabled envelope remains explicit and carries tombstone revision`() {
        assertEquals(
            NoRootTargetConfigBridge.Resolution.Disabled(17L),
            NoRootTargetConfigBridge.resolve(true, false, 17L, null, "provider")
        )
    }

    fun `invalid or incomplete enabled envelope fails closed`() {
        assertEquals(
            NoRootTargetConfigBridge.Resolution.Invalid,
            NoRootTargetConfigBridge.resolve(false, true, 17L, "{}", "provider")
        )
        assertEquals(
            NoRootTargetConfigBridge.Resolution.Invalid,
            NoRootTargetConfigBridge.resolve(true, true, 17L, null, "provider")
        )
    }

    fun `ready snapshot must match envelope revision`() {
        val snapshot = NoRootConfigSnapshot(
            schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
            catalogVersion = 3,
            modulePackage = BuildConfig.APPLICATION_ID,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            revision = 23L,
            adapterResetRevision = 0L,
            enabled = true,
            values = mapOf("feature.enabled" to true)
        )
        val payload = NoRootConfigSnapshotCodec.encode(snapshot)
        val ready = NoRootTargetConfigBridge.resolve(true, true, 23L, payload, "callback")
        assertTrue(ready is NoRootTargetConfigBridge.Resolution.Ready)
        assertEquals(
            NoRootTargetConfigBridge.Resolution.Invalid,
            NoRootTargetConfigBridge.resolve(true, true, 24L, payload, "callback")
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootTargetConfigBridgeCases) -> Unit) {
            val s = NoRootTargetConfigBridgeCases()
            block(s)
        }
        runIsolated { s -> s.`disabled envelope remains explicit and carries tombstone revision`() }
        runIsolated { s -> s.`invalid or incomplete enabled envelope fails closed`() }
        runIsolated { s -> s.`ready snapshot must match envelope revision`() }
    }
}

private class NoRootUpgradeRecoveryCoordinatorCases {
    fun durableDirectExportCompletesRecoveryWithoutManagerMirror() {
        assertTrue(NoRootUpgradeRecoveryCoordinator.isRecoveryExportReady(true, true, 42, false, true))
        assertFalse(NoRootUpgradeRecoveryCoordinator.isRecoveryExportReady(false, true, 42, false, true))
        assertFalse(NoRootUpgradeRecoveryCoordinator.isRecoveryExportReady(true, true, 0, false, true))
        assertFalse(NoRootUpgradeRecoveryCoordinator.isRecoveryExportReady(true, true, 42, false, false))
    }


    fun `recovers only authorized enabled state without a ready snapshot`() {
        assertTrue(
            NoRootUpgradeRecoveryCoordinator.shouldRecoverSnapshot(
                authorized = true,
                desiredEnabled = true,
                hasReadySnapshot = false
            )
        )
    }

    fun `does not recover when terms are unauthorized`() {
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldRecoverSnapshot(
                authorized = false,
                desiredEnabled = true,
                hasReadySnapshot = false
            )
        )
    }

    fun `does not recover when no-root support is disabled`() {
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldRecoverSnapshot(
                authorized = true,
                desiredEnabled = false,
                hasReadySnapshot = false
            )
        )
    }

    fun `does not rewrite a strictly valid snapshot already synced to remote store`() {
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldRecoverSnapshot(
                authorized = true,
                desiredEnabled = true,
                hasReadySnapshot = true
            )
        )
    }

    fun `enabled export is not ready until its revision is remotely synced`() {
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.isRecoveryExportReady(
                exportValid = true,
                exportEnabled = true,
                revision = 42L,
                remoteSynced = false
            )
        )
        assertTrue(
            NoRootUpgradeRecoveryCoordinator.isRecoveryExportReady(
                exportValid = true,
                exportEnabled = true,
                revision = 42L,
                remoteSynced = true
            )
        )
    }

    fun `first failed attempt waits for the only retry`() {
        assertEquals(
            NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RETRY_WAIT,
            NoRootUpgradeRecoveryCoordinator.transitionAfterAttempt(
                stillNeedsRecovery = true,
                attemptsStarted = 1
            )
        )
    }

    fun `second failed attempt exhausts the per-process retry budget`() {
        assertEquals(
            NoRootUpgradeRecoveryCoordinator.RecoveryPhase.EXHAUSTED,
            NoRootUpgradeRecoveryCoordinator.transitionAfterAttempt(
                stillNeedsRecovery = true,
                attemptsStarted = NoRootUpgradeRecoveryCoordinator.MAX_ATTEMPTS_PER_PROCESS
            )
        )
    }

    fun `ready export completes without retry`() {
        assertEquals(
            NoRootUpgradeRecoveryCoordinator.RecoveryPhase.READY,
            NoRootUpgradeRecoveryCoordinator.transitionAfterAttempt(
                stillNeedsRecovery = false,
                attemptsStarted = 1
            )
        )
    }

    fun `retry starts only after cooldown and before budget is exhausted`() {
        val retryAt = 20_000L
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldStartRetry(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RETRY_WAIT,
                attemptsStarted = 1,
                nowElapsedMs = retryAt - 1L,
                retryNotBeforeElapsedMs = retryAt
            )
        )
        assertTrue(
            NoRootUpgradeRecoveryCoordinator.shouldStartRetry(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RETRY_WAIT,
                attemptsStarted = 1,
                nowElapsedMs = retryAt,
                retryNotBeforeElapsedMs = retryAt
            )
        )
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldStartRetry(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RETRY_WAIT,
                attemptsStarted = NoRootUpgradeRecoveryCoordinator.MAX_ATTEMPTS_PER_PROCESS,
                nowElapsedMs = retryAt,
                retryNotBeforeElapsedMs = retryAt
            )
        )
    }

    fun `first host query may start recovery only after its current export is built`() {
        assertTrue(
            NoRootUpgradeRecoveryCoordinator.shouldStartAttempt(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.NOT_STARTED,
                attemptsStarted = 0,
                nowElapsedMs = 1L,
                retryNotBeforeElapsedMs = 0L
            )
        )
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldStartAttempt(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RUNNING,
                attemptsStarted = 1,
                nowElapsedMs = 1L,
                retryNotBeforeElapsedMs = 0L
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootUpgradeRecoveryCoordinatorCases) -> Unit) {
            val s = NoRootUpgradeRecoveryCoordinatorCases()
            block(s)
        }
        runIsolated { s -> s.durableDirectExportCompletesRecoveryWithoutManagerMirror() }
        runIsolated { s -> s.`recovers only authorized enabled state without a ready snapshot`() }
        runIsolated { s -> s.`does not recover when terms are unauthorized`() }
        runIsolated { s -> s.`does not recover when no-root support is disabled`() }
        runIsolated { s -> s.`does not rewrite a strictly valid snapshot already synced to remote store`() }
        runIsolated { s -> s.`enabled export is not ready until its revision is remotely synced`() }
        runIsolated { s -> s.`first failed attempt waits for the only retry`() }
        runIsolated { s -> s.`second failed attempt exhausts the per-process retry budget`() }
        runIsolated { s -> s.`ready export completes without retry`() }
        runIsolated { s -> s.`retry starts only after cooldown and before budget is exhausted`() }
        runIsolated { s -> s.`first host query may start recovery only after its current export is built`() }
    }
}
