package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after

/** 静态边界断言不替代跨进程/系统后台限制的真机验收。 */
class TelemetryVersionIntegrationContractTest {
    fun `busy version signal is drained through normal revalidation rather than discarded`() {
        val coordinator = source("telemetry/TelemetryCoordinator.kt")
        val busy = coordinator.after("if (!uploadInFlight.compareAndSet(false, true))")
            .before("HostRuntimeDiagnosticsQueryClient.query")
        assertTrue(busy.contains("pendingVersionUpload.offer"))
        assertTrue(busy.contains("maybeUpload(appContext, versionChange = true, callback = callback)"))
        val finish = coordinator.after("private fun finishUpload(").before("private fun finishPreview(")
        assertTrue(finish.contains("mainHandler.post"))
        assertTrue(finish.indexOf("uploadInFlight.set(false)") < finish.indexOf("pending?.invoke()"))
        assertTrue(finish.contains("pendingVersionUpload.take()"))
    }
    private fun source(relative: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative"
        return SourceContract.read(path)
    }

    fun `notification carries no report and receiver validates system identity`() {
        val receiver = source("receiver/TelemetryHostReadyReceiver.kt")
        assertTrue(receiver.contains("proof.creatorUid == uid"))
        assertTrue(receiver.contains("proof.creatorPackage == host"))
        assertTrue(receiver.contains("sentFromUid == uid"))
        assertTrue(receiver.contains("inFlight.compareAndSet(false, true)"))
        val provider = source("provider/RoamingCompatProvider.kt")
            .after("TelemetryVersionTrigger.METHOD -> {")
            .before("METHOD_REPORT_NO_ROOT_HEARTBEAT ->")
        assertTrue(provider.indexOf("enforceTrustedCaller()") < provider.indexOf("TelemetryVersionTrigger.handle(it)"))
        assertFalse(provider.contains("extras"))
    }

    fun `host notification is once per process on an independent worker`() {
        val bootstrap = source("hook/HookEntry.kt").after("\"callApplicationOnCreate\",")
            // 2026-09-24：ef3cca9 把调用改成多行参数后，旧锚点（整行实参）早已失配，截取静默退回
            // 整份剩余文件、断言形同虚设；契约底座改为锚点缺失即失败后暴露。
            .before("authorizeAndInstall(")
        assertTrue(bootstrap.contains("HostRuntimeDiagnosticsBridge.observeVersionLaunch(it)"))
        val host = source("runtime/HostRuntimeDiagnosticsBridge.kt")
            .after("fun recordInstallChainCompleted()")
            .before("fun recordInstallChainFailed()")
        assertTrue(host.contains("versionSignalSent.compareAndSet(false, true)"))
        assertTrue(host.contains("if (!hostOpened || !installCompleted) return"))
        assertTrue(host.indexOf("versionSignalExecutor.execute") < host.indexOf("acquireUnstableContentProviderClient"))
        assertFalse(host.contains("TelemetryHttpTransport"))
    }

    fun `version state writes cannot consume manual or regular automatic quota`() {
        val store = source("telemetry/TelemetryStore.kt").after("fun versionDecision(")
            .before("fun getOrCreateIdentity(")
        listOf("KEY_MANUAL_ATTEMPTS", "KEY_MANUAL_RETRY_AT", "KEY_LAST_SUCCESS_AT", "KEY_NEXT_ATTEMPT_AT")
            .forEach { assertFalse(store.contains(it)) }
        assertTrue(store.contains(".commit()"))
        val coordinator = source("telemetry/TelemetryCoordinator.kt")
        assertTrue(coordinator.contains("receipt.source"))
        assertTrue(coordinator.contains("HostInstallChainState.COMPLETED"))
        assertTrue(coordinator.contains("if (!versionChange) TelemetryStore.recordCollectionUnavailable"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (TelemetryVersionIntegrationContractTest) -> Unit) {
            val s = TelemetryVersionIntegrationContractTest()
            block(s)
        }
        runIsolated { s -> s.`busy version signal is drained through normal revalidation rather than discarded`() }
        runIsolated { s -> s.`notification carries no report and receiver validates system identity`() }
        runIsolated { s -> s.`host notification is once per process on an independent worker`() }
        runIsolated { s -> s.`version state writes cannot consume manual or regular automatic quota`() }
    }
}
