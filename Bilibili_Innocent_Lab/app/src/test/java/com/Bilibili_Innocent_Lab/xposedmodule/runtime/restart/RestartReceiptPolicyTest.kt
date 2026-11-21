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

    fun currentOnlineReceiptFromNewProcessIsAccepted() { assertTrue(accepts()) }
    fun oldProcessWithIdenticalConfigurationIsRejected() { assertFalse(accepts(pid = 199)) }
    fun oldSessionCannotConfirmEvenIfPidIsReused() { assertFalse(accepts(started = 99)) }
    fun cachedOrMissingReceiptCannotConfirm() { assertFalse(accepts(ready = false)); assertFalse(accepts(value = null)) }
    fun staleConfigurationAndPreviousInstallAreRejected() {
        listOf(identity.copy(snapshotRevision = 2), identity.copy(incarnation = "previous-install"),
            identity.copy(consentRevision = 0), identity.copy(fingerprint = "old-config")).forEach {
            assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(admission = it))))
        }
    }
    fun partialInstallAndRejectedConfigurationCannotConfirm() {
        assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(installChainState = HostInstallChainState.STARTED))))
        assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(configState = HostConfigState.REJECTED))))
        assertFalse(accepts(installed = false))
    }
    fun missingSessionIsUnknown() { assertFalse(accepts(pid = null)); assertFalse(accepts(started = null)) }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (RestartReceiptPolicyTest) -> Unit) {
            val s = RestartReceiptPolicyTest()
            block(s)
        }
        runIsolated { s -> s.currentOnlineReceiptFromNewProcessIsAccepted() }
        runIsolated { s -> s.oldProcessWithIdenticalConfigurationIsRejected() }
        runIsolated { s -> s.oldSessionCannotConfirmEvenIfPidIsReused() }
        runIsolated { s -> s.cachedOrMissingReceiptCannotConfirm() }
        runIsolated { s -> s.staleConfigurationAndPreviousInstallAreRejected() }
        runIsolated { s -> s.partialInstallAndRejectedConfigurationCannotConfirm() }
        runIsolated { s -> s.missingSessionIsUnknown() }
    }
}
