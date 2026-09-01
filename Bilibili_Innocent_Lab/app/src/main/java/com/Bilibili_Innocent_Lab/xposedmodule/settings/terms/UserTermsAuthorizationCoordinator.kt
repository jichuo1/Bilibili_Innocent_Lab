package com.Bilibili_Innocent_Lab.xposedmodule.settings.terms

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishEvent
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatusListener
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

internal enum class UserTermsSyncState {
    IDLE,
    PENDING,
    WAITING_FOR_SERVICE,
    SYNCING,
    UNSUPPORTED,
    FAILED
}

internal fun resolveUserTermsSyncState(
    hasPendingAcceptance: Boolean,
    hasLocalFailure: Boolean,
    publishPending: Boolean,
    publishState: RemoteHookConfigPublishState,
    frameworkConnected: Boolean,
    frameworkCapable: Boolean
): UserTermsSyncState = when {
    !hasPendingAcceptance -> UserTermsSyncState.IDLE
    hasLocalFailure -> UserTermsSyncState.FAILED
    publishPending || publishState == RemoteHookConfigPublishState.PUBLISHING ->
        UserTermsSyncState.SYNCING
    !frameworkConnected -> UserTermsSyncState.WAITING_FOR_SERVICE
    !frameworkCapable -> UserTermsSyncState.UNSUPPORTED
    publishState == RemoteHookConfigPublishState.FAILED -> UserTermsSyncState.FAILED
    else -> UserTermsSyncState.PENDING
}

internal fun didUserTermsAuthorizationComplete(
    previous: UserTermsDecision,
    current: UserTermsDecision
): Boolean = !previous.isAuthorized && current.isAuthorized

internal data class UserTermsAuthorizationSnapshot(
    val consentState: UserTermsConsentState,
    val syncState: UserTermsSyncState,
    val failureCode: String?
)

internal data class UserTermsActionResult(
    val succeeded: Boolean,
    val state: UserTermsConsentState,
    val failureCode: String? = null
)

internal fun interface UserTermsAuthorizationListener {
    fun onUserTermsAuthorizationChanged(snapshot: UserTermsAuthorizationSnapshot)
}

/**
 * 把“用户已经同意”和“API 102 授权快照已经发布并读回”编排为可恢复的两阶段流程。
 * Remote 协议、宿主授权条件和 NPatch/Provider 的本地决定读取均保持不变。
 */
internal object UserTermsAuthorizationCoordinator {
    internal const val FAILURE_LOCAL_WRITE = "local_terms_write_failed"

    private val listenerRegistered = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<UserTermsAuthorizationListener>()
    @Volatile private var applicationContext: Context? = null
    @Volatile private var localFailureCode: String? = null

    private val remotePublishListener = RemoteHookConfigPublishListener { event ->
        handleRemotePublish(event)
    }
    private val frameworkStatusListener = ModernFrameworkStatusListener { status ->
        val appContext = applicationContext ?: return@ModernFrameworkStatusListener
        if (status.connected && status.capable && localFailureCode == null) {
            val state = UserTermsConsentStore.readStateOrInitialize(appContext)
            if (state.pendingAcceptance != null) {
                RemoteHookConfigStore.requestDecisionPublish(
                    appContext,
                    UserTermsDecision.ACCEPTED
                )
            }
        }
        notifyListeners(buildSnapshot(appContext))
    }

    fun initialize(context: Context): UserTermsConsentState {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        if (listenerRegistered.compareAndSet(false, true)) {
            RemoteHookConfigStore.addPublishListener(remotePublishListener)
            RemoteHookConfigStore.addStatusListener(frameworkStatusListener)
        }
        val initial = UserTermsConsentStore.readStateOrInitialize(appContext)
        RemoteHookConfigStore.initialize(
            appContext,
            initial.requestedRemoteDecision
        ).also(RemoteHookConfigStore::logFailure)
        return UserTermsConsentStore.readStateOrInitialize(appContext)
    }

    fun snapshot(context: Context): UserTermsAuthorizationSnapshot {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        return buildSnapshot(appContext)
    }

    fun beginAcceptance(context: Context): UserTermsActionResult {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        localFailureCode = null
        val state = UserTermsConsentStore.beginPendingAcceptance(appContext)
            ?: return localFailure(appContext)
        notifyListeners(buildSnapshot(appContext))
        if (state.pendingAcceptance != null) {
            RemoteHookConfigStore.requestDecisionPublish(
                appContext,
                UserTermsDecision.ACCEPTED
            )
            notifyListeners(buildSnapshot(appContext))
        }
        return UserTermsActionResult(succeeded = true, state = state)
    }

    fun retryPendingAcceptance(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        val state = UserTermsConsentStore.readStateOrInitialize(appContext)
        if (state.pendingAcceptance == null) return false
        localFailureCode = null
        RemoteHookConfigStore.requestDecisionPublish(
            appContext,
            UserTermsDecision.ACCEPTED
        )
        notifyListeners(buildSnapshot(appContext))
        return true
    }

    /**
     * 拒绝继续沿用原来的同步安全顺序：先使在途接受结果失效，再确认远端关闭态，
     * 最后写入本地决定。该低频操作不另建第二条发布队列。
     */
    fun decline(context: Context): UserTermsActionResult {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        localFailureCode = null
        if (!UserTermsConsentStore.cancelPendingAcceptance(appContext)) {
            return localFailure(appContext)
        }
        val publishResult = RemoteHookConfigStore.publish(
            appContext,
            UserTermsDecision.DECLINED
        ).also(RemoteHookConfigStore::logFailure)
        if (!publishResult.succeeded) {
            val state = UserTermsConsentStore.readStateOrInitialize(appContext)
            notifyListeners(buildSnapshot(appContext))
            return UserTermsActionResult(
                succeeded = false,
                state = state,
                failureCode = RemoteHookConfigStore.diagnostics().failureCode
            )
        }
        if (!UserTermsConsentStore.writeDecision(appContext, UserTermsDecision.DECLINED)) {
            return localFailure(appContext)
        }
        val state = UserTermsConsentStore.readStateOrInitialize(appContext)
        notifyListeners(buildSnapshot(appContext))
        return UserTermsActionResult(succeeded = true, state = state)
    }

    fun addListener(listener: UserTermsAuthorizationListener) {
        listeners.add(listener)
        applicationContext?.let { context ->
            notifyListener(listener, buildSnapshot(context))
        }
    }

    fun removeListener(listener: UserTermsAuthorizationListener) {
        listeners.remove(listener)
    }

    private fun handleRemotePublish(event: RemoteHookConfigPublishEvent) {
        val appContext = applicationContext ?: return
        val current = UserTermsConsentStore.readStateOrInitialize(appContext)
        val pending = current.pendingAcceptance
        if (event.decision != UserTermsDecision.ACCEPTED || pending == null) {
            notifyListeners(buildSnapshot(appContext))
            return
        }
        if (event.result !is RemoteHookConfigPublishResult.Success) {
            notifyListeners(buildSnapshot(appContext))
            return
        }

        when (UserTermsConsentStore.completePendingAcceptance(appContext, pending.revision)) {
            UserTermsPendingCompletion.COMPLETED -> {
                localFailureCode = null
            }
            UserTermsPendingCompletion.STALE -> {
                // 用户已产生更新决定；确保单线程发布器最终收敛到当前目标。
                val latest = UserTermsConsentStore.readStateOrInitialize(appContext)
                RemoteHookConfigStore.requestDecisionPublish(
                    appContext,
                    latest.requestedRemoteDecision
                )
            }
            UserTermsPendingCompletion.WRITE_FAILED -> {
                // 远端已短暂前进但私有权威状态未提交，尽力恢复原关闭态；pending 保留供手动重试。
                localFailureCode = FAILURE_LOCAL_WRITE
                RemoteHookConfigStore.requestDecisionPublish(
                    appContext,
                    pending.previousDecision
                )
            }
        }
        notifyListeners(buildSnapshot(appContext))
    }

    private fun localFailure(context: Context): UserTermsActionResult {
        localFailureCode = FAILURE_LOCAL_WRITE
        val state = UserTermsConsentStore.readStateOrInitialize(context)
        val snapshot = buildSnapshot(context)
        notifyListeners(snapshot)
        return UserTermsActionResult(
            succeeded = false,
            state = state,
            failureCode = FAILURE_LOCAL_WRITE
        )
    }

    private fun buildSnapshot(context: Context): UserTermsAuthorizationSnapshot {
        val consent = UserTermsConsentStore.readStateOrInitialize(context)
        val diagnostics = RemoteHookConfigStore.diagnostics()
        val framework = RemoteHookConfigStore.status()
        val syncState = resolveUserTermsSyncState(
            hasPendingAcceptance = consent.pendingAcceptance != null,
            hasLocalFailure = localFailureCode != null,
            publishPending = diagnostics.publishPending,
            publishState = diagnostics.state,
            frameworkConnected = framework.connected,
            frameworkCapable = framework.capable
        )
        return UserTermsAuthorizationSnapshot(
            consentState = consent,
            syncState = syncState,
            failureCode = localFailureCode ?: diagnostics.failureCode
        )
    }

    private fun notifyListeners(snapshot: UserTermsAuthorizationSnapshot) {
        listeners.forEach { listener -> notifyListener(listener, snapshot) }
    }

    private fun notifyListener(
        listener: UserTermsAuthorizationListener,
        snapshot: UserTermsAuthorizationSnapshot
    ) {
        runCatching { listener.onUserTermsAuthorizationChanged(snapshot) }
    }
}
