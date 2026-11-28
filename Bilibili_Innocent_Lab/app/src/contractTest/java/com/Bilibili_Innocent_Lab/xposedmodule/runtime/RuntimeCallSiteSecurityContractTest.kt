package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住运行时进程边界与宿主唤起权限：
 * - 公平内存适配只进模块设置 App，不进宿主、不改已验证通道；
 * - 宿主进程保底唤起只能由用户主动点开扫描面板触发，遥测与静默拉取不得开启 `allowWake`。
 */
class RuntimeCallSiteSecurityContractTest {

    @Test
    fun `fair running memory protocol stays isolated from host entry and manifest`() {
        val allSources = SourceContract.allMainKotlinSources()
        val fileNames = allSources.map { it.first.substringAfterLast('/') }
        assertTrue(allSources.size >= 80)
        assertTrue("DefaultApplication.kt" in fileNames)
        assertTrue("HookEntry.kt" in fileNames)
        assertTrue("FairRunningMemoryCoordinator.kt" in fileNames)

        val offenders = allSources
            .filter { (path, text) ->
                text.contains("itgsa.intent.action") &&
                    path !in setOf(
                        "runtime/FairRunningMemoryContract.kt",
                        "runtime/FairRunningMemoryCoordinator.kt"
                    )
            }
            .map { (path, _) -> path }
        assertEquals(emptyList<String>(), offenders)

        val callers = allSources
            .filter { (_, text) -> text.contains("FairRunningMemoryCoordinator.initialize(") }
            .map { (path, _) -> path }
        assertEquals(listOf("application/DefaultApplication.kt"), callers)

        val hook = SourceContract.read("hook/HookEntry.kt")
        assertFalse(hook.contains("itgsa.intent.action"))
        assertFalse(hook.contains("FairRunningMemory"))
        assertFalse(hook.contains("ModuleMemoryPressureHub"))

        val manifest = SourceContract.read("src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("itgsa.intent.action"))
        assertFalse(manifest.contains("FairRunningMemory"))
    }

    @Test
    fun `fair running memory avoids host auth channels and registers with module pressure hub`() {
        val forbidden = listOf(
            "HostProcessWaker",
            "allowWake",
            "RemoteHookConfig",
            "hook_config",
            "QUERY_HOOK",
            "sendBroadcast",
            "sendOrderedBroadcast",
            "Debug.getMemoryInfo",
            "Debug.getPss",
            "registerPrivateCallbackReceiver",
            "NoRootUpgrade",
            "TelemetryCoordinator",
            "HostAdmission"
        )
        listOf(
            "runtime/FairRunningMemoryContract.kt",
            "runtime/FairRunningMemoryCoordinator.kt"
        ).forEach { path ->
            val text = SourceContract.read(path)
            forbidden.forEach { token ->
                assertFalse("$path contains $token", text.contains(token))
            }
        }
        val coordinator = SourceContract.read("runtime/FairRunningMemoryCoordinator.kt")
        assertTrue(coordinator.contains("RECEIVER_EXPORTED"))
        assertFalse(coordinator.contains("RECEIVER_NOT_EXPORTED"))
        assertTrue(coordinator.contains("FLAG_ONEWAY"))
        assertFalse(coordinator.contains("readException"))
        assertTrue(
            "debug probe must stay behind BuildConfig.DEBUG",
            coordinator.contains("BuildConfig.DEBUG && intent.getBooleanExtra(DEBUG_PROBE_EXTRA")
        )

        val app = SourceContract.read("application/DefaultApplication.kt")
        val fairIdx = app.indexOf("FairRunningMemoryCoordinator.initialize(")
        val termsIdx = app.indexOf("if (!termsDecision.isAuthorized")
        assertTrue("missing fair-memory initialize", fairIdx >= 0)
        assertTrue("missing terms gate", termsIdx >= 0)
        assertTrue("fair-memory must be registered even on the terms page", fairIdx < termsIdx)
        assertFalse(app.contains("itgsa.intent.action"))

        val session = LceMotionSource.engine("core/runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("LumenMemoryPressureHub::addListener"))
        assertTrue(session.contains("removeListener(this)"))
        assertTrue(session.contains("onReleaseGraphics"))
        assertTrue(session.contains("onLowMemory()"))
        val hub = SourceContract.read("ui/skin/runtime/ModuleMemoryPressureHub.kt")
        assertTrue(hub.contains("LumenEngine.releaseGraphics()"))
        assertTrue(app.contains("ModuleMemoryPressureHub.releaseGraphics()"))

        val compat = SourceContract.read("runtime/CrossAppBroadcastCompat.kt")
        assertTrue(compat.contains("registerPrivateCallbackReceiver"))
        assertFalse(compat.contains("itgsa.intent.action"))
    }

    @Test
    fun `host process wake stays disabled for telemetry and silent pulls`() {
        val wakeSources = SourceContract.mainSourcesUnder("runtime") +
            SourceContract.mainSourcesUnder("ui/activity") +
            SourceContract.mainSourcesUnder("telemetry")
        val names = wakeSources.map { it.first.substringAfterLast('/') }
        assertTrue("没扫到源码，护栏会静默通过", wakeSources.size >= 30)
        listOf(
            "ReceiptQueryTransport.kt",
            "MineComponentSnapshotQueryClient.kt",
            "HostRuntimeDiagnosticsQueryClient.kt",
            "TelemetryCoordinator.kt",
            "RecommendationBlocklistDialogs.kt"
        ).forEach {
            assertTrue("$it 不在扫描范围内", it in names)
        }

        val client = SourceContract.read("runtime/MineComponentSnapshotQueryClient.kt")
        assertTrue("扫描客户端应当把自己的开关转发给 transport", client.contains("allowWake = wakeHost"))
        val allowWakeOffenders = wakeSources
            .filter { (_, text) -> text.contains("allowWake = true") }
            .map { (path, _) -> path }
        assertEquals("唤起宿主只准由用户主动打开的扫描面板触发", emptyList<String>(), allowWakeOffenders)

        val diagnostics = SourceContract.read("runtime/HostRuntimeDiagnosticsQueryClient.kt")
        assertTrue("诊断通道仍应调用同一条 transport", diagnostics.contains("ReceiptQueryTransport.query("))
        assertFalse(diagnostics.contains("allowWake"))

        val wakeHostOffenders = wakeSources
            .filter { (_, text) -> text.contains("wakeHost = true") }
            .map { (path, _) -> path }
        assertEquals("静默拉取不准唤起宿主", emptyList<String>(), wakeHostOffenders)

        val autoConfirm = SourceContract.read("ui/activity/RecommendationBlocklistDialogs.kt")
        assertTrue("自动确认的后台合并必须显式关掉唤起", autoConfirm.contains("wakeHost = false"))
    }
}
