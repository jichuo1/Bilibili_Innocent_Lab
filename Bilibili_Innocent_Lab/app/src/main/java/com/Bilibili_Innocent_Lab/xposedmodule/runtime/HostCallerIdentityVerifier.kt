package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一的跨进程宿主调用方身份核验器。
 *
 * - 同用户内严格要求 `callerUid == primaryHostUid`；同用户内其他 UID 一律拒绝。
 * - 跨用户分身 / 工作资料场景下，仅当 `callerUserId > 0 && callerAppId == primaryAppId &&
 *   callerAppId in 10_000..19_999`（且排除隔离进程 `90_000..99_999`）时放行并记录可审计的跨用户标记。
 * - 高敏写入与授权端点绝不复用 shell/root/self 权限；特权只读通道通过 [verifyProviderUid] 显式声明边界。
 */
internal object HostCallerIdentityVerifier {
    const val PER_USER_RANGE = 100_000
    const val FIRST_APPLICATION_UID = 10_000
    const val LAST_APPLICATION_UID = 19_999
    const val FIRST_ISOLATED_UID = 90_000
    const val LAST_ISOLATED_UID = 99_999

    private val PRIVILEGED_SHELL_UIDS = setOf(
        0, // Process.ROOT_UID
        1000, // Process.SYSTEM_UID
        2000 // Process.SHELL_UID
    )

    enum class RejectionReason {
        NONE,
        INVALID_UID,
        MISSING_PRIMARY_HOST,
        ISOLATED_PROCESS,
        SAME_USER_UID_MISMATCH,
        INVALID_USER_ID,
        APP_ID_MISMATCH,
        APP_ID_OUT_OF_RANGE,
        PACKAGE_MISMATCH
    }

    data class VerificationResult(
        val allowed: Boolean,
        val callerUid: Int,
        val primaryHostUid: Int?,
        val isCrossUser: Boolean = false,
        val callerUserId: Int = -1,
        val primaryUserId: Int = -1,
        val callerAppId: Int = -1,
        val isSelfOrPrivileged: Boolean = false,
        val reason: RejectionReason = RejectionReason.NONE
    )

    data class CrossUserAuditRecord(
        val callerUid: Int,
        val primaryHostUid: Int?,
        val callerUserId: Int,
        val callerAppId: Int,
        val expectedPackage: String,
        val auditTag: String
    )

    private val crossUserAuditCount = AtomicLong(0L)
    @Volatile
    private var lastCrossUserAudit: CrossUserAuditRecord? = null
    @Volatile
    internal var auditRecorder: ((CrossUserAuditRecord) -> Unit)? = null

    fun crossUserAuditCount(): Long = crossUserAuditCount.get()

    fun lastCrossUserAudit(): CrossUserAuditRecord? = lastCrossUserAudit

    internal fun resetAuditForTests() {
        crossUserAuditCount.set(0L)
        lastCrossUserAudit = null
        auditRecorder = null
    }

    /** 纯 JVM 可测试的核心宿主 UID 校验（含同用户精确校验与双开/工作资料跨用户校验）。 */
    fun verifyHostUid(
        callerUid: Int,
        primaryHostUid: Int?,
        callerPackages: Collection<String>? = null,
        expectedPackage: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
        auditTag: String = "host"
    ): VerificationResult {
        if (callerUid <= 0) {
            return VerificationResult(
                allowed = false,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                reason = RejectionReason.INVALID_UID
            )
        }
        val callerAppId = callerUid % PER_USER_RANGE
        val callerUserId = callerUid / PER_USER_RANGE
        if (callerAppId in FIRST_ISOLATED_UID..LAST_ISOLATED_UID) {
            return VerificationResult(
                allowed = false,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                callerUserId = callerUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.ISOLATED_PROCESS
            )
        }
        if (primaryHostUid == null || primaryHostUid <= 0) {
            return VerificationResult(
                allowed = false,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                callerUserId = callerUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.MISSING_PRIMARY_HOST
            )
        }
        val primaryAppId = primaryHostUid % PER_USER_RANGE
        val primaryUserId = primaryHostUid / PER_USER_RANGE
        if (primaryAppId in FIRST_ISOLATED_UID..LAST_ISOLATED_UID) {
            return VerificationResult(
                allowed = false,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                callerUserId = callerUserId,
                primaryUserId = primaryUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.ISOLATED_PROCESS
            )
        }
        if (callerPackages != null && callerPackages.isNotEmpty() && expectedPackage !in callerPackages) {
            return VerificationResult(
                allowed = false,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                callerUserId = callerUserId,
                primaryUserId = primaryUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.PACKAGE_MISMATCH
            )
        }
        if (callerUserId == primaryUserId) {
            return if (callerUid == primaryHostUid) {
                VerificationResult(
                    allowed = true,
                    callerUid = callerUid,
                    primaryHostUid = primaryHostUid,
                    isCrossUser = false,
                    callerUserId = callerUserId,
                    primaryUserId = primaryUserId,
                    callerAppId = callerAppId,
                    reason = RejectionReason.NONE
                )
            } else {
                VerificationResult(
                    allowed = false,
                    callerUid = callerUid,
                    primaryHostUid = primaryHostUid,
                    isCrossUser = false,
                    callerUserId = callerUserId,
                    primaryUserId = primaryUserId,
                    callerAppId = callerAppId,
                    reason = RejectionReason.SAME_USER_UID_MISMATCH
                )
            }
        }
        if (callerUserId <= 0) {
            return VerificationResult(
                allowed = false,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                callerUserId = callerUserId,
                primaryUserId = primaryUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.INVALID_USER_ID
            )
        }
        if (callerAppId !in FIRST_APPLICATION_UID..LAST_APPLICATION_UID) {
            return VerificationResult(
                allowed = false,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                callerUserId = callerUserId,
                primaryUserId = primaryUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.APP_ID_OUT_OF_RANGE
            )
        }
        if (callerAppId != primaryAppId) {
            return VerificationResult(
                allowed = false,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                callerUserId = callerUserId,
                primaryUserId = primaryUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.APP_ID_MISMATCH
            )
        }
        recordCrossUserAllow(
            CrossUserAuditRecord(
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                callerUserId = callerUserId,
                callerAppId = callerAppId,
                expectedPackage = expectedPackage,
                auditTag = auditTag
            )
        )
        return VerificationResult(
            allowed = true,
            callerUid = callerUid,
            primaryHostUid = primaryHostUid,
            isCrossUser = true,
            callerUserId = callerUserId,
            primaryUserId = primaryUserId,
            callerAppId = callerAppId,
            reason = RejectionReason.NONE
        )
    }

    /** 纯 JVM 可测试的 Provider 调用方校验，明确分离自身/系统只读特权与宿主核验。 */
    fun verifyProviderUid(
        callerUid: Int,
        selfUid: Int,
        primaryHostUid: Int?,
        callerPackages: Collection<String>? = null,
        expectedPackage: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
        allowSelf: Boolean = true,
        allowPrivilegedShell: Boolean = false,
        allowPackageListFallbackWhenPrimaryMissing: Boolean = false,
        auditTag: String = "provider"
    ): VerificationResult {
        if (allowSelf && selfUid > 0 && callerUid == selfUid) {
            return VerificationResult(
                allowed = true,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                isSelfOrPrivileged = true,
                reason = RejectionReason.NONE
            )
        }
        if (allowPrivilegedShell && callerUid in PRIVILEGED_SHELL_UIDS) {
            return VerificationResult(
                allowed = true,
                callerUid = callerUid,
                primaryHostUid = primaryHostUid,
                isSelfOrPrivileged = true,
                reason = RejectionReason.NONE
            )
        }
        val hostResult = verifyHostUid(
            callerUid = callerUid,
            primaryHostUid = primaryHostUid,
            callerPackages = callerPackages,
            expectedPackage = expectedPackage,
            auditTag = auditTag
        )
        if (hostResult.allowed) return hostResult
        if (allowPackageListFallbackWhenPrimaryMissing &&
            hostResult.reason == RejectionReason.MISSING_PRIMARY_HOST &&
            callerPackages != null &&
            expectedPackage in callerPackages
        ) {
            val callerAppId = callerUid % PER_USER_RANGE
            val callerUserId = callerUid / PER_USER_RANGE
            if (callerAppId in FIRST_APPLICATION_UID..LAST_APPLICATION_UID &&
                callerAppId !in FIRST_ISOLATED_UID..LAST_ISOLATED_UID
            ) {
                val crossUser = callerUserId > 0
                if (crossUser) {
                    recordCrossUserAllow(
                        CrossUserAuditRecord(
                            callerUid = callerUid,
                            primaryHostUid = primaryHostUid,
                            callerUserId = callerUserId,
                            callerAppId = callerAppId,
                            expectedPackage = expectedPackage,
                            auditTag = auditTag
                        )
                    )
                }
                return VerificationResult(
                    allowed = true,
                    callerUid = callerUid,
                    primaryHostUid = null,
                    isCrossUser = crossUser,
                    callerUserId = callerUserId,
                    primaryUserId = -1,
                    callerAppId = callerAppId,
                    reason = RejectionReason.NONE
                )
            }
        }
        return hostResult
    }

    /** 纯 JVM 可测试的 PendingIntent 证明核验，支持包可见性隔离回退与 Android 14+ 发送者一致性核验。 */
    fun verifyPendingIntentIdentity(
        creatorPackage: String?,
        creatorUid: Int,
        primaryHostUid: Int?,
        callerPackages: Collection<String>? = null,
        expectedPackage: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
        sentFromPackage: String? = null,
        sentFromUid: Int = -1,
        enforceSenderMatch: Boolean = false,
        requirePrimaryHostUid: Boolean = false,
        auditTag: String = "pending_intent"
    ): VerificationResult {
        if (creatorPackage != expectedPackage) {
            return VerificationResult(
                allowed = false,
                callerUid = creatorUid,
                primaryHostUid = primaryHostUid,
                reason = RejectionReason.PACKAGE_MISMATCH
            )
        }
        if (enforceSenderMatch) {
            if (sentFromPackage != expectedPackage || sentFromUid <= 0 || sentFromUid != creatorUid) {
                return VerificationResult(
                    allowed = false,
                    callerUid = creatorUid,
                    primaryHostUid = primaryHostUid,
                    reason = RejectionReason.SAME_USER_UID_MISMATCH
                )
            }
        }
        if (primaryHostUid != null) {
            return verifyHostUid(
                callerUid = creatorUid,
                primaryHostUid = primaryHostUid,
                callerPackages = callerPackages,
                expectedPackage = expectedPackage,
                auditTag = auditTag
            )
        }
        if (requirePrimaryHostUid || creatorUid <= 0) {
            return VerificationResult(
                allowed = false,
                callerUid = creatorUid,
                primaryHostUid = null,
                reason = if (creatorUid <= 0) RejectionReason.INVALID_UID else RejectionReason.MISSING_PRIMARY_HOST
            )
        }
        val callerAppId = creatorUid % PER_USER_RANGE
        val callerUserId = creatorUid / PER_USER_RANGE
        if (callerAppId in FIRST_ISOLATED_UID..LAST_ISOLATED_UID) {
            return VerificationResult(
                allowed = false,
                callerUid = creatorUid,
                primaryHostUid = null,
                callerUserId = callerUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.ISOLATED_PROCESS
            )
        }
        if (callerAppId !in FIRST_APPLICATION_UID..LAST_APPLICATION_UID) {
            return VerificationResult(
                allowed = false,
                callerUid = creatorUid,
                primaryHostUid = null,
                callerUserId = callerUserId,
                callerAppId = callerAppId,
                reason = RejectionReason.APP_ID_OUT_OF_RANGE
            )
        }
        if (callerUserId > 0) {
            recordCrossUserAllow(
                CrossUserAuditRecord(
                    callerUid = creatorUid,
                    primaryHostUid = null,
                    callerUserId = callerUserId,
                    callerAppId = callerAppId,
                    expectedPackage = expectedPackage,
                    auditTag = auditTag
                )
            )
        }
        return VerificationResult(
            allowed = true,
            callerUid = creatorUid,
            primaryHostUid = null,
            isCrossUser = callerUserId > 0,
            callerUserId = callerUserId,
            primaryUserId = -1,
            callerAppId = callerAppId,
            reason = RejectionReason.NONE
        )
    }

    fun verifyHostCaller(
        context: Context,
        callerUid: Int,
        expectedPackage: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
        auditTag: String = "host"
    ): VerificationResult {
        val primaryHostUid = resolvePrimaryHostUid(context, expectedPackage)
        val callerPackages = resolvePackagesForUid(context, callerUid)
        return verifyHostUid(
            callerUid = callerUid,
            primaryHostUid = primaryHostUid,
            callerPackages = callerPackages,
            expectedPackage = expectedPackage,
            auditTag = auditTag
        )
    }

    fun verifyProviderCaller(
        context: Context,
        callerUid: Int,
        expectedPackage: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
        allowSelf: Boolean = true,
        allowPrivilegedShell: Boolean = false,
        allowPackageListFallbackWhenPrimaryMissing: Boolean = false,
        auditTag: String = "provider"
    ): VerificationResult {
        val selfUid = Process.myUid()
        if (allowSelf && callerUid == selfUid) {
            return VerificationResult(
                allowed = true,
                callerUid = callerUid,
                primaryHostUid = null,
                isSelfOrPrivileged = true,
                reason = RejectionReason.NONE
            )
        }
        if (allowPrivilegedShell && callerUid in PRIVILEGED_SHELL_UIDS) {
            return VerificationResult(
                allowed = true,
                callerUid = callerUid,
                primaryHostUid = null,
                isSelfOrPrivileged = true,
                reason = RejectionReason.NONE
            )
        }
        val primaryHostUid = resolvePrimaryHostUid(context, expectedPackage)
        val callerPackages = resolvePackagesForUid(context, callerUid)
        return verifyProviderUid(
            callerUid = callerUid,
            selfUid = selfUid,
            primaryHostUid = primaryHostUid,
            callerPackages = callerPackages,
            expectedPackage = expectedPackage,
            allowSelf = allowSelf,
            allowPrivilegedShell = allowPrivilegedShell,
            allowPackageListFallbackWhenPrimaryMissing = allowPackageListFallbackWhenPrimaryMissing,
            auditTag = auditTag
        )
    }

    fun verifyPendingIntentCaller(
        context: Context,
        proof: PendingIntent,
        expectedPackage: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
        sentFromPackage: String? = null,
        sentFromUid: Int = -1,
        enforceSenderMatch: Boolean = false,
        requirePrimaryHostUid: Boolean = false,
        auditTag: String = "pending_intent"
    ): VerificationResult {
        val primaryHostUid = resolvePrimaryHostUid(context, expectedPackage)
        val callerPackages = resolvePackagesForUid(context, proof.creatorUid)
        return verifyPendingIntentIdentity(
            creatorPackage = proof.creatorPackage,
            creatorUid = proof.creatorUid,
            primaryHostUid = primaryHostUid,
            callerPackages = callerPackages,
            expectedPackage = expectedPackage,
            sentFromPackage = sentFromPackage,
            sentFromUid = sentFromUid,
            enforceSenderMatch = enforceSenderMatch,
            requirePrimaryHostUid = requirePrimaryHostUid,
            auditTag = auditTag
        )
    }

    internal fun resolvePrimaryHostUid(
        context: Context,
        expectedPackage: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE
    ): Int? = runCatching {
        context.packageManager.getApplicationInfo(expectedPackage, 0).uid
    }.getOrNull()

    internal fun resolvePrimaryHostPackageInfo(
        context: Context,
        expectedPackage: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE
    ): PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(expectedPackage, 0)
    }.getOrNull()

    internal fun resolvePackagesForUid(context: Context, uid: Int): List<String>? = runCatching {
        context.packageManager.getPackagesForUid(uid)?.toList()
    }.getOrNull()

    private fun recordCrossUserAllow(record: CrossUserAuditRecord) {
        crossUserAuditCount.incrementAndGet()
        lastCrossUserAudit = record
        auditRecorder?.invoke(record)
        runCatching {
            Log.i(
                "BilibiliInnocentLab",
                "[BIL] 跨用户宿主调用已放行(tag=${record.auditTag}, user=${record.callerUserId}, appId=${record.callerAppId})"
            )
        }
    }
}
