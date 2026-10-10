package com.Bilibili_Innocent_Lab.xposedmodule.agent.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentTaskState
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentNotificationReceiver
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity

/** 使用标准系统通知；新版是否提升为状态栏 chip/岛由系统和用户通知设置决定。 */
internal object AgentTaskNotification {
    const val ID = 7301
    const val CHANNEL = "agent_task"
    const val OPEN_LOGS = "com.Bilibili_Innocent_Lab.xposedmodule.agent.OPEN_LOGS"
    fun create(context: Context, task: String, state: AgentTaskState): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.agent_title), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        val logs = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply {
            action = OPEN_LOGS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun control(actionName: String, code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, AgentNotificationReceiver::class.java).setAction(actionName).setData(Uri.parse("agent-task:$task")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val currentTip = AgentStatusText.tip(context, state)
        val recent = if (state.showRecentOperation && state.lastOperation.isNotBlank()) context.getString(
            if (state.lastOperationSucceeded == true) R.string.agent_recent_completed else R.string.agent_recent_failed,
            AgentStatusText.tip(context, state.copy(phase = state.lastOperation))) else null
        val tip = recent?.let { "$it · $currentTip" } ?: currentTip
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_science)
            .setContentTitle(context.getString(R.string.agent_notification_title)).setContentText(tip)
            .setContentIntent(logs).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setDeleteIntent(control(AgentNotificationReceiver.DISMISS, 2))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.agent_logs_title), logs).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.agent_stop), control(AgentNotificationReceiver.STOP, 1)).build())
        if (Build.VERSION.SDK_INT >= 36) AgentPromotedNotification.configure(builder, manager, recent ?: currentTip,
            AgentPreferences.islandAllowed(context) && !AgentNotificationReceiver.dismissed(task))
        return builder.build()
    }
    fun settingsIntent(context: Context): Intent {
        val base = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT < 36) return base
        val promoted = AgentPromotedNotification.settingsIntent(context)
        return if (promoted.resolveActivity(context.packageManager) != null) promoted else base
    }
}

/** 隔离新 API，低版本既不加载 ProgressStyle，也不请求新版授权界面。 */
@RequiresApi(36)
private object AgentPromotedNotification {
    fun configure(builder: Notification.Builder, manager: NotificationManager, tip: String, allowed: Boolean) {
        builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            .setShortCriticalText(tip.take(7))
            // SDK 37 将 native setter 标为 36.1；36.0 使用同一个标准通知 extra，系统不支持提升时自然降为普通通知。
            .addExtras(android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", allowed && manager.canPostPromotedNotifications())
            })
    }
    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
