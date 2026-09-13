package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.util.UUID

internal data class PublicationIdentity(
    val incarnation: String,
    val consentRevision: Long,
    val policyEpoch: Long,
    val snapshotRevision: Long,
    val fingerprint: String
)

internal data class AuthorityPublication(
    val identity: PublicationIdentity,
    val decision: UserTermsDecision,
    val values: Map<String, Any>,
    val pending: Boolean,
    val directAllowed: Boolean
) {
    fun document(): Map<String, Any> = RemoteHookConfigContract.encode(
        generation = identity.snapshotRevision, moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
        deliveryEnabled = true, noRootRevision = 0L, decision = decision, values = values)
}

/**
 * 条款文件里的派生发布记录，不是第二份授权决定。
 * 每次发放许可都在同一条款锁内重读决定、模式与完整设置；旧记录不匹配时先持久化新记录。
 * 跨载体按内容身份匹配，不比较各管理器自己的时间戳 generation。
 */
internal object PublicationAuthorityStore {
    @Volatile private var storageFault = false
    private const val INCARNATION = "publication_incarnation"
    private const val REVISION = "publication_revision"
    private const val POLICY = "publication_policy_revision"
    private const val INPUT = "publication_policy_input"
    private const val FINGERPRINT = "publication_content_fingerprint"

    fun stopGrants() { storageFault = true }

    fun current(context: Context): AuthorityPublication? = UserTermsConsentStore.withAuthorityLock {
        if (storageFault) return@withAuthorityLock null
        runCatching {
            val consent = UserTermsConsentStore.readStateOrInitialize(context)
            if (consent.consentRevision <= 0L) return@runCatching null
            val noRootSelected = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore.isDesiredEnabled(context)
            val raw = context.modulePreferences().all.toMutableMap()
            if (noRootSelected) raw[RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP] =
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore.adapterResetRevision(context)
            val values = RemoteHookConfigContract.resolveSourceValues(raw)
            val decision = consent.requestedRemoteDecision
            val fingerprint = RemoteHookConfigContract.contentFingerprint(BuildConfig.VERSION_CODE.toLong(), decision, values)
            val direct = CommunicationCompatibilityStore.isEnabled(context)
            val policyInput = "${consent.consentRevision}:${CommunicationCompatibilityStore.epoch(context)}:$direct:$noRootSelected"
            val prefs = context.getSharedPreferences(UserTermsConsentStore.PREF_FILE, Context.MODE_PRIVATE)
            val incarnation = prefs.getString(INCARNATION, null) ?: UUID.randomUUID().toString()
            var revision = prefs.getLong(REVISION, 0L)
            var policy = prefs.getLong(POLICY, 0L)
            val changedPolicy = prefs.getString(INPUT, null) != policyInput
            if (revision <= 0L || changedPolicy || prefs.getString(FINGERPRINT, null) != fingerprint) {
                check(revision < Long.MAX_VALUE && policy < Long.MAX_VALUE)
                revision = revision.coerceAtLeast(0L) + 1L
                if (changedPolicy || policy <= 0L) policy = policy.coerceAtLeast(0L) + 1L
                check(prefs.edit().putString(INCARNATION, incarnation).putLong(REVISION, revision)
                    .putLong(POLICY, policy).putString(INPUT, policyInput).putString(FINGERPRINT, fingerprint).commit())
            }
            AuthorityPublication(PublicationIdentity(incarnation, consent.consentRevision, policy, revision, fingerprint),
                decision, values, consent.isAcceptancePending, direct)
        }.getOrElse { storageFault = true; null }
    }

    fun matches(context: Context, publication: AuthorityPublication): Boolean =
        current(context)?.identity == publication.identity

    /** 现有管理器发布器也从同一份当前内容取样，禁止发布旧的接受意图。 */
    fun forPublication(context: Context, decision: UserTermsDecision, values: Map<String, Any>): AuthorityPublication? =
        current(context)?.takeIf { it.decision == decision && it.values == values }
}
