package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

internal enum class TelemetryAttemptDecision {
    ALLOWED,
    DISABLED,
    RETIRED,
    NOT_DUE,
    MANUAL_LIMIT_REACHED
}

internal enum class TelemetryHttpOutcome {
    SUCCESS,
    RETIRED,
    RATE_LIMITED,
    REJECTED,
    RETRYABLE_FAILURE
}

/** 不依赖 Android 的遥测授权、节流与退避规则，供存储层和 JVM 测试共用。 */
internal object TelemetryPolicy {
    const val CURRENT_DISCLOSURE_VERSION = 5

    fun disclosureAuthorizesUpload(storedVersion: Int): Boolean =
        storedVersion == CURRENT_DISCLOSURE_VERSION

    const val SUCCESS_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    const val IDENTITY_ROTATION_MS = 90L * 24L * 60L * 60L * 1_000L
    const val PREVIOUS_PURGE_TOKEN_GRACE_MS = 31L * 24L * 60L * 60L * 1_000L
    const val DEFAULT_RETRY_AFTER_MS = 60L * 1_000L
    const val COLLECTION_RETRY_MS = 15L * 60L * 1_000L
    const val MANUAL_LIMIT = 3

    /** Future timestamps remain counted on clock rollback; malformed state fails closed. */
    fun activeManualAttempts(encoded: String, nowEpochMs: Long): List<Long>? {
        if (encoded.isEmpty()) return emptyList()
        val parts = encoded.split(',')
        if (parts.size > MANUAL_LIMIT) return null
        val attempts = parts.map { it.toLongOrNull()?.takeIf { value -> value > 0L } ?: return null }
        return attempts.filter { it > nowEpochMs - SUCCESS_INTERVAL_MS }
    }

    fun manualDecision(
        enabled: Boolean,
        endpointRetired: Boolean,
        nowEpochMs: Long,
        retryAtEpochMs: Long,
        encodedAttempts: String
    ): TelemetryAttemptDecision = when {
        !enabled -> TelemetryAttemptDecision.DISABLED
        endpointRetired -> TelemetryAttemptDecision.RETIRED
        (activeManualAttempts(encodedAttempts, nowEpochMs)?.size ?: MANUAL_LIMIT) >= MANUAL_LIMIT ->
            TelemetryAttemptDecision.MANUAL_LIMIT_REACHED
        nowEpochMs < retryAtEpochMs -> TelemetryAttemptDecision.NOT_DUE
        else -> TelemetryAttemptDecision.ALLOWED
    }

    fun termsChoice(
        hasCurrentChoice: Boolean,
        currentChoice: Boolean
    ): Boolean = if (hasCurrentChoice) currentChoice else true

    fun consentAuthorizesUpload(
        termsAuthorized: Boolean,
        hasEnabledChoice: Boolean,
        enabled: Boolean,
        storedTermsVersion: Int,
        currentTermsVersion: Int
    ): Boolean = termsAuthorized &&
        hasEnabledChoice &&
        enabled &&
        storedTermsVersion == currentTermsVersion

    fun attemptDecision(
        enabled: Boolean,
        endpointRetired: Boolean,
        nowEpochMs: Long,
        nextAttemptAtEpochMs: Long,
        lastSuccessAtEpochMs: Long,
        force: Boolean
    ): TelemetryAttemptDecision = when {
        !enabled -> TelemetryAttemptDecision.DISABLED
        endpointRetired -> TelemetryAttemptDecision.RETIRED
        nowEpochMs < nextAttemptAtEpochMs -> TelemetryAttemptDecision.NOT_DUE
        !force && lastSuccessAtEpochMs > 0L &&
            nowEpochMs - lastSuccessAtEpochMs in 0 until SUCCESS_INTERVAL_MS ->
            TelemetryAttemptDecision.NOT_DUE
        else -> TelemetryAttemptDecision.ALLOWED
    }

    fun shouldRotateIdentity(createdAtEpochMs: Long, nowEpochMs: Long): Boolean =
        createdAtEpochMs <= 0L ||
            nowEpochMs < createdAtEpochMs ||
            nowEpochMs - createdAtEpochMs >= IDENTITY_ROTATION_MS

    fun classifyHttpStatus(statusCode: Int): TelemetryHttpOutcome = when (statusCode) {
        204 -> TelemetryHttpOutcome.SUCCESS
        410 -> TelemetryHttpOutcome.RETIRED
        429 -> TelemetryHttpOutcome.RATE_LIMITED
        400, 413, 415 -> TelemetryHttpOutcome.REJECTED
        in 500..599 -> TelemetryHttpOutcome.RETRYABLE_FAILURE
        else -> TelemetryHttpOutcome.REJECTED
    }
}
