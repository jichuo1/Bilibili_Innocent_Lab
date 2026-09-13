package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore

/** 只控制额外的低约束配置通道，不关闭普通模式共有的鉴权连接。 */
internal object CommunicationCompatibilityStore {
    const val KEY = "communication_compatibility_enabled"
    const val DEFAULT = false
    private const val EPOCH_KEY = "communication_compatibility_policy_epoch"
    private const val WARNING_KEY = "communication_compatibility_warning_version"
    private const val WARNING_VERSION = 1

    fun hasConsent(context: Context): Boolean = runCatching {
        val consent = UserTermsConsentStore.readStateOrInitialize(context)
        CommunicationCompatibilityPolicy.consentAllows(consent.decision.isAuthorized, consent.isAcceptancePending)
    }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean = runCatching {
        context.prefs().getBoolean(KEY, DEFAULT) && warningAccepted(context) && hasConsent(context)
    }.getOrDefault(false)

    fun warningAccepted(context: Context): Boolean = runCatching {
        context.prefs().getInt(WARNING_KEY, 0) == WARNING_VERSION
    }.getOrDefault(false)

    fun epoch(context: Context): Long = runCatching {
        context.prefs().getLong(EPOCH_KEY, 0L).coerceAtLeast(0L)
    }.getOrDefault(0L)

    /** mode + epoch 同一笔提交；导入器不能自动开启模式，取消确认不触碰这里。 */
    fun setEnabled(context: Context, enabled: Boolean): Boolean = UserTermsConsentStore.withAuthorityLock {
        runCatching {
            if (enabled && !hasConsent(context)) return@runCatching false
            val preferences = context.prefs()
            val previous = preferences.getBoolean(KEY, DEFAULT)
            if (previous == enabled && (!enabled || warningAccepted(context))) return@runCatching true
            val epoch = preferences.getLong(EPOCH_KEY, 0L).coerceAtLeast(0L)
            val previousWarning = preferences.getInt(WARNING_KEY, 0)
            check(epoch < Long.MAX_VALUE)
            val saved = preferences.edit().putBoolean(KEY, enabled).putLong(EPOCH_KEY, epoch + 1L)
                .putInt(WARNING_KEY, if (enabled) WARNING_VERSION else 0).commit()
            if (!saved) {
                PublicationAuthorityStore.stopGrants()
                preferences.edit().putBoolean(KEY, previous).putLong(EPOCH_KEY, epoch).putInt(WARNING_KEY, previousWarning).commit()
            }
            saved
        }.getOrDefault(false)
    }
}
