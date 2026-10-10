package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import com.Bilibili_Innocent_Lab.xposedmodule.agent.ui.AgentIslandOverlay
import com.Bilibili_Innocent_Lab.xposedmodule.agent.ui.AgentTaskNotification

/** 用户启动的任务由前台服务维持；结束即回收，不恢复或重放目标。 */
class AgentSessionService : Service() {
    private var ownerTaskId: String? = null
    private var island: AgentIslandOverlay? = null
    private val main = Handler(Looper.getMainLooper())
    private val notificationPolicy = AgentNotificationPolicy()
    private val update: Runnable = Runnable {
        val task = ownerTaskId ?: return@Runnable
        val state = AgentController.state
        if (!state.running || AgentController.currentTaskId() != task || AgentNotificationReceiver.dismissed(task)) return@Runnable
        val visible = notificationPolicy.take(SystemClock.elapsedRealtime())
        if (visible != null && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            runCatching { getSystemService(NotificationManager::class.java).notify(AgentTaskNotification.ID,
                AgentTaskNotification.create(this, task, visible)) }
        }
        notificationPolicy.delay(SystemClock.elapsedRealtime())?.let { main.postDelayed(update, it) }
    }
    private val observer: (AgentTaskState) -> Unit = {
        val current = AgentController.currentTaskId()
        if (ownerTaskId != current) { notificationPolicy.reset(); ownerTaskId = current }
        val delay = notificationPolicy.offer(it, SystemClock.elapsedRealtime())
        main.removeCallbacks(update)
        if (!it.running) stopForeground(STOP_FOREGROUND_REMOVE) else delay?.let { wait -> main.postDelayed(update, wait) }
    }
    override fun onCreate() {
        super.onCreate()
        ownerTaskId = AgentController.currentTaskId()
        AgentExecutionLogStore.initialize(applicationContext)
        val task = ownerTaskId ?: return
        val notification = AgentTaskNotification.create(this, task, AgentController.state)
        if (Build.VERSION.SDK_INT >= 34) startForeground(AgentTaskNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(AgentTaskNotification.ID, notification)
        if (!AgentUiPolicy.systemNotification(Build.VERSION.SDK_INT)) island = runCatching { AgentIslandOverlay(this) }.getOrNull()
        AgentController.observe(observer)
    }
    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != AgentWire.SERVICE_KEEP_ALIVE || data.dataSize() > 4096) return false
            return runCatching {
                @Suppress("DEPRECATION")
                val uid = packageManager.getApplicationInfo(AgentWire.TARGET_PACKAGE, 0).uid
                if (getCallingUid() != uid) return@runCatching false
                data.enforceInterface(AgentWire.SERVICE_DESCRIPTOR)
                if (!AgentController.owns(data.readString().orEmpty())) return@runCatching false
                // 前台服务要保留 started 所有权；宿主释放绑定不应停止正在运行的任务。
                true
            }.getOrDefault(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder = endpoint
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        main.removeCallbacks(update)
        notificationPolicy.reset()
        ownerTaskId = AgentController.currentTaskId()
        val task = ownerTaskId
        if (!AgentController.state.running || task == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        else {
            val notification = AgentTaskNotification.create(this, task, AgentController.state)
            if (Build.VERSION.SDK_INT >= 34) startForeground(AgentTaskNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(AgentTaskNotification.ID, notification)
            notificationPolicy.offer(AgentController.state, SystemClock.elapsedRealtime())?.let { main.postDelayed(update, it) }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        island?.close(); island = null
        AgentController.removeObserver(observer)
        main.removeCallbacks(update)
        stopForeground(STOP_FOREGROUND_REMOVE)
        AgentExecutionLogStore.flush()
        ownerTaskId?.takeIf(AgentController::owns)?.let {
            AgentController.cancel(this, "service_stopped")
        }
        super.onDestroy()
    }
}
