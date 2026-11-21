package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

/** 公开悬浮窗展示与宿主任务控制必须分离；真实焦点、状态栏和截图行为仍需设备验收。 */
class AgentIslandWiringContractTest {
    fun overlayPermissionHasAnExplicitUserSettingsEntryAndASeparatePrivateToggle() {
        val manifest = SourceContract.read("src/main/AndroidManifest.xml")
        assertEquals(1, Regex("android.permission.SYSTEM_ALERT_WINDOW").findAll(manifest).count())
        val dialog = SourceContract.read("ui/activity/AgentDialogs.kt")
        val display = dialog.after("var restoringIsland = false").before("R.string.agent_configure_sources")
        assertTrue(display.contains("AgentPreferences.islandAllowed(activity)"))
        assertTrue(display.contains("AgentPreferences.saveIsland(activity, checked)"))
        assertTrue(display.contains("button.isChecked = previous"))
        assertTrue(display.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"))
        assertTrue(display.contains("Uri.parse(\"package:$" + "packageName\")"))
        val preferences = SourceContract.read("agent/AgentPreferences.kt")
        assertTrue(preferences.contains("getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(\"island\""))
    }

    fun overlayUsesOneBoundedUnfocusedSecureWindowWithoutLaunchingAnotherActivity() {
        val overlay = SourceContract.read("agent/ui/AgentIslandOverlay.kt")
        val layout = overlay.after("val layout = WindowManager.LayoutParams(").before("container.addView(view,")
        assertTrue(layout.contains("bounds.width, bounds.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
        assertTrue(layout.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertTrue(layout.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL"))
        assertTrue(layout.contains("WindowManager.LayoutParams.FLAG_SECURE"))
        assertEquals(1, Regex("wm\\.addView\\(").findAll(overlay).count())
        assertFalse(overlay.contains("startActivity"))
        assertFalse(overlay.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(overlay.contains("TYPE_STATUS_BAR"))
        assertFalse(overlay.contains("FLAG_NOT_TOUCHABLE"))
        assertTrue(overlay.contains("AgentIslandGeometry.panel(currentInput, anchor"))
    }

    fun neutralDisplayChangesReuseTheAttachedWindowAndKeepStopExplicit() {
        val overlay = SourceContract.read("agent/ui/AgentIslandOverlay.kt")
        val rebuild = overlay.after("private fun rebuild()").before("private fun safeRebuild()")
        assertTrue(rebuild.contains("val container = window as? FrameLayout ?: FrameLayout(context)"))
        assertTrue(rebuild.contains("container.removeAllViews()"))
        assertTrue(rebuild.contains("wm.updateViewLayout(container, layout)"))
        assertTrue(rebuild.contains("if (window == null) wm.addView(container, layout)"))
        for (mode in 0..2) assertTrue(rebuild.contains("mode = $mode; safeRebuild()"))
        assertEquals(1, Regex("AgentController\\.cancel\\(").findAll(overlay).count())
        assertTrue(rebuild.contains("button(R.string.agent_stop) { AgentController.cancel(service) }"))
        val attached = overlay.after("internal fun attachedWindowId()").before("internal fun obscures(")
        assertTrue(attached.contains("it.isAttachedToWindow && it.isShown"))
        assertTrue(attached.contains("view.createAccessibilityNodeInfo()"))
        assertTrue(attached.contains("node.windowId.takeIf { it >= 0 }"))
        val remove = overlay.after("private fun remove()").before("override fun onConfigurationChanged")
        assertTrue(remove.contains("if (visibleWindow.get() === window) visibleWindow.clear()"))
    }

    fun displayIsRemovedWhenTaskPermissionScreenOrOwnershipIsUnavailable() {
        val overlay = SourceContract.read("agent/ui/AgentIslandOverlay.kt")
        val apply = overlay.after("private fun apply(").before("private fun geometry(")
        assertTrue(apply.contains("!next.running"))
        assertTrue(apply.contains("AgentController.currentTaskId() == null"))
        assertTrue(apply.contains("!AgentPreferences.islandAllowed(service)"))
        assertTrue(apply.contains("!power.isInteractive"))
        assertTrue(apply.contains("keyguard.isKeyguardLocked"))
        assertTrue(apply.contains("!Settings.canDrawOverlays(service)"))
        assertTrue(apply.indexOf("Settings.canDrawOverlays") < apply.indexOf("rebuild()"))
        val remove = overlay.after("private fun remove()").before("override fun onConfigurationChanged")
        assertTrue(remove.contains("island?.close(); logs?.close()"))
        assertTrue(remove.contains("wm.removeViewImmediate(it)"))
        val close = overlay.after("fun close()")
        assertTrue(close.contains("AgentController.removeObserver(observer)"))
        assertTrue(close.contains("unregisterReceiver(screen)"))
        assertTrue(close.contains("unregisterComponentCallbacks(this)"))
        assertTrue(close.contains("stopWatchingMode(opListener)"))
    }

    fun geometryUsesDisplayAreaInsetsWithCutoutAndWaterfallWithoutADoubleCoordinateOffset() {
        val overlay = SourceContract.read("agent/ui/AgentIslandOverlay.kt")
        val context = overlay.after("private val context =").before("private val wm =")
        assertTrue(context.contains("getDisplay(Display.DEFAULT_DISPLAY)"))
        assertTrue(context.contains("service.createDisplayContext(display).createWindowContext("))
        val geometry = overlay.after("private fun geometry(): AgentIslandGeometry.Input {").before("private fun rebuild()")
        val modern = geometry.after("if (Build.VERSION.SDK_INT >= 30) {").before("val display = android.util.DisplayMetrics()")
        assertTrue(modern.contains("wm.currentWindowMetrics"))
        assertTrue(modern.contains("WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()"))
        assertTrue(modern.contains("displayCutout?.waterfallInsets"))
        for (side in listOf("top", "left", "right", "bottom")) {
            assertTrue(modern.contains("maxOf(insets.$side, waterfall?.$side ?: 0)"))
        }
        val cutouts = modern.after("val cutouts =").before("return AgentIslandGeometry.Input")
        assertTrue(cutouts.contains("AgentIslandGeometry.Rect(it.left, it.top, it.right, it.bottom)"))
        assertFalse(cutouts.contains("- bounds.left"))
        assertFalse(cutouts.contains("- bounds.top"))
        assertFalse(modern.contains("rootWindowInsets"))
    }

    fun logDialogKeepsTitleFirstAndUsesTheCommonModalPresenter() {
        val dialog = SourceContract.read("ui/activity/AgentLogDialogs.kt")
        assertTrue(dialog.contains("installDialogElasticInteraction"))
        assertTrue(dialog.contains("createModalContainer()"))
        assertTrue(dialog.indexOf("R.string.agent_logs_title") < dialog.indexOf("val logs = AgentLogView(this)"))
        assertTrue(dialog.contains("presentModalDialog(dialog, container, anchor)"))
        assertTrue(dialog.contains("dismissWithAnimation(dialog, container)"))
        val list = SourceContract.read("agent/ui/AgentLogView.kt")
        assertTrue(list.contains("setTextIsSelectable(false)"))
        assertTrue(list.contains("override fun onDetachedFromWindow()"))
        assertTrue(list.contains("AgentExecutionLogStore.removeObserver(observer)"))
        assertFalse(list.contains("startActivity"))
        assertFalse(list.contains("AgentController.cancel"))
    }

    fun serviceDisplayLifecycleDoesNotBroadenTheExistingTaskPermissionChain() {
        val service = SourceContract.read("agent/AgentSessionService.kt")
        assertTrue(service.contains("AgentIslandOverlay(this)"))
        assertTrue(service.contains("island?.close(); island = null"))
        assertTrue(service.contains("START_NOT_STICKY"))
        assertTrue(service.contains("getCallingUid() != uid"))
        assertTrue(service.contains("data.enforceInterface(AgentWire.SERVICE_DESCRIPTOR)"))
        assertTrue(service.contains("AgentController.owns(data.readString().orEmpty())"))
        assertTrue(service.contains("ownerTaskId?.takeIf(AgentController::owns)"))
        assertFalse(service.contains("getStringExtra"))
        val runner = SourceContract.read("agent/AgentController.kt")
        val authorization = runner.after("private fun authorized(").before("private fun host(")
        assertTrue(authorization.contains("UserTermsConsentStore.readOrInitialize(task.context).isAuthorized"))
        assertTrue(authorization.contains("!task.stopped()"))
        val stopped = runner.after("fun stopped()").before("private val main = Handler")
        assertTrue(stopped.contains("getBoolean(AgentPreferences.ENABLED, false)"))
        assertFalse(authorization.contains("canDrawOverlays"))
        assertFalse(authorization.contains("islandAllowed"))
    }

    fun islandAndLogsAreNotInjectedIntoTheWindowCopiedForModelVision() {
        val host = SourceContract.read("agent/host/HostAgentWindow.kt")
        assertFalse(host.contains("AgentIsland"))
        assertFalse(host.contains("AgentLogView"))
        assertTrue(host.contains("PixelCopy.request(activity.window"))
        val takeover = host.after("private inner class TaskCallback").before("fun showStop(")
        assertTrue(takeover.contains("if (!hasFocus"))
        assertTrue(takeover.contains("MotionEvent.ACTION_DOWN"))
        assertFalse(takeover.contains("canDrawOverlays"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentIslandWiringContractTest) -> Unit) {
            val s = AgentIslandWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.overlayPermissionHasAnExplicitUserSettingsEntryAndASeparatePrivateToggle() }
        runIsolated { s -> s.overlayUsesOneBoundedUnfocusedSecureWindowWithoutLaunchingAnotherActivity() }
        runIsolated { s -> s.neutralDisplayChangesReuseTheAttachedWindowAndKeepStopExplicit() }
        runIsolated { s -> s.displayIsRemovedWhenTaskPermissionScreenOrOwnershipIsUnavailable() }
        runIsolated { s -> s.geometryUsesDisplayAreaInsetsWithCutoutAndWaterfallWithoutADoubleCoordinateOffset() }
        runIsolated { s -> s.logDialogKeepsTitleFirstAndUsesTheCommonModalPresenter() }
        runIsolated { s -> s.serviceDisplayLifecycleDoesNotBroadenTheExistingTaskPermissionChain() }
        runIsolated { s -> s.islandAndLogsAreNotInjectedIntoTheWindowCopiedForModelVision() }
    }
}
