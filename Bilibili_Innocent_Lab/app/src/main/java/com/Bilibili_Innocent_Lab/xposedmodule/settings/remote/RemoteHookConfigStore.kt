package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface RemoteHookConfigPublishResult {
    val succeeded: Boolean

    data class Success(
        val generation: Long,
        val changed: Boolean
    ) : RemoteHookConfigPublishResult {
        override val succeeded: Boolean = true
    }

    data class Failure(
        val reason: String,
        val throwable: Throwable? = null
    ) : RemoteHookConfigPublishResult {
        override val succeeded: Boolean = false
    }
}

internal enum class RemoteHookConfigPublishState {
    NOT_INITIALIZED,
    WAITING_FOR_SERVICE,
    PUBLISHING,
    READY,
    FAILED
}

/**
 * 仅供模块自身诊断页读取的有界状态；不保留设置值、异常对象、路径或 Binder 句柄。
 */
internal data class RemoteHookConfigDiagnostics(
    val state: RemoteHookConfigPublishState,
    val lastAttemptAtEpochMs: Long,
    val lastSuccessAtEpochMs: Long,
    val generation: Long,
    val failureCode: String?,
    val publishPending: Boolean,
    val connectionId: Long = 0L
)

internal fun interface ModernFrameworkStatusListener {
    fun onFrameworkStatusChanged(status: ModernFrameworkStatus)
}

internal data class RemoteHookConfigPublishEvent(
    val decision: UserTermsDecision,
    val result: RemoteHookConfigPublishResult
)

internal fun interface RemoteHookConfigPublishListener {
    fun onRemoteHookConfigPublished(event: RemoteHookConfigPublishEvent)
}

internal fun shouldRepeatRemotePublish(
    dirty: Boolean,
    attemptedDecision: UserTermsDecision,
    requestedDecision: UserTermsDecision
): Boolean = dirty || attemptedDecision != requestedDecision

/**
 * 模块进程中的 API 102 Remote Preferences 发布器和服务状态单点。
 *
 * 私有默认设置仍是权威源。服务绑定、设置变更和条款决定只会在单线程发布器上合并，宿主
 * 读取的是框架数据库中的完整不可变快照，不再接触模块私有目录。
 */
internal object RemoteHookConfigStore {
    private const val TAG = "BilibiliInnocentLab"
    private val lock = Any()
    private val committer = RemoteHookConfigCommitter()
    private var connectionId = 0L
    private val observedKeys = RemoteHookConfigContract.hookValueKeys
    private val publishScheduled = AtomicBoolean(false)
    private val publishDirty = AtomicBoolean(false)
    private val listenerRegistered = AtomicBoolean(false)
    private val statusListeners = CopyOnWriteArraySet<ModernFrameworkStatusListener>()
    private val publishListeners = CopyOnWriteArraySet<RemoteHookConfigPublishListener>()
    private val publishExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bil-remote-config").apply { isDaemon = true }
    }

    private var applicationContext: Context? = null
    private var observedPreferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var service: XposedService? = null
    @Volatile private var requestedDecision = UserTermsDecision.UNDECIDED
    @Volatile private var frameworkStatus = ModernFrameworkStatus(
        connected = false,
        capable = false,
        name = "",
        apiVersion = 0
    )
    @Volatile private var publishDiagnostics = RemoteHookConfigDiagnostics(
        state = RemoteHookConfigPublishState.NOT_INITIALIZED,
        lastAttemptAtEpochMs = 0L,
        lastSuccessAtEpochMs = 0L,
        generation = 0L,
        failureCode = null,
        publishPending = false
    )

    fun initialize(context: Context, decision: UserTermsDecision): RemoteHookConfigPublishResult {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        requestedDecision = decision
        registerServiceListener()
        synchronized(lock) {
            if (observedPreferences == null) {
                val source = appContext.modulePreferences()
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key !in observedKeys) return@OnSharedPreferenceChangeListener
                    requestPublish(appContext)
                }
                source.registerOnSharedPreferenceChangeListener(listener)
                observedPreferences = source
                preferenceListener = listener
            }
        }
        return publishSnapshotAndNotify(appContext, decision)
    }

    fun publish(
        context: Context,
        decision: UserTermsDecision
    ): RemoteHookConfigPublishResult {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        requestedDecision = decision
        return publishSnapshotAndNotify(appContext, decision)
    }

    /**
     * 更新最终用户意图并交给既有单线程发布器合并。条款等待同步、设置变化和服务重连
     * 都经过同一个队列，避免 Binder/SharedPreferences 并发写入同一远端分组。
     */
    fun requestDecisionPublish(context: Context, decision: UserTermsDecision) {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        requestedDecision = decision
        requestPublish(appContext)
    }

    private fun publishSnapshotAndNotify(
        appContext: Context,
        decision: UserTermsDecision
    ): RemoteHookConfigPublishResult {
        val result = publishSnapshot(appContext, decision)
        notifyPublishListeners(RemoteHookConfigPublishEvent(decision, result))
        return result
    }

    /** 在取得发布锁后才截取目标决定，避免同步拒绝返回后旧 ACCEPTED 任务再次落盘。 */
    private fun publishRequestedSnapshotAndNotify(
        appContext: Context
    ): Pair<UserTermsDecision, RemoteHookConfigPublishResult> {
        val (decision, result) = synchronized(lock) {
            val target = requestedDecision
            target to publishSnapshot(appContext, target)
        }
        notifyPublishListeners(RemoteHookConfigPublishEvent(decision, result))
        return decision to result
    }

    private fun publishSnapshot(
        appContext: Context,
        decision: UserTermsDecision
    ): RemoteHookConfigPublishResult = synchronized(lock) {
        val attemptAt = System.currentTimeMillis().coerceAtLeast(1L)
        publishDiagnostics = publishDiagnostics.copy(
            state = RemoteHookConfigPublishState.PUBLISHING,
            lastAttemptAtEpochMs = attemptAt,
            failureCode = null,
            publishPending = true
        )
        val activeService = service
        val result = when {
            activeService == null -> RemoteHookConfigPublishResult.Failure(
                "Xposed service is not connected"
            )
            !frameworkStatus.capable && frameworkStatus.failureCode == "framework_metadata_unavailable" ->
                RemoteHookConfigPublishResult.Failure("Xposed framework metadata is unavailable")
            !frameworkStatus.capable -> RemoteHookConfigPublishResult.Failure(
                "Xposed framework does not provide API 102 remote preferences"
            )
            else -> publishWithService(appContext, decision, activeService)
        }
        if (!result.succeeded) committer.invalidate()
        publishDiagnostics = when (result) {
            is RemoteHookConfigPublishResult.Success -> publishDiagnostics.copy(
                state = RemoteHookConfigPublishState.READY,
                lastSuccessAtEpochMs = System.currentTimeMillis().coerceAtLeast(attemptAt),
                generation = result.generation,
                connectionId = connectionId,
                failureCode = null,
                publishPending = false
            )
            is RemoteHookConfigPublishResult.Failure -> publishDiagnostics.copy(
                state = if (activeService == null) {
                    RemoteHookConfigPublishState.WAITING_FOR_SERVICE
                } else {
                    RemoteHookConfigPublishState.FAILED
                },
                failureCode = result.toFailureCode(),
                publishPending = false
            )
        }
        result
    }

    private fun publishWithService(
        appContext: Context,
        decision: UserTermsDecision,
        activeService: XposedService
    ): RemoteHookConfigPublishResult = runCatching {
        val values = RemoteHookConfigContract.resolveSourceValues(appContext.modulePreferences().all)
        val preferences = activeService.getRemotePreferences(RemoteHookConfigContract.GROUP)
        committer.publish(
            connectionId = connectionId,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            decision = decision,
            values = values,
            nowEpochMs = System.currentTimeMillis(),
            backend = object : RemoteHookConfigBackend {
                override fun readCached(): Map<String, *> = preferences.all

                override fun commit(document: Map<String, Any>): Boolean {
                    // 仅替换专用远端分组，不清除模块私有设置。clear 保证失败后的重试确实发送。
                    val editor = preferences.edit().clear()
                    document.forEach { (key, value) ->
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is String -> editor.putString(key, value)
                            else -> error("Unsupported remote preference value")
                        }
                    }
                    return editor.commit()
                }
            }
        )
    }.getOrElse { throwable ->
        committer.invalidate()
        RemoteHookConfigPublishResult.Failure(
            throwable.message ?: throwable.javaClass.simpleName, throwable
        )
    }

    fun status(): ModernFrameworkStatus = frameworkStatus

    fun diagnostics(): RemoteHookConfigDiagnostics = synchronized(lock) {
        publishDiagnostics.copy(
            publishPending = publishDiagnostics.publishPending ||
                publishScheduled.get() || publishDirty.get()
        )
    }

    /**
     * 框架服务由 LSPosed 异步投递；订阅时立即回送当前快照，消除 Activity 首次绘制与
     * Binder 到达之间的竞态。监听器必须由调用方按生命周期移除。
     */
    fun addStatusListener(listener: ModernFrameworkStatusListener) {
        statusListeners.add(listener)
        notifyStatusListener(listener, frameworkStatus)
    }

    fun removeStatusListener(listener: ModernFrameworkStatusListener) {
        statusListeners.remove(listener)
    }

    fun addPublishListener(listener: RemoteHookConfigPublishListener) {
        publishListeners.add(listener)
    }

    fun removePublishListener(listener: RemoteHookConfigPublishListener) {
        publishListeners.remove(listener)
    }

    fun logFailure(result: RemoteHookConfigPublishResult) {
        if (result !is RemoteHookConfigPublishResult.Failure) return
        Log.w(TAG, "publish remote hook config failed: ${result.reason}", result.throwable)
    }

    private fun registerServiceListener() {
        if (!listenerRegistered.compareAndSet(false, true)) return
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(boundService: XposedService) {
                val metadata = readModernFrameworkStatus(
                    readApiVersion = { boundService.apiVersion },
                    readProperties = { boundService.frameworkProperties },
                    readName = { boundService.frameworkName },
                    readVersion = { boundService.frameworkVersion },
                    readVersionCode = { boundService.frameworkVersionCode }
                )
                val newStatus = synchronized(lock) {
                    if (!metadata.capable && frameworkStatus.capable && service !== boundService) {
                        null
                    } else {
                        if (service !== boundService) {
                            connectionId += 1L
                            committer.invalidate()
                            publishDiagnostics = publishDiagnostics.copy(
                                state = RemoteHookConfigPublishState.WAITING_FOR_SERVICE,
                                failureCode = null
                            )
                        }
                        service = boundService
                        metadata.copy(connectionId = connectionId).also { frameworkStatus = it }
                    }
                }
                if (newStatus != null) notifyStatusListeners(newStatus)
                applicationContext?.let(::requestPublish)
            }

            override fun onServiceDied(deadService: XposedService) {
                val disconnected = synchronized(lock) {
                    if (service !== deadService) {
                        null
                    } else {
                        service = null
                        committer.invalidate()
                        publishDiagnostics = publishDiagnostics.copy(
                            state = RemoteHookConfigPublishState.WAITING_FOR_SERVICE,
                            failureCode = "service_not_connected"
                        )
                        frameworkStatus.copy(
                            connected = false, capable = false, failureCode = "service_died"
                        ).also { frameworkStatus = it }
                    }
                }
                if (disconnected != null) notifyStatusListeners(disconnected)
            }
        })
    }

    private fun notifyStatusListeners(status: ModernFrameworkStatus) {
        statusListeners.forEach { listener -> notifyStatusListener(listener, status) }
    }

    private fun notifyPublishListeners(event: RemoteHookConfigPublishEvent) {
        publishListeners.forEach { listener ->
            runCatching { listener.onRemoteHookConfigPublished(event) }
                .onFailure { throwable ->
                    Log.w(TAG, "remote publish listener failed", throwable)
                }
        }
    }

    private fun notifyStatusListener(
        listener: ModernFrameworkStatusListener,
        status: ModernFrameworkStatus
    ) {
        runCatching { listener.onFrameworkStatusChanged(status) }
            .onFailure { throwable ->
                Log.w(TAG, "framework status listener failed", throwable)
            }
    }

    private fun requestPublish(context: Context) {
        publishDirty.set(true)
        if (!publishScheduled.compareAndSet(false, true)) return
        publishExecutor.execute {
            try {
                var attemptedDecision: UserTermsDecision
                do {
                    publishDirty.set(false)
                    val attempt = publishRequestedSnapshotAndNotify(context)
                    attemptedDecision = attempt.first
                    val result = attempt.second
                    logFailure(result)
                } while (shouldRepeatRemotePublish(
                    dirty = publishDirty.get(),
                    attemptedDecision = attemptedDecision,
                    requestedDecision = requestedDecision
                ))
            } finally {
                publishScheduled.set(false)
                if (publishDirty.get()) requestPublish(context)
            }
        }
    }

    private fun RemoteHookConfigPublishResult.Failure.toFailureCode(): String = when (reason) {
        "Xposed service is not connected" -> "service_not_connected"
        "Xposed framework metadata is unavailable" -> "framework_metadata_unavailable"
        "Xposed framework does not provide API 102 remote preferences" ->
            "remote_preferences_unsupported"
        else -> "publish_failed"
    }
}
