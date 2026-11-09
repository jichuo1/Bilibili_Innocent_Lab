package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit test-device operations only. Existing account consent and all host data remain intact. */
@RunWith(AndroidJUnit4::class)
class NPatchStartupBenchmarkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    /** Same reset action as settings UI; never clears host/account data or bypasses admission. */
    @Test fun resetAdaptationForFirstStartup() {
        assumeTrue(args.getString("startupReset") == "true")
        val prefs = context.modulePreferences()
        val started = System.currentTimeMillis()
        VersionAdapter.clearCache(context, prefs)
        val reset = prefs.getLong(RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP, 0L)
        assertTrue("Reset action did not update its authoritative marker", reset >= started)
        // Instrumentation can terminate immediately after this method. Flush the UI action's apply()
        // before the next test process starts; an in-memory request is not durable reset evidence.
        assertTrue(prefs.edit().putLong(RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP, reset).commit())
        assertTrue("This opt-in test requires enabled NPatch", NoRootSupportStore.isDesiredEnabled(context))
        // ConfirmationDialogs also advances the NPatch reset revision. Its publication deliberately
        // replaces the standard-framework timestamp with this revision; exercise both UI actions.
        val revision = NoRootSupportStore.markAdapterReset(context)
        assertTrue(context.getSharedPreferences(NoRootSupportStore.PREF_FILE, android.content.Context.MODE_PRIVATE)
            .edit().commit())
        val publication = requireNotNull(PublicationAuthorityStore.current(context))
        assertEquals(revision, publication.values[RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP])
        report("STARTUP_RESET=$reset; NPATCH_RESET=$revision; current publication checked")
    }

    @Test fun configureFixedProfile() {
        val profile = args.getString("startupProfile")
        assumeTrue(profile != null)
        require(profile in setOf("default", "combined"))
        val specs = SettingsCatalog.specs.filter { it.restorePolicy == RestorePolicy.AUTOMATIC &&
            ImportEffect.RESTART_BILIBILI in it.effects && !it.id.startsWith("communication.") &&
            !it.id.startsWith("compat.") && !it.id.startsWith("module_ui.") }
        val store = ModuleSettingsStore(context.modulePreferences())
        assertTrue(store.commit(specs.map { SettingWrite(it, it.defaultValue) }))
        if (profile == "combined") assertTrue(context.modulePreferences().edit()
            .putBoolean(FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS, true)
            .putBoolean(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, true)
            .putBoolean(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, true)
            .putInt(FeaturePreferences.PLAYER_DEFAULT_QUALITY_QN, 80)
            .putInt(FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT, 150).commit())
        val prefs = context.modulePreferences()
        val revision = prefs.getLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, 0L) + 1L
        assertTrue(prefs.edit().putLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, revision).commit())
        assertTrue(FreeCopyConfigStore.write(context,
            prefs.getBoolean(HookEntry.PREF_FREE_COPY_ENABLED, true),
            prefs.getBoolean(HookEntry.PREF_FREE_COPY_DESC_ENABLED, true), revision))
        assertTrue("Existing test-device consent required", PublicationAuthorityStore.current(context)?.decision?.isAuthorized == true)
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        report("STARTUP_PROFILE=" + JSONObject().put("name", profile)
            .put("fingerprint", requireNotNull(PublicationAuthorityStore.current(context)).identity.fingerprint))
    }

    @Test fun collectColdProcessRuns() {
        assumeTrue(args.getString("startupBenchmark") == "true")
        val mode = NPatchMode.valueOf(requireNotNull(args.getString("expectedMode")))
        assertEquals(mode, NPatchRuntimeProfiles.read(context).mode)
        val rounds = args.getString("rounds")?.toInt() ?: 1
        require(rounds in 1..10)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        repeat(rounds) { index ->
            val publication = requireNotNull(PublicationAuthorityStore.current(context))
            val oldPid = shell("pidof tv.danmaku.bili").trim()
            shell("am force-stop tv.danmaku.bili")
            assertTrue(shell("pidof tv.danmaku.bili").isBlank())
            val startedWall = System.currentTimeMillis()
            val started = SystemClock.elapsedRealtime()
            shell("am start -n tv.danmaku.bili/.MainActivityV2")
            val deadline = started + 45_000L
            var search: AccessibilityNodeInfo? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val root = automation.rootInActiveWindow
                if (root?.packageName == "tv.danmaku.bili" &&
                    root.findAccessibilityNodeInfosByViewId("tv.danmaku.bili:id/tab_text").any { it.text == "首页" }) {
                    search = root.findAccessibilityNodeInfosByViewId("tv.danmaku.bili:id/expand_search")
                        .firstOrNull { it.isVisibleToUser && it.isClickable }
                    if (search != null) break
                }
                Thread.sleep(250)
            }
            val homeMs = SystemClock.elapsedRealtime() - started
            assertNotNull("Home did not become available before deadline", search)
            val bounds = Rect().also { search!!.getBoundsInScreen(it) }
            shell("input stylus tap ${bounds.centerX()} ${bounds.centerY()}")
            var responded = false
            val responseDeadline = SystemClock.elapsedRealtime() + 8_000L
            while (SystemClock.elapsedRealtime() < responseDeadline) {
                val root = automation.rootInActiveWindow
                if (root?.packageName == "tv.danmaku.bili" && hasInput(root)) { responded = true; break }
                Thread.sleep(100)
            }
            val interactiveMs = SystemClock.elapsedRealtime() - started
            assertTrue("Fixed search operation did not respond", responded)
            shell("input keyevent 4")
            shell("input keyevent 4")
            val traces = if (StartupTrace.enabled) NPatchStartupTraceQuery.query(context) else JSONObject()
            if (StartupTrace.enabled) {
                val trace = traces.getJSONObject("tv.danmaku.bili").getJSONObject("trace")
                assertEquals("Incomplete timing table", 0, trace.getInt("dropped"))
                val rows = trace.getJSONArray("rows")
                assertTrue("Installation timing missing", (0 until rows.length()).any {
                    rows.getJSONObject(it).getString("id") == "install"
                })
            }
            val processes = NPatchProcessDiagnostics.query(context)
            val main = processes.single { it.processName == "tv.danmaku.bili" }
            assertEquals(NPatchProcessState.INSTALLED, main.state)
            assertTrue(main.pid.toString() != oldPid || oldPid.isBlank())
            val done = CountDownLatch(1)
            val result = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
            HostRuntimeDiagnosticsQueryClient.query(context) { result.set(it); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(HostRuntimeDiagnosticsQueryClient.Status.READY, result.get().status)
            val snapshot = requireNotNull(result.get().snapshot)
            assertEquals(publication.identity, snapshot.bootstrap.admission)
            assertEquals(HostInstallChainState.COMPLETED, snapshot.bootstrap.installChainState)
            assertEquals(publication.identity, PublicationAuthorityStore.current(context)?.identity)
            val logs = shell("logcat -v epoch -t 400 -s ActivityTaskManager:I WindowManager:I")
            val display = logs.lineSequence().firstOrNull { line ->
                line.contains("Displayed tv.danmaku.bili/") &&
                    (line.trim().substringBefore(' ').toDoubleOrNull()?.times(1000)?.toLong() ?: 0) >= startedWall
            }
            val duration = display?.substringAfter(": +", "")?.substringBefore(' ')
            val firstFrameMs = duration?.let(::durationMs)
            report("STARTUP_RUN=" + JSONObject().put("round", index + 1).put("pid", main.pid)
                .put("mode", mode.name).put("homeMs", homeMs).put("interactiveMs", interactiveMs)
                .put("firstFrameMs", firstFrameMs ?: JSONObject.NULL).put("pollMs", 250)
                .put("fingerprint", publication.identity.fingerprint).put("hooks", main.installedHookCount)
                .put("processes", org.json.JSONArray(NPatchProcessCodec.encode(processes)))
                .put("traces", traces))
            Thread.sleep(1_000)
        }
    }

    private fun hasInput(node: AccessibilityNodeInfo, depth: Int = 0): Boolean = depth < 16 &&
        (node.className == "android.widget.EditText" || (0 until node.childCount).any { node.getChild(it)?.let { child -> hasInput(child, depth + 1) } == true })

    private fun durationMs(raw: String): Long? {
        if (raw.isEmpty()) return null
        val matches = Regex("(\\d+)(ms|s|m)").findAll(raw).toList()
        if (matches.joinToString("") { it.value } != raw) return null
        return matches.sumOf { it.groupValues[1].toLong() * when (it.groupValues[2]) { "m" -> 60_000L; "s" -> 1000L; else -> 1L } }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$value\n") })
}
