package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class AgentAccessibilityWiringContractTest {
    fun postActionObservationCannotReplayEffectsOrPublishAfterTheAdmissionDeadline() {
        val service = SourceContract.read("agent/AgentAccessibilityService.kt")
        val observation = service.after("private fun changed(").before("private fun gesture(")
        assertTrue(observation.contains("if (result.isDone) return"))
        val read = observation.indexOf("val data = observe(task)")
        assertTrue(observation.indexOf("admit()") < read)
        assertTrue(observation.indexOf("admit()", read) > read)
        assertTrue(observation.contains("attempt < 3"))
        assertTrue(observation.contains("observation_error"))
        assertFalse(observation.contains("performAction"))
        assertFalse(observation.contains("performGlobalAction"))
        assertFalse(observation.contains("dispatchGesture"))
        val update = SourceContract.read("agent/AgentSessionService.kt").after("private val update:").before("private val observer:")
        assertTrue(update.contains("AgentController.currentTaskId() != task"))
        assertFalse(update.contains("AgentController.owns")) // 仅显示 stopping；控制接收器仍检查 owns。
        assertTrue(SourceContract.read("agent/AgentNotificationReceiver.kt").contains("if (!AgentController.owns(task)) return"))
    }
    fun readingModuleLogsWaitsWithoutReplayingThePreviousUiAction() {
        val controller = SourceContract.read("agent/AgentController.kt")
        val boundary = controller.after("val visibleResponse =").before("private fun leaseUntil(")
        assertTrue(boundary.contains("AgentAccessibilityService.Foreground.MODULE"))
        assertTrue(boundary.contains("AgentAccessibilityService.clear(task.id)"))
        assertTrue(boundary.contains("while (authorized(task)"))
        assertTrue(boundary.contains("\"ui_snapshot_stale\""))
        assertFalse(boundary.contains("AgentAccessibilityService.request"))
    }
    fun everyUiActionHasTaskWindowAndFreshTargetChecks() {
        val service = SourceContract.read("agent/AgentAccessibilityService.kt")
        assertTrue(service.contains("AgentController.actionAuthorized(task)"))
        assertTrue(service.contains("it.isActive && it.isFocused"))
        assertTrue(service.contains("root.packageName?.toString() != AgentWire.TARGET_PACKAGE"))
        assertTrue(service.contains("old.task == task && old.id == args.optString(\"snapshot_id\")"))
        assertTrue(service.contains("now.signature == previous.signature"))
        assertTrue(service.contains("AgentUiPolicy.protectedControl"))
        assertTrue(service.contains("AgentUiPolicy.mayClick(target.label, target.protected)"))
        assertTrue(service.contains("check(now.enabled && !now.protected)"))
        assertFalse(service.contains("ACTION_ACCESSIBILITY_SETTINGS"))
    }
    fun aNeutralIslandAllowsOnlyTheStillFocusedHostAndCannotBeHitByModelCoordinates() {
        val service = SourceContract.read("agent/AgentAccessibilityService.kt")
        val selection = service.after("private fun hostWindow()").before("private fun hostRoot()")
        assertTrue(selection.contains("AgentForegroundWindowPolicy.hostWindowId("))
        assertTrue(selection.contains("AgentIslandOverlay.attachedWindowId()"))
        assertTrue(selection.contains("root?.let(::release)"))
        val host = service.after("private fun hostRoot()").before("private fun scan()")
        assertTrue(host.contains("hostWindow() ?: throw IllegalStateException(\"host_not_foreground\")"))
        assertTrue(host.contains("root.packageName?.toString() != AgentWire.TARGET_PACKAGE"))
        val foreground = service.after("internal fun foreground()").before("internal fun clear(")
        assertTrue(foreground.contains("if (service.hostWindow() != null) return@runCatching Foreground.HOST"))
        val gesture = service.after("private fun gesture(").before("private fun execute(")
        assertTrue(gesture.indexOf("AgentIslandOverlay.obscures(gestureBounds)") < gesture.indexOf("dispatchGesture("))
        assertTrue(gesture.contains("\"ui_target_obscured\""))
        assertFalse(selection.contains("canDrawOverlays"))
        assertFalse(selection.contains("AgentPreferences.islandAllowed"))
    }
    fun oldAndroidDoesNotEnterNewApiOrOverlayOnModernAndroid() {
        val service = SourceContract.read("agent/AgentSessionService.kt")
        assertTrue(service.contains("if (!AgentUiPolicy.systemNotification(Build.VERSION.SDK_INT))"))
        assertTrue(service.contains("startForeground(AgentTaskNotification.ID"))
        assertTrue(service.contains("AgentController.removeObserver(observer)"))
        val notification = SourceContract.read("agent/ui/AgentTaskNotification.kt")
        assertTrue(notification.contains("if (Build.VERSION.SDK_INT >= 36) AgentPromotedNotification.configure"))
        assertTrue(notification.contains("manager.canPostPromotedNotifications()"))
        assertTrue(notification.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertFalse(notification.contains("RemoteViews"))
    }
    fun screenshotsDoNotContainOtherAppWindowsAndExpiredCallbacksCannotPublish() {
        val capture = SourceContract.read("agent/AgentAccessibilityService.kt").after("private fun screenshot(").before("companion object")
        assertTrue(SourceContract.read("agent/AgentAccessibilityService.kt").contains("if (Build.VERSION.SDK_INT >= 34) screenshot"))
        assertTrue(capture.contains("takeScreenshotOfWindow(shot.tree.window"))
        assertTrue(capture.contains("check(!result.isDone)"))
        assertTrue(capture.contains("!tree.privateInput"))
        assertTrue(capture.contains("frame.contains(tree.bounds)"))
        assertTrue(capture.contains("buffer.close()"))
        val service = SourceContract.read("agent/AgentAccessibilityService.kt")
        assertTrue(service.contains("renderer.contains(\"WebView\", true)"))
        assertTrue(service.contains("renderer.contains(\"ComposeView\", true)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentAccessibilityWiringContractTest) -> Unit) {
            val s = AgentAccessibilityWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.postActionObservationCannotReplayEffectsOrPublishAfterTheAdmissionDeadline() }
        runIsolated { s -> s.readingModuleLogsWaitsWithoutReplayingThePreviousUiAction() }
        runIsolated { s -> s.everyUiActionHasTaskWindowAndFreshTargetChecks() }
        runIsolated { s -> s.aNeutralIslandAllowsOnlyTheStillFocusedHostAndCannotBeHitByModelCoordinates() }
        runIsolated { s -> s.oldAndroidDoesNotEnterNewApiOrOverlayOnModernAndroid() }
        runIsolated { s -> s.screenshotsDoNotContainOtherAppWindowsAndExpiredCallbacksCannotPublish() }
    }
}
