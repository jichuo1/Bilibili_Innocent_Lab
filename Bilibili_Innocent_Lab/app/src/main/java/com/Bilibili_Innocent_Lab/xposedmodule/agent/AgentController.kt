// 启动和停止共用显式组件 Intent，保持任务服务的成对生命周期可见。
@file:Suppress("ReplaceWithIntentExtension", "ReplaceWithServiceExtension")

package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.content.Context
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelException
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentRoutePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelRole
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationListener
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSource
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.highcapable.kavaref.extension.classOf

internal data class AgentTaskState(val running: Boolean = false, val phase: String = "idle", val step: Long = 0,
                                   val source: Int = 0, val detail: String = "", val observations: Long = 0,
                                   val maximumSteps: Long = AgentWire.MAX_STEPS.toLong(), val role: AgentModelRole? = null,
                                   val lastOperation: String = "", val lastOperationSucceeded: Boolean? = null,
                                   val lastOperationStep: Long = 0, val showRecentOperation: Boolean = false)

/** 所有模型请求在模块进程串行执行。状态与对话只在内存，进程回收后不会自动恢复或重放动作。 */
internal object AgentController {
    private class Task(val context: Application, val goal: String, val sources: List<AgentModelSource>, val route: AgentRoutePolicy,
                       val vision: Boolean, val limits: AgentTaskLimits) {
        val id = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        fun deadline(capMs: Long) = limits.requestDeadline(startedAt, SystemClock.elapsedRealtime(), capMs)
        val sequence = AtomicLong()
        val cancelled = AtomicBoolean()
        val closing = AtomicBoolean()
        val accessibility = AgentAccessibilityService.connected()
        var lastOperation = ""
        var lastOperationSucceeded: Boolean? = null
        var lastOperationStep = 0L
        var future: Future<*>? = null
        fun stopped() = cancelled.get() || Thread.currentThread().isInterrupted || limits.timeExceeded(startedAt, SystemClock.elapsedRealtime()) ||
            !context.prefs().getBoolean(AgentPreferences.ENABLED, false)
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { Thread(it, "BIL-AgentTask").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val cancellations = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { Thread(it, "BIL-AgentCancel").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val observers = CopyOnWriteArraySet<(AgentTaskState) -> Unit>()
    private val notificationPending = AtomicBoolean()
    private val notification = Runnable {
        notificationPending.set(false)
        val current = state
        observers.forEach { runCatching { it(current) } }
    }
    @Volatile private var active: Task? = null
    @Volatile var state = AgentTaskState()
        private set

    fun observe(observer: (AgentTaskState) -> Unit) { observers += observer; observer(state) }
    fun removeObserver(observer: (AgentTaskState) -> Unit) { observers -= observer }
    fun currentTaskId(): String? = active?.id
    fun usesAccessibility(): Boolean = active?.accessibility == true
    fun actionAuthorized(taskId: String): Boolean = active?.let { it.id == taskId && owns(taskId) && authorized(it) } == true
    fun owns(taskId: String): Boolean = active?.let { it.id == taskId && !it.cancelled.get() && !it.closing.get() &&
        !it.limits.timeExceeded(it.startedAt, SystemClock.elapsedRealtime()) } == true

    /** 必须由可见设置页的用户点击调用；服务不接受 Intent 中的目标、凭据或命令。 */
    @Synchronized fun start(context: Context, goal: String, selected: Set<Int>, fixed: Int?, allowVision: Boolean,
                            limits: AgentTaskLimits = AgentTaskLimits(), fallback: Boolean = false): String? {
        if (active != null) return "already_running"
        if (goal.isBlank() || goal.length > AgentWire.MAX_GOAL_LENGTH || goal.any { it.isISOControl() && !it.isWhitespace() }) return "invalid_goal"
        if (!context.prefs().getBoolean(AgentPreferences.ENABLED, false) || !UserTermsConsentStore.readOrInitialize(context).isAuthorized)
            return "not_authorized"
        val sources = AgentPreferences.sources(context).filter { it.index in selected }
        if (sources.isEmpty() || (fixed != null && sources.none { it.index == fixed })) return "no_sources"
        val capabilities = sources.associate { it.fingerprint to AgentPreferences.capabilities(context, it) }
        if (sources.none { (fixed == null || fallback || it.index == fixed) &&
                (capabilities[it.fingerprint]?.tools == true || capabilities[it.fingerprint]?.plainPlanning == true || capabilities[it.fingerprint]?.decisions == true) }) return "probe_required"
        val policy = AgentRoutePolicy(sources.mapTo(linkedSetOf()) { it.index }, fixed, fallback)
        val app = context.applicationContext as? Application ?: return "not_authorized"
        val task = Task(app, goal.trim(), sources, policy, allowVision, limits)
        AgentExecutionLogStore.initialize(app)
        AgentExecutionLogStore.begin(task.id)
        active = task
        return try {
            publish(task, AgentTaskState(true, "connecting"))
            app.startForegroundService(Intent(app, classOf<AgentSessionService>()))
            val launch = context.packageManager.getLaunchIntentForPackage(AgentWire.TARGET_PACKAGE)
                ?: throw IllegalStateException("host_missing")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            task.future = worker.submit { run(app, task) }
            null
        } catch (_: Exception) {
            active = null
            app.stopService(Intent(app, classOf<AgentSessionService>()))
            state = AgentTaskState(phase = "failed", detail = "host_launch_failed")
            AgentExecutionLogStore.finish(task.id, AgentLogStatus.FAILED, reason = AgentLogReason.HOST_UNAVAILABLE)
            notifyObservers()
            "host_launch_failed"
        }
    }

    @Synchronized fun cancel(context: Context, reason: String = "cancelled") {
        val task = active ?: return
        if (task.closing.get() || !task.cancelled.compareAndSet(false, true)) return
        task.closing.set(true)
        task.future?.cancel(true)
        worker.purge()
        state = state.copy(running = true, phase = "stopping", detail = reason)
        AgentTaskLog.state(task.id, state)
        notifyObservers()
        runCatching { cancellations.execute {
            try {
                if (!task.accessibility) AgentHostClient.request(task.id, task.sequence.incrementAndGet(), SystemClock.elapsedRealtime() + 3_000, "cancel")
            } finally { completeClose(context.applicationContext, task, state.copy(running = false, phase = "cancelled", detail = reason)) }
        } }.onFailure { completeClose(context.applicationContext, task, state.copy(running = false, phase = "cancelled", detail = reason)) }
    }

    private fun run(context: Context, task: Task) {
        var observed = 0L
        var steps = 0L
        var conversation: AgentConversation? = null
        var cooperation: AgentCooperation? = null
        val progress = AgentProgressGuard()
        var verificationPending = false
        val preferences = context.prefs()
        val sourceKeys = (1..SemanticSource.MAX_SOURCES).flatMap { index ->
            val keys = FeaturePreferences.semanticSourceKeys(index)
            listOf(keys.first, keys.second, keys.third, RemoteHookConfigContract.semanticApiKey(index))
        }.toSet() + AgentPreferences.ENABLED
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (active === task && key in sourceKeys) cancel(context, "configuration_changed")
        }
        val termsListener = UserTermsAuthorizationListener { snapshot ->
            if (active === task && !snapshot.consentState.decision.isAuthorized) cancel(context, "not_authorized")
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        UserTermsAuthorizationCoordinator.addListener(termsListener)
        try {
            val readyDeadline = task.deadline(AgentWire.IPC_TIMEOUT_MS)
            if (task.accessibility) {
                var ready = false
                while (!task.stopped() && SystemClock.elapsedRealtime() < readyDeadline) {
                    val response = AgentAccessibilityService.request(task.id, "get_ui_state", JSONObject(), task::stopped)
                    if (response.optBoolean("ok")) { ready = true; break }
                    Thread.sleep(150)
                }
                check(ready) { "accessibility_host_unavailable" }
            } else check(AgentHostClient.awaitReady(readyDeadline, task::stopped)) { "host_unavailable_restart" }
            val capabilities = task.sources.mapNotNull { source -> AgentPreferences.capabilities(context, source)?.let { source.fingerprint to it } }.toMap()
            val canSee = task.vision && task.sources.any { source ->
                capabilities[source.fingerprint]?.vision == true
            }
            if (!task.accessibility) {
                val begin = host(task, "begin", JSONObject().put("allow_vision", canSee).put("lease_until", leaseUntil(task)))
                check(begin.optBoolean("ok")) { begin.optString("error", "host_rejected") }
            }
            val history = AgentConversation(AgentToolCatalog.SYSTEM, task.goal).also { conversation = it }
            val models = AgentCooperation(task.sources, capabilities, task.route, task.goal, canSee,
                checkpoint = { renew(task); (task.deadline(30_000) - SystemClock.elapsedRealtime()).coerceIn(1, 30_000).toInt() },
                cancelled = task::stopped, sourceChanged = { index -> publish(task, AgentTaskState(true, "thinking", steps, index,
                    observations = observed, maximumSteps = task.limits.maximumSteps)) }, elapsed = SystemClock::elapsedRealtime,
                capabilityChanged = { source, updated -> if (authorized(task)) AgentPreferences.saveCapabilities(context, source, updated) },
                requestEvent = { update ->
                    if (active === task && !task.cancelled.get()) {
                        if (update.status == AgentRequestStatus.STARTED) publish(task, AgentTaskState(true,
                            when (update.role) { AgentModelRole.PLANNER -> "waiting_response"; AgentModelRole.VISION -> "analyzing_image"; AgentModelRole.DECISION -> "reviewing" },
                            steps, update.source, observations = observed, role = update.role), recordLog = false)
                        AgentTaskLog.model(task.id, update, steps, observed)
                    }
                }).also { cooperation = it }
            var previousOperation = ""
            var repeatedFailures = 0
            while (!task.stopped() && task.limits.allowsStep(steps)) {
                check(context.prefs().getBoolean(AgentPreferences.ENABLED, false) && UserTermsConsentStore.readOrInitialize(context).isAuthorized) { "not_authorized" }
                val currentSources = AgentPreferences.sources(context).associateBy { it.index }
                check(task.sources.all { currentSources[it.index]?.fingerprint == it.fingerprint }) { "source_config_changed" }
                val turn = models.next(history)
                if (turn.toolCalls.isEmpty()) {
                    check(!verificationPending) { "ui_verification_required" }
                    check(observed > 0) { "no_observation" }
                    check(turn.text.isNotBlank()) { "empty_answer" }
                    publish(task, AgentTaskState(false, "finished", steps, detail = turn.text.take(6_000), observations = observed))
                    return
                }
                // 一轮多个动作会令取消和中间页面状态失去可判定的前后关系；要求模型重新逐步规划。
                check(turn.toolCalls.size == 1) { "parallel_tools_rejected" }
                val call = turn.toolCalls.single()
                check(history.acceptCallId(call.id)) { "duplicate_tool_call" }
                check(AgentToolCatalog.valid(call.name, call.arguments, canSee)) { "invalid_tool_arguments" }
                steps++
                val planner = task.sources.firstOrNull { it.fingerprint == models.plannerFingerprint }
                publish(task, AgentTaskState(true, call.name, steps, source = planner?.index ?: 0, observations = observed,
                    role = if (planner?.protocol == com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentSourceProtocol.DECISIONS) AgentModelRole.DECISION else AgentModelRole.PLANNER))
                renew(task)
                val response = host(task, call.name, call.arguments)
                task.lastOperation = call.name
                task.lastOperationSucceeded = response.optBoolean("ok")
                task.lastOperationStep = steps
                publish(task, state, recordLog = false)
                if (task.accessibility && response.optString("error") in setOf("host_not_foreground", "device_locked", "accessibility_not_connected"))
                    throw IllegalStateException(response.optString("error"))
                if (response.optBoolean("ok")) {
                    if (call.name in AgentToolCatalog.uiActions) verificationPending = response.optJSONObject("data")?.optBoolean("observation_after_action") != true
                    else if (call.name == "open_video") verificationPending = true
                    else if (call.name in setOf("get_host_state", "get_ui_state", "inspect_screen")) verificationPending = false
                }
                check(response.optString("error") !in setOf("task_inactive", "closed_task", "task_budget_exhausted", "host_disconnected")) { "task_inactive" }
                if (response.optBoolean("ok") && (call.name !in AgentToolCatalog.uiActions && call.name != "open_video" ||
                    response.optJSONObject("data")?.optBoolean("observation_after_action") == true)) observed++
                val data = response.optJSONObject("data")
                val image = data?.optString("image_data_url").orEmpty()
                if (image.isNotEmpty()) {
                    check(canSee && call.name == "inspect_screen" && image.startsWith("data:image/jpeg;base64,") && image.length <= 180_000) { "invalid_image_response" }
                    data?.remove("image_data_url")
                    data?.put("image_digest", models.imageDigest(image))
                    try { data?.put("visual_assessment", models.inspect(image, response)) }
                    catch (error: AgentModelException) {
                        if (error.reason == AgentModelException.Reason.CANCELLED || !authorized(task)) throw error
                        data?.put("visual_status", "unavailable")
                    } catch (error: IllegalStateException) {
                        if (error.message != "vision_route_unavailable" || !authorized(task)) throw error
                        data?.put("visual_status", "unavailable")
                    }
                }
                models.record(call, response)
                if (call.name in setOf("search_videos", "get_video_details", "inspect_screen")) models.review(response)
                history.append(turn, response, models.plannerFingerprint)
                check(progress.observe(call, response)) { "task_stalled" }
                val signature = call.name + call.arguments.toString()
                repeatedFailures = if (!response.optBoolean("ok") && signature == previousOperation) repeatedFailures + 1 else 0
                previousOperation = signature
                check(repeatedFailures < 3) { "repeated_operation_failed" }
            }
            if (!task.cancelled.get()) publish(task, AgentTaskState(false, "limited", steps, detail = "task_budget", observations = observed))
        } catch (error: Exception) {
            if (!task.cancelled.get()) {
                val reason = when (error) {
                    is AgentModelException -> error.reason.description
                    is IllegalStateException -> error.message?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "task_failed"
                    else -> "task_failed"
                }
                val limited = task.limits.timeExceeded(task.startedAt, SystemClock.elapsedRealtime())
                publish(task, AgentTaskState(false, if (limited) "limited" else "failed", steps,
                    detail = if (limited) "task_budget" else reason, observations = observed))
            }
        } finally {
            conversation?.clear()
            cooperation?.clear()
            progress.clear()
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            UserTermsAuthorizationCoordinator.removeListener(termsListener)
            if (!task.cancelled.get()) {
                task.closing.set(true)
                try {
                    if (!task.accessibility) AgentHostClient.request(task.id, task.sequence.incrementAndGet(), SystemClock.elapsedRealtime() + 3_000, "finish")
                } finally { completeClose(context, task) }
            }
        }
    }

    private fun authorized(task: Task): Boolean = !task.stopped() &&
        UserTermsConsentStore.readOrInitialize(task.context).isAuthorized &&
        AgentPreferences.sources(task.context).associateBy { it.index }.let { current ->
            task.sources.all { current[it.index]?.fingerprint == it.fingerprint }
        }

    private fun host(task: Task, operation: String, args: JSONObject = JSONObject()): JSONObject {
        check(authorized(task)) { "not_authorized" }
        val started = SystemClock.elapsedRealtime()
        val response = if (task.accessibility) AgentAccessibilityService.request(task.id, operation, args, task::stopped)
            else if (operation == "get_ui_state" || operation in AgentToolCatalog.uiActions) JSONObject().put("ok", false).put("error", "accessibility_not_connected")
            else AgentHostClient.request(task.id, task.sequence.incrementAndGet(), task.deadline(AgentWire.IPC_TIMEOUT_MS), operation, args, task::stopped)
        val visibleResponse = if (task.accessibility && response.optString("error") == "host_not_foreground" &&
            AgentAccessibilityService.foreground() == AgentAccessibilityService.Foreground.MODULE) {
            // 用户从通知查看模块日志时等待；返回宿主后要求新的观察，不重放已计划的动作。
            AgentAccessibilityService.clear(task.id)
            publish(task, state.copy(running = true, phase = "waiting_host", detail = ""))
            var foreground = AgentAccessibilityService.Foreground.MODULE
            while (authorized(task) && foreground == AgentAccessibilityService.Foreground.MODULE) {
                Thread.sleep(750)
                foreground = AgentAccessibilityService.foreground()
            }
            check(authorized(task)) { "task_inactive" }
            JSONObject().put("ok", false).put("error", if (foreground == AgentAccessibilityService.Foreground.HOST) "ui_snapshot_stale" else "host_not_foreground")
        } else response
        return visibleResponse.also {
            AgentTaskLog.host(task.id, operation, it, (SystemClock.elapsedRealtime() - started).coerceAtLeast(0), state)
        }
    }

    private fun leaseUntil(task: Task): Long = task.deadline(AgentWire.MAX_LEASE_MS)

    private fun renew(task: Task) {
        if (task.accessibility) {
            check(authorized(task) && AgentAccessibilityService.connected()) { "accessibility_disconnected" }
            return
        }
        val response = host(task, "renew", JSONObject().put("lease_until", leaseUntil(task)))
        check(response.optBoolean("ok")) { "task_inactive" }
    }

    private fun completeClose(context: Context, task: Task, finalState: AgentTaskState? = null) {
        main.post {
            synchronized(this) {
                if (active !== task) return@synchronized
                state = (finalState ?: if (task.cancelled.get()) AgentTaskState(phase = "cancelled", detail = state.detail)
                    else state).copy(running = false, maximumSteps = task.limits.maximumSteps)
                active = null
                AgentAccessibilityService.clear(task.id)
                AgentTaskLog.state(task.id, state)
                context.stopService(Intent(context, classOf<AgentSessionService>()))
                notifyObservers()
            }
        }
    }

    private fun publish(task: Task, next: AgentTaskState, recordLog: Boolean = true) { if (active === task && !task.cancelled.get()) {
        state = next.copy(maximumSteps = task.limits.maximumSteps, lastOperation = task.lastOperation,
            lastOperationSucceeded = task.lastOperationSucceeded, lastOperationStep = task.lastOperationStep)
        if (recordLog) AgentTaskLog.state(task.id, state)
        notifyObservers()
    } }
    private fun notifyObservers() { if (observers.isNotEmpty() && notificationPending.compareAndSet(false, true)) main.post(notification) }
}
