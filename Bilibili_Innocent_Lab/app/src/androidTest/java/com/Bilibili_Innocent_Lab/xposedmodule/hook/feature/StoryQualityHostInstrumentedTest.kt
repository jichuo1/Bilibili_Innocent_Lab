package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartConfigBarrier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 显式双框架专项：安装回执与真实播放证据分开检查，不把安装成功冒充画质已应用。 */
@RunWith(AndroidJUnit4::class)
class StoryQualityHostInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun configuredHostInstallsTheCompleteStoryFamily() {
        assumeTrue(args.getString("storyPhase") == "configure")
        verifyFramework()
        assertTrue(PublicationAuthorityStore.current(context)?.decision?.isAuthorized == true)
        val qn = requireNotNull(args.getString("qualityQn")).toInt()
        require(qn != 0 && PlayerQualityConfig.normalize(qn) == qn)
        val prefs = context.modulePreferences()
        assertTrue(prefs.edit().putInt(FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN, qn).commit())
        assertTrue(CommunicationCompatibilityStore.setEnabled(context, false))
        if (args.getString("expectedFramework") == "NPatch") {
            assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        }
        val saved = CountDownLatch(1)
        val success = AtomicReference(false)
        RestartConfigBarrier.flush(context, prefs, args.getString("expectedFramework") == "NPatch") {
            success.set(it); saved.countDown()
        }
        assertTrue(saved.await(10, TimeUnit.SECONDS))
        assertTrue(success.get())
        shell("am force-stop tv.danmaku.bili")
        shell("am start -n tv.danmaku.bili/.MainActivityV2")
        report(awaitComplete())
    }

    @Test fun currentPlaybackReportsRealQualityApplication() {
        assumeTrue(args.getString("storyPhase") == "observe")
        verifyFramework()
        val snapshot = awaitComplete()
        val quality = snapshot.features.single { it.featureId == PlayerQualityFeatureInstaller.ID }
        assertTrue("No real playback callback", quality.observedCount > 0)
        assertTrue("No real quality application", quality.appliedCount > 0)
        report(snapshot)
    }

    private fun verifyFramework() {
        when (args.getString("expectedFramework")) {
            "LSPatch" -> {
                val profile = LspatchRuntimeProfiles.read(context)
                assertTrue(profile.modern)
                assertEquals(487L, profile.loaderVersion)
            }
            "NPatch" -> {
                val profile = NPatchRuntimeProfiles.read(context)
                assertEquals(NPatchMode.MANAGER, profile.mode)
                assertEquals(833L, profile.loaderVersionCode)
            }
            else -> error("Explicit expectedFramework is required")
        }
    }

    private fun awaitComplete(): HostRuntimeDiagnosticsSnapshot {
        val expected = requireNotNull(PublicationAuthorityStore.current(context)).identity
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        var last: HostRuntimeDiagnosticsQueryClient.Result? = null
        while (System.nanoTime() < deadline) {
            val done = CountDownLatch(1)
            val response = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
            HostRuntimeDiagnosticsQueryClient.query(context) { response.set(it); done.countDown() }
            if (done.await(4, TimeUnit.SECONDS)) {
                last = response.get()
                val snapshot = last?.snapshot
                val quality = snapshot?.features?.singleOrNull { it.featureId == PlayerQualityFeatureInstaller.ID }
                if (snapshot?.bootstrap?.installChainState == HostInstallChainState.COMPLETED &&
                    snapshot.bootstrap.admission == expected && quality?.installState == HostFeatureInstallState.INSTALLED) {
                    assertEquals(HostAdmissionContract.DIRECT, snapshot.bootstrap.configSource)
                    assertTrue(quality.installedHookCount > 1)
                    assertFalse(quality.runtimeError)
                    assertEquals(expected, PublicationAuthorityStore.current(context)?.identity)
                    return snapshot
                }
            }
            Thread.sleep(500)
        }
        error("Complete Story family not observed: ${last?.status}/${last?.snapshot?.features}")
    }

    private fun report(snapshot: HostRuntimeDiagnosticsSnapshot) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\nStory ${args.getString("expectedFramework")}: ${HostRuntimeDiagnosticsCodec.encode(snapshot)}\n")
    })

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
