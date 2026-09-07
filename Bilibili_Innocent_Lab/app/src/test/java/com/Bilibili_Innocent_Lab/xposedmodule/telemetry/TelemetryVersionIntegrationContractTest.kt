package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 静态边界断言不替代跨进程/系统后台限制的真机验收。 */
class TelemetryVersionIntegrationContractTest {
    @Test fun `busy version signal is drained through normal revalidation rather than discarded`() {
        val coordinator = source("telemetry/TelemetryCoordinator.kt")
        val busy = coordinator.substringAfter("if (!uploadInFlight.compareAndSet(false, true))")
            .substringBefore("HostRuntimeDiagnosticsQueryClient.query")
        assertTrue(busy.contains("pendingVersionUpload.offer"))
        assertTrue(busy.contains("maybeUpload(appContext, versionChange = true, callback = callback)"))
        val finish = coordinator.substringAfter("private fun finishUpload(").substringBefore("private fun finishPreview(")
        assertTrue(finish.contains("mainHandler.post"))
        assertTrue(finish.indexOf("uploadInFlight.set(false)") < finish.indexOf("pending?.invoke()"))
        assertTrue(finish.contains("pendingVersionUpload.take()"))
    }
    private fun source(relative: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test fun `notification carries no report and receiver validates system identity`() {
        val receiver = source("receiver/TelemetryHostReadyReceiver.kt")
        assertTrue(receiver.contains("proof.creatorUid == uid"))
        assertTrue(receiver.contains("proof.creatorPackage == host"))
        assertTrue(receiver.contains("sentFromUid == uid"))
        assertTrue(receiver.contains("inFlight.compareAndSet(false, true)"))
        val provider = source("provider/RoamingCompatProvider.kt")
            .substringAfter("TelemetryVersionTrigger.METHOD -> {")
            .substringBefore("METHOD_REPORT_NO_ROOT_HEARTBEAT ->")
        assertTrue(provider.indexOf("enforceTrustedCaller()") < provider.indexOf("TelemetryVersionTrigger.handle(it)"))
        assertFalse(provider.contains("extras"))
    }

    @Test fun `host notification is once per process on an independent worker`() {
        val bootstrap = source("hook/HookEntry.kt").substringAfter("\"callApplicationOnCreate\",")
            .substringBefore("authorizeAndInstall(args.firstOrNull() as? Context)")
        assertTrue(bootstrap.contains("HostRuntimeDiagnosticsBridge.observeVersionLaunch(it)"))
        val host = source("runtime/HostRuntimeDiagnosticsBridge.kt")
            .substringAfter("fun recordInstallChainCompleted()")
            .substringBefore("fun recordInstallChainFailed()")
        assertTrue(host.contains("versionSignalSent.compareAndSet(false, true)"))
        assertTrue(host.contains("if (!hostOpened || !installCompleted) return"))
        assertTrue(host.indexOf("versionSignalExecutor.execute") < host.indexOf("acquireUnstableContentProviderClient"))
        assertFalse(host.contains("TelemetryHttpTransport"))
    }

    @Test fun `version state writes cannot consume manual or regular automatic quota`() {
        val store = source("telemetry/TelemetryStore.kt").substringAfter("fun versionDecision(")
            .substringBefore("fun getOrCreateIdentity(")
        listOf("KEY_MANUAL_ATTEMPTS", "KEY_MANUAL_RETRY_AT", "KEY_LAST_SUCCESS_AT", "KEY_NEXT_ATTEMPT_AT")
            .forEach { assertFalse(store.contains(it)) }
        assertTrue(store.contains(".commit()"))
        val coordinator = source("telemetry/TelemetryCoordinator.kt")
        assertTrue(coordinator.contains("receipt.source"))
        assertTrue(coordinator.contains("HostInstallChainState.COMPLETED"))
        assertTrue(coordinator.contains("if (!versionChange) TelemetryStore.recordCollectionUnavailable"))
    }
}
