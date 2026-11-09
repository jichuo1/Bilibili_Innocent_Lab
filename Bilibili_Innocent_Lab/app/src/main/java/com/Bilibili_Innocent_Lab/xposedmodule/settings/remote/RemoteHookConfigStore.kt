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
    BYPASSED,
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
    private val displayMetadataExecutor = HostReceiptWire.executor("bil-framework-description")
    private val metadataRecovery = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "bil-framework-recovery").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
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
                    // Android R+ reports clear() with a null key; republish defaults and invalidate old intent.
                    if (key != null && key !in observedKeys) return@OnSharedPreferenceChangeListener
                    intentEpoch.incrementAndGet()
                    requestPublish(appContext)
                    // NPatch 选中时上面的标准发布会被 publishSnapshot 短路，设置变更就没人发布了；
                    // 交给 NPatch 通道去抖同步。未选免 Root 时是一次内存布尔读取即返回。
                    com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
                        .requestSyncAfterSettingsChange(appContext, source)
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

    /** 握手发现旧快照时请求模块权威源重发；不使用宿主传入的决定或配置。 */
    fun requestAuthorityRefresh(context: Context) {
        val app = context.applicationContext ?: context
        if (app.packageName != com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig.APPLICATION_ID ||
            app.applicationInfo.uid != android.os.Process.myUid()) return
        val requested = UserTermsConsentStore.withAuthorityLock {
            val decision = UserTermsConsentStore.readStateOrInitialize(app).requestedRemoteDecision
            if (!decision.isAuthorized) false else {
                requestDecisionPublish(app, decision)
                true
            }
        }
        if (!requested) return
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
            .requestSyncAfterSettingsChange(app, app.modulePreferences())
    }

    /** 条款决定、设置变更与连接恢复均交给同一发布队列合并。 */
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
            StandardPublisherPolicy.isBypassed(com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore.isDesiredEnabled(appContext)) ->
                RemoteHookConfigPublishResult.Failure(StandardPublisherPolicy.NPATCH_SELECTED)
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
                state = when {
                    result.isStandardPublisherBypass -> RemoteHookConfigPublishState.BYPASSED
                    activeService == null -> RemoteHookConfigPublishState.WAITING_FOR_SERVICE
                    else -> RemoteHookConfigPublishState.FAILED
                },
                failureCode = if (result.isStandardPublisherBypass) null else result.toFailureCode(),
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
        // Authority captures and normalizes the complete source under its authorization lock.
        // Reuse that snapshot; stillCurrent below rechecks authority before and after IPC.
        val publication = PublicationAuthorityStore.current(appContext)?.takeIf { it.decision == decision }
            ?: return@runCatching RemoteHookConfigPublishResult.Failure("stale_publication")
        val values = publication.values
        val nPatchChannel = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannels
            .read(appContext, frameworkStatus) == com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel.NPATCH
        val preferences = activeService.getRemotePreferences(RemoteHookConfigContract.GROUP)
        val result = committer.publish(
            connectionId = capturedConnection,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            decision = decision,
            values = values,
            nowEpochMs = System.currentTimeMillis(),
            force = force,
            // B1：NPatch 通道下免 Root 开关是总开关。关闭后发布“不可授权”文档，既不给宿主
            // 留下复活口子，也顺手清掉旧版本写下的可授权标准文档。
            deliveryEnabled = !nPatchChannel,
            // B3：标准发布器清理 NPatch 文档时也保留关闭代次，宿主才能回执这一次停用。
            noRootRevision = if (nPatchChannel)
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
                    .readSnapshot(appContext)?.takeUnless { it.enabled }?.revision ?: 0L else 0L,
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

    /** 在已绑定的框架服务上执行一次性操作（如写入远程文件）；未绑定或失败时返回 null。 */
    fun <T> withService(action: (XposedService) -> T): T? = service?.let { runCatching { action(it) }.getOrNull() }

    fun <T> withServiceResult(action: (XposedService) -> T): FrameworkServiceCall<T> {
        val captured = synchronized(stateLock) { service to frameworkStatus }
        return callFrameworkService(captured.first, captured.second,
            isCurrent = { service === captured.first && connectionId == captured.second.connectionId },
            onDeath = { captured.first?.let(::serviceDied) }, action = action)
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
        if (result.isStandardPublisherBypass) {
            Log.d(TAG, "standard remote hook config publisher bypassed: ${result.reason}")
            return
        }
        Log.w(TAG, "publish remote hook config failed: ${result.reason}", result.throwable)
    }

    private fun registerServiceListener() {
        if (!listenerRegistered.compareAndSet(false, true)) return
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(boundService: XposedService) {
                val arrival = synchronized(stateLock) {
                    candidates.arrive(boundService)
                }
                readServiceCapabilities(boundService, arrival, 0)
            }

            override fun onServiceDied(deadService: XposedService) {
                serviceDied(deadService)
            }
        })
    }

    private fun serviceDied(deadService: XposedService) {
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

    private fun readServiceCapabilities(bound: XposedService, arrival: Long, attempt: Int) {
        if (!candidates.matches(bound, arrival)) return
        fun retry() {
            if (attempt < 2) metadataRecovery.schedule({ readServiceCapabilities(bound, arrival, attempt + 1) },
                750L, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val timeout = metadataRecovery.schedule({
            val failed = synchronized(stateLock) {
                if (!candidates.matches(bound, arrival) || frameworkStatus.capable) null
                else frameworkStatus.copy(failureCode = "framework_metadata_unavailable").also { frameworkStatus = it }
            }
            if (failed != null) notifyStatusListeners(failed)
        }, 2_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        runCatching { metadataExecutor.execute {
            try {
                if (!candidates.matches(bound, arrival)) return@execute
                val metadata = readModernFrameworkCapabilities({ bound.apiVersion }, { bound.frameworkProperties })
                    .withModernFrameworkIdentity { bound.frameworkName }
                val updated = synchronized(stateLock) {
                    if (!candidates.matches(bound, arrival) || !metadata.capable && frameworkStatus.capable && service !== bound) null
                    else {
                        if (service !== bound) connectionId += 1L
                        service = bound
                        metadata.copy(connectionId = connectionId).also { frameworkStatus = it }
                    }
                }
                if (updated != null) {
                    notifyStatusListeners(updated)
                    applicationContext?.let(::requestPublish)
                    if (metadata.failureCode == null) readServiceDescription(bound, arrival, updated)
                }
                if (metadata.failureCode != null) retry()
            } finally { timeout.cancel(false) }
        } }.onFailure {
            timeout.cancel(false)
            Log.w(TAG, "framework metadata queue unavailable")
            retry()
        }
    }

    private fun readServiceDescription(bound: XposedService, arrival: Long, status: ModernFrameworkStatus) {
        runCatching { displayMetadataExecutor.execute {
            if (!candidates.matches(bound, arrival)) return@execute
            val metadata = status.withModernFrameworkVersions({ bound.frameworkVersion }, { bound.frameworkVersionCode })
            val updated = synchronized(stateLock) {
                if (!candidates.matches(bound, arrival) || service !== bound || connectionId != status.connectionId ||
                    frameworkStatus.capable != status.capable || frameworkStatus.apiVersion != status.apiVersion ||
                    frameworkStatus.properties != status.properties) null
                else metadata.also { frameworkStatus = it }
            }
            if (updated != null) notifyStatusListeners(updated)
        } }.onFailure { Log.w(TAG, "framework description queue unavailable") }
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
                            "Xposed framework metadata is unavailable") && !isFrameworkServiceDeath(failure.throwable)) break
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
