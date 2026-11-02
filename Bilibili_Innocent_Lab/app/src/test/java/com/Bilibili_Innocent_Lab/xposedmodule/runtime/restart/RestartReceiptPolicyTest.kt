package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import org.junit.Assert.*
import org.junit.Test

class RestartReceiptPolicyTest {
    private val identity = PublicationIdentity("current-install", 1, 2, 3, "current-config")
    private val bootstrap = HostRuntimeBootstrapEvidence(configState = HostConfigState.ACCEPTED,
        installChainState = HostInstallChainState.COMPLETED, admission = identity)
    private val snapshot = HostRuntimeDiagnosticsSnapshot(100, "tv.danmaku.bili", emptyList(), bootstrap)
    private fun accepts(value: HostRuntimeDiagnosticsSnapshot? = snapshot, ready: Boolean = true,
                        pid: Int? = 200, started: Long? = 101, installed: Boolean = true) =
        RestartReceiptPolicy.accepts(identity, value, ready, 200, pid, started, 100, installed)

    @Test fun currentOnlineReceiptFromNewProcessIsAccepted() { assertTrue(accepts()) }
    @Test fun oldProcessWithIdenticalConfigurationIsRejected() { assertFalse(accepts(pid = 199)) }
    @Test fun oldSessionCannotConfirmEvenIfPidIsReused() { assertFalse(accepts(started = 99)) }
    @Test fun cachedOrMissingReceiptCannotConfirm() { assertFalse(accepts(ready = false)); assertFalse(accepts(value = null)) }
    @Test fun staleConfigurationAndPreviousInstallAreRejected() {
        listOf(identity.copy(snapshotRevision = 2), identity.copy(incarnation = "previous-install"),
            identity.copy(consentRevision = 0), identity.copy(fingerprint = "old-config")).forEach {
            assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(admission = it))))
        }
    }
    @Test fun partialInstallAndRejectedConfigurationCannotConfirm() {
        assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(installChainState = HostInstallChainState.STARTED))))
        assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(configState = HostConfigState.REJECTED))))
        assertFalse(accepts(installed = false))
    }
    @Test fun missingSessionIsUnknown() { assertFalse(accepts(pid = null)); assertFalse(accepts(started = null)) }
}
