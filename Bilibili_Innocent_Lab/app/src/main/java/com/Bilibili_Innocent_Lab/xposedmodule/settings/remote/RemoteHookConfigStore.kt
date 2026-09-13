package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityPolicy
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import java.util.concurrent.atomic.AtomicLong
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
        val changed: Boolean,
        val proof: RemotePublicationProof? = null
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
    val result: RemoteHookConfigPublishResult,
    val consentRevision: Long? = null
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
    private val lock = Any() // 串行发布与 committer，不被 UI getter 获取。
    private val stateLock = Any()
    private val intentEpoch = AtomicLong()
    private val operationIds = AtomicLong()
    private val candidates = ServiceCandidateGate<XposedService>()
    private val metadataExecutor = HostReceiptWire.executor("bil-framework-metadata")
    private val committer = RemoteHookConfigCommitter()
    @Volatile private var connectionId = 0L
    private val observedKeys = RemoteHookConfigContract.hookValueKeys
    private val publishScheduled = AtomicBoolean(false)
    private val publishDirty = AtomicBoolean(false)
    private val listenerRegistered = AtomicBoolean(false)
    private val statusListeners = CopyOnWriteArraySet<ModernFrameworkStatusListener>()
    private val publishListeners = CopyOnWriteArraySet<RemoteHookConfigPublishListener>()
    private val publishExecutor = HostReceiptWire.executor("bil-remote-config")

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
        synchronized(stateLock) {
            if (observedPreferences == null) {
                val source = appContext.modulePreferences()
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key !in observedKeys) return@OnSharedPreferenceChangeListener
                    intentEpoch.incrementAndGet()
                    requestPublish(appContext)
                }
                source.registerOnSharedPreferenceChangeListener(listener)
                observedPreferences = source
                preferenceListener = listener
            }
        }
        requestPublish(appContext)
        return RemoteHookConfigPublishResult.Failure("publication_pending")
    }

    fun publish(
        context: Context,
        decision: UserTermsDecision
    ): RemoteHookConfigPublishResult {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        requestedDecision = decision
        intentEpoch.incrementAndGet()
        return publishSnapshotAndNotify(appContext, decision)
    }

    /**
     * 更新最终用户意图并交给既有单线程发布器合并。条款等待同步、设置变化和服务重连
     * 都经过同一个队列，避免 Binder/SharedPreferences 并发写入同一远端分组。
     */
    fun requestDecisionPublish(context: Context, decision: UserTermsDecision) {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        if (requestedDecision != decision) {
            requestedDecision = decision
            intentEpoch.incrementAndGet()
        }
        requestPublish(appContext)
    }

    fun requestManualAttempt(
        context: Context,
        consentRevision: Long,
        origin: RemotePublicationAttempt.Origin,
        callback: (RemoteHookConfigPublishResult) -> Unit
    ): RemotePublicationAttempt {
        val app = context.applicationContext ?: context
        requestedDecision = UserTermsDecision.ACCEPTED
        val epoch = intentEpoch.incrementAndGet()
        val attempt = RemotePublicationAttempt(operationIds.incrementAndGet(), consentRevision, epoch,
            SystemClock.elapsedRealtime() + 15_000L, SystemClock::elapsedRealtime, callback, origin)
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CompatibilityManagerProbe.request(app)
        val work = Runnable {
            val connectDeadline = SystemClock.elapsedRealtime() + 6_000L
            while (attempt.isActive() && intentEpoch.get() == epoch && !frameworkStatus.capable &&
                SystemClock.elapsedRealtime() < connectDeadline) Thread.sleep(100L)
            if (!attempt.isActive()) return@Runnable
            val result = publishSnapshot(app, UserTermsDecision.ACCEPTED, consentRevision,
                force = true, isAttemptCurrent = { attempt.isActive() && intentEpoch.get() == epoch })
            notifyPublishListeners(RemoteHookConfigPublishEvent(UserTermsDecision.ACCEPTED, result, consentRevision))
            attempt.complete(result)
        }
        attempt.onCancel { publishExecutor.remove(work) }
        runCatching { publishExecutor.execute(work) }.onFailure {
            attempt.complete(RemoteHookConfigPublishResult.Failure("publication_queue_busy"))
        }
        return attempt
    }

    private fun publishSnapshotAndNotify(
        appContext: Context,
        decision: UserTermsDecision
    ): RemoteHookConfigPublishResult {
        val revision = UserTermsConsentStore.readStateOrInitialize(appContext).pendingAcceptance?.revision
        val result = publishSnapshot(appContext, decision, revision)
        notifyPublishListeners(RemoteHookConfigPublishEvent(decision, result, revision))
        return result
    }

    private fun publishRequestedSnapshotAndNotify(
        appContext: Context
    ): Pair<UserTermsDecision, RemoteHookConfigPublishResult> {
        val target = requestedDecision
        return target to publishSnapshotAndNotify(appContext, target)
    }

    private fun publishSnapshot(
        appContext: Context,
        decision: UserTermsDecision,
        consentRevision: Long?,
        force: Boolean = false,
        isAttemptCurrent: () -> Boolean = { true }
    ): RemoteHookConfigPublishResult = synchronized(lock) {
        val capturedEpoch = intentEpoch.get()
        val capturedConnection = connectionId
        val activeService = service
        fun current(): Boolean = isAttemptCurrent() && requestedDecision == decision &&
            intentEpoch.get() == capturedEpoch && service === activeService && connectionId == capturedConnection &&
            (consentRevision == null || UserTermsConsentStore.readStateOrInitialize(appContext).pendingAcceptance?.revision == consentRevision)
        if (!current()) return@synchronized RemoteHookConfigPublishResult.Failure("stale_publication")
        val attemptAt = System.currentTimeMillis().coerceAtLeast(1L)
        publishDiagnostics = publishDiagnostics.copy(
            state = RemoteHookConfigPublishState.PUBLISHING,
            lastAttemptAtEpochMs = attemptAt,
            failureCode = null,
            publishPending = true
        )
        val result = when {
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore.isDesiredEnabled(appContext) ->
                RemoteHookConfigPublishResult.Failure("NPatch selected")
            activeService == null -> RemoteHookConfigPublishResult.Failure(
                "Xposed service is not connected"
            )
            !frameworkStatus.capable && frameworkStatus.failureCode == "framework_metadata_unavailable" ->
                RemoteHookConfigPublishResult.Failure("Xposed framework metadata is unavailable")
            !frameworkStatus.capable -> RemoteHookConfigPublishResult.Failure(
                "Xposed framework does not provide supported Modern remote preferences"
            )
            else -> publishWithService(appContext, decision, activeService, capturedConnection, capturedEpoch, force || consentRevision != null, ::current)
        }
        if (!result.succeeded) committer.invalidate()
        if (!current()) {
            committer.invalidate()
            publishDiagnostics = publishDiagnostics.copy(publishPending = false,
                state = if (service == null) RemoteHookConfigPublishState.WAITING_FOR_SERVICE else RemoteHookConfigPublishState.FAILED,
                failureCode = "publication_outcome_unknown")
            return@synchronized RemoteHookConfigPublishResult.Failure("stale_publication")
        }
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
        activeService: XposedService,
        capturedConnection: Long,
        capturedEpoch: Long,
        force: Boolean,
        stillCurrent: () -> Boolean
    ): RemoteHookConfigPublishResult = runCatching {
        val values = RemoteHookConfigContract.resolveSourceValues(appContext.modulePreferences().all)
        val publication = PublicationAuthorityStore.forPublication(appContext, decision, values)
            ?: return@runCatching RemoteHookConfigPublishResult.Failure("stale_publication")
        val preferences = activeService.getRemotePreferences(RemoteHookConfigContract.GROUP)
        val result = committer.publish(
            connectionId = capturedConnection,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            decision = decision,
            values = values,
            nowEpochMs = System.currentTimeMillis(),
            force = force,
            stillCurrent = { stillCurrent() && PublicationAuthorityStore.matches(appContext, publication) },
            backend = object : RemoteHookConfigBackend {
                override fun readCached(): Map<String, *> = preferences.all

                override fun commit(document: Map<String, Any>, removedKeys: Set<String>): Boolean {
                    // Irena 101 不处理 clear 标志。显式删除 + 全量 put 在同次提交中更新 group。
                    // SDK 不会省略相同值的 put；完整白名单保证每次重试都真实发送。
                    val editor = preferences.edit()
                    removedKeys.forEach(editor::remove)
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
        if (result is RemoteHookConfigPublishResult.Success) result.copy(
            proof = RemotePublicationProof(publication.identity, capturedConnection, capturedEpoch, stillCurrent))
        else result
    }.getOrElse { throwable ->
        committer.invalidate()
        RemoteHookConfigPublishResult.Failure(
            throwable.message ?: throwable.javaClass.simpleName, throwable
        )
    }

    /** 调用方先持有短期授权锁；这里锁住连接交接，结束后才能分发监听器。 */
    fun <T> withCurrentPublication(proof: RemotePublicationProof?, action: () -> T): T? = synchronized(stateLock) {
        if (proof == null || !frameworkStatus.capable || service == null ||
            !proof.matches(applicationContext?.let { PublicationAuthorityStore.current(it)?.identity }, connectionId, intentEpoch.get())) null
        else action()
    }

    fun status(): ModernFrameworkStatus = frameworkStatus

    fun diagnostics(): RemoteHookConfigDiagnostics = publishDiagnostics.copy(
            publishPending = publishDiagnostics.publishPending ||
                publishScheduled.get() || publishDirty.get()
        )

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
        if (result !is RemoteHookConfigPublishResult.Failure || result.reason == "publication_pending") return
        Log.w(TAG, "publish remote hook config failed: ${result.reason}", result.throwable)
    }

    private fun registerServiceListener() {
        if (!listenerRegistered.compareAndSet(false, true)) return
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(boundService: XposedService) {
                val arrival = synchronized(stateLock) {
                    candidates.arrive(boundService)
                }
                runCatching { metadataExecutor.execute {
                    val metadata = readModernFrameworkStatus(
                        readApiVersion = { boundService.apiVersion },
                        readProperties = { boundService.frameworkProperties },
                        readName = { boundService.frameworkName },
                        readVersion = { boundService.frameworkVersion },
                        readVersionCode = { boundService.frameworkVersionCode })
                    if (!candidates.matches(boundService, arrival)) return@execute
                    val newStatus = synchronized(stateLock) {
                        if (!candidates.matches(boundService, arrival)) null
                        else if (!metadata.capable && frameworkStatus.capable && service !== boundService) null
                        else {
                            if (service !== boundService) connectionId += 1L
                            service = boundService
                            metadata.copy(connectionId = connectionId).also { frameworkStatus = it }
                        }
                    }
                    if (newStatus != null) notifyStatusListeners(newStatus)
                    applicationContext?.let(::requestPublish)
                } }
            }

            override fun onServiceDied(deadService: XposedService) {
                val disconnected = synchronized(stateLock) {
                    candidates.remove(deadService)
                    if (service !== deadService) {
                        null
                    } else {
                        service = null
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
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CompatibilityManagerProbe.request(context)
        publishDirty.set(true)
        if (!publishScheduled.compareAndSet(false, true)) return
        runCatching { publishExecutor.execute {
            try {
                var attemptedDecision: UserTermsDecision
                do {
                    publishDirty.set(false)
                    attemptedDecision = requestedDecision
                    val attempts = CommunicationCompatibilityPolicy.managerAttempts(CommunicationCompatibilityStore.isEnabled(context))
                    for (index in 0 until attempts) {
                        if (index > 0) {
                            if (attemptedDecision != requestedDecision || publishDirty.get()) break
                            Thread.sleep(CommunicationCompatibilityPolicy.RETRY_DELAY_MS)
                            if (attemptedDecision != requestedDecision) break
                        }
                        val attempt = publishRequestedSnapshotAndNotify(context)
                        attemptedDecision = attempt.first
                        logFailure(attempt.second)
                        val failure = attempt.second as? RemoteHookConfigPublishResult.Failure
                        if (failure == null || failure.reason !in setOf("Xposed service is not connected",
                            "Xposed framework metadata is unavailable") && failure.throwable !is android.os.DeadObjectException) break
                    }
                } while (shouldRepeatRemotePublish(
                    dirty = publishDirty.get(),
                    attemptedDecision = attemptedDecision,
                    requestedDecision = requestedDecision
                ))
            } finally {
                publishScheduled.set(false)
                if (publishDirty.get()) requestPublish(context)
            }
        } }.onFailure {
            publishScheduled.set(false)
            publishDiagnostics = publishDiagnostics.copy(state = RemoteHookConfigPublishState.FAILED,
                failureCode = "publish_queue_busy", publishPending = false)
            // 脏标志保留给下一次外部事件；拒绝时不自旋创建更多任务。
        }
    }


    private fun RemoteHookConfigPublishResult.Failure.toFailureCode(): String = when (reason) {
        "stale_publication" -> "publication_outcome_unknown"
        "Xposed service is not connected" -> "service_not_connected"
        "Xposed framework metadata is unavailable" -> "framework_metadata_unavailable"
        "Xposed framework does not provide supported Modern remote preferences" ->
            "remote_preferences_unsupported"
        else -> "publish_failed"
    }
}
