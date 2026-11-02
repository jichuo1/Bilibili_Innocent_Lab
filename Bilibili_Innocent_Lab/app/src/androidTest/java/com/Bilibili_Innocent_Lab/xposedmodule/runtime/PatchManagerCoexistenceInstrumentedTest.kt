package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartConfigBarrier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isNpatchConfigChannel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 显式有线专项：只启动已安装管理器/宿主并读取回执，不修改条款、开关或其它业务设置。 */
@RunWith(AndroidJUnit4::class)
class PatchManagerCoexistenceInstrumentedTest {
    @Test fun npatchServiceCannotTakeOverInstalledLspatchHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPatchCoexistence") == "true")
        val app = instrumentation.targetContext.applicationContext
        assertTrue(LspatchRuntimeProfiles.read(app).modern)
        val before = requireNotNull(PublicationAuthorityStore.current(app))
        assertTrue(before.decision.isAuthorized)
        assertEquals(0L, before.noRootRevision)
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText() }
        }
        try {
            shell("am start -n top.nkbe.npatch/.ui.activity.MainActivity")
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (!isNpatchConfigChannel(RemoteHookConfigStore.status()) && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
            }
            assertTrue("The other Manager must really deliver its NPatch service", isNpatchConfigChannel(RemoteHookConfigStore.status()))
            assertEquals(HostConfigChannel.LSPATCH, HostConfigChannels.read(app))
            assertFalse(NoRootSupportStore.isDesiredEnabled(app))
            val saved = CountDownLatch(1)
            val flushed = AtomicReference(false)
            RestartConfigBarrier.flush(app, app.modulePreferences(), false) { flushed.set(it); saved.countDown() }
            assertTrue(saved.await(8, TimeUnit.SECONDS))
            assertTrue("The LSPatch restart barrier must still use the direct document", flushed.get())
            shell("am force-stop tv.danmaku.bili")
            shell("am start -n tv.danmaku.bili/.MainActivityV2")
            val until = SystemClock.elapsedRealtime() + 40_000
            var running: HostRuntimeDiagnosticsSnapshot? = null
            while (running == null && SystemClock.elapsedRealtime() < until) {
                val done = CountDownLatch(1)
                val response = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
                HostRuntimeDiagnosticsQueryClient.query(app) { response.set(it); done.countDown() }
                if (done.await(4, TimeUnit.SECONDS)) {
                    running = response.get()?.takeIf { it.status == HostRuntimeDiagnosticsQueryClient.Status.READY }
                        ?.snapshot?.takeIf { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
                }
                if (running == null) Thread.sleep(200)
            }
            assertNotNull("LSPatch host did not complete its installation with the other Manager connected", running)
            assertEquals(HostAdmissionContract.DIRECT, running!!.bootstrap.configSource)
            assertEquals(before.identity, running.bootstrap.admission)
            assertTrue(running.bootstrap.hookPointInstalledCount > 0)
            assertEquals(before.identity, PublicationAuthorityStore.current(app)?.identity)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nOther service=NPatch; actual carrier=LSPatch; restart barrier saved; current direct identity installed, hooks=${running.bootstrap.hookPointInstalledCount}\n")
            })
        } finally {
            shell("am force-stop top.nkbe.npatch")
        }
    }
}
