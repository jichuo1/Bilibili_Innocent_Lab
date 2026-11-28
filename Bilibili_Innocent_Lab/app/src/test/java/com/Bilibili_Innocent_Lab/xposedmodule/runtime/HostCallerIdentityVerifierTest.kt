package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostCallerIdentityVerifierTest {

    @Before
    fun setUp() {
        HostCallerIdentityVerifier.resetAuditForTests()
    }

    @After
    fun tearDown() {
        HostCallerIdentityVerifier.resetAuditForTests()
    }

    private inline fun runIsolated(block: () -> Unit) {
        HostCallerIdentityVerifier.resetAuditForTests()
        try {
            block()
        } finally {
            HostCallerIdentityVerifier.resetAuditForTests()
        }
    }

    @Test
    fun verifyHostUidAcceptsAuthorizedUsersAndRejectsMismatchedCallers() {
        runIsolated { sameUserExactUidAllowedWithoutCrossUserAudit() }
        runIsolated { sameUserOtherUidStrictlyRejected() }
        runIsolated { crossUserCloneAndWorkProfileWithMatchingAppIdAllowedAndAudited() }
        runIsolated { crossUserIsolatedProcessAndMismatchedAppIdAndNonAppUidRejected() }
    }

    @Test
    fun verifyProviderAndPendingIntentEnforcePrivilegeAndSenderConsistency() {
        runIsolated { privilegedShellOnlyAllowedWhenExplicitlyEnabledForReadProvider() }
        runIsolated { pendingIntentVerificationEnforcesSenderConsistencyAndCrossUserClone() }
    }

    private fun sameUserExactUidAllowedWithoutCrossUserAudit() {
        val result = HostCallerIdentityVerifier.verifyHostUid(
            callerUid = 10_250,
            primaryHostUid = 10_250,
            callerPackages = listOf(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE),
            auditTag = "test_same_user"
        )
        assertTrue(result.allowed)
        assertFalse(result.isCrossUser)
        assertEquals(0, result.callerUserId)
        assertEquals(10_250, result.callerAppId)
        assertEquals(0L, HostCallerIdentityVerifier.crossUserAuditCount())
        assertNull(HostCallerIdentityVerifier.lastCrossUserAudit())
    }

    private fun sameUserOtherUidStrictlyRejected() {
        val result = HostCallerIdentityVerifier.verifyHostUid(
            callerUid = 10_086,
            primaryHostUid = 10_250,
            auditTag = "test_same_user_other"
        )
        assertFalse(result.allowed)
        assertEquals(
            HostCallerIdentityVerifier.RejectionReason.SAME_USER_UID_MISMATCH,
            result.reason
        )
        assertEquals(0L, HostCallerIdentityVerifier.crossUserAuditCount())
    }

    private fun crossUserCloneAndWorkProfileWithMatchingAppIdAllowedAndAudited() {
        val recorded = mutableListOf<HostCallerIdentityVerifier.CrossUserAuditRecord>()
        HostCallerIdentityVerifier.auditRecorder = { recorded += it }

        // 小米双开 userId=999
        val miuiClone = HostCallerIdentityVerifier.verifyHostUid(
            callerUid = 999 * 100_000 + 10_250,
            primaryHostUid = 10_250,
            auditTag = "miui_clone"
        )
        assertTrue(miuiClone.allowed)
        assertTrue(miuiClone.isCrossUser)
        assertEquals(999, miuiClone.callerUserId)
        assertEquals(10_250, miuiClone.callerAppId)

        // Android 工作资料 userId=10
        val workProfile = HostCallerIdentityVerifier.verifyHostUid(
            callerUid = 10 * 100_000 + 10_250,
            primaryHostUid = 10_250,
            callerPackages = listOf(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE),
            auditTag = "work_profile"
        )
        assertTrue(workProfile.allowed)
        assertTrue(workProfile.isCrossUser)
        assertEquals(10, workProfile.callerUserId)
        assertEquals(2L, HostCallerIdentityVerifier.crossUserAuditCount())
        assertEquals(2, recorded.size)
        assertNotNull(HostCallerIdentityVerifier.lastCrossUserAudit())
        assertEquals("work_profile", HostCallerIdentityVerifier.lastCrossUserAudit()?.auditTag)
    }

    private fun crossUserIsolatedProcessAndMismatchedAppIdAndNonAppUidRejected() {
        // 隔离进程 UID (90_000..99_999)
        val isolatedUser0 = HostCallerIdentityVerifier.verifyHostUid(90_123, 10_250)
        assertFalse(isolatedUser0.allowed)
        assertEquals(HostCallerIdentityVerifier.RejectionReason.ISOLATED_PROCESS, isolatedUser0.reason)

        val isolatedClone = HostCallerIdentityVerifier.verifyHostUid(999 * 100_000 + 95_000, 10_250)
        assertFalse(isolatedClone.allowed)
        assertEquals(HostCallerIdentityVerifier.RejectionReason.ISOLATED_PROCESS, isolatedClone.reason)

        // 同用户隔离伪装也拒绝
        val isolatedPrimaryClone = HostCallerIdentityVerifier.verifyHostUid(999 * 100_000 + 95_000, 95_000)
        assertFalse(isolatedPrimaryClone.allowed)
        assertEquals(HostCallerIdentityVerifier.RejectionReason.ISOLATED_PROCESS, isolatedPrimaryClone.reason)

        // 跨用户不同 appId 拒绝
        val otherAppClone = HostCallerIdentityVerifier.verifyHostUid(10 * 100_000 + 10_300, 10_250)
        assertFalse(otherAppClone.allowed)
        assertEquals(HostCallerIdentityVerifier.RejectionReason.APP_ID_MISMATCH, otherAppClone.reason)

        // 跨用户非普通应用范围 appId (< 10_000 或 > 19_999) 拒绝
        val systemClone = HostCallerIdentityVerifier.verifyHostUid(10 * 100_000 + 1_000, 1_000)
        assertFalse(systemClone.allowed)
        assertEquals(HostCallerIdentityVerifier.RejectionReason.APP_ID_OUT_OF_RANGE, systemClone.reason)

        val highRangeClone = HostCallerIdentityVerifier.verifyHostUid(10 * 100_000 + 20_005, 20_005)
        assertFalse(highRangeClone.allowed)
        assertEquals(HostCallerIdentityVerifier.RejectionReason.APP_ID_OUT_OF_RANGE, highRangeClone.reason)

        // 跨用户包列表明确不匹配拒绝
        val wrongPkg = HostCallerIdentityVerifier.verifyHostUid(
            callerUid = 10 * 100_000 + 10_250,
            primaryHostUid = 10_250,
            callerPackages = listOf("com.example.other")
        )
        assertFalse(wrongPkg.allowed)
        assertEquals(HostCallerIdentityVerifier.RejectionReason.PACKAGE_MISMATCH, wrongPkg.reason)

        // 主用户包在 user 10 而 caller 在 user 0 (callerUserId == 0) 也拒绝
        val user0ToWork = HostCallerIdentityVerifier.verifyHostUid(10_250, 10 * 100_000 + 10_250)
        assertFalse(user0ToWork.allowed)
        assertEquals(HostCallerIdentityVerifier.RejectionReason.INVALID_USER_ID, user0ToWork.reason)

        assertEquals(0L, HostCallerIdentityVerifier.crossUserAuditCount())
    }

    private fun privilegedShellOnlyAllowedWhenExplicitlyEnabledForReadProvider() {
        // 高敏写入接口仅调用 verifyHostUid：shell/root/self 一律拒绝
        for (privileged in listOf(0, 1000, 2000)) {
            assertFalse(HostCallerIdentityVerifier.verifyHostUid(privileged, 10_250).allowed)
        }
        // 只读 Provider 允许 self/root/system/shell
        assertTrue(
            HostCallerIdentityVerifier.verifyProviderUid(
                callerUid = 2000,
                selfUid = 10_999,
                primaryHostUid = 10_250,
                allowSelf = true,
                allowPrivilegedShell = true
            ).allowed
        )
        // HostBackgroundProvider 类仅允许 self + 宿主，不允许 shell/root
        assertFalse(
            HostCallerIdentityVerifier.verifyProviderUid(
                callerUid = 2000,
                selfUid = 10_999,
                primaryHostUid = 10_250,
                allowSelf = true,
                allowPrivilegedShell = false
            ).allowed
        )
        assertTrue(
            HostCallerIdentityVerifier.verifyProviderUid(
                callerUid = 10_999,
                selfUid = 10_999,
                primaryHostUid = 10_250,
                allowSelf = true,
                allowPrivilegedShell = false
            ).allowed
        )
    }

    private fun pendingIntentVerificationEnforcesSenderConsistencyAndCrossUserClone() {
        val target = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE
        // Android 14+ 发送者与 creatorUid 一致（含分身）放行
        val cloneAllowed = HostCallerIdentityVerifier.verifyPendingIntentIdentity(
            creatorPackage = target,
            creatorUid = 999 * 100_000 + 10_250,
            primaryHostUid = 10_250,
            sentFromPackage = target,
            sentFromUid = 999 * 100_000 + 10_250,
            enforceSenderMatch = true,
            requirePrimaryHostUid = true,
            auditTag = "pi_clone"
        )
        assertTrue(cloneAllowed.allowed)
        assertTrue(cloneAllowed.isCrossUser)

        // 发送者 UID 与 creatorUid 不一致拒绝（防止转交重放）
        val mismatchedSender = HostCallerIdentityVerifier.verifyPendingIntentIdentity(
            creatorPackage = target,
            creatorUid = 10_250,
            primaryHostUid = 10_250,
            sentFromPackage = target,
            sentFromUid = 10_300,
            enforceSenderMatch = true,
            requirePrimaryHostUid = true
        )
        assertFalse(mismatchedSender.allowed)

        // 包可见性隔离且 requirePrimaryHostUid = true 拒绝；requirePrimaryHostUid = false 时放行合法应用 UID
        assertFalse(
            HostCallerIdentityVerifier.verifyPendingIntentIdentity(
                creatorPackage = target,
                creatorUid = 10_250,
                primaryHostUid = null,
                requirePrimaryHostUid = true
            ).allowed
        )
        assertTrue(
            HostCallerIdentityVerifier.verifyPendingIntentIdentity(
                creatorPackage = target,
                creatorUid = 10_250,
                primaryHostUid = null,
                requirePrimaryHostUid = false
            ).allowed
        )
        assertFalse(
            HostCallerIdentityVerifier.verifyPendingIntentIdentity(
                creatorPackage = target,
                creatorUid = 90_001,
                primaryHostUid = null,
                requirePrimaryHostUid = false
            ).allowed
        )
    }
}
