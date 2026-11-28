package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.afterLast
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无障碍执行、长任务监听所有权与截图后台编码的接线契约。 */
class AgentAccessibilityAndCaptureContractTest {

    @Test
    fun accessibilityActionsObservationAndNeutralIslandKeepTaskBoundaries() {
        val accessibility = SourceContract.read("agent/AgentAccessibilityService.kt")
        val observation = accessibility.after("private fun changed(").before("private fun gesture(")
        assertTrue(observation.contains("if (result.isDone) return"))
        val read = observation.indexOf("val data = observe(task)")
        assertTrue(observation.indexOf("admit()") < read)
        assertTrue(observation.indexOf("admit()", read) > read)
        assertTrue(observation.contains("attempt < 3"))
        assertTrue(observation.contains("observation_error"))
        assertFalse(observation.contains("performAction"))
        assertFalse(observation.contains("performGlobalAction"))
        assertFalse(observation.contains("dispatchGesture"))

        val sessionService = SourceContract.read("agent/AgentSessionService.kt")
        val update = sessionService.after("private val update:").before("private val observer:")
        assertTrue(update.contains("AgentController.currentTaskId() != task"))
        assertFalse(update.contains("AgentController.owns")) // 仅显示 stopping；控制接收器仍检查 owns。
        assertTrue(SourceContract.read("agent/AgentNotificationReceiver.kt").contains("if (!AgentController.owns(task)) return"))

        val controller = SourceContract.read("agent/AgentController.kt")
        val boundary = controller.after("val visibleResponse =").before("private fun leaseUntil(")
        assertTrue(boundary.contains("AgentAccessibilityService.Foreground.MODULE"))
        assertTrue(boundary.contains("AgentAccessibilityService.clear(task.id)"))
        assertTrue(boundary.contains("while (authorized(task)"))
        assertTrue(boundary.contains("\"ui_snapshot_stale\""))
        assertFalse(boundary.contains("AgentAccessibilityService.request"))

        assertTrue(accessibility.contains("AgentController.actionAuthorized(task)"))
        assertTrue(accessibility.contains("it.isActive && it.isFocused"))
        assertTrue(accessibility.contains("root.packageName?.toString() != AgentWire.TARGET_PACKAGE"))
        assertTrue(accessibility.contains("old.task == task && old.id == args.optString(\"snapshot_id\")"))
        assertTrue(accessibility.contains("now.signature == previous.signature"))
        assertTrue(accessibility.contains("AgentUiPolicy.protectedControl"))
        assertTrue(accessibility.contains("AgentUiPolicy.mayClick(target.label, target.protected)"))
        assertTrue(accessibility.contains("check(now.enabled && !now.protected)"))
        assertFalse(accessibility.contains("ACTION_ACCESSIBILITY_SETTINGS"))

        val selection = accessibility.after("private fun hostWindow()").before("private fun hostRoot()")
        assertTrue(selection.contains("AgentForegroundWindowPolicy.hostWindowId("))
        assertTrue(selection.contains("AgentIslandOverlay.attachedWindowId()"))
        assertTrue(selection.contains("root?.let(::release)"))
        val host = accessibility.after("private fun hostRoot()").before("private fun scan()")
        assertTrue(host.contains("hostWindow() ?: throw IllegalStateException(\"host_not_foreground\")"))
        assertTrue(host.contains("root.packageName?.toString() != AgentWire.TARGET_PACKAGE"))
        val foreground = accessibility.after("internal fun foreground()").before("internal fun clear(")
        assertTrue(foreground.contains("if (service.hostWindow() != null) return@runCatching Foreground.HOST"))
        val gesture = accessibility.after("private fun gesture(").before("private fun execute(")
        assertTrue(gesture.indexOf("AgentIslandOverlay.obscures(gestureBounds)") < gesture.indexOf("dispatchGesture("))
        assertTrue(gesture.contains("\"ui_target_obscured\""))
        assertFalse(selection.contains("canDrawOverlays"))
        assertFalse(selection.contains("AgentPreferences.islandAllowed"))

        assertTrue(sessionService.contains("if (!AgentUiPolicy.systemNotification(Build.VERSION.SDK_INT))"))
        assertTrue(sessionService.contains("startForeground(AgentTaskNotification.ID"))
        assertTrue(sessionService.contains("AgentController.removeObserver(observer)"))
        val notification = SourceContract.read("agent/ui/AgentTaskNotification.kt")
        assertTrue(notification.contains("if (Build.VERSION.SDK_INT >= 36) AgentPromotedNotification.configure"))
        assertTrue(notification.contains("manager.canPostPromotedNotifications()"))
        assertTrue(notification.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertFalse(notification.contains("RemoteViews"))

        val capture = accessibility.after("private fun screenshot(").before("companion object")
        assertTrue(accessibility.contains("if (Build.VERSION.SDK_INT >= 34) screenshot"))
        assertTrue(capture.contains("takeScreenshotOfWindow(shot.tree.window"))
        assertTrue(capture.contains("check(!result.isDone)"))
        assertTrue(capture.contains("!tree.privateInput"))
        assertTrue(capture.contains("frame.contains(tree.bounds)"))
        assertTrue(capture.contains("buffer.close()"))
        assertTrue(accessibility.contains("renderer.contains(\"WebView\", true)"))
        assertTrue(accessibility.contains("renderer.contains(\"ComposeView\", true)"))
    }

    @Test
    fun longTaskListenersSeparateRenewableOwnershipFromOperationDeadlines() {
        val window = SourceContract.read("agent/host/HostAgentWindow.kt")
        val input = window.after("private inner class TaskCallback").before("fun showStop(")
        assertTrue(input.contains("ownedCallback.get() === this"))
        assertTrue(input.contains("ownershipGeneration == lease.generation"))
        assertFalse(input.contains("session.isTaskActive(lease, now())"))
        assertFalse(input.contains("session.isActive(lease, now())"))
        assertFalse(window.contains("AccessibilityStateChangeListener"))

        val accessibility = SourceContract.read("agent/AgentAccessibilityService.kt")
        assertTrue(accessibility.contains("if (AgentController.usesAccessibility())"))
        assertTrue(accessibility.contains("AgentController.cancel(this, \"accessibility_disconnected\")"))

        val runtime = SourceContract.read("agent/host/HostAgentRuntime.kt")
        val connected = runtime.after("override fun onServiceConnected").before("override fun onServiceDisconnected")
        assertTrue(connected.contains("session.isTaskActive(lease, now())"))
        assertFalse(connected.contains("session.isActive(lease, now())"))
        val mainAction = runtime.after("private fun <T> onMain(").before("private fun stop(")
        assertTrue(mainAction.contains("session.whileActive(lease, now())"))
        val delivery = runtime.after("private fun deliver(").before("private data class Binding")
        assertTrue(delivery.contains("session.isActive(lease, now())"))
        val capture = window.after("fun capture(").before("private data class Capture")
        assertTrue(capture.contains("session.isActive(lease, now())"))

        val expiry = runtime.after("private fun scheduleExpiry(").before("private fun bind(")
        assertTrue(expiry.contains("session.current(now())"))
        assertTrue(expiry.contains("current.generation != lease.generation"))
        assertTrue(expiry.contains("current.deadline - now()"))
        assertFalse(expiry.contains("lease.deadline - now()"))
        val release = runtime.after("private fun release(").before("private fun deliver(")
        assertTrue(release.contains("it.generation == lease.generation"))
        assertTrue(release.contains("cacheGeneration == lease.generation"))
        assertTrue(release.contains("windows.releaseTask(lease)"))
    }

    @Test
    fun screenshotProcessingStaysOffMainThreadWithSingleBufferOwnerAndWindowGate() {
        val service = SourceContract.read("agent/AgentAccessibilityService.kt")
        val callback = service.after("override fun onSuccess(screenshot: ScreenshotResult)").before("private fun encodeScreenshot(")
        val encode = service.after("private fun encodeScreenshot(").before("private fun deliverScreenshot(")
        val deliver = service.after("private fun deliverScreenshot(").before("companion object")

        for (heavy in listOf(
            "hardware.copy(", ".copy(Bitmap.Config", "createBitmap(", "createScaledBitmap(", ".compress(",
            "Base64.encodeToString", "wrapHardwareBuffer", "ByteArrayOutputStream"
        )) {
            assertFalse("主线程回调不应出现 $heavy", callback.contains(heavy))
        }
        assertTrue(callback.contains("screenshotWorker.trySubmit"))

        for (heavy in listOf(
            "wrapHardwareBuffer", ".copy(Bitmap.Config.ARGB_8888", "createBitmap(", "createScaledBitmap(",
            ".compress(Bitmap.CompressFormat.JPEG", "Base64.encodeToString"
        )) {
            assertTrue("worker 函数应包含 $heavy", encode.contains(heavy))
        }
        for (forbidden in listOf("scan()", "windows", "hostRoot", "getChild", "performAction", "visualGate", "snapshot")) {
            assertFalse("worker 函数不应访问 $forbidden", encode.contains(forbidden))
        }

        assertTrue(callback.contains("var handedOff = false"))
        assertTrue(callback.contains("finally { if (!handedOff) buffer.close() }"))
        assertTrue(callback.contains("check(handedOff) { \"screen_busy\" }"))
        assertEquals(1, Regex("""buffer\.close\(\)""").findAll(encode).count())
        assertTrue(encode.afterLast("finally {").contains("buffer.close()"))
        assertTrue(encode.indexOf("if (result.isDone) return") in 0 until encode.indexOf("wrapHardwareBuffer"))

        assertTrue(callback.contains("main.post { deliverScreenshot("))
        assertTrue(deliver.contains("if (result.isDone) return"))
        assertTrue(deliver.contains("check(snapshot === shot && visualGate.isCurrent(windowToken))"))
        assertTrue(deliver.indexOf("admit()") < deliver.indexOf("visualGate.commit("))

        assertTrue(callback.indexOf("ready(task)") < callback.indexOf("val windowToken = visualGate.token()"))
        assertTrue(callback.indexOf("val windowToken = visualGate.token()") < callback.indexOf("screenshotWorker.trySubmit"))
        assertTrue(callback.contains("deliverScreenshot(shot, state, outcome, windowToken, result, admit)"))
        val event = service.after("override fun onAccessibilityEvent(").before("override fun onInterrupt()")
        assertTrue(event.contains("TYPE_WINDOW_STATE_CHANGED) visualGate.invalidate()"))
        assertTrue(deliver.contains("check(visualGate.commit(windowToken, shot.id, capturedAt)) { \"ui_snapshot_stale\" }"))
        assertFalse("旧的无条件写回不得复活", service.contains("visualSnapshot = shot.id"))
        assertFalse(service.contains("visualSnapshot"))

        val worker = SourceContract.read("agent/AgentScreenshotWorker.kt")
        assertFalse(worker.contains("CallerRunsPolicy"))
        assertTrue(worker.contains("ThreadPoolExecutor.AbortPolicy()"))
        assertTrue(worker.contains("ArrayBlockingQueue<Runnable>(1)"))
        val submit = worker.after("fun trySubmit(").before("val busy: Boolean")
        assertTrue(submit.contains("var accepted = false"))
        assertTrue(submit.contains("finally {"))
        assertTrue(submit.contains("if (!accepted) inFlight.set(false)"))
    }
}
