package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 静态边界断言不替代跨进程/系统后台限制的真机验收。 */
class TelemetryVersionIntegrationContractTest {

    @Test
    fun `busy version signal drains via revalidation and receiver validates system identity`() {
        val coordinator = SourceContract.read("telemetry/TelemetryCoordinator.kt")
        val busy = coordinator.after("if (!uploadInFlight.compareAndSet(false, true))")
            .before("HostRuntimeDiagnosticsQueryClient.query")
        assertTrue(busy.contains("pendingVersionUpload.offer"))
        assertTrue(busy.contains("maybeUpload(appContext, versionChange = true, callback = callback)"))
        val finish = coordinator.after("private fun finishUpload(").before("private fun finishPreview(")
        assertTrue(finish.contains("mainHandler.post"))
        assertTrue(finish.indexOf("uploadInFlight.set(false)") < finish.indexOf("pending?.invoke()"))
        assertTrue(finish.contains("pendingVersionUpload.take()"))

        val receiver = SourceContract.read("receiver/TelemetryHostReadyReceiver.kt")
        assertTrue(receiver.contains("proof.creatorUid == uid"))
        assertTrue(receiver.contains("proof.creatorPackage == host"))
        assertTrue(receiver.contains("sentFromUid == uid"))
        assertTrue(receiver.contains("inFlight.compareAndSet(false, true)"))
        val provider = SourceContract.read("provider/RoamingCompatProvider.kt")
            .after("TelemetryVersionTrigger.METHOD -> {")
            .before("METHOD_REPORT_NO_ROOT_HEARTBEAT ->")
        assertTrue(provider.indexOf("enforceTrustedCaller()") < provider.indexOf("TelemetryVersionTrigger.handle(it)"))
        assertFalse(provider.contains("extras"))
    }

    @Test
    fun `host version notification is once per process and writes never consume upload quota`() {
        val bootstrap = SourceContract.read("hook/HookEntry.kt").after("\"callApplicationOnCreate\",")
            .before("authorizeAndInstall(")
        assertTrue(bootstrap.contains("HostRuntimeDiagnosticsBridge.observeVersionLaunch(it)"))
        val host = SourceContract.read("runtime/HostRuntimeDiagnosticsBridge.kt")
            .after("fun recordInstallChainCompleted()")
            .before("fun recordInstallChainFailed()")
        assertTrue(host.contains("versionSignalSent.compareAndSet(false, true)"))
        assertTrue(host.contains("if (!hostOpened || !installCompleted) return"))
        assertTrue(host.indexOf("versionSignalExecutor.execute") < host.indexOf("acquireUnstableContentProviderClient"))
        assertFalse(host.contains("TelemetryHttpTransport"))

        val store = SourceContract.read("telemetry/TelemetryStore.kt").after("fun versionDecision(")
            .before("fun getOrCreateIdentity(")
        listOf("KEY_MANUAL_ATTEMPTS", "KEY_MANUAL_RETRY_AT", "KEY_LAST_SUCCESS_AT", "KEY_NEXT_ATTEMPT_AT")
            .forEach { assertFalse(store.contains(it)) }
        assertTrue(store.contains(".commit()"))
        val coordinator = SourceContract.read("telemetry/TelemetryCoordinator.kt")
        assertTrue(coordinator.contains("receipt.source"))
        assertTrue(coordinator.contains("HostInstallChainState.COMPLETED"))
        assertTrue(coordinator.contains("if (!versionChange) TelemetryStore.recordCollectionUnavailable"))
    }
}
