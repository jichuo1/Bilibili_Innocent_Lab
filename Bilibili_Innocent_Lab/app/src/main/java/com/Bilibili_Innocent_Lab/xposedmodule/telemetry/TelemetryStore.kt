package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import java.security.SecureRandom
import java.util.UUID

internal data class TelemetryIdentity(
    val installId: String,
    val purgeToken: String
)

internal data class TelemetryDisplayState(
    val enabled: Boolean,
    val lastOutcome: String?,
    val lastSuccessAtEpochMs: Long
)

/**
 * 模块 App 私有的遥测授权、轮换标识与节流状态。该文件不进入设置备份或 hook_config，
 * 宿主进程无法读取；任何缺项、损坏或条款版本漂移都按关闭处理。
 */
internal object TelemetryStore {
    const val PREF_FILE = "telemetry_preferences"

    internal const val KEY_ENABLED = "enabled"
    internal const val KEY_CONSENT_TERMS_VERSION = "consent_terms_version"
    internal const val KEY_CONSENT_DECIDED_AT = "consent_decided_at"
    internal const val KEY_DISCLOSURE_VERSION = "disclosure_version"
    internal const val KEY_INSTALL_ID = "install_id"
    internal const val KEY_PURGE_TOKEN = "purge_token"
    internal const val KEY_IDENTITY_CREATED_AT = "identity_created_at"
    internal const val KEY_PREVIOUS_PURGE_TOKEN = "previous_purge_token"
    internal const val KEY_PREVIOUS_PURGE_EXPIRES_AT = "previous_purge_expires_at"
    internal const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
    internal const val KEY_LAST_SUCCESS_AT = "last_success_at"
    internal const val KEY_NEXT_ATTEMPT_AT = "next_attempt_at"
    internal const val KEY_LAST_OUTCOME = "last_outcome"
    internal const val KEY_MANUAL_ATTEMPTS = "manual_attempts"
    internal const val KEY_MANUAL_RETRY_AT = "manual_retry_at"
    internal const val KEY_RETIRED_ENDPOINT = "retired_endpoint"

    private val lock = Any()
    @Volatile private var consentWriteHealthy = true
    private val secureRandom = SecureRandom()
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")

    fun termsChoice(context: Context): Boolean {
        val preferences = preferences(context) ?: return true
        val hasCurrentChoice = runCatching {
            preferences.contains(KEY_ENABLED) &&
                preferences.getInt(KEY_CONSENT_TERMS_VERSION, -1) ==
                UserTermsConsentStore.CURRENT_TERMS_VERSION
        }.getOrDefault(false)
        val currentChoice = runCatching {
            preferences.getBoolean(KEY_ENABLED, false)
        }.getOrDefault(false)
        return TelemetryPolicy.termsChoice(hasCurrentChoice, currentChoice)
    }

    /** 条款页和 GitHub 菜单共用；同步 commit 后读回，失败时上传授权自然保持关闭。 */
    @SuppressLint("UseKtx")
    fun writeConsentChoice(
        context: Context,
        enabled: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = synchronized(lock) {
        consentWriteHealthy = false
        val preferences = preferences(context) ?: return@synchronized false
        val committed = runCatching {
            val editor = preferences.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putInt(
                    KEY_CONSENT_TERMS_VERSION,
                    UserTermsConsentStore.CURRENT_TERMS_VERSION
                )
                .putLong(KEY_CONSENT_DECIDED_AT, nowEpochMs.coerceAtLeast(1L))
                .putInt(KEY_DISCLOSURE_VERSION, TelemetryPolicy.CURRENT_DISCLOSURE_VERSION)
                .putString(KEY_LAST_OUTCOME, if (enabled) "enabled" else "disabled")
            editor.commit()
        }.getOrDefault(false)
        val verified = committed && runCatching {
            preferences.contains(KEY_ENABLED) &&
                preferences.getBoolean(KEY_ENABLED, !enabled) == enabled &&
                preferences.getInt(KEY_DISCLOSURE_VERSION, -1) == TelemetryPolicy.CURRENT_DISCLOSURE_VERSION &&
                preferences.getInt(KEY_CONSENT_TERMS_VERSION, -1) ==
                UserTermsConsentStore.CURRENT_TERMS_VERSION
        }.getOrDefault(false)
        consentWriteHealthy = verified
        verified
    }

    fun hasCurrentDisclosure(context: Context): Boolean = runCatching {
        TelemetryPolicy.disclosureAuthorizesUpload(
            preferences(context)?.getInt(KEY_DISCLOSURE_VERSION, -1) ?: -1
        )
    }.getOrDefault(false)

    fun needsDisclosureReview(context: Context): Boolean = !hasCurrentDisclosure(context) &&
        runCatching { preferences(context)?.getBoolean(KEY_ENABLED, false) == true }.getOrDefault(false)

    fun isEnabledForUpload(context: Context): Boolean {
        val preferences = preferences(context) ?: return false
        if (!consentWriteHealthy || !hasCurrentDisclosure(context)) return false
        val decision = UserTermsConsentStore.readOrInitialize(context)
        return TelemetryPolicy.consentAuthorizesUpload(
            termsAuthorized = decision.isAuthorized,
            hasEnabledChoice = runCatching { preferences.contains(KEY_ENABLED) }.getOrDefault(false),
            enabled = runCatching { preferences.getBoolean(KEY_ENABLED, false) }.getOrDefault(false),
            storedTermsVersion = runCatching {
                preferences.getInt(KEY_CONSENT_TERMS_VERSION, -1)
            }.getOrDefault(-1),
            currentTermsVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION
        )
    }

    fun displayState(context: Context): TelemetryDisplayState {
        val preferences = preferences(context) ?: return TelemetryDisplayState(
            enabled = false,
            lastOutcome = null,
            lastSuccessAtEpochMs = 0L
        )
        return TelemetryDisplayState(
            enabled = isEnabledForUpload(context),
            lastOutcome = runCatching {
                preferences.getString(KEY_LAST_OUTCOME, null)
            }.getOrNull(),
            lastSuccessAtEpochMs = runCatching {
                preferences.getLong(KEY_LAST_SUCCESS_AT, 0L)
            }.getOrDefault(0L)
        )
    }

    fun attemptDecision(
        context: Context,
        endpointKey: String,
        manual: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TelemetryAttemptDecision {
        val preferences = preferences(context) ?: return TelemetryAttemptDecision.DISABLED
        val retiredEndpoint = runCatching {
            preferences.getString(KEY_RETIRED_ENDPOINT, null)
        }.getOrNull()
        if (manual) return runCatching {
            TelemetryPolicy.manualDecision(
                isEnabledForUpload(context), retiredEndpoint == endpointKey, nowEpochMs,
                preferences.getLong(KEY_MANUAL_RETRY_AT, 0L),
                preferences.getString(KEY_MANUAL_ATTEMPTS, "") ?: "invalid"
            )
        }.getOrDefault(TelemetryAttemptDecision.MANUAL_LIMIT_REACHED)
        val storedNextAttempt = runCatching {
            preferences.getLong(KEY_NEXT_ATTEMPT_AT, 0L)
        }.getOrDefault(0L)
        return TelemetryPolicy.attemptDecision(
            enabled = isEnabledForUpload(context),
            endpointRetired = retiredEndpoint == endpointKey,
            nowEpochMs = nowEpochMs,
            nextAttemptAtEpochMs = if (
                retiredEndpoint != null && retiredEndpoint != endpointKey &&
                storedNextAttempt == Long.MAX_VALUE
            ) 0L else storedNextAttempt,
            lastSuccessAtEpochMs = runCatching {
                preferences.getLong(KEY_LAST_SUCCESS_AT, 0L)
            }.getOrDefault(0L),
            force = false
        )
    }

    /**
     * 在真正打开网络连接前同步写入 24 小时窗口；写入失败就不发送，确保崩溃/断网
     * 也不会把“每 24 小时至多一次”退化成高频重试。
     */
    @SuppressLint("UseKtx")
    fun beginNetworkAttempt(
        context: Context,
        endpointKey: String,
        manual: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = synchronized(lock) {
        val preferences = preferences(context) ?: return@synchronized false
        if (attemptDecision(context, endpointKey, manual, nowEpochMs) != TelemetryAttemptDecision.ALLOWED) {
            return@synchronized false
        }
        if (manual) return@synchronized runCatching {
            val attempts = TelemetryPolicy.activeManualAttempts(
                preferences.getString(KEY_MANUAL_ATTEMPTS, "") ?: "invalid", nowEpochMs
            ) ?: return@runCatching false
            preferences.edit()
                .putString(KEY_MANUAL_ATTEMPTS, (attempts + nowEpochMs.coerceAtLeast(1L)).joinToString(","))
                .putString(KEY_LAST_OUTCOME, "sending")
                .commit()
        }.getOrDefault(false)
        runCatching {
            preferences.edit()
                .putLong(KEY_LAST_ATTEMPT_AT, nowEpochMs.coerceAtLeast(1L))
                .putLong(KEY_NEXT_ATTEMPT_AT, nowEpochMs + TelemetryPolicy.SUCCESS_INTERVAL_MS)
                .putString(KEY_LAST_OUTCOME, "sending")
                .commit()
        }.getOrDefault(false)
    }

    @SuppressLint("UseKtx")
    fun recordTransportResult(
        context: Context,
        endpointKey: String,
        result: TelemetryTransportResult,
        manual: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val preferences = preferences(context) ?: return@synchronized
        if (manual) {
            val manualEditor = preferences.edit()
                .putString(KEY_LAST_OUTCOME, result.outcome.name.lowercase())
            when (result.outcome) {
                TelemetryHttpOutcome.RETIRED -> manualEditor.putString(KEY_RETIRED_ENDPOINT, endpointKey)
                TelemetryHttpOutcome.RATE_LIMITED -> manualEditor.putLong(
                    KEY_MANUAL_RETRY_AT, nowEpochMs + result.retryAfterMs
                )
                else -> Unit
            }
            manualEditor.apply()
            return@synchronized
        }
        val editor = preferences.edit()
            .putLong(KEY_LAST_ATTEMPT_AT, nowEpochMs.coerceAtLeast(1L))
            .putString(KEY_LAST_OUTCOME, result.outcome.name.lowercase())
        when (result.outcome) {
            TelemetryHttpOutcome.SUCCESS -> editor
                .putLong(KEY_LAST_SUCCESS_AT, nowEpochMs.coerceAtLeast(1L))
                .putLong(KEY_NEXT_ATTEMPT_AT, nowEpochMs + TelemetryPolicy.SUCCESS_INTERVAL_MS)
                .remove(KEY_RETIRED_ENDPOINT)
            TelemetryHttpOutcome.RETIRED -> editor
                .putString(KEY_RETIRED_ENDPOINT, endpointKey)
                .putLong(KEY_NEXT_ATTEMPT_AT, Long.MAX_VALUE)
            TelemetryHttpOutcome.RATE_LIMITED -> editor.putLong(
                KEY_NEXT_ATTEMPT_AT,
                nowEpochMs + result.retryAfterMs.coerceAtLeast(TelemetryPolicy.SUCCESS_INTERVAL_MS)
            )
            TelemetryHttpOutcome.REJECTED -> editor
                .putLong(KEY_NEXT_ATTEMPT_AT, nowEpochMs + TelemetryPolicy.SUCCESS_INTERVAL_MS)
            TelemetryHttpOutcome.RETRYABLE_FAILURE -> {
                editor.putLong(
                    KEY_NEXT_ATTEMPT_AT,
                    nowEpochMs + TelemetryPolicy.SUCCESS_INTERVAL_MS
                )
            }
        }
        editor.apply()
    }

    @SuppressLint("UseKtx")
    fun recordCollectionUnavailable(
        context: Context,
        manual: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            val preferences = preferences(context) ?: return@synchronized
            runCatching {
                preferences.edit()
                    .putString(KEY_LAST_OUTCOME, "host_unavailable")
                    .apply {
                        if (!manual) putLong(
                            KEY_NEXT_ATTEMPT_AT, nowEpochMs + TelemetryPolicy.COLLECTION_RETRY_MS
                        )
                    }
                    .apply()
            }
        }
    }

    fun getOrCreateIdentity(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TelemetryIdentity? = synchronized(lock) {
        val preferences = preferences(context) ?: return@synchronized null
        val current = readIdentity(preferences)
        val createdAt = runCatching {
            preferences.getLong(KEY_IDENTITY_CREATED_AT, 0L)
        }.getOrDefault(0L)
        if (current != null && !TelemetryPolicy.shouldRotateIdentity(createdAt, nowEpochMs)) {
            return@synchronized current
        }

        val next = TelemetryIdentity(
            installId = UUID.randomUUID().toString(),
            purgeToken = newPurgeToken()
        )
        val editor = preferences.edit()
            .putString(KEY_INSTALL_ID, next.installId)
            .putString(KEY_PURGE_TOKEN, next.purgeToken)
            .putLong(KEY_IDENTITY_CREATED_AT, nowEpochMs.coerceAtLeast(1L))
        current?.let {
            editor
                .putString(KEY_PREVIOUS_PURGE_TOKEN, it.purgeToken)
                .putLong(
                    KEY_PREVIOUS_PURGE_EXPIRES_AT,
                    nowEpochMs + TelemetryPolicy.PREVIOUS_PURGE_TOKEN_GRACE_MS
                )
        }
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (!committed) return@synchronized null
        readIdentity(preferences)?.takeIf { it == next }
    }

    fun purgeTokens(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<String> = synchronized(lock) {
        val preferences = preferences(context) ?: return@synchronized emptyList()
        buildList {
            readIdentity(preferences)?.purgeToken?.let(::add)
            val previous = runCatching {
                preferences.getString(KEY_PREVIOUS_PURGE_TOKEN, null)
            }.getOrNull()
            val expiresAt = runCatching {
                preferences.getLong(KEY_PREVIOUS_PURGE_EXPIRES_AT, 0L)
            }.getOrDefault(0L)
            if (previous != null && tokenPattern.matches(previous) && nowEpochMs < expiresAt) {
                add(previous)
            }
        }.distinct()
    }

    @SuppressLint("UseKtx")
    fun recordPurgeSuccess(context: Context) {
        synchronized(lock) {
            val preferences = preferences(context) ?: return@synchronized
            runCatching {
                preferences.edit()
                    .remove(KEY_PREVIOUS_PURGE_TOKEN)
                    .remove(KEY_PREVIOUS_PURGE_EXPIRES_AT)
                    .putString(KEY_LAST_OUTCOME, "purged")
                    .commit()
            }
        }
    }

    private fun readIdentity(preferences: SharedPreferences): TelemetryIdentity? {
        val installId = runCatching {
            preferences.getString(KEY_INSTALL_ID, null)
        }.getOrNull() ?: return null
        val purgeToken = runCatching {
            preferences.getString(KEY_PURGE_TOKEN, null)
        }.getOrNull() ?: return null
        val uuidValid = runCatching {
            UUID.fromString(installId).version() == 4
        }.getOrDefault(false)
        return if (uuidValid && tokenPattern.matches(purgeToken)) {
            TelemetryIdentity(installId, purgeToken)
        } else null
    }

    private fun newPurgeToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun preferences(context: Context): SharedPreferences? = runCatching {
        (context.applicationContext ?: context).getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }.getOrNull()
}
