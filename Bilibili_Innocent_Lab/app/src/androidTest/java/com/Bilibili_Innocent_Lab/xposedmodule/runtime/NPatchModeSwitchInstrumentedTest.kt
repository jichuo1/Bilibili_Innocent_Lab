package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 安装切换由外部 ADB 完成。此检查不重启宿主、不改业务设置，以免代替待验的恢复动作。
 * checkpoint 与 retained 之间不打开设置首页；首页会刷新自由复制运行修订号，属于另一笔配置变更。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class NPatchModeSwitchInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val file = "files/npatch-mode-switch.json"
    private val testPackage = "com.Bilibili_Innocent_Lab.xposedmodule.test"

    @Test fun installedModeRetainsCurrentConfigurationAndFreshHostAuthority() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit mode switch phase required", args.getString("modePhase") != null)
        val phase = requireNotNull(args.getString("modePhase"))
        require(phase in setOf("checkpoint", "retained"))
        val expected = NPatchMode.valueOf(requireNotNull(args.getString("expectedMode")))
        require(expected != NPatchMode.UNKNOWN)
        assertEquals(expected, NPatchRuntimeProfiles.read(context).mode)
        assertTrue(NoRootSupportStore.isDesiredEnabled(context))
        val current = requireNotNull(PublicationAuthorityStore.current(context))
        assertTrue(current.decision.isAuthorized)
        if (phase == "retained") {
            val record = JSONObject(shell("run-as $testPackage cat $file"))
            assertEquals(HostAdmissionContract.identityJson(record.getJSONObject("identity")), current.identity)
            assertEquals(record.getLong("noRootRevision"), current.noRootRevision)
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var actual: HostRuntimeDiagnosticsSnapshot? = null
        while (System.nanoTime() < deadline && actual == null) {
            val latch = CountDownLatch(1)
            val response = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
            HostRuntimeDiagnosticsQueryClient.query(context) { response.set(it); latch.countDown() }
            if (latch.await(5, TimeUnit.SECONDS)) {
                actual = response.get()?.snapshot?.takeIf { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
            }
            if (actual == null) Thread.sleep(500)
        }
        val bootstrap = requireNotNull(actual) { "Current host did not complete installation" }.bootstrap
        assertEquals(HostConfigState.ACCEPTED, bootstrap.configState)
        assertEquals(HostAdmissionContract.DIRECT, bootstrap.configSource)
        assertEquals(current.identity, bootstrap.admission)
        assertEquals(current.identity, PublicationAuthorityStore.current(context)?.identity)
        assertTrue(bootstrap.hookPointInstalledCount > 0)
        val row = NPatchProcessDiagnostics.query(context).single { it.processName == "tv.danmaku.bili" }
        assertEquals(expected, row.mode)
        assertEquals(NPatchProcessState.INSTALLED, row.state)
        if (expected == NPatchMode.EMBEDDED) {
            assertEquals(NPatchEmbeddedBuildState.MATCHED, NPatchEmbeddedBuild.read(context))
            assertEquals(NPatchScopeProbe.ScopeState.UNKNOWN, NPatchScopeProbe.cached(context))
        } else assertEquals(NPatchEmbeddedBuildState.NOT_APPLICABLE, NPatchEmbeddedBuild.read(context))
        if (phase == "checkpoint") {
            val record = JSONObject().put("identity", HostAdmissionContract.identityJson(current.identity))
                .put("noRootRevision", current.noRootRevision).toString()
            shell("run-as $testPackage mkdir -p files")
            val pipes = instrumentation.uiAutomation.executeShellCommandRw("run-as $testPackage sh -c cat>$file")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(record.toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            assertTrue("Private checkpoint write failed", shell("run-as $testPackage cat $file").trim() == record)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nNPatch mode switch: mode=$expected phase=$phase; current identity matched; " +
                "hooks=${bootstrap.hookPointInstalledCount}\n")
        })
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
