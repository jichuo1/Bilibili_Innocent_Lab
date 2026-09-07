package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

/** 独立的版本变化窗口；不读写常规自动或手动上传的额度。 */
internal object TelemetryVersionPolicy {
    const val LIMIT = 3
    const val RETRY_MS = 15L * 60L * 1_000L

    fun key(moduleVersion: Long, hostVersion: Long): String? =
        if (moduleVersion in 1..Int.MAX_VALUE.toLong() && hostVersion in 1..Int.MAX_VALUE.toLong()) {
            "$moduleVersion:$hostVersion"
        } else null

    fun allowed(
        versionKey: String,
        successfulKey: String?,
        attempts: String,
        retryAt: Long,
        now: Long
    ): Boolean = versionKey != successfulKey && now >= retryAt &&
        (TelemetryPolicy.activeManualAttempts(attempts, now)?.size ?: LIMIT) < LIMIT
}
