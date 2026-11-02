package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot

internal data class HostAdmissionDiagnostic(
    val route: String,
    val phase: String,
    val status: String?,
    val envelopeValid: Boolean? = null,
    val fingerprintMatches: Boolean? = null
) {
    fun summary(): String {
        val safeRoute = route.takeIf { it in setOf("provider", "binder", "local", "broadcast") } ?: "unknown"
        val safePhase = phase.takeIf { it in setOf("transport", "prepare", "config", "confirm") } ?: "unknown"
        val safeStatus = status?.takeIf { it in setOf("prepared", "granted", "denied", "manager_sync_required",
            "consent_commit_failed", "identity_rejected", "protocol_rejected", "storage_failed", "stale", "busy",
            "security", "timeout", "exception", "identity_missing", "challenge_invalid", "source_invalid",
            "direct_not_allowed", "document_invalid", "snapshot_invalid", "snapshot_unavailable",
            "config_not_authorized", "fingerprint_mismatch", "binding_mismatch", "embedded_mode_unsupported") } ?: "unknown"
        return "route=$safeRoute, phase=$safePhase, status=$safeStatus, binding=${envelopeValid ?: "unknown"}, fingerprint=${fingerprintMatches ?: "unknown"}"
    }
}

/** 运输只负责收发；握手统一验证响应绑定、完整文档、内容身份和最终许可。 */
internal object HostAdmissionHandshake {
    data class Reply(
        val version: Int,
        val moduleVersion: Long,
        val nonce: String?,
        val status: String?,
        val identity: PublicationIdentity? = null,
        val source: String? = null,
        val challenge: String? = null,
        val document: String? = null,
        val disabledRevision: Long = 0L
    )

    fun exchange(
        route: String,
        nonce: String,
        normal: RemoteHookConfigSnapshot?,
        directAllowed: Boolean,
        deadline: Long,
        now: () -> Long,
        prepare: () -> Reply?,
        confirm: (String, PublicationIdentity) -> Reply?,
        nPatchRuntime: Boolean = false
    ): HostAdmissionClient.Result {
        fun valid(reply: Reply): Boolean = reply.version == HostAdmissionContract.VERSION &&
            reply.moduleVersion == BuildConfig.VERSION_CODE.toLong() && reply.nonce == nonce
        fun result(reason: String, phase: String, status: String?, bound: Boolean? = null,
            matches: Boolean? = null, grant: HostAdmissionClient.Grant? = null) = HostAdmissionClient.Result(
            grant, reason, listOf(HostAdmissionDiagnostic(route, phase, status, bound, matches)))
        fun outcome(value: HostAdmissionRouteClassifier.Outcome, phase: String, reply: Reply): HostAdmissionClient.Result =
            result(if (!valid(reply)) HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID
                else checkNotNull(HostAdmissionRouteClassifier.reason(value)), phase, reply.status, valid(reply)).copy(
                disabledRevision = HostAdmissionRouteClassifier.disabledRevision(reply.status, valid(reply), reply.disabledRevision)
            )
        fun timeout(phase: String) = result(HostAdmissionRouteClassifier.REASON_TIMEOUT, phase, "timeout")

        if (now() >= deadline) return timeout("prepare")
        val prepared = prepare() ?: return result(HostAdmissionRouteClassifier.REASON_UNAVAILABLE, "prepare", null)
        // 已绑定的否定结果即使处于超时边界也阻止回退；迟到的正向许可仍不安装。
        val prepareOutcome = HostAdmissionRouteClassifier.prepare(prepared.status, valid(prepared))
        if (prepareOutcome != HostAdmissionRouteClassifier.Outcome.CONTINUE) return outcome(prepareOutcome, "prepare", prepared)
        if (now() >= deadline) return timeout("prepare")
        val identity = prepared.identity ?: return result(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, "config", "identity_missing", true)
        val challenge = prepared.challenge?.takeIf(HostRuntimeDiagnosticsQueryContract::isValidNonce)
            ?: return result(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, "config", "challenge_invalid", true)
        val snapshot = when (prepared.source) {
            HostAdmissionContract.NORMAL -> normal
                ?: return result(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, "config", "snapshot_unavailable", true)
            HostAdmissionContract.DIRECT -> {
                if (!directAllowed) return result(HostAdmissionRouteClassifier.REASON_CONFIG_INVALID, "config", "direct_not_allowed", true)
                val document = HostAdmissionContract.decode(prepared.document.orEmpty())
                    ?: return result(HostAdmissionRouteClassifier.REASON_CONFIG_INVALID, "config", "document_invalid", true)
                (RemoteHookConfigContract.decode(document) as? RemoteHookConfigDecodeResult.Ready)?.snapshot
                    ?: return result(HostAdmissionRouteClassifier.REASON_CONFIG_INVALID, "config", "snapshot_invalid", true)
            }
            else -> return result(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, "config", "source_invalid", true)
        }
        if (!snapshot.authorized || snapshot.moduleVersionCode != BuildConfig.VERSION_CODE.toLong()) {
            return result(HostAdmissionRouteClassifier.REASON_CONFIG_INVALID, "config", "config_not_authorized", true)
        }
        // NPatch 的标准框架镜像可能过时，必须携带当前免 Root 发布修订。
        if (nPatchRuntime && snapshot.noRootRevision <= 0L) {
            return result(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, "config", "snapshot_invalid", true)
        }
        if (RemoteHookConfigContract.contentFingerprint(snapshot) != identity.fingerprint) {
            return result(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, "config", "fingerprint_mismatch", true, false)
        }
        if (now() >= deadline) return timeout("confirm")
        val granted = confirm(challenge, identity)
            ?: return result(HostAdmissionRouteClassifier.REASON_UNAVAILABLE, "confirm", null)
        if (granted.status == "granted" && valid(granted) &&
            (granted.source != prepared.source || granted.identity != identity)) {
            return result(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, "confirm", "binding_mismatch", false)
        }
        val confirmOutcome = HostAdmissionRouteClassifier.confirm(granted.status, valid(granted),
            granted.source == prepared.source && granted.identity == identity)
        if (confirmOutcome != HostAdmissionRouteClassifier.Outcome.CONTINUE) return outcome(confirmOutcome, "confirm", granted)
        if (now() >= deadline) return timeout("confirm")
        return result("", "confirm", granted.status, true, true,
            HostAdmissionClient.Grant(snapshot, checkNotNull(prepared.source), identity))
    }
}
