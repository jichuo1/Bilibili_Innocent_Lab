package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主入口、免 Root 接收器与准入/拉起链路的源码契约护栏。
 */
class HostEntryAndAdmissionContractTest {

    @Test
    fun `modern xposed metadata, hot reload refusal, and diagnostics drain match contract`() {
        val properties = Properties().apply {
            SourceContract.read("src/main/resources/META-INF/xposed/module.prop").reader().use(::load)
        }
        assertEquals(ModernApiSupport.MIN_API.toString(), properties.getProperty("minApiVersion"))
        assertEquals(ModernApiSupport.MIN_API.toString(), properties.getProperty("targetApiVersion"))
        assertEquals("protective", properties.getProperty("exceptionMode"))
        assertEquals("true", properties.getProperty("staticScope"))
        assertEquals("false", properties.getProperty("autoHotReload"))
        assertEquals(
            listOf("tv.danmaku.bili"),
            SourceContract.read("src/main/resources/META-INF/xposed/scope.list").lines().filter(String::isNotBlank)
        )

        val entry = SourceContract.read("hook/HookEntry.kt")
        val refusal = entry.after("override fun onHotReloading(").before("override fun onPackageReady(")
        assertTrue(refusal.contains("return false"))
        assertTrue("a refusal must not claim to hand hooks over", !refusal.contains("return true"))
        // 逃出功能回调的异常经快照折成既有的 runtimeError 位：入口只接线，不起线程、不改线协议。
        assertTrue(entry.contains("HostRuntimeDiagnosticsBridge.hookFaults = modernRuntime::drainFaults"))
        val bridge = SourceContract.read("runtime/HostRuntimeDiagnosticsBridge.kt")
        val snapshot = bridge.after("internal fun snapshot()").before("private fun absorbHookFaults")
        assertTrue(snapshot.indexOf("absorbHookFaults()") in 0 until snapshot.indexOf("synchronized(lock)"))
        assertTrue(
            SourceContract.read("hook/feature/FeatureInstaller.kt")
                .contains("registrar = FeatureScopedRegistrar(environment.registrar, installer.id)")
        )
    }

    @Test
    fun `no-root host dynamic receivers run inside host thread guard from first statement`() {
        fun assertGuardedFromTheFirstStatement(source: String, receiverHeader: String, key: String) {
            val body = source.after(receiverHeader).trimStart()
            val guard = "HostThreadGuard.run(\"$key\")"
            assertTrue(
                "$key must wrap the whole onReceive body",
                body.startsWith(guard) ||
                    body.lines().dropWhile { it.trimStart().startsWith("//") }.joinToString("\n").trimStart().startsWith(guard)
            )
        }

        assertGuardedFromTheFirstStatement(
            SourceContract.read("runtime/noroot/HostRuntimeProbeResponder.kt"),
            "override fun onReceive(context: Context, intent: Intent) {",
            "host.runtime_probe.receive"
        )
        assertGuardedFromTheFirstStatement(
            SourceContract.read("runtime/noroot/NPatchProcessDiagnostics.kt"),
            "override fun onReceive(context: Context, intent: Intent) {",
            "npatch.process_diagnostics.receive"
        )
        assertTrue(
            SourceContract.read("runtime/noroot/NPatchLiveConfig.kt")
                .contains("HostThreadGuard.run(\"live_config.receive\")")
        )
    }

    @Test
    fun `roaming settings launch and host admission keep target, lifecycle, and guard bounds`() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        listOf(
            "onSystemServerStarting", "installSystemHooks", "systemHooksStarted",
            "shouldAbortBackgroundActivityStart", "isAllowedStartActivity",
            "system:allow-module-background-start"
        ).forEach { assertFalse(it, entry.contains(it)) }

        val route = SourceContract.read("hook/RoamingCompatHook.kt")
            .after("fun patchMineEntry(").before("private fun injectMineEntry(")
        assertTrue(route.contains("allowModuleInstance = !includeRoaming"))
        assertTrue(route.contains("if (moduleInstance) thisObject else args.getOrNull(0)"))
        assertTrue(route.contains("if (moduleInstance && (!mineEntryPatched || throwable != null)) return@after"))
        assertTrue(route.contains("if (!moduleInstance && roamingEntryRequested) injectMineEntry("))
        assertTrue(route.contains("uri == ROAMING_URI && !moduleInstance && roamingEntryRequested"))
        assertTrue(route.contains("uri == MODULE_URI && moduleEntryRequested -> RoamingSettingsLauncher.requestModule(ctx)"))

        val receiver = SourceContract.read("receiver/RoamingOpenReceiver.kt")
        assertTrue(receiver.contains("RoamingSettingsLauncher.reply(context, intent"))
        assertFalse(receiver.contains("startActivity("))
        val roamingOpen = SourceContract.read("hook/RoamingCompatHook.kt")
            .after("private fun openRoamingSettings(context: Context)").before("fun isEnabled(context: Context?)")
        assertTrue(roamingOpen.contains("context.startActivity(intent)"))
        assertTrue(roamingOpen.contains("RoamingSettingsLauncher.request(context)"))

        val launcher = SourceContract.read("runtime/RoamingSettingsLauncher.kt")
        val factory = launcher.after("internal fun createLaunchToken(").before("private class Request(")
        assertTrue(factory.contains("PendingIntent.getActivity(context"))
        assertTrue(factory.contains("ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY)"))
        assertTrue(factory.contains("PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT"))
        assertFalse(factory.contains("getParcelableExtra"))
        assertFalse(factory.contains("getStringExtra"))
        assertFalse(launcher.contains("setPendingIntentCreatorBackgroundActivityStartMode"))
        assertFalse(launcher.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS"))

        assertTrue(launcher.contains("WeakReference(activity)"))
        assertTrue(launcher.contains("isVisible(source.get())"))
        assertTrue(launcher.contains("HostThreadGuard.run(\"roaming.response\")"))
        assertTrue(launcher.contains("HostThreadGuard.runnable(\"roaming.timeout\")"))
        assertTrue(launcher.contains("HostThreadGuard.runnable(\"roaming.token-expire\")"))
        assertTrue(launcher.contains("context.unregisterReceiver(receiver)"))
        assertTrue(launcher.contains("handler.removeCallbacks(timeout)"))
        assertTrue(launcher.contains("callback?.cancel()"))
        assertFalse(launcher.contains("Thread.sleep"))
        assertFalse(launcher.contains(".get(5"))

        val api31 = launcher.after("private object Api31").before("@RequiresApi(34)")
        assertTrue(api31.contains("token.isActivity && token.isImmutable"))
        val api34 = launcher.after("private object Api34").before("@RequiresApi(36)")
        assertTrue(api34.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOWED"))
        val api36 = launcher.after("private object Api36")
        assertTrue(api36.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE"))

        val retry = entry.after("scheduleAuthorizationRetry = retry@").before("// 未授权前只保留两个")
        assertTrue(retry.contains(".initializeHost(activity)"))
        val applicationCreate = entry.after("\"callApplicationOnCreate\",")
        assertTrue(applicationCreate.contains(".initializeHost(application)"))

        val recovery = SourceContract.read("runtime/HostAdmissionRecovery.kt")
        val resume = recovery.after("override fun onActivityResumed(").before("override fun onActivityPaused(")
        assertFalse(resume.contains("admit()"))
        assertTrue(recovery.after("worker.submit").before("val receivedAt").contains("admit()"))
        val pause = recovery.after("override fun onActivityPaused(").before("override fun onActivityCreated(")
        assertTrue(pause.contains("releaseActivity(value)"))
        val release = recovery.after("private fun releaseActivity(").before("fun start()")
        assertTrue(release.contains("policy.invalidate()"))
        assertTrue(recovery.after("override fun onActivityStopped(").before("private fun releaseActivity(").contains("releaseActivity(value)"))
        val close = recovery.after("private fun close(").before("private fun report(")
        assertTrue(close.contains("unregisterActivityLifecycleCallbacks"))
        assertTrue(close.contains("main.removeCallbacks(expire)"))
        assertTrue(close.contains("inFlight?.cancel(false)"))
        assertTrue(recovery.contains("policy.takeDelivery(token, now, delivery)"))
        assertTrue(recovery.contains("policy.postDelivery(token,"))
        assertTrue(recovery.contains("WeakReference(value)"))

        val client = SourceContract.read("runtime/HostAdmissionClient.kt")
        val fast = client.after("if (collect.grant != null) {").before("if (collect.sawDenied)")
        assertTrue(fast.contains("drainRouted(collect, responses, acceptGrant = false"))
    }
}
