package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsPendingCompletion
import java.util.UUID

/** 两种模式共享元数据与最终许可；只有额外的完整文档操作受兼容模式控制。 */
internal object HostAdmissionEndpoint {
    private val leases = AdmissionLeaseBook(SystemClock::elapsedRealtime)

    fun handle(context: Context, method: String, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        // 身份先于任何应用提供的 Bundle 解码；不信任载荷里的包名/UID。
        val trusted = runCatching {
            context.packageManager.getApplicationInfo(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE, 0).uid == uid
        }.getOrDefault(false)
        if (!trusted || pid <= 0) return status("identity_rejected")
        return runCatching {
            if (extras == null || extras.getInt("version") != HostAdmissionContract.VERSION) return@runCatching status("protocol_rejected")
            val nonce = extras.getString("nonce").orEmpty()
            if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce)) return@runCatching status("protocol_rejected")
            UserTermsConsentStore.withAuthorityLock {
                val current = PublicationAuthorityStore.current(context) ?: return@withAuthorityLock status("storage_failed", nonce)
                if (!current.decision.isAuthorized) return@withAuthorityLock status("denied", nonce)
                when (method) {
                    HostAdmissionContract.METHOD_PREPARE -> {
                        val now = SystemClock.elapsedRealtime()
                        val deadline = minOf(extras.getLong("deadline"), now + HostAdmissionContract.CHALLENGE_TIMEOUT_MS)
                        if (deadline <= now) return@withAuthorityLock status("stale", nonce)
                        val noRootSelected = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore.isDesiredEnabled(context)
                        val source = HostAdmissionSourcePolicy.select(noRootSelected, extras.getLong("normalNoRootRevision"),
                            current.identity.fingerprint, extras.getString("normalFingerprint"),
                            extras.getBoolean("allowDirect", false), current.directAllowed)
                            ?: return@withAuthorityLock status("manager_sync_required", nonce)
                        val document = if (source == HostAdmissionContract.DIRECT) HostAdmissionContract.encode(current.document()) else null
                        val challenge = UUID.randomUUID().toString()
                        val lease = AdmissionLeaseBook.Lease(uid, pid, nonce, challenge, source, current.identity,
                            deadline)
                        if (!leases.prepare(lease)) return@withAuthorityLock status("busy", nonce)
                        status("prepared", nonce).apply {
                            putString("challenge", challenge)
                            putString("source", source)
                            document?.let { putString("document", it) }
                            HostAdmissionContract.putIdentity(this, current.identity)
                        }
                    }
                    HostAdmissionContract.METHOD_CONFIRM -> {
                        val identity = HostAdmissionContract.identity(extras) ?: return@withAuthorityLock status("protocol_rejected", nonce)
                        val lease = leases.consume(uid, pid, nonce, extras.getString("challenge").orEmpty(), identity)
                            ?: return@withAuthorityLock status("stale", nonce)
                        if (lease.identity != current.identity ||
                            lease.source == HostAdmissionContract.DIRECT && !current.directAllowed) return@withAuthorityLock status("stale", nonce)
                        if (current.pending) {
                            val outcome = UserTermsConsentStore.completePendingAcceptance(context, current.identity.consentRevision)
                            if (outcome != UserTermsPendingCompletion.COMPLETED) {
                                if (outcome == UserTermsPendingCompletion.WRITE_FAILED) PublicationAuthorityStore.stopGrants()
                                return@withAuthorityLock status("consent_commit_failed", nonce)
                            }
                            UserTermsAuthorizationCoordinator.refreshFromExternalPublisher(context)
                        }
                        status("granted", nonce).apply {
                            putString("source", lease.source)
                            HostAdmissionContract.putIdentity(this, current.identity)
                        }
                    }
                    else -> status("protocol_rejected", nonce)
                }
            }
        }.getOrElse { status("protocol_rejected") }
    }

    private fun status(status: String, nonce: String = "") = Bundle().apply {
        putInt("version", HostAdmissionContract.VERSION)
        putLong("moduleVersion", BuildConfig.VERSION_CODE.toLong())
        putString("status", status)
        putString("nonce", nonce)
    }
}
