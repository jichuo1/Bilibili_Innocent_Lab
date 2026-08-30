package com.Bilibili_Innocent_Lab.xposedmodule.settings.terms

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion

internal enum class UserTermsDecision {
    UNDECIDED,
    ACCEPTED,
    DECLINED,
    LEGACY_EXEMPT;

    val isAuthorized: Boolean
        get() = this == ACCEPTED || this == LEGACY_EXEMPT
}

/**
 * 模块自身的用户条款状态以独立私有文件为权威来源。Remote Preferences 发布器把决定
 * 连同完整设置写入 LSPosed 数据库，供宿主只读校验；该配置不进入设置备份。
 *
 * 首个上线版本只在状态缺失时识别历史用户；识别结果会同步持久化，因此后续读取幂等。
 * 已存在但损坏、或条款版本不匹配的记录一律回到未决定状态，避免错误放行。
 */
internal object UserTermsConsentStore {

    const val CURRENT_TERMS_VERSION = 1

    /**
     * 首次引入条款门禁时的固定迁移截止点（2026-08-28 17:25:00 +08:00）。
     * 只有在此前已安装的旧版用户才可进入 LEGACY_EXEMPT，不能以当前
     * APK 的版本号或每次更新时间动态推进，否则新用户日后清除数据会被误放行。
     */
    internal const val LEGACY_ROLLOUT_CUTOFF_EPOCH_MS = 1_787_909_100_000L

    internal const val PREF_FILE = "user_terms_consent"
    internal const val KEY_DECISION = "decision"
    internal const val KEY_TERMS_VERSION = "terms_version"
    private const val LEGACY_PREFS_ALIVE_KEY = "prefs_alive_ts"
    private val lock = Any()

    fun readOrInitialize(context: Context): UserTermsDecision = synchronized(lock) {
        val appContext = context.applicationContext ?: context
        val preferences = runCatching {
            appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }.getOrNull() ?: return@synchronized UserTermsDecision.UNDECIDED

        val hasPersistedDecision = runCatching {
            preferences.contains(KEY_DECISION)
        }.getOrElse { return@synchronized UserTermsDecision.UNDECIDED }
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
                persist(preferences, UserTermsDecision.UNDECIDED)
            }
            return@synchronized persisted
        }

        val initial = inferInitialDecision(
            packageInfo = readPackageInfo(appContext),
            prefsAliveTimestamp = readLegacyPrefsAliveTimestamp(appContext)
        )
        if (persist(preferences, initial)) initial else UserTermsDecision.UNDECIDED
    }

    fun accept(context: Context): Boolean {
        val previous = readOrInitialize(context)
        if (!write(context, UserTermsDecision.ACCEPTED)) return false
        if (RemoteHookConfigStore.publish(
                context,
                UserTermsDecision.ACCEPTED
            ).also(RemoteHookConfigStore::logFailure).succeeded
        ) {
            return true
        }
        // 宿主配置未确认时不能让界面权威状态单独前进；尽力回滚到原决定。
        write(context, previous)
        RemoteHookConfigStore.publish(context, previous).also(RemoteHookConfigStore::logFailure)
        return false
    }

    fun decline(context: Context): Boolean {
        readOrInitialize(context)
        // 必须先同步撤销宿主可见的正向授权；撤销未确认时不得只改私有权威文件。
        if (!RemoteHookConfigStore.publish(
                context,
                UserTermsDecision.DECLINED
            ).also(RemoteHookConfigStore::logFailure).succeeded
        ) {
            return false
        }
        if (write(context, UserTermsDecision.DECLINED)) return true
        // 私有提交失败也保留宿主侧关闭态，不能为了状态一致性重新放行 Hook。
        return false
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

    private fun inferInitialDecision(
        packageInfo: PackageInfo?,
        prefsAliveTimestamp: Long?
    ): UserTermsDecision = inferInitialDecision(
        firstInstallTime = packageInfo?.firstInstallTime,
        lastUpdateTime = packageInfo?.lastUpdateTime,
        prefsAliveTimestamp = prefsAliveTimestamp
    )

    private fun write(context: Context, decision: UserTermsDecision): Boolean = synchronized(lock) {
        val preferences = runCatching {
            (context.applicationContext ?: context).getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }.getOrNull() ?: return@synchronized false
        persist(preferences, decision)
    }

    @SuppressLint("UseKtx") // 必须检查 commit() 返回值，KTX edit(commit=true) 会丢失该结果。
    private fun persist(
        preferences: SharedPreferences,
        decision: UserTermsDecision
    ): Boolean = runCatching {
        preferences.edit()
            .putString(KEY_DECISION, decision.name)
            .putInt(KEY_TERMS_VERSION, CURRENT_TERMS_VERSION)
            .commit()
    }.getOrDefault(false)

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
