package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 仅在专项有线备用机显式运行：改变测试配置并冷启动真实宿主；不调用伪造 UID 的端点入口。 */
@RunWith(AndroidJUnit4::class)
class NPatchDirectHostInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun managerStoppedSettingsChangeAndDisableUseCurrentDirectAuthority() {
        verifyCurrentDirectAuthority(NPatchMode.MANAGER)
    }

    internal fun verifyCurrentDirectAuthority(mode: NPatchMode) {
        assertEquals(mode, NPatchRuntimeProfiles.read(context).mode)
        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.ACCEPTED))
        // 明确覆盖曾触发 Web 阻塞的默认组合，不能沿用对照实验里关闭的复制开关。
        assertTrue(context.modulePreferences().edit()
            .putBoolean(HookEntry.PREF_FREE_COPY_ENABLED, true)
            .putBoolean(HookEntry.PREF_FREE_COPY_DESC_ENABLED, true).commit())
        assertTrue(CommunicationCompatibilityStore.setEnabled(context, false))
        assertTrue(NoRootSupportStore.disable(context))
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        val first = PublicationAuthorityStore.current(context)!!
        shell("am force-stop top.nkbe.npatch")
        restartHost()
        val running = awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
        assertEquals(HostAdmissionContract.DIRECT, running.bootstrap.configSource)
        assertEquals(first.identity, running.bootstrap.admission)
        assertTrue(running.bootstrap.hookPointInstalledCount > 0)
        assertProcess(NPatchProcessState.INSTALLED, mode)

        val prefs = context.modulePreferences()
        val key = RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION
        assertTrue(prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1).commit())
        val updated = PublicationAuthorityStore.current(context)!!
        assertNotEquals(first.identity, updated.identity)
        restartHost()
        val changed = awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
        assertEquals(updated.identity, changed.bootstrap.admission)
        assertEquals(HostAdmissionContract.DIRECT, changed.bootstrap.configSource)
        assertFalse(NoRootSupportStore.isRemoteSynced(context, updated.noRootRevision))

        assertTrue(NoRootSupportStore.disable(context))
        restartHost()
        val disabled = awaitHost { it.bootstrap.configState == HostConfigState.REJECTED }
        assertEquals(HostInstallChainState.NOT_STARTED, disabled.bootstrap.installChainState)
        assertEquals(0, disabled.bootstrap.hookPointInstalledCount)
        assertProcess(NPatchProcessState.DISABLED, mode)
        assertWebWithoutHooks(NPatchProcessState.DISABLED)
        val settled = CountDownLatch(1)
        HostRuntimeProbeClient.probeAsync(context, verify = true) { settled.countDown() }
        assertTrue(settled.await(18, TimeUnit.SECONDS))
        assertEquals(NoRootSupportStore.SyncState.DISABLED, NoRootSupportStore.readStatus(context).syncState)

        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.DECLINED))
        restartHost()
        val rejected = awaitHost { it.bootstrap.configState == HostConfigState.REJECTED }
        assertEquals(HostInstallChainState.NOT_STARTED, rejected.bootstrap.installChainState)
        assertEquals(0, rejected.bootstrap.hookPointInstalledCount)
        assertWebWithoutHooks(NPatchProcessState.REJECTED)
        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.ACCEPTED))

        // 留在已启用的修复后宿主，便于人工查看；不触发 Manager 镜像同步。
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        assertNotNull(PublicationAuthorityStore.current(context))
        restartHost()
        assertEquals(HostAdmissionContract.DIRECT,
            awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }.bootstrap.configSource)
        if (mode == NPatchMode.EMBEDDED) {
            assertProcess(NPatchProcessState.INSTALLED, mode)
            assertEquals(NPatchScopeProbe.ScopeState.UNKNOWN, NPatchScopeProbe.cached(context))
            assertFalse(NoRootSupportStore.readStatus(context).detail.orEmpty().startsWith("manager_cache_"))
        }
        assertProcessScopes(mode)
    }

    private fun assertProcessScopes(mode: NPatchMode) {
        openPrivacyPage()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var rows = emptyList<NPatchProcessSnapshot>()
        while (System.nanoTime() < deadline) {
            rows = NPatchProcessDiagnostics.query(context)
            val web = rows.singleOrNull { it.processName == "tv.danmaku.bili:web" }
            val child = rows.firstOrNull { it.processName in setOf("tv.danmaku.bili:download", "tv.danmaku.bili:ijkservice") &&
                it.state == NPatchProcessState.INSTALLED && it.installedHookCount > 0 }
            if (web?.state == NPatchProcessState.INSTALLED && child != null) {
                assertTrue(web.installedHookCount > 0)
                assertEquals(HostAdmissionContract.DIRECT, web.source)
                assertEquals(HostAdmissionContract.DIRECT, child.source)
                rows.forEach { assertEquals(mode, it.mode) }
                val evidence = "NPatch process acceptance: ${NPatchProcessCodec.encode(rows)}"
                android.util.Log.i("BilibiliInnocentLab", evidence)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$evidence\n") })
                assertWebActivityReady()
                // 同一进程重复打开、返回及后台恢复；无需账号，不发送分享消息。
                shell("input keyevent 3")
                openPrivacyPage()
                assertWebActivityReady()
                shell("input keyevent 4")
                openPrivacyPage()
                assertWebActivityReady()
                return
            }
            Thread.sleep(500)
        }
        fail("$mode process scopes not observed: $rows")
    }

    private fun assertWebWithoutHooks(state: NPatchProcessState) {
        openPrivacyPage()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            val web = NPatchProcessDiagnostics.query(context).singleOrNull {
                it.processName == "tv.danmaku.bili:web" && it.state == state
            }
            if (web != null) {
                assertEquals(0, web.installedHookCount)
                assertWebActivityReady()
                return
            }
            Thread.sleep(500)
        }
        fail("Web did not reach $state without feature hooks")
    }

    private fun openPrivacyPage() {
        shell("am start -a android.intent.action.VIEW -d bilibili://browser?url=https%3A%2F%2Fwww.bilibili.com%2Fblackboard%2Fprivacy-h5.html -p tv.danmaku.bili")
    }

    private fun assertWebActivityReady() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun hasWebView(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
            if (depth > 12) return false
            if (node.className == "android.webkit.WebView" && node.isVisibleToUser) return true
            return (0 until node.childCount).any { index ->
                node.getChild(index)?.let { hasWebView(it, depth + 1) } == true
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName == "tv.danmaku.bili" && hasWebView(root) &&
                root.findAccessibilityNodeInfosByViewId("tv.danmaku.bili:id/tv_toolbar_title")
                    .any { !it.text.isNullOrBlank() }) return
            Thread.sleep(300)
        }
        fail("Web Activity did not create a visible WebView and page title")
    }

    private fun assertProcess(state: NPatchProcessState, mode: NPatchMode) {
        // Binder 回执在 attach 中已可读，动态广播仍需等待各进程主线程完成宿主初始化。
        // 每次查询仍保持 1.5 秒边界；仅专项冷启动验收重试，不拿历史记录补成功。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        var observed: NPatchProcessSnapshot? = null
        while (System.nanoTime() < deadline && observed == null) {
            observed = NPatchProcessDiagnostics.query(context).singleOrNull { it.processName == NoRootSupportState.TARGET_PACKAGE }
            if (observed == null) Thread.sleep(500)
        }
        assertNotNull("Current process did not respond after host initialization", observed)
        val row = observed!!
        assertEquals(mode, row.mode)
        assertEquals(state, row.state)
        if (state == NPatchProcessState.INSTALLED) {
            assertEquals(HostAdmissionContract.DIRECT, row.source)
            assertTrue(row.installedHookCount > 0)
        } else assertEquals(0, row.installedHookCount)
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
