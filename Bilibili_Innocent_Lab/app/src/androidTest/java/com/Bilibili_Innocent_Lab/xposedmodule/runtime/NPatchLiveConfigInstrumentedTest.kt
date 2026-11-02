package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveApplyReport
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences as P
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/** 显式测试机配置；apply 阶段不得重启宿主，旧完整安装身份不能冒充新值回执。 */
@RunWith(AndroidJUnit4::class)
class NPatchLiveConfigInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun configureAlreadyEnabledFamilies() {
        assumeTrue(args.getString("livePhase") == "configure")
        assertTrue(PublicationAuthorityStore.current(context)?.decision?.isAuthorized == true)
        val prefs = context.modulePreferences()
        val specs = SettingsCatalog.specs.filter { it.restorePolicy == RestorePolicy.AUTOMATIC &&
            ImportEffect.RESTART_BILIBILI in it.effects && !it.id.startsWith("communication.") &&
            !it.id.startsWith("compat.") && !it.id.startsWith("module_ui.") }
        assertTrue(ModuleSettingsStore(prefs).commit(specs.map { SettingWrite(it, it.defaultValue) }))
        assertTrue(prefs.edit().putBoolean(HookEntry.PREF_FREE_COPY_ENABLED, true)
            .putBoolean(HookEntry.PREF_FREE_COPY_DESC_ENABLED, true)
            .putBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, false).putBoolean(HookEntry.PREF_FREE_COPY_AUTO_LIGHT, false)
            .putBoolean(P.DANMAKU_WEIGHT_FILTER_ENABLED, true).putInt(P.DANMAKU_WEIGHT_FILTER_MINIMUM, 3)
            .putBoolean(P.REMOVE_VIP_COLORFUL_DANMAKU, true)
            .putBoolean(P.HOME_RECOMMEND_TITLE_FILTER_ENABLED, true)
            .putString(P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS, "BIL_LIVE_BASELINE_UNMATCHED")
            .putInt(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS, 1).putInt(P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS, 6_000)
            .putInt(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT, 1).putInt(P.RECOMMEND_VIDEO_MAX_PLAY_COUNT, Int.MAX_VALUE)
            .commit())
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        assertNotNull(NoRootSupportStore.upsertEnabledSnapshot(context, prefs))
    }

    @Test fun appliesCurrentValuesWithoutReplacingHooksOrTheStartupReceipt() {
        assumeTrue(args.getString("livePhase") == "apply")
        val expectedMode = NPatchMode.valueOf(requireNotNull(args.getString("expectedMode")))
        assertEquals(expectedMode, NPatchRuntimeProfiles.read(context).mode)
        val before = query()
        assertEquals(HostInstallChainState.COMPLETED, before.bootstrap.installChainState)
        assertEquals(PublicationAuthorityStore.current(context)?.identity, before.bootstrap.admission)
        val originalPid = shell("pidof tv.danmaku.bili").trim()
        assertTrue(originalPid.isNotEmpty())
        val prefs = context.modulePreferences()
        assertTrue(prefs.edit().putBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, true)
            .putInt(P.DANMAKU_WEIGHT_FILTER_MINIMUM, 8)
            .putString(P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS, "的")
            .putInt(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS, 2).putInt(P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS, 5_999)
            .putInt(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT, 2).putInt(P.RECOMMEND_VIDEO_MAX_PLAY_COUNT, Int.MAX_VALUE - 1)
            // 明确证明未安装的新结构不会被热应用偷偷挂载。
            .putBoolean(P.HIDE_PLAYER_PORTRAIT_CONTROL, true).commit())
        assertNotNull(NoRootSupportStore.upsertEnabledSnapshot(context, prefs))
        val current = requireNotNull(PublicationAuthorityStore.current(context))
        assertNotEquals(before.bootstrap.admission, current.identity)
        val done = CountDownLatch(1)
        val response = AtomicReference<LiveApplyReport>()
        NPatchLiveConfig.applyFromSettings(context) { response.set(it); done.countDown() }
        assertTrue("Live application timed out", done.await(15, TimeUnit.SECONDS))
        val report = response.get()
        val caps = before.features.filter { it.installState == HostFeatureInstallState.INSTALLED && !it.runtimeError }
            .mapTo(hashSetOf()) { it.featureId }
        var expected = 0
        if ("free_copy_comment_enabled" in caps || "free_copy_description_enabled" in caps) expected++
        if ("player_danmaku_weight_filter_enabled" in caps) expected++
        if ("home_recommend_title_filter_enabled" in caps) expected++
        if ("home_recommend_duration_filter" in caps) expected += 2
        if ("home_recommend_play_count_filter" in caps) expected += 2
        assertTrue("Fixture has no independently installed live path", expected > 0)
        assertEquals("APPLIED", report.status)
        assertEquals(expected, report.applied)
        assertTrue(report.deferred > 0)
        assertEquals(current.noRootRevision, report.revision)
        assertEquals(originalPid, shell("pidof tv.danmaku.bili").trim())
        val after = query()
        assertEquals(before.bootstrap.admission, after.bootstrap.admission)
        assertEquals(before.bootstrap.hookPointInstalledCount, after.bootstrap.hookPointInstalledCount)
        assertEquals(HostInstallChainState.COMPLETED, after.bootstrap.installChainState)
        val duplicate = CountDownLatch(1)
        NPatchLiveConfig.applyFromSettings(context) { response.set(it); duplicate.countDown() }
        assertTrue(duplicate.await(15, TimeUnit.SECONDS))
        assertEquals(0, response.get().applied)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream",
            "\nLIVE_VALUES: mode=$expectedMode applied=${report.applied} deferred=${report.deferred}; same PID and hook count; startup receipt preserved\n") })
    }

    private fun query(): HostRuntimeDiagnosticsSnapshot {
        val done = CountDownLatch(1)
        val response = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
        HostRuntimeDiagnosticsQueryClient.query(context) { response.set(it); done.countDown() }
        assertTrue(done.await(6, TimeUnit.SECONDS))
        assertEquals(HostRuntimeDiagnosticsQueryClient.Status.READY, response.get().status)
        return requireNotNull(response.get().snapshot)
    }

    @Test fun reportsCopyEvidenceWithoutPromotingThePartialConfiguration() {
        assumeTrue(args.getString("livePhase") == "observe")
        val expectedPid = requireNotNull(args.getString("expectedPid"))
        assertEquals(expectedPid, shell("pidof tv.danmaku.bili").trim())
        val snapshot = query()
        assertNotEquals(PublicationAuthorityStore.current(context)?.identity, snapshot.bootstrap.admission)
        val leaf = snapshot.features.single { it.featureId == "free_copy_description_enabled" }
        assertTrue(leaf.appliedCount > 0); assertFalse(leaf.runtimeError)
    }

    @Test fun freshHostAuthorizationRejectsASignedRequestAfterDisable() {
        assumeTrue(args.getString("livePhase") == "reject")
        val before = query()
        val pid = shell("pidof tv.danmaku.bili").trim()
        assertTrue(pid.isNotEmpty())
        val current = requireNotNull(PublicationAuthorityStore.current(context))
        val hash = requireNotNull(LoadedModuleArtifact.capture(context.applicationInfo.sourceDir)?.digest())
        val nonce = UUID.randomUUID().toString()
        val proof = PendingIntent.getBroadcast(context, nonce.hashCode(), Intent("live.reject.$nonce").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT)
        val done = CountDownLatch(1)
        val response = AtomicReference<Bundle>()
        try {
            assertTrue(NoRootSupportController.setDesiredEnabled(context, false))
            CrossAppBroadcastCompat.sendOrderedBroadcast(context, Intent(NPatchLiveConfig.ACTION).setPackage("tv.danmaku.bili")
                .putExtra("nonce", nonce).putExtra("proof", proof).putExtra("moduleHash", hash)
                .putExtra("fingerprint", current.identity.fingerprint).putExtra("revision", current.noRootRevision)
                .putExtra("deadline", SystemClock.elapsedRealtime() + 8_000L), object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent?) {
                        response.set(getResultExtras(false)); done.countDown()
                    }
                }, Handler(Looper.getMainLooper()))
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(nonce, response.get()?.getString("nonce"))
            assertEquals("REJECTED", response.get()?.getString("status"))
            assertEquals(0, response.get()?.getInt("applied"))
            assertEquals(pid, shell("pidof tv.danmaku.bili").trim())
            assertEquals(before.bootstrap.admission, query().bootstrap.admission)
        } finally {
            proof.cancel()
            assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
            assertNotNull(NoRootSupportStore.upsertEnabledSnapshot(context, context.modulePreferences()))
        }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
