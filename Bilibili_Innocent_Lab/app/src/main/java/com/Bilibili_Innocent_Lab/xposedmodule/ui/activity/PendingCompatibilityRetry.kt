package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemotePublicationAttempt
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import java.lang.ref.WeakReference

/** 只接收本次请求的完成回调；超时撤销逻辑所有权，不把全局状态轮询当作请求结果。 */
internal class PendingCompatibilityRetry(
    activity: MainActivity,
    private val token: Long?,
    private val revision: Long,
    private val noRoot: Boolean
) {
    private val owner = WeakReference(activity)
    private val main = Handler(Looper.getMainLooper())
    private var finished = false
    private var remoteAttempt: RemotePublicationAttempt? = null
    private val timeout = Runnable { receive(RemoteHookConfigPublishResult.Failure("publication_outcome_unknown")) }

    fun start() {
        val activity = owner.get() ?: return
        main.postDelayed(timeout, 15_000L)
        val self = WeakReference(this)
        if (noRoot) {
            activity.synchronizePendingTermsThroughNoRoot(enableFirst = false) { current ->
                self.get()?.let { attempt ->
                    if (!current) attempt.cancel()
                    else attempt.receive(RemoteHookConfigPublishResult.Failure("no_root_attempt_completed"))
                }
            }
        } else {
            val origin = if (token == null) RemotePublicationAttempt.Origin.MODE_ENABLE else RemotePublicationAttempt.Origin.MANUAL
            remoteAttempt = UserTermsAuthorizationCoordinator.retryPendingAcceptanceAttempt(activity.applicationContext, origin) {
                self.get()?.receive(it)
            }
            if (remoteAttempt == null) cancel()
        }
    }

    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { cancel() }; return }
        if (finished) return
        finished = true
        remoteAttempt?.cancel()
        main.removeCallbacks(timeout)
        val activity = owner.get() ?: return
        if (activity.compatibilityPendingRetry === this) {
            activity.compatibilityPendingRetry = null
            if (token != null) activity.compatibilityRetryTracker.cancel()
            activity.compatibilityRetryButton?.isEnabled = true
        }
    }

    private fun receive(result: RemoteHookConfigPublishResult) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { receive(result) }; return }
        val activity = owner.get()
        if (finished || activity == null || activity.isFinishing || activity.isDestroyed) { cancel(); return }
        if (result is RemoteHookConfigPublishResult.Failure && result.reason == "stale_publication") {
            cancel()
            return
        }
        val snapshot = UserTermsAuthorizationCoordinator.snapshot(activity)
        val consent = snapshot.consentState
        val outcome = when {
            consent.decision.isAuthorized -> CompatibilityRetryTracker.Outcome.SUCCESS
            consent.pendingAcceptance?.revision != revision -> CompatibilityRetryTracker.Outcome.OTHER_FAILURE
            snapshot.failureCode == UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE -> CompatibilityRetryTracker.Outcome.OTHER_FAILURE
            else -> CompatibilityRetryTracker.Outcome.CONNECTION_FAILED
        }
        finished = true
        remoteAttempt?.cancel()
        main.removeCallbacks(timeout)
        if (activity.compatibilityPendingRetry !== this) return
        activity.compatibilityPendingRetry = null
        activity.compatibilityRetryButton?.isEnabled = true
        if (token != null) activity.compatibilityRetryTracker.finish(token, outcome)
        else if (outcome == CompatibilityRetryTracker.Outcome.SUCCESS) activity.compatibilityRetryTracker.reset()
        activity.updateCommunicationCompatibilityHint()
    }
}

internal fun MainActivity.retryPendingTermsWithCompatibility(manual: Boolean = true) {
    if (compatibilityPendingRetry != null) return
    val state = UserTermsAuthorizationCoordinator.snapshot(applicationContext).consentState
    val revision = state.pendingAcceptance?.revision ?: return
    val token = if (manual) compatibilityRetryTracker.begin(revision) ?: return else null
    compatibilityRetryButton?.isEnabled = false
    val noRoot = NoRootSupportStore.isDesiredEnabled(applicationContext)
    val attempt = PendingCompatibilityRetry(this, token, revision, noRoot)
    compatibilityPendingRetry = attempt
    attempt.start()
    termsAuthorizationSnapshot = UserTermsAuthorizationCoordinator.snapshot(applicationContext)
    termsAuthorizationSnapshot?.let(::renderPendingTermsUi)
}
