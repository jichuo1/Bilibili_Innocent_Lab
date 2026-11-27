package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import android.content.Context
import android.os.UserManager
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.io.IOException
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
    val directAllowed: Boolean,
    val noRootRevision: Long = 0L
) {
    fun document(): Map<String, Any> = RemoteHookConfigContract.encode(
        generation = identity.snapshotRevision, moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
        deliveryEnabled = true, noRootRevision = 0L, decision = decision, values = values)

    /** NPatch 文档必须绑定本次本地快照；标准发布器继续使用 document()。 */
    fun noRootDocument(): Map<String, Any> {
        check(noRootRevision > 0L)
        return RemoteHookConfigContract.encode(
            generation = identity.snapshotRevision, moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = true, noRootRevision = noRootRevision, decision = decision, values = values)
    }
}

/**
 * 条款文件里的派生发布记录，不是第二份授权决定。
 * 每次发放许可都在同一条款锁内重读决定、模式与完整设置；旧记录不匹配时先持久化新记录。
 * 跨载体按内容身份匹配，不比较各管理器自己的时间戳 generation。
 */
internal object PublicationAuthorityStore {
    @Volatile private var storageFault = false
    @Volatile private var transientStorageFault = false
    private const val INCARNATION = "publication_incarnation"
    private const val REVISION = "publication_revision"
    private const val POLICY = "publication_policy_revision"
    private const val INPUT = "publication_policy_input"
    private const val FINGERPRINT = "publication_content_fingerprint"

    fun stopGrants() { storageFault = true }

    internal fun resetFaultStateForTest() {
        UserTermsConsentStore.withAuthorityLock {
            storageFault = false
            transientStorageFault = false
        }
    }

    internal fun isStorageFaultedForTest(): Boolean = storageFault
    internal fun isTransientFaultedForTest(): Boolean = transientStorageFault

    fun clearStorageFaultIfHealthy(context: Context): Boolean =
        clearStorageFaultIfHealthy(context, ::isUserUnlocked, ::defaultStorageProbe)

    internal fun clearStorageFaultIfHealthy(
        context: Context,
        userUnlocked: (Context) -> Boolean = ::isUserUnlocked,
        storageProbe: (Context) -> Boolean = ::defaultStorageProbe
    ): Boolean = UserTermsConsentStore.withAuthorityLock {
        if (storageFault) return@withAuthorityLock false
        val appContext = safeAppContext(context)
        if (!runCatching { userUnlocked(appContext) }.getOrDefault(true)) return@withAuthorityLock false
        if (!transientStorageFault) return@withAuthorityLock true
        val healthy = runCatching { storageProbe(appContext) }.getOrDefault(false)
        if (healthy) {
            transientStorageFault = false
        }
        healthy
    }

    fun current(context: Context): AuthorityPublication? =
        current(context, ::isUserUnlocked, ::defaultStorageProbe)

    internal fun current(
        context: Context,
        userUnlocked: (Context) -> Boolean,
        storageProbe: (Context) -> Boolean
    ): AuthorityPublication? = UserTermsConsentStore.withAuthorityLock {
        if (storageFault) return@withAuthorityLock null
        val appContext = safeAppContext(context)
        if (!runCatching { userUnlocked(appContext) }.getOrDefault(true)) {
            // Direct Boot 未解锁前仅 fail-closed 返回 null，不锁死解锁后的读取。
            return@withAuthorityLock null
        }
        if (transientStorageFault && !clearStorageFaultIfHealthy(appContext, userUnlocked, storageProbe)) {
            return@withAuthorityLock null
        }
        try {
            val consent = UserTermsConsentStore.readStateOrInitialize(appContext)
            if (consent.consentRevision <= 0L) return@withAuthorityLock null
            val noRootSelected = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore.isDesiredEnabled(appContext)
            val bridge = appContext.modulePreferences()
            val raw = bridge.all
            val decision = consent.requestedRemoteDecision
            val pluginIndexDigest = if (decision.isAuthorized) {
                NoRootSupportStore.resolveCurrentPluginDigest(appContext)
            } else {
                ""
            }
            // 本地权威记录不等待 Manager；在同一授权锁内构造当前快照，关闭/条款变化会使租约失效。
            val noRootSnapshot = if (noRootSelected) NoRootSupportStore.upsertEnabledSnapshot(appContext, bridge)
                ?: return@withAuthorityLock null else null
            val values = if (noRootSnapshot != null) {
                NPatchRemoteGateway.resolveRemoteValues(noRootSnapshot, raw, pluginIndexDigest)
            } else {
                RemoteHookConfigContract.resolveSourceValues(
                    HashMap<String, Any>().apply {
                        raw.forEach { (key, value) -> if (value != null) put(key, value) }
                        put(RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST, pluginIndexDigest)
                    }
                )
            }
            val fingerprint = RemoteHookConfigContract.contentFingerprint(BuildConfig.VERSION_CODE.toLong(), decision, values)
            val direct = CommunicationCompatibilityStore.isEnabled(appContext)
            val policyInput = "${consent.consentRevision}:${CommunicationCompatibilityStore.epoch(appContext)}:$direct:$noRootSelected:${noRootSnapshot?.revision ?: 0L}:$pluginIndexDigest"
            val prefs = appContext.getSharedPreferences(UserTermsConsentStore.PREF_FILE, Context.MODE_PRIVATE)
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
                decision, values, consent.isAcceptancePending, direct, noRootSnapshot?.revision ?: 0L)
        } catch (throwable: Throwable) {
            val unlocked = runCatching { userUnlocked(appContext) }.getOrDefault(true)
            if (isTransientStorageThrowable(throwable, unlocked)) {
                transientStorageFault = true
            } else {
                storageFault = true
            }
            null
        }
    }

    internal fun isTransientStorageThrowable(throwable: Throwable, userUnlocked: Boolean): Boolean {
        if (!userUnlocked) return true
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 16) {
            if (current is IOException) return true
            if (current is IllegalStateException) {
                val message = current.message.orEmpty()
                if (message.contains("credential encrypted storage", ignoreCase = true) ||
                    message.contains("user is unlocked", ignoreCase = true)
                ) {
                    return true
                }
            }
            current = current.cause
            depth++
        }
        return false
    }

    internal fun isUserUnlocked(context: Context): Boolean {
        val appContext = safeAppContext(context)
        val manager = runCatching { appContext.getSystemService(UserManager::class.java) }.getOrNull()
            ?: runCatching { appContext.getSystemService(Context.USER_SERVICE) as? UserManager }.getOrNull()
            ?: return true
        return runCatching { manager.isUserUnlocked }.getOrDefault(true)
    }

    private fun defaultStorageProbe(context: Context): Boolean {
        val appContext = safeAppContext(context)
        val prefs = appContext.getSharedPreferences(UserTermsConsentStore.PREF_FILE, Context.MODE_PRIVATE)
        prefs.all
        appContext.modulePreferences().all
        return true
    }

    private fun safeAppContext(context: Context): Context =
        runCatching { context.applicationContext }.getOrNull() ?: context

    fun matches(context: Context, publication: AuthorityPublication): Boolean =
        current(context)?.identity == publication.identity
}
