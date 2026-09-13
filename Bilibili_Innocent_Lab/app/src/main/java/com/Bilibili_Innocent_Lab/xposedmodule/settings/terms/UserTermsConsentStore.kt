package com.Bilibili_Innocent_Lab.xposedmodule.settings.terms

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import kotlin.math.max

internal enum class UserTermsDecision {
    UNDECIDED,
    ACCEPTED,
    DECLINED,
    LEGACY_EXEMPT;

    val isAuthorized: Boolean
        get() = this == ACCEPTED || this == LEGACY_EXEMPT
}

/**
 * 私有条款记录的完整状态。等待同步只存在于模块进程，不进入 API 102 协议；因此
 * [decision] 在远端完整发布并读回之前始终保持原来的关闭态决定。
 */
internal data class UserTermsConsentState(
    val decision: UserTermsDecision,
    val pendingAcceptance: UserTermsPendingAcceptance? = null,
    val consentRevision: Long = 0L
) {
    val isAcceptancePending: Boolean
        get() = pendingAcceptance != null

    val requestedRemoteDecision: UserTermsDecision
        get() = if (pendingAcceptance != null) {
            UserTermsDecision.ACCEPTED
        } else {
            decision
        }
}

internal data class UserTermsPendingAcceptance(
    val revision: Long,
    val previousDecision: UserTermsDecision
)

internal enum class UserTermsPendingCompletion {
    COMPLETED,
    STALE,
    WRITE_FAILED
}

/**
 * 模块自身的用户条款状态以独立私有文件为权威来源。Remote Preferences 发布器把决定
 * 连同完整设置写入框架数据库，供宿主只读校验；该配置不进入设置备份。
 *
 * 首个上线版本只在状态缺失时识别历史用户；识别结果会同步持久化，因此后续读取幂等。
 * 已存在但损坏、或条款版本不匹配的记录一律回到未决定状态，避免错误放行。
 */
internal object UserTermsConsentStore {

    const val CURRENT_TERMS_VERSION = 2

    /**
     * 首次引入条款门禁时的固定迁移截止点（2026-08-28 17:25:00 +08:00）。
     * 只有在此前已安装的旧版用户才可进入 LEGACY_EXEMPT，不能以当前
     * APK 的版本号或每次更新时间动态推进，否则新用户日后清除数据会被误放行。
     */
    internal const val LEGACY_ROLLOUT_CUTOFF_EPOCH_MS = 1_787_909_100_000L

    internal const val PREF_FILE = "user_terms_consent"
    internal const val KEY_DECISION = "decision"
    internal const val KEY_TERMS_VERSION = "terms_version"
    internal const val KEY_PENDING_ACCEPTANCE = "pending_acceptance"
    internal const val KEY_PENDING_TERMS_VERSION = "pending_terms_version"
    internal const val KEY_PENDING_REVISION = "pending_revision"
    internal const val KEY_PENDING_PREVIOUS_DECISION = "pending_previous_decision"
    private const val LEGACY_PREFS_ALIVE_KEY = "prefs_alive_ts"
    private val lock = Any()
    internal const val KEY_CONSENT_REVISION = "consent_revision"
    internal fun <T> withAuthorityLock(action: () -> T): T = synchronized(lock, action)

    fun readOrInitialize(context: Context): UserTermsDecision =
        readStateOrInitialize(context).decision

    fun readStateOrInitialize(context: Context): UserTermsConsentState = synchronized(lock) {
        val appContext = context.applicationContext ?: context
        val preferences = openPreferences(appContext)
            ?: return@synchronized UserTermsConsentState(UserTermsDecision.UNDECIDED)

        val hasPersistedDecision = runCatching {
            preferences.contains(KEY_DECISION)
        }.getOrElse {
            return@synchronized UserTermsConsentState(UserTermsDecision.UNDECIDED)
        }
        val rawDecision = runCatching {
            preferences.getString(KEY_DECISION, null)
        }.getOrNull()
        val storedVersion = runCatching {
            preferences.getInt(KEY_TERMS_VERSION, -1)
        }.getOrDefault(-1)
        val persisted = resolvePersistedDecision(
            hasDecision = hasPersistedDecision,
            rawDecision = rawDecision,
            storedVersion = storedVersion
        )
        if (persisted != null) {
            val storedRecordIsValid = storedVersion == CURRENT_TERMS_VERSION &&
                UserTermsDecision.entries.any { it.name == rawDecision }
            if (!storedRecordIsValid) {
                persistDecision(preferences, UserTermsDecision.UNDECIDED)
                return@synchronized UserTermsConsentState(UserTermsDecision.UNDECIDED)
            }
            val pending = resolvePendingAcceptance(
                hasPending = runCatching {
                    preferences.getBoolean(KEY_PENDING_ACCEPTANCE, false)
                }.getOrDefault(false),
                storedTermsVersion = runCatching {
                    preferences.getInt(KEY_PENDING_TERMS_VERSION, -1)
                }.getOrDefault(-1),
                revision = runCatching {
                    preferences.getLong(KEY_PENDING_REVISION, 0L)
                }.getOrDefault(0L),
                rawPreviousDecision = runCatching {
                    preferences.getString(KEY_PENDING_PREVIOUS_DECISION, null)
                }.getOrNull(),
                currentDecision = persisted
            )
            val hasPendingMetadata = runCatching {
                preferences.contains(KEY_PENDING_ACCEPTANCE) ||
                    preferences.contains(KEY_PENDING_TERMS_VERSION) ||
                    preferences.contains(KEY_PENDING_REVISION) ||
                    preferences.contains(KEY_PENDING_PREVIOUS_DECISION)
            }.getOrDefault(false)
            if (hasPendingMetadata && pending == null) clearPending(preferences)
            var revision = preferences.getLong(KEY_CONSENT_REVISION, 0L)
            if (revision <= 0L) {
                revision = pending?.revision ?: System.currentTimeMillis().coerceAtLeast(1L)
                if (!preferences.edit().putLong(KEY_CONSENT_REVISION, revision).commit()) {
                    com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.stopGrants()
                    return@synchronized UserTermsConsentState(UserTermsDecision.UNDECIDED)
                }
            }
            return@synchronized UserTermsConsentState(persisted, pending, revision)
        }

        val initial = inferInitialDecision(
            packageInfo = readPackageInfo(appContext),
            prefsAliveTimestamp = readLegacyPrefsAliveTimestamp(appContext)
        )
        val resolved = if (persistDecision(preferences, initial)) {
            initial
        } else {
            UserTermsDecision.UNDECIDED
        }
        UserTermsConsentState(resolved, consentRevision = preferences.getLong(KEY_CONSENT_REVISION, 0L))
    }

    /**
     * 先同步持久化“已同意、等待发布”。原决定在 API 102 快照确认前不前进，所有
     * 继续只读取 [readOrInitialize] 的旧调用方自然保持 fail-closed。
     */
    fun beginPendingAcceptance(context: Context): UserTermsConsentState? = synchronized(lock) {
        val appContext = context.applicationContext ?: context
        val current = readStateOrInitialize(appContext)
        if (current.decision.isAuthorized || current.pendingAcceptance != null) {
            return@synchronized current
        }
        val preferences = openPreferences(appContext) ?: return@synchronized null
        val storedRevision = runCatching {
            preferences.getLong(KEY_CONSENT_REVISION, 0L)
        }.getOrDefault(0L)
        if (storedRevision == Long.MAX_VALUE) return@synchronized null
        val before = preferences.all.toMap()
        val revision = max(
            System.currentTimeMillis().coerceAtLeast(1L),
            storedRevision.nextRevision()
        )
        val committed = runCatching {
            preferences.edit()
                .putString(KEY_DECISION, current.decision.name)
                .putInt(KEY_TERMS_VERSION, CURRENT_TERMS_VERSION)
                .putBoolean(KEY_PENDING_ACCEPTANCE, true)
                .putInt(KEY_PENDING_TERMS_VERSION, CURRENT_TERMS_VERSION)
                .putLong(KEY_PENDING_REVISION, revision)
                .putLong(KEY_CONSENT_REVISION, revision)
                .putString(KEY_PENDING_PREVIOUS_DECISION, current.decision.name)
                .commit()
        }.getOrDefault(false)
        if (!committed) {
            com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.stopGrants()
            val rollback = preferences.edit()
            listOf(KEY_DECISION, KEY_TERMS_VERSION, KEY_PENDING_ACCEPTANCE, KEY_PENDING_TERMS_VERSION,
                KEY_PENDING_REVISION, KEY_CONSENT_REVISION, KEY_PENDING_PREVIOUS_DECISION).forEach { key ->
                when (val value = before[key]) {
                    is String -> rollback.putString(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Long -> rollback.putLong(key, value)
                    else -> rollback.remove(key)
                }
            }
            runCatching { rollback.commit() }
            return@synchronized null
        }
        UserTermsConsentState(
            decision = current.decision,
            pendingAcceptance = UserTermsPendingAcceptance(
                revision = revision,
                previousDecision = current.decision
            ),
            consentRevision = revision
        )
    }

    fun completePendingAcceptance(
        context: Context,
        expectedRevision: Long
    ): UserTermsPendingCompletion = synchronized(lock) {
        val appContext = context.applicationContext ?: context
        val current = readStateOrInitialize(appContext)
        val pending = current.pendingAcceptance
            ?: return@synchronized UserTermsPendingCompletion.STALE
        if (pending.revision != expectedRevision) {
            return@synchronized UserTermsPendingCompletion.STALE
        }
        val preferences = openPreferences(appContext)
            ?: return@synchronized UserTermsPendingCompletion.WRITE_FAILED
        if (persistDecision(preferences, UserTermsDecision.ACCEPTED, expectedRevision)) {
            UserTermsPendingCompletion.COMPLETED
        } else {
            UserTermsPendingCompletion.WRITE_FAILED
        }
    }

    /** 用户改为拒绝前先使任何在途 ACCEPTED 结果失效。 */
    fun cancelPendingAcceptance(context: Context): Boolean = synchronized(lock) {
        val appContext = context.applicationContext ?: context
        val current = readStateOrInitialize(appContext)
        if (current.pendingAcceptance == null) return@synchronized true
        val preferences = openPreferences(appContext) ?: return@synchronized false
        clearPending(preferences)
    }

    fun writeDecision(context: Context, decision: UserTermsDecision): Boolean = synchronized(lock) {
        val preferences = openPreferences(context.applicationContext ?: context)
            ?: return@synchronized false
        persistDecision(preferences, decision)
    }

    internal fun inferInitialDecision(
        firstInstallTime: Long?,
        lastUpdateTime: Long?,
        prefsAliveTimestamp: Long?,
        rolloutCutoffEpochMs: Long = LEGACY_ROLLOUT_CUTOFF_EPOCH_MS
    ): UserTermsDecision {
        val installedBeforeRollout = firstInstallTime != null &&
            firstInstallTime > 0L &&
            firstInstallTime < rolloutCutoffEpochMs
        val upgradedInstallation = installedBeforeRollout &&
            lastUpdateTime != null &&
            lastUpdateTime > firstInstallTime
        val hasLegacyPrefsEvidence = installedBeforeRollout &&
            prefsAliveTimestamp != null &&
            prefsAliveTimestamp > 0L &&
            prefsAliveTimestamp < rolloutCutoffEpochMs
        return if (upgradedInstallation || hasLegacyPrefsEvidence) {
            UserTermsDecision.LEGACY_EXEMPT
        } else {
            UserTermsDecision.UNDECIDED
        }
    }

    /**
     * @return `null` 仅表示没有记录、调用方应继续执行旧用户识别；损坏或过期记录
     * 明确返回 [UserTermsDecision.UNDECIDED]，不得通过旧用户推断重新获得豁免。
     */
    internal fun resolvePersistedDecision(
        hasDecision: Boolean,
        rawDecision: String?,
        storedVersion: Int
    ): UserTermsDecision? {
        if (!hasDecision) return null
        if (storedVersion != CURRENT_TERMS_VERSION) return UserTermsDecision.UNDECIDED
        return UserTermsDecision.entries.firstOrNull { it.name == rawDecision }
            ?: UserTermsDecision.UNDECIDED
    }

    internal fun resolvePendingAcceptance(
        hasPending: Boolean,
        storedTermsVersion: Int,
        revision: Long,
        rawPreviousDecision: String?,
        currentDecision: UserTermsDecision
    ): UserTermsPendingAcceptance? {
        if (!hasPending || storedTermsVersion != CURRENT_TERMS_VERSION || revision <= 0L) {
            return null
        }
        if (currentDecision.isAuthorized) return null
        val previousDecision = UserTermsDecision.entries.firstOrNull {
            it.name == rawPreviousDecision
        } ?: return null
        if (previousDecision != currentDecision || previousDecision.isAuthorized) return null
        return UserTermsPendingAcceptance(revision, previousDecision)
    }

    private fun inferInitialDecision(
        packageInfo: PackageInfo?,
        prefsAliveTimestamp: Long?
    ): UserTermsDecision = inferInitialDecision(
        firstInstallTime = packageInfo?.firstInstallTime,
        lastUpdateTime = packageInfo?.lastUpdateTime,
        prefsAliveTimestamp = prefsAliveTimestamp
    )

    @SuppressLint("UseKtx") // 必须检查 commit() 返回值，KTX edit(commit=true) 会丢失该结果。
    private fun persistDecision(
        preferences: SharedPreferences,
        decision: UserTermsDecision,
        keepRevision: Long? = null
    ): Boolean = runCatching {
        val keys = listOf(KEY_DECISION, KEY_TERMS_VERSION, KEY_PENDING_ACCEPTANCE,
            KEY_PENDING_TERMS_VERSION, KEY_PENDING_REVISION, KEY_PENDING_PREVIOUS_DECISION, KEY_CONSENT_REVISION)
        val before = preferences.all.filterKeys { it in keys }
        val previous = (before[KEY_CONSENT_REVISION] as? Long)?.coerceAtLeast(0L) ?: 0L
        check(previous < Long.MAX_VALUE || keepRevision == previous)
        val revision = keepRevision ?: max(System.currentTimeMillis().coerceAtLeast(1L), previous + 1L)
        val saved = preferences.edit()
            .putString(KEY_DECISION, decision.name)
            .putInt(KEY_TERMS_VERSION, CURRENT_TERMS_VERSION)
            .putLong(KEY_CONSENT_REVISION, revision)
            .remove(KEY_PENDING_ACCEPTANCE).remove(KEY_PENDING_TERMS_VERSION)
            .remove(KEY_PENDING_REVISION).remove(KEY_PENDING_PREVIOUS_DECISION).commit()
        if (!saved) {
            com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.stopGrants()
            // commit 失败时 Android 的内存映像仍可能已改变，不能据此放行。
            val rollback = preferences.edit()
            keys.forEach { key ->
                when (val value = before[key]) {
                    is String -> rollback.putString(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Long -> rollback.putLong(key, value)
                    else -> rollback.remove(key)
                }
            }
            rollback.commit()
        }
        saved
    }.getOrDefault(false)

    private fun clearPending(preferences: SharedPreferences): Boolean {
        val decision = resolvePersistedDecision(true, preferences.getString(KEY_DECISION, null),
            preferences.getInt(KEY_TERMS_VERSION, -1)) ?: UserTermsDecision.UNDECIDED
        // 取消会推进持久代次，旧挑战及异步发布无法再次完成这次同意。
        return persistDecision(preferences, decision)
    }

    private fun openPreferences(context: Context): SharedPreferences? = runCatching {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }.getOrNull()

    private fun Long.nextRevision(): Long = when {
        this < 1L -> 1L
        this == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> this + 1L
    }

    private fun readPackageInfo(context: Context): PackageInfo? = runCatching {
        if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }.getOrNull()

    private fun readLegacyPrefsAliveTimestamp(context: Context): Long? = runCatching {
        context.getSharedPreferences(
            "${context.packageName}_preferences",
            Context.MODE_PRIVATE
        ).getLong(LEGACY_PREFS_ALIVE_KEY, 0L)
    }.getOrNull()
}
