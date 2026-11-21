package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNotificationLogWiringContractTest {
    fun runningNotificationUsesOnlyTaskBoundImmutableBroadcastsForLogsAndStop() {
        val notification = SourceContract.read("agent/ui/AgentTaskNotification.kt")
        assertFalse(notification.contains("PendingIntent.getActivity"))
        assertFalse(notification.contains("MainActivity::class.java"))
        assertTrue(notification.contains("PendingIntent.getBroadcast"))
        assertTrue(notification.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(notification.contains("AgentNotificationReceiver.TOGGLE_LOGS, 3"))
        assertTrue(notification.contains("setContentIntent(logs)"))
        assertTrue(notification.contains("AgentNotificationReceiver.STOP, 1"))
        assertTrue(notification.contains("setData(Uri.parse(\"agent-task:$" + "task\"))"))
        val receiver = SourceContract.read("agent/AgentNotificationReceiver.kt")
        val receive = receiver.after("override fun onReceive(").before("companion object")
        assertTrue(receive.contains("if (!AgentController.owns(task)) return"))
        assertTrue(receive.contains("task == AgentController.currentTaskId() && AgentController.state.running"))
        assertTrue(receive.contains("AgentSessionService.toggleNotificationLogs(task)"))
        val manifest = SourceContract.read("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("<receiver android:name=\".agent.AgentNotificationReceiver\" android:exported=\"false\" />"))
    }

    fun logToggleOnlyRefreshesExistingServiceDisplayAndCleansItsObserver() {
        val service = SourceContract.read("agent/AgentSessionService.kt")
        val toggle = service.after("private fun toggleLogs(").before("override fun onDestroy()")
        assertTrue(toggle.contains("!AgentController.owns(task)"))
        assertTrue(toggle.contains("logMode.toggle(task, AgentController.currentTaskId(), AgentController.state.running)"))
        assertTrue(toggle.contains("AgentExecutionLogStore.observe(logObserver)"))
        assertTrue(toggle.contains("AgentExecutionLogStore.removeObserver(logObserver)"))
        assertTrue(toggle.contains("forceNotificationRefresh = true"))
        assertTrue(toggle.contains("main.postDelayed(update, delay ?: 0L)"))
        for (forbidden in listOf("AgentController.cancel", "AgentHostClient", "startActivity", "startService", "notificationPolicy.reset()"))
            assertFalse(toggle.contains(forbidden))
        val update = service.after("private val update:").before("private val observer:")
        assertTrue(update.contains("AgentController.currentTaskId() != task"))
        assertTrue(update.contains("revision != shownLogRevision"))
        assertTrue(update.contains("AgentTaskNotification.create(this, task, visible, logVisible)"))
        val cleanup = service.after("override fun onDestroy()").before("companion object")
        assertTrue(cleanup.contains("if (activeService.get() === this) activeService.clear()"))
        assertTrue(cleanup.contains("synchronizeLogOwner(null)"))
        assertFalse(service.contains("getStringExtra"))
    }

    fun notificationLogContainsOnlyBoundedEnumDescriptionsAndNumbers() {
        val notification = SourceContract.read("agent/ui/AgentTaskNotification.kt")
        val text = notification.after("private fun logText(").before("fun settingsIntent(")
        assertTrue(text.contains("AgentNotificationLogContent.recent(AgentExecutionLogStore.snapshot(), task)"))
        assertTrue(text.contains("AgentStatusText.status(context, event.status)"))
        assertTrue(text.contains("AgentStatusText.reason(context, event.reason)"))
        assertTrue(text.contains(".take(AgentNotificationLogContent.MAX_CHARS)"))
        for (forbidden in listOf("state.detail", "goal", "response", "apiKey", "sources(", "JSONObject", "image_data_url"))
            assertFalse(text.contains(forbidden))
        val promoted = notification.after("fun configure(").before("fun settingsIntent(")
        assertTrue(promoted.contains("if (!logMode) builder.setStyle(Notification.ProgressStyle()"))
        assertTrue(notification.contains("if (logMode) builder.setStyle(Notification.BigTextStyle()"))
        assertTrue(promoted.contains("allowed && manager.canPostPromotedNotifications()"))
    }

    fun allSupportedLocalesHaveBothNeutralModeLabels() {
        for (folder in listOf("values", "values-zh-rCN", "values-b+zh+Hant")) {
            val strings = SourceContract.read("src/main/res/$folder/strings.xml")
            assertTrue(strings.contains("name=\"agent_notification_show_status\""))
            assertTrue(strings.contains("name=\"agent_notification_logs_hint\""))
        }
        val logEntry = SourceContract.read("ui/activity/AgentLogDialogs.kt")
        assertTrue(logEntry.contains("showAgentLogsDialog"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentNotificationLogWiringContractTest) -> Unit) {
            val s = AgentNotificationLogWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.runningNotificationUsesOnlyTaskBoundImmutableBroadcastsForLogsAndStop() }
        runIsolated { s -> s.logToggleOnlyRefreshesExistingServiceDisplayAndCleansItsObserver() }
        runIsolated { s -> s.notificationLogContainsOnlyBoundedEnumDescriptionsAndNumbers() }
        runIsolated { s -> s.allSupportedLocalesHaveBothNeutralModeLabels() }
    }
}
