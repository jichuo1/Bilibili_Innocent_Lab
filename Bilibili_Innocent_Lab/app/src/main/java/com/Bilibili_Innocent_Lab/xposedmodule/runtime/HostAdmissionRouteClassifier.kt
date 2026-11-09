package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** 条款拒绝、配置待同步与运输失败分别处理；只有通过响应绑定的结果才有权阻止旧配置回退。 */
internal object HostAdmissionRouteClassifier {
    enum class StatusClass { PREPARED, GRANTED, AUTHORITY_DENIED, SYNC_REQUIRED, CONSENT_PENDING, UNAVAILABLE }

    enum class Outcome { CONTINUE, AUTHORITY_DENIED, SYNC_REQUIRED, CONSENT_PENDING, UNAVAILABLE }

    const val REASON_DENIED = "admission_denied"
    const val REASON_UNAVAILABLE = "admission_unavailable"
    const val REASON_SYNC_REQUIRED = "admission_sync_required"
    const val REASON_CONSENT_PENDING = "admission_consent_pending"
    const val REASON_RESPONSE_INVALID = "admission_response_invalid"
    const val REASON_CONFIG_INVALID = "admission_config_invalid"
    const val REASON_TIMEOUT = "admission_timeout"

    fun classify(status: String?): StatusClass = when (status) {
        "prepared" -> StatusClass.PREPARED
        "granted" -> StatusClass.GRANTED
        "denied", "embedded_mode_unsupported" -> StatusClass.AUTHORITY_DENIED
        "manager_sync_required" -> StatusClass.SYNC_REQUIRED
        "consent_commit_failed" -> StatusClass.CONSENT_PENDING
        else -> StatusClass.UNAVAILABLE
    }

    fun prepare(status: String?, envelopeValid: Boolean): Outcome =
        response(status, envelopeValid, StatusClass.PREPARED)

    fun confirm(status: String?, envelopeValid: Boolean, successBinding: Boolean): Outcome =
        response(status, envelopeValid, StatusClass.GRANTED).let {
            if (it == Outcome.CONTINUE && !successBinding) Outcome.UNAVAILABLE else it
        }

    private fun response(status: String?, envelopeValid: Boolean, success: StatusClass): Outcome {
        if (!envelopeValid) return Outcome.UNAVAILABLE
        return when (classify(status)) {
            success -> Outcome.CONTINUE
            StatusClass.AUTHORITY_DENIED -> Outcome.AUTHORITY_DENIED
            StatusClass.SYNC_REQUIRED -> Outcome.SYNC_REQUIRED
            StatusClass.CONSENT_PENDING -> Outcome.CONSENT_PENDING
            else -> Outcome.UNAVAILABLE
        }
    }

    fun reason(outcome: Outcome): String? = when (outcome) {
        Outcome.CONTINUE -> null
        Outcome.AUTHORITY_DENIED -> REASON_DENIED
        Outcome.SYNC_REQUIRED -> REASON_SYNC_REQUIRED
        Outcome.CONSENT_PENDING -> REASON_CONSENT_PENDING
        Outcome.UNAVAILABLE -> REASON_UNAVAILABLE
    }

    fun latchesDenial(reason: String): Boolean = reason == REASON_DENIED

    fun disabledRevision(status: String?, envelopeValid: Boolean, revision: Long): Long =
        if (status == "manager_sync_required" && envelopeValid && revision > 0L) revision else 0L

    fun blocksFallback(reason: String): Boolean = reason in setOf(
        REASON_DENIED, REASON_SYNC_REQUIRED, REASON_CONSENT_PENDING, REASON_CONFIG_INVALID
    )

    fun retryable(reason: String): Boolean = reason in setOf(
        REASON_UNAVAILABLE, REASON_SYNC_REQUIRED, REASON_CONSENT_PENDING,
        REASON_RESPONSE_INVALID, REASON_CONFIG_INVALID, REASON_TIMEOUT
    )

    fun allowRemoteConfigFallback(
        sawDenied: Boolean,
        normalFailure: String?,
        authorized: Boolean,
        moduleVersionMatches: Boolean,
        generation: Long,
        fallbackBlocked: Boolean = false,
        nPatchRuntime: Boolean = false,
    ): Boolean = !sawDenied && !fallbackBlocked &&
        normalFailure == null &&
        authorized &&
        moduleVersionMatches &&
        generation > 0L &&
        // NPatch 的正 revision 也可能是 Manager 失联前的旧缓存：无法证明用户之后没有关闭。
        // 必须通过已有授权端点取得当前许可，不能只凭缓存文档恢复功能 Hook。
        !nPatchRuntime
}
