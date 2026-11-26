package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住公平内存适配的进程边界：只进模块设置 App，不进宿主，不改已验证通道。
 */
class FairRunningMemoryCallSiteTest {

    private fun dirOf(relative: String): File =
        sequenceOf(File(relative), File("app/$relative")).firstOrNull(File::isDirectory)
            ?: error("找不到源码目录: $relative")

    private fun fileOf(relative: String): File =
        sequenceOf(File(relative), File("app/$relative")).firstOrNull(File::isFile)
            ?: error("找不到文件: $relative")

    private val javaRoot: File by lazy {
        dirOf("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule")
    }

    private fun kotlinSources(): List<File> =
        javaRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    fun `the scan actually sees the production sources`() {
        val names = kotlinSources().map(File::getName)
        assertTrue(names.size >= 80)
        assertTrue("DefaultApplication.kt" in names)
        assertTrue("HookEntry.kt" in names)
        assertTrue("FairRunningMemoryCoordinator.kt" in names)
    }

    fun `itgsa actions exist only in the fair-memory files`() {
        val offenders = kotlinSources().filter { file ->
            val text = file.readText()
            text.contains("itgsa.intent.action") &&
                file.name !in setOf(
                    "FairRunningMemoryContract.kt",
                    "FairRunningMemoryCoordinator.kt"
                )
        }.map(File::getName)
        assertEquals(emptyList<String>(), offenders)
    }

    fun `initialize is only called from the module application`() {
        val callers = kotlinSources().filter { file ->
            file.readText().contains("FairRunningMemoryCoordinator.initialize(")
        }.map(File::getName)
        assertEquals(listOf("DefaultApplication.kt"), callers)
    }

    fun `hook entry and system_server never mention the protocol`() {
        val hook = File(javaRoot, "hook/HookEntry.kt").readText()
        assertFalse(hook.contains("itgsa.intent.action"))
        assertFalse(hook.contains("FairRunningMemory"))
        assertFalse(hook.contains("ModuleMemoryPressureHub"))
    }

    fun `manifest does not export a process-starting itgsa receiver`() {
        val manifest = fileOf("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("itgsa.intent.action"))
        assertFalse(manifest.contains("FairRunningMemory"))
    }

    fun `fair-memory sources do not touch verified host or auth channels`() {
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
        val files = listOf(
            File(javaRoot, "runtime/FairRunningMemoryContract.kt"),
            File(javaRoot, "runtime/FairRunningMemoryCoordinator.kt")
        )
        files.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("${file.name} contains $token", text.contains(token))
            }
        }
        val coordinator = File(javaRoot, "runtime/FairRunningMemoryCoordinator.kt").readText()
        assertTrue(coordinator.contains("RECEIVER_EXPORTED"))
        assertFalse(coordinator.contains("RECEIVER_NOT_EXPORTED"))
        assertTrue(coordinator.contains("FLAG_ONEWAY"))
        assertFalse(coordinator.contains("readException"))
        assertTrue(
            "debug probe must stay behind BuildConfig.DEBUG",
            coordinator.contains("BuildConfig.DEBUG && intent.getBooleanExtra(DEBUG_PROBE_EXTRA")
        )
    }

    fun `module application registers fair memory before the terms early return`() {
        val app = File(javaRoot, "application/DefaultApplication.kt").readText()
        val fairIdx = app.indexOf("FairRunningMemoryCoordinator.initialize(")
        val termsIdx = app.indexOf("if (!termsDecision.isAuthorized")
        assertTrue("missing fair-memory initialize", fairIdx >= 0)
        assertTrue("missing terms gate", termsIdx >= 0)
        assertTrue("fair-memory must be registered even on the terms page", fairIdx < termsIdx)
        assertFalse(app.contains("itgsa.intent.action"))
    }

    fun `skin sessions register with the module-only pressure hub`() {
        val session = com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
            .engine("core/runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("LumenMemoryPressureHub::addListener"))
        assertTrue(session.contains("removeListener(this)"))
        assertTrue(session.contains("onReleaseGraphics"))
        assertTrue(session.contains("onLowMemory()"))
        val hub = File(javaRoot, "ui/skin/runtime/ModuleMemoryPressureHub.kt").readText()
        assertTrue(hub.contains("LumenEngine.releaseGraphics()"))
        val app = File(javaRoot, "application/DefaultApplication.kt").readText()
        assertTrue(app.contains("ModuleMemoryPressureHub.releaseGraphics()"))
    }

    fun `private callback registration stays on the NPatch path`() {
        val compat = File(javaRoot, "runtime/CrossAppBroadcastCompat.kt").readText()
        assertTrue(compat.contains("registerPrivateCallbackReceiver"))
        assertFalse(compat.contains("itgsa.intent.action"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FairRunningMemoryCallSiteTest) -> Unit) {
            val s = FairRunningMemoryCallSiteTest()
            block(s)
        }
        runIsolated { s -> s.`the scan actually sees the production sources`() }
        runIsolated { s -> s.`itgsa actions exist only in the fair-memory files`() }
        runIsolated { s -> s.`initialize is only called from the module application`() }
        runIsolated { s -> s.`hook entry and system_server never mention the protocol`() }
        runIsolated { s -> s.`manifest does not export a process-starting itgsa receiver`() }
        runIsolated { s -> s.`fair-memory sources do not touch verified host or auth channels`() }
        runIsolated { s -> s.`module application registers fair memory before the terms early return`() }
        runIsolated { s -> s.`skin sessions register with the module-only pressure hub`() }
        runIsolated { s -> s.`private callback registration stays on the NPatch path`() }
    }
}
