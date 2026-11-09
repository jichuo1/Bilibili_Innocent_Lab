package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 只对显式选择的有线专项机运行，改变配置并通过真实宿主 UID 验证授权和运行回执。 */
@RunWith(AndroidJUnit4::class)
class LspatchHostInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun currentConfigAndTermsSurviveOldNpatchPreferenceAndStoppedManager() {
        val patch = LspatchRuntimeProfiles.read(context)
        assertTrue("Expected a modern JingMatrix LSPatch host: $patch", patch.modern)
        assertEquals(487L, patch.loaderVersion)
        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.ACCEPTED))
        assertTrue(CommunicationCompatibilityStore.setEnabled(context, false))
        // issue #31：保留 raw=true，不能靠清掉用户开关来绕过冲突。
        assertTrue(NoRootSupportStore.setDesiredEnabled(context, true))
        assertFalse(NoRootSupportStore.isDesiredEnabled(context))
        assertFalse(NoRootSupportStore.readStatus(context).desiredEnabled)
        val first = PublicationAuthorityStore.current(context)!!
        assertEquals(0L, first.noRootRevision)
        restartHost()
        val running = awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
        assertEquals(HostAdmissionContract.DIRECT, running.bootstrap.configSource)
        assertEquals(first.identity, running.bootstrap.admission)
        assertTrue(running.bootstrap.hookPointInstalledCount > 0)
        report("initial", running)

        val prefs = context.modulePreferences()
        val key = RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION
        assertTrue(prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).commit())
        val changed = PublicationAuthorityStore.current(context)!!
        assertNotEquals(first.identity, changed.identity)
        shell("am force-stop org.lsposed.lspatch")
        val saved = CountDownLatch(1)
        val flush = AtomicReference(false)
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartConfigBarrier.flush(context, prefs, false) {
            flush.set(it)
            saved.countDown()
        }
        assertTrue(saved.await(8, TimeUnit.SECONDS))
        assertTrue("LSPatch direct restart barrier must not depend on Manager", flush.get())
        restartHost()
        val updated = awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
        assertEquals(changed.identity, updated.bootstrap.admission)
        assertEquals(HostAdmissionContract.DIRECT, updated.bootstrap.configSource)
        report("manager-stopped-config-change", updated)

        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.DECLINED))
        restartHost()
        val declined = awaitHost { it.bootstrap.configState == HostConfigState.REJECTED }
        assertEquals(HostInstallChainState.NOT_STARTED, declined.bootstrap.installChainState)
        assertEquals(0, declined.bootstrap.hookPointInstalledCount)
        report("terms-declined", declined)

        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.ACCEPTED))
        assertTrue(NoRootSupportStore.setDesiredEnabled(context, false))
        val restored = PublicationAuthorityStore.current(context)!!
        restartHost()
        val final = awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
        assertEquals(restored.identity, final.bootstrap.admission)
        assertEquals(HostAdmissionContract.DIRECT, final.bootstrap.configSource)
        report("restored", final)
    }

    private fun report(stage: String, snapshot: HostRuntimeDiagnosticsSnapshot) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nLSPatch $stage: ${HostRuntimeDiagnosticsCodec.encode(snapshot)}\n")
        })
    }

    private fun restartHost() {
        shell("am force-stop tv.danmaku.bili")
        shell("am start -n tv.danmaku.bili/.MainActivityV2")
    }

    private fun awaitHost(accept: (HostRuntimeDiagnosticsSnapshot) -> Boolean): HostRuntimeDiagnosticsSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var last: HostRuntimeDiagnosticsQueryClient.Result? = null
        while (System.nanoTime() < deadline) {
            val done = CountDownLatch(1)
            val result = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
            HostRuntimeDiagnosticsQueryClient.query(context) { result.set(it); done.countDown() }
            if (done.await(4, TimeUnit.SECONDS)) {
                last = result.get()
                last?.snapshot?.takeIf(accept)?.let { return it }
            }
            Thread.sleep(500)
        }
        fail("Host did not reach expected state: ${last?.status}/${last?.failure}/${last?.snapshot?.bootstrap}")
        error("unreachable")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
