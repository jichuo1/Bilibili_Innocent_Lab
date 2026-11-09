package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveApplyReport
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartConfigBarrier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 显式有线专项：仅修改五个布尔业务设置，finally 精确还原并冷启动恢复。 */
@RunWith(AndroidJUnit4::class)
class LspatchLiveConfigInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext

    @Test fun appliesCurrentLspatchValuesInTheSameHostProcess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLspatchLiveConfig") == "true")
        assertTrue(LspatchRuntimeProfiles.read(app).modern)
        assertEquals(LspatchMode.MANAGER, LspatchRuntimeProfiles.read(app).mode)
        assertEquals(HostConfigChannel.LSPATCH, HostConfigChannels.read(app))
        assertTrue(PublicationAuthorityStore.current(app)?.decision?.isAuthorized == true)
        assertFalse(NoRootSupportStore.isDesiredEnabled(app))
        val prefs = app.modulePreferences()
        val fixture = mapOf(
            HookEntry.PREF_FREE_COPY_ENABLED to true,
            HookEntry.PREF_FREE_COPY_DESC_ENABLED to true,
            HookEntry.PREF_FREE_COPY_LIGHT_MODE to false,
            HookEntry.PREF_FREE_COPY_AUTO_LIGHT to false,
            FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL to false
        )
        val stored = prefs.all
        val original = fixture.keys.associateWith { stored[it] }
        // 在任何写入之前拒绝旧类型，确保 finally 可以逐项精确恢复。
        require(original.values.all { it == null || it is Boolean }) { "Fixture requires boolean preferences" }
        try {
            assertTrue(prefs.edit().apply { fixture.forEach { (key, value) -> putBoolean(key, value) } }.commit())
            flush()
            val initial = requireNotNull(PublicationAuthorityStore.current(app))
            assertEquals(0L, initial.noRootRevision)
            restart()
            val before = awaitHost(initial.identity)
            assertTrue("A complete copy leaf is required for live style application", before.features.any {
                it.featureId in setOf("free_copy_comment_enabled", "free_copy_description_enabled") &&
                    it.installState == HostFeatureInstallState.INSTALLED && !it.runtimeError
            })
            val pid = shell("pidof tv.danmaku.bili").trim()
            assertTrue(pid.isNotEmpty())
            assertTrue(prefs.edit().putBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, true)
                .putBoolean(FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL, true).commit())
            flush()
            val candidate = requireNotNull(PublicationAuthorityStore.current(app))
            assertEquals(0L, candidate.noRootRevision)
            assertTrue(candidate.identity.snapshotRevision > initial.identity.snapshotRevision)
            val applied = apply()
            assertEquals("APPLIED", applied.status)
            assertEquals(1, applied.applied)
            assertTrue("Structural changes must still wait for restart", applied.deferred > 0)
            assertEquals(candidate.identity.snapshotRevision, applied.revision)
            assertEquals(pid, shell("pidof tv.danmaku.bili").trim())
            val after = awaitHost(initial.identity)
            assertEquals(before.bootstrap.hookPointInstalledCount, after.bootstrap.hookPointInstalledCount)
            assertEquals(0, apply().applied)
            assertFalse(NoRootSupportStore.isDesiredEnabled(app))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nLSPatch live apply: revision=${applied.revision}, applied=${applied.applied}, deferred=${applied.deferred}; PID=$pid and startup receipt unchanged\n")
            })
        } finally {
            assertTrue(prefs.edit().apply {
                original.forEach { (key, value) ->
                    if (value == null) remove(key) else putBoolean(key, value as Boolean)
                }
            }.commit())
            flush()
            val restored = requireNotNull(PublicationAuthorityStore.current(app))
            restart()
            awaitHost(restored.identity)
            original.forEach { (key, value) -> assertEquals("Restored $key", value, prefs.all[key]) }
        }
    }

    private fun flush() {
        val done = CountDownLatch(1)
        val saved = AtomicReference(false)
        RestartConfigBarrier.flush(app, app.modulePreferences(), false) { saved.set(it); done.countDown() }
        assertTrue("Save timed out", done.await(8, TimeUnit.SECONDS))
        assertTrue("Current direct publication was not saved", saved.get())
    }

    private fun apply(): LiveApplyReport {
        val done = CountDownLatch(1)
        val response = AtomicReference<LiveApplyReport>()
        NPatchLiveConfig.applyFromSettings(app) { response.set(it); done.countDown() }
        assertTrue("Live request timed out", done.await(15, TimeUnit.SECONDS))
        return requireNotNull(response.get())
    }

    private fun awaitHost(identity: PublicationIdentity): HostRuntimeDiagnosticsSnapshot {
        val until = SystemClock.elapsedRealtime() + 45_000L
        var last: HostRuntimeDiagnosticsQueryClient.Result? = null
        while (SystemClock.elapsedRealtime() < until) {
            val done = CountDownLatch(1)
            val response = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
            HostRuntimeDiagnosticsQueryClient.query(app) { response.set(it); done.countDown() }
            if (done.await(4, TimeUnit.SECONDS)) {
                last = response.get()
                last?.snapshot?.takeIf {
                    last?.status == HostRuntimeDiagnosticsQueryClient.Status.READY &&
                        it.bootstrap.admission == identity &&
                        it.bootstrap.installChainState == HostInstallChainState.COMPLETED
                }?.let { return it }
            }
            Thread.sleep(200)
        }
        error("Host did not confirm expected publication: ${last?.status}/${last?.snapshot?.bootstrap}")
    }

    private fun restart() {
        shell("am force-stop tv.danmaku.bili")
        shell("am start -n tv.danmaku.bili/.MainActivityV2")
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
