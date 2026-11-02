package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoamingSettingsLaunchContractTest {
    private val launcher = SourceContract.read("runtime/RoamingSettingsLauncher.kt")

    @Test
    fun `module no longer changes system activity start decisions`() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        listOf("onSystemServerStarting", "installSystemHooks", "systemHooksStarted",
            "shouldAbortBackgroundActivityStart", "isAllowedStartActivity",
            "system:allow-module-background-start").forEach { assertFalse(it, entry.contains(it)) }
    }

    @Test
    fun `receiver returns a token and never starts a background activity`() {
        val receiver = SourceContract.read("receiver/RoamingOpenReceiver.kt")
        assertTrue(receiver.contains("RoamingSettingsLauncher.reply(context, intent"))
        assertFalse(receiver.contains("startActivity("))
        val hook = SourceContract.read("hook/RoamingCompatHook.kt")
            .after("private fun openRoamingSettings(context: Context)").before("fun isEnabled(context: Context?)")
        assertTrue(hook.contains("context.startActivity(intent)"))
        assertTrue(hook.contains("RoamingSettingsLauncher.request(context)"))
    }

    @Test
    fun `module owns an immutable one-shot fixed activity target`() {
        val factory = launcher.after("internal fun createLaunchToken(").before("private class Request(")
        assertTrue(factory.contains("PendingIntent.getActivity(context"))
        assertTrue(factory.contains("ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY)"))
        assertTrue(factory.contains("PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT"))
        assertFalse(factory.contains("getParcelableExtra"))
        assertFalse(factory.contains("getStringExtra"))
        assertFalse(launcher.contains("setPendingIntentCreatorBackgroundActivityStartMode"))
        assertFalse(launcher.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS"))
    }

    @Test
    fun `host callbacks are guarded and bounded to a visible original activity`() {
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
    }

    @Test
    fun `new platform API calls stay in version-specific implementations`() {
        val api31 = launcher.after("private object Api31").before("@RequiresApi(34)")
        assertTrue(api31.contains("token.isActivity && token.isImmutable"))
        val api34 = launcher.after("private object Api34").before("@RequiresApi(36)")
        assertTrue(api34.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOWED"))
        val api36 = launcher.after("private object Api36")
        assertTrue(api36.contains("MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE"))
    }

    @Test
    fun `lifecycle registration has a real application and seeds late resumed authorization`() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        val retry = entry.after("scheduleAuthorizationRetry = retry@").before("// 未授权前只保留两个")
        assertTrue(retry.contains(".initializeHost(activity)"))
        val applicationCreate = entry.after("\"callApplicationOnCreate\",")
        assertTrue(applicationCreate.contains(".initializeHost(application)"))
    }

    @Test
    fun `admission recovery runs IPC off lifecycle callbacks and releases result ownership`() {
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
    }

    @Test
    fun `fast admission drains already arrived refusals before installing a grant`() {
        val client = SourceContract.read("runtime/HostAdmissionClient.kt")
        val fast = client.after("if (collect.grant != null) {").before("if (collect.sawDenied)")
        assertTrue(fast.contains("drainRouted(collect, responses, acceptGrant = false"))
    }
}
