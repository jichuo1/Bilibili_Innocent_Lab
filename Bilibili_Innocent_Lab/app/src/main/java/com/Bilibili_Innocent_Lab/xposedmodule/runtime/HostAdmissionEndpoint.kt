package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannels
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsPendingCompletion
import java.util.UUID

/** 标准框架的完整直达文档受兼容模式控制；已确认的补丁框架独立使用当前文档及新鲜握手。 */
internal object HostAdmissionEndpoint {
    private val leases = AdmissionLeaseBook(SystemClock::elapsedRealtime)

    fun handle(context: Context, method: String, extras: Bundle?): Bundle =
        handleWithIdentity(context, method, extras, Binder.getCallingUid(), Binder.getCallingPid())

    /**
     * 包可见性隔离时由显式有序广播承载同一握手。PendingIntent 创建者 UID 已在接收器内
     * 通过模块侧 PackageManager 验真；广播没有可用的调用 pid，nonce + challenge 已提供
     * 每次握手的绑定，因此只使用一个正数占位 pid 参与有界 lease 记录。
     */
    fun handleBroadcast(context: Context, method: String, extras: Bundle?, callerUid: Int): Bundle =
        handleWithIdentity(context, method, extras, callerUid, BROADCAST_CALLER_PID)

    /**
     * 宿主进程内通过 createPackageContext 拿到的模块 Context。调用方必须先用宿主
     * 自己的 PackageManager 把 UID 验成目标宿主；这里不再向模块 PM 查询宿主包，
     * 否则包可见性会把整条 in-process 握手打成 identity_rejected，冷启动空等 2s。
     */
    fun handleLocal(context: Context, method: String, extras: Bundle?, callerUid: Int): Bundle =
        handleWithIdentity(context, method, extras, callerUid, LOCAL_CALLER_PID, callerAlreadyBound = true)

    private fun handleWithIdentity(
        context: Context,
        method: String,
        extras: Bundle?,
        uid: Int,
        pid: Int,
        callerAlreadyBound: Boolean = false,
    ): Bundle {
        // 身份先于任何应用提供的 Bundle 解码；不信任载荷里的包名/UID。
        // 模块 Context 的 PackageManager 可能因包可见性看不到宿主，此时 status
        // 为 identity_rejected：调用方必须把它当成这一路不可用，而不是条款拒绝。
        val trusted = callerAlreadyBound || runCatching {
            context.packageManager.getApplicationInfo(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE, 0).uid == uid
        }.getOrDefault(false)
        if (!trusted || pid <= 0 || uid <= 0) return status("identity_rejected")
        val result = runCatching {
            if (extras == null || extras.getInt("version") != HostAdmissionContract.VERSION) return@runCatching status("protocol_rejected")
            val nonce = extras.getString("nonce").orEmpty()
            if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce)) return@runCatching status("protocol_rejected")
            if (com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.EmbeddedModePolicy.isSettingsBlocked(context)) {
                return@runCatching status("embedded_mode_unsupported", nonce)
            }
            UserTermsConsentStore.withAuthorityLock {
                val current = PublicationAuthorityStore.current(context) ?: return@withAuthorityLock status("storage_failed", nonce)
                if (!current.decision.isAuthorized) return@withAuthorityLock status("denied", nonce)
                // 身份已在上方按内核 UID 验证；从模块侧读取已安装宿主补丁标记，不信任请求自报的模式。
                val channel = HostConfigChannels.read(context)
                val nPatchChannel = channel == HostConfigChannel.NPATCH
                val lspatchChannel = channel == HostConfigChannel.LSPATCH
                when (method) {
                    HostAdmissionContract.METHOD_PREPARE -> {
                        val now = SystemClock.elapsedRealtime()
                        val deadline = minOf(extras.getLong("deadline"), now + HostAdmissionContract.CHALLENGE_TIMEOUT_MS)
                        if (deadline <= now) return@withAuthorityLock status("stale", nonce)
                        val noRootSelected = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore.isDesiredEnabled(context)
                        // B1：免 Root 开关是 NPatch 通道的总开关，关闭后不发放任何来源。
                        val source = HostAdmissionSourcePolicy.select(
                            noRootSelected = noRootSelected,
                            normalNoRootRevision = extras.getLong("normalNoRootRevision"),
                            expectedFingerprint = current.identity.fingerprint,
                            normalFingerprint = extras.getString("normalFingerprint"),
                            allowDirect = extras.getBoolean("allowDirect", false),
                            directEnabled = current.directAllowed,
                            nPatchChannel = nPatchChannel,
                            lspatchChannel = lspatchChannel
                        ) ?: return@withAuthorityLock status("manager_sync_required", nonce).apply {
                            // Manager 离线时宿主可能仍读到旧开启文档；携带当前关闭 revision，
                            // 让宿主在放弃安装闭包后通过已有身份/nonce 探测回执确认停用；标准通道不附带此信息。
                            if (!noRootSelected && (nPatchChannel || extras.getLong("normalNoRootRevision") > 0L)) {
                                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
                                    .readSnapshot(context)?.takeIf {
                                        !it.enabled && it.moduleVersionCode == BuildConfig.VERSION_CODE.toLong()
                                    }?.let { putLong(HostAdmissionContract.DISABLED_REVISION, it.revision) }
                            }
                        }
                        val document = if (source == HostAdmissionContract.DIRECT) HostAdmissionContract.encode(
                            if (nPatchChannel) current.noRootDocument() else current.document()) else null
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
                            lease.source == HostAdmissionContract.DIRECT &&
                                !(current.directAllowed || lspatchChannel || nPatchChannel && current.noRootRevision > 0L)
                        ) {
                            // 握手期间权威记录已换成另一份仍授权的文档，或关掉了直达来源。
                            // 条款拒绝走上面的 denied，不会落到这里。
                            return@withAuthorityLock status("stale", nonce)
                        }
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
        if (result.getString("status") == "manager_sync_required") {
            runCatching {
                com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore.requestAuthorityRefresh(context)
            }
        }
        return result
    }

    private fun status(status: String, nonce: String = "") = Bundle().apply {
        putInt("version", HostAdmissionContract.VERSION)
        putLong("moduleVersion", BuildConfig.VERSION_CODE.toLong())
        putString("status", status)
        putString("nonce", nonce)
    }

    private const val BROADCAST_CALLER_PID = 1
    private const val LOCAL_CALLER_PID = 2
}
