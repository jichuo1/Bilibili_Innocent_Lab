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
import java.lang.ref.WeakReference

/** 用户启动的任务由前台服务维持；结束即回收，不恢复或重放目标。 */
class AgentSessionService : Service() {
    private var ownerTaskId: String? = null
    private var island: AgentIslandOverlay? = null
    private val main = Handler(Looper.getMainLooper())
    private val notificationPolicy = AgentNotificationPolicy()
    private val logMode = AgentNotificationLogMode()
    private var logObserverRegistered = false
    private var shownLogRevision = -1L
    private var forceNotificationRefresh = false
    private val update: Runnable = Runnable {
        val task = ownerTaskId ?: return@Runnable
        val state = AgentController.state
        if (!state.running || AgentController.currentTaskId() != task || AgentNotificationReceiver.dismissed(task)) return@Runnable
        val logVisible = logMode.showing(task)
        val revision = if (logVisible) AgentExecutionLogStore.snapshot().revision else -1L
        val visible = notificationPolicy.take(SystemClock.elapsedRealtime())
            ?: state.takeIf { forceNotificationRefresh || logVisible && revision != shownLogRevision }
        if (visible != null && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            runCatching { getSystemService(NotificationManager::class.java).notify(AgentTaskNotification.ID,
                AgentTaskNotification.create(this, task, visible, logVisible))
                shownLogRevision = revision; forceNotificationRefresh = false }
        }
        notificationPolicy.delay(SystemClock.elapsedRealtime())?.let { main.postDelayed(update, it) }
    }
    private val observer: (AgentTaskState) -> Unit = {
        val current = AgentController.currentTaskId()
        if (ownerTaskId != current) { notificationPolicy.reset(); ownerTaskId = current; synchronizeLogOwner(current) }
        val delay = notificationPolicy.offer(it, SystemClock.elapsedRealtime())
        main.removeCallbacks(update)
        if (!it.running) runCatching { stopForeground(STOP_FOREGROUND_REMOVE) } else delay?.let { wait -> main.postDelayed(update, wait) }
    }
    private val logObserver: (AgentLogSnapshot) -> Unit = {
        val task = ownerTaskId
        if (task != null && logMode.showing(task) && task == AgentController.currentTaskId() && AgentController.state.running) {
            val delay = notificationPolicy.offer(AgentController.state, SystemClock.elapsedRealtime())
            main.removeCallbacks(update)
            delay?.let { wait -> main.postDelayed(update, wait) }
        }
    }
    private fun promoteForeground(task: String): Boolean = runForegroundSafely {
        val notification = AgentTaskNotification.create(this, task, AgentController.state, logMode.showing(task))
        if (Build.VERSION.SDK_INT >= 34) startForeground(AgentTaskNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(AgentTaskNotification.ID, notification)
    }
    override fun onCreate() {
        super.onCreate()
        ownerTaskId = AgentController.currentTaskId()
        synchronizeLogOwner(ownerTaskId)
        AgentExecutionLogStore.initialize(applicationContext)
        val task = ownerTaskId
        if (task == null) {
            stopSelf()
            return
        }
        activeService = WeakReference(this)
        promoteForeground(task)
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
        val current = AgentController.currentTaskId()
        if (ownerTaskId != current) { ownerTaskId = current; synchronizeLogOwner(current) }
        val task = ownerTaskId
        if (!AgentController.state.running || task == null) {
            runForegroundSafely {
                if (promoteForeground(task.orEmpty())) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
            stopSelf(startId)
        } else {
            promoteForeground(task)
            notificationPolicy.offer(AgentController.state, SystemClock.elapsedRealtime())?.let { main.postDelayed(update, it) }
        }
        return START_NOT_STICKY
    }
    private fun synchronizeLogOwner(task: String?) {
        logMode.synchronize(task)
        shownLogRevision = -1L
        forceNotificationRefresh = false
        if (logObserverRegistered) {
            AgentExecutionLogStore.removeObserver(logObserver)
            logObserverRegistered = false
        }
    }
    private fun toggleLogs(task: String) {
        if (!AgentController.owns(task) || !logMode.toggle(task, AgentController.currentTaskId(), AgentController.state.running)) return
        if (logMode.showing(task) && !logObserverRegistered) {
            logObserverRegistered = true
            AgentExecutionLogStore.observe(logObserver)
        } else if (!logMode.showing(task) && logObserverRegistered) {
            AgentExecutionLogStore.removeObserver(logObserver)
            logObserverRegistered = false
        }
        shownLogRevision = -1L
        forceNotificationRefresh = true
        // 用户切换只刷新同一通知，不启动 Activity、续租宿主、撤销任务或改写模型状态。
        val delay = notificationPolicy.offer(AgentController.state, SystemClock.elapsedRealtime())
        main.removeCallbacks(update)
        main.postDelayed(update, delay ?: 0L)
    }
    override fun onDestroy() {
        if (activeService.get() === this) activeService.clear()
        synchronizeLogOwner(null)
        island?.close(); island = null
        AgentController.removeObserver(observer)
        main.removeCallbacks(update)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        AgentExecutionLogStore.flush()
        ownerTaskId?.takeIf(AgentController::owns)?.let {
            AgentController.cancel(this, "service_stopped")
        }
        super.onDestroy()
    }
    companion object {
        private var activeService = WeakReference<AgentSessionService>(null)
        internal fun toggleNotificationLogs(task: String) { activeService.get()?.toggleLogs(task) }
    }
}

internal fun runForegroundSafely(
    block: () -> Unit
): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    Api31ForegroundGuard.run(block)
} else {
    try {
        block()
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}

@androidx.annotation.RequiresApi(31)
private object Api31ForegroundGuard {
    @androidx.annotation.DoNotInline
    fun run(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: android.app.ForegroundServiceStartNotAllowedException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}
