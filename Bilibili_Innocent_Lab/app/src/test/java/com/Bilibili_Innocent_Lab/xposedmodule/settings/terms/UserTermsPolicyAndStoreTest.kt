package com.Bilibili_Innocent_Lab.xposedmodule.settings.terms

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.receiver.TermsMirrorFlushCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishEvent
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.StandardPublisherPolicy
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTermsPolicyAndStoreTest {

    @Test
    fun consentStorePersistsDecisionsAndConsentVersion() {
        UserTermsConsentStoreCases().verifyAllCases()
    }

    @Test
    fun publicationAuthorityStoreSeparatesTransientFromPersistentFaults() {
        TermsMirrorFlushAndPublicationAuthorityCases().`publication authority store distinguishes direct boot transient faults from persistent faults`()
    }

    @Test
    fun authorizationCoordinatorTracksServicePublishState() {
        UserTermsAuthorizationCoordinatorCases().verifyAllCases()
    }

    @Test
    fun gateDiagnosticsAndMirrorFlushWorkCohesively() {
        UserTermsGateDiagnosticsCases().verifyAllCases()
        TermsMirrorFlushAndPublicationAuthorityCases().verifyMirrorCases()
    }
}

private class UserTermsAuthorizationCoordinatorCases {

    fun `pending acceptance remains closed until a successful capable publication`() {
        assertEquals(
            UserTermsSyncState.IDLE,
            resolve(hasPending = false)
        )
        assertEquals(
            UserTermsSyncState.WAITING_FOR_SERVICE,
            resolve(frameworkConnected = false)
        )
        assertEquals(
            UserTermsSyncState.UNSUPPORTED,
            resolve(frameworkCapable = false)
        )
        assertEquals(
            UserTermsSyncState.SYNCING,
            resolve(publishPending = true)
        )
        assertEquals(
            UserTermsSyncState.FAILED,
            resolve(publishState = RemoteHookConfigPublishState.FAILED)
        )
        assertEquals(
            UserTermsSyncState.PENDING,
            resolve()
        )
    }

    fun `local finalization failure outranks remote progress`() {
        assertEquals(
            UserTermsSyncState.FAILED,
            resolve(
                localFailure = true,
                publishPending = true,
                publishState = RemoteHookConfigPublishState.PUBLISHING
            )
        )
    }

    fun `activity recreation is requested only for a real authorization transition`() {
        assertTrue(
            didUserTermsAuthorizationComplete(
                UserTermsDecision.UNDECIDED,
                UserTermsDecision.ACCEPTED
            )
        )
        assertTrue(
            didUserTermsAuthorizationComplete(
                UserTermsDecision.DECLINED,
                UserTermsDecision.LEGACY_EXEMPT
            )
        )
        assertFalse(
            didUserTermsAuthorizationComplete(
                UserTermsDecision.ACCEPTED,
                UserTermsDecision.ACCEPTED
            )
        )
        assertFalse(
            didUserTermsAuthorizationComplete(
                UserTermsDecision.LEGACY_EXEMPT,
                UserTermsDecision.ACCEPTED
            )
        )
    }

    private fun resolve(
        hasPending: Boolean = true,
        localFailure: Boolean = false,
        publishPending: Boolean = false,
        publishState: RemoteHookConfigPublishState = RemoteHookConfigPublishState.READY,
        frameworkConnected: Boolean = true,
        frameworkCapable: Boolean = true
    ): UserTermsSyncState = resolveUserTermsSyncState(
        hasPendingAcceptance = hasPending,
        hasLocalFailure = localFailure,
        publishPending = publishPending,
        publishState = publishState,
        frameworkConnected = frameworkConnected,
        frameworkCapable = frameworkCapable
    )

    fun verifyAllCases() {
        fun runIsolated(block: (UserTermsAuthorizationCoordinatorCases) -> Unit) {
            val s = UserTermsAuthorizationCoordinatorCases()
            block(s)
        }
        runIsolated { s -> s.`pending acceptance remains closed until a successful capable publication`() }
        runIsolated { s -> s.`local finalization failure outranks remote progress`() }
        runIsolated { s -> s.`activity recreation is requested only for a real authorization transition`() }
    }
}

private class UserTermsConsentStoreCases {

    fun `current terms version is a positive locked protocol value`() {
        assertEquals(2, UserTermsConsentStore.CURRENT_TERMS_VERSION)
        assertTrue(UserTermsConsentStore.CURRENT_TERMS_VERSION > 0)
        assertEquals(1_787_909_100_000L, UserTermsConsentStore.LEGACY_ROLLOUT_CUTOFF_EPOCH_MS)
    }

    fun `missing persisted decision delegates only to legacy inference`() {
        listOf(
            Triple(null, -1, false),
            Triple(UserTermsDecision.ACCEPTED.name, -1, false),
            Triple("CORRUPT", UserTermsConsentStore.CURRENT_TERMS_VERSION, false)
        ).forEach { (rawDecision, version, hasDecision) ->
            assertNull(
                UserTermsConsentStore.resolvePersistedDecision(
                    hasDecision = hasDecision,
                    rawDecision = rawDecision,
                    storedVersion = version
                )
            )
        }
    }

    fun `all valid persisted decisions round trip at the current version`() {
        UserTermsDecision.entries.forEach { decision ->
            assertEquals(
                decision,
                UserTermsConsentStore.resolvePersistedDecision(
                    hasDecision = true,
                    rawDecision = decision.name,
                    storedVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION
                )
            )
        }
    }

    fun `corrupt or blank persisted decisions fail closed`() {
        listOf(null, "", "accepted", "CORRUPT", "LEGACY").forEach { rawDecision ->
            assertEquals(
                UserTermsDecision.UNDECIDED,
                UserTermsConsentStore.resolvePersistedDecision(
                    hasDecision = true,
                    rawDecision = rawDecision,
                    storedVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION
                )
            )
        }
    }

    fun `records from another terms version require a new decision`() {
        val otherVersions = listOf(
            -1,
            0,
            UserTermsConsentStore.CURRENT_TERMS_VERSION - 1,
            UserTermsConsentStore.CURRENT_TERMS_VERSION + 1
        ).distinct()
        otherVersions.forEach { version ->
            UserTermsDecision.entries.forEach { decision ->
                assertEquals(
                    "version=$version decision=$decision",
                    UserTermsDecision.UNDECIDED,
                    UserTermsConsentStore.resolvePersistedDecision(
                        hasDecision = true,
                        rawDecision = decision.name,
                        storedVersion = version
                    )
                )
            }
        }
    }

    fun `only accepted and legacy exempt decisions authorize module hooks`() {
        val authorizationSnapshot = UserTermsDecision.entries.associateWith { it.isAuthorized }
        assertEquals(
            mapOf(
                UserTermsDecision.UNDECIDED to false,
                UserTermsDecision.ACCEPTED to true,
                UserTermsDecision.DECLINED to false,
                UserTermsDecision.LEGACY_EXEMPT to true
            ),
            authorizationSnapshot
        )
    }

    fun `pending acceptance stays local and keeps the previous decision unauthorized`() {
        val pending = UserTermsConsentStore.resolvePendingAcceptance(
            hasPending = true,
            storedTermsVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION,
            revision = 42L,
            rawPreviousDecision = UserTermsDecision.UNDECIDED.name,
            currentDecision = UserTermsDecision.UNDECIDED
        )
        val state = UserTermsConsentState(
            decision = UserTermsDecision.UNDECIDED,
            pendingAcceptance = pending
        )

        assertEquals(UserTermsPendingAcceptance(42L, UserTermsDecision.UNDECIDED), pending)
        assertTrue(state.isAcceptancePending)
        assertFalse(state.decision.isAuthorized)
        assertEquals(UserTermsDecision.ACCEPTED, state.requestedRemoteDecision)
    }

    fun `pending acceptance metadata fails closed when incomplete stale or inconsistent`() {
        val invalidRecords = listOf(
            UserTermsConsentStore.resolvePendingAcceptance(
                hasPending = false,
                storedTermsVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION,
                revision = 1L,
                rawPreviousDecision = UserTermsDecision.UNDECIDED.name,
                currentDecision = UserTermsDecision.UNDECIDED
            ),
            UserTermsConsentStore.resolvePendingAcceptance(
                hasPending = true,
                storedTermsVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION + 1,
                revision = 1L,
                rawPreviousDecision = UserTermsDecision.UNDECIDED.name,
                currentDecision = UserTermsDecision.UNDECIDED
            ),
            UserTermsConsentStore.resolvePendingAcceptance(
                hasPending = true,
                storedTermsVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION,
                revision = 0L,
                rawPreviousDecision = UserTermsDecision.UNDECIDED.name,
                currentDecision = UserTermsDecision.UNDECIDED
            ),
            UserTermsConsentStore.resolvePendingAcceptance(
                hasPending = true,
                storedTermsVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION,
                revision = 1L,
                rawPreviousDecision = UserTermsDecision.DECLINED.name,
                currentDecision = UserTermsDecision.UNDECIDED
            ),
            UserTermsConsentStore.resolvePendingAcceptance(
                hasPending = true,
                storedTermsVersion = UserTermsConsentStore.CURRENT_TERMS_VERSION,
                revision = 1L,
                rawPreviousDecision = UserTermsDecision.ACCEPTED.name,
                currentDecision = UserTermsDecision.ACCEPTED
            )
        )

        assertTrue(invalidRecords.all { it == null })
        assertEquals(
            UserTermsDecision.DECLINED,
            UserTermsConsentState(UserTermsDecision.DECLINED).requestedRemoteDecision
        )
    }

    fun `upgraded installations are exempt only during missing-state migration`() {
        val cutoff = 10_000L
        assertEquals(
            UserTermsDecision.LEGACY_EXEMPT,
            UserTermsConsentStore.inferInitialDecision(
                firstInstallTime = 1_000L,
                lastUpdateTime = 20_000L,
                prefsAliveTimestamp = null,
                rolloutCutoffEpochMs = cutoff
            )
        )
        assertEquals(
            UserTermsDecision.UNDECIDED,
            UserTermsConsentStore.inferInitialDecision(
                firstInstallTime = 1_000L,
                lastUpdateTime = 1_000L,
                prefsAliveTimestamp = null,
                rolloutCutoffEpochMs = cutoff
            )
        )
        assertEquals(
            UserTermsDecision.UNDECIDED,
            UserTermsConsentStore.inferInitialDecision(
                firstInstallTime = 2_000L,
                lastUpdateTime = 1_000L,
                prefsAliveTimestamp = null,
                rolloutCutoffEpochMs = cutoff
            )
        )
    }

    fun `installations at or after rollout never receive package time exemption`() {
        val cutoff = 10_000L
        listOf(cutoff, cutoff + 1L, cutoff + 5_000L).forEach { firstInstallTime ->
            assertEquals(
                UserTermsDecision.UNDECIDED,
                UserTermsConsentStore.inferInitialDecision(
                    firstInstallTime = firstInstallTime,
                    lastUpdateTime = firstInstallTime + 1_000L,
                    prefsAliveTimestamp = null,
                    rolloutCutoffEpochMs = cutoff
                )
            )
        }
    }

    fun `legacy prefs marker requires both an old install and an old marker`() {
        val cutoff = 10_000L
        assertEquals(
            UserTermsDecision.LEGACY_EXEMPT,
            UserTermsConsentStore.inferInitialDecision(
                firstInstallTime = cutoff - 1L,
                lastUpdateTime = cutoff - 1L,
                prefsAliveTimestamp = cutoff - 1L,
                rolloutCutoffEpochMs = cutoff
            )
        )
        listOf(
            Triple<Long?, Long?, Long?>(null, null, cutoff - 1L),
            Triple(cutoff, cutoff + 1L, cutoff - 1L),
            Triple(cutoff + 1L, cutoff + 2L, cutoff - 1L),
            Triple(cutoff - 1L, cutoff - 1L, cutoff),
            Triple(cutoff - 1L, cutoff - 1L, cutoff + 1L)
        ).forEach { (firstInstallTime, lastUpdateTime, prefsAliveTimestamp) ->
            assertEquals(
                UserTermsDecision.UNDECIDED,
                UserTermsConsentStore.inferInitialDecision(
                    firstInstallTime = firstInstallTime,
                    lastUpdateTime = lastUpdateTime,
                    prefsAliveTimestamp = prefsAliveTimestamp,
                    rolloutCutoffEpochMs = cutoff
                )
            )
        }
    }

    fun `invalid timestamps never create a legacy exemption`() {
        val cutoff = 10_000L
        listOf(
            Triple(null, null, null),
            Triple(0L, 0L, 0L),
            Triple(-1L, 2_000L, -1L),
            Triple(1_000L, null, 0L),
            Triple(null, 2_000L, 0L)
        ).forEach { (firstInstallTime, lastUpdateTime, prefsAliveTimestamp) ->
            assertEquals(
                UserTermsDecision.UNDECIDED,
                UserTermsConsentStore.inferInitialDecision(
                    firstInstallTime = firstInstallTime,
                    lastUpdateTime = lastUpdateTime,
                    prefsAliveTimestamp = prefsAliveTimestamp,
                    rolloutCutoffEpochMs = cutoff
                )
            )
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (UserTermsConsentStoreCases) -> Unit) {
            val s = UserTermsConsentStoreCases()
            block(s)
        }
        runIsolated { s -> s.`current terms version is a positive locked protocol value`() }
        runIsolated { s -> s.`missing persisted decision delegates only to legacy inference`() }
        runIsolated { s -> s.`all valid persisted decisions round trip at the current version`() }
        runIsolated { s -> s.`corrupt or blank persisted decisions fail closed`() }
        runIsolated { s -> s.`records from another terms version require a new decision`() }
        runIsolated { s -> s.`only accepted and legacy exempt decisions authorize module hooks`() }
        runIsolated { s -> s.`pending acceptance stays local and keeps the previous decision unauthorized`() }
        runIsolated { s -> s.`pending acceptance metadata fails closed when incomplete stale or inconsistent`() }
        runIsolated { s -> s.`upgraded installations are exempt only during missing-state migration`() }
        runIsolated { s -> s.`installations at or after rollout never receive package time exemption`() }
        runIsolated { s -> s.`legacy prefs marker requires both an old install and an old marker`() }
        runIsolated { s -> s.`invalid timestamps never create a legacy exemption`() }
    }
}

private class UserTermsGateDiagnosticsCases {

    fun `android uid encoding resolves primary and secondary users`() {
        assertEquals(0, androidUserIdFromUid(10_234))
        assertEquals(10, androidUserIdFromUid(1_010_234))
    }

    fun `same-user result remains unknown until target identity is visible`() {
        assertNull(resolveSameAndroidUser(moduleUserId = 10, targetUserId = null))
        assertTrue(resolveSameAndroidUser(moduleUserId = 10, targetUserId = 10) == true)
        assertFalse(resolveSameAndroidUser(moduleUserId = 10, targetUserId = 0) == true)
    }

    fun `remote failure codes are normalized for gated diagnostics`() {
        assertEquals(
            USER_TERMS_FAILURE_SERVICE_NOT_CONNECTED,
            failureCode(explicit = "service_not_connected")
        )
        assertEquals(
            USER_TERMS_FAILURE_API_UNSUPPORTED,
            failureCode(explicit = "remote_preferences_unsupported")
        )
        assertEquals(
            USER_TERMS_FAILURE_REMOTE_PUBLISH,
            failureCode(explicit = "publish_failed")
        )
    }

    fun `local finalization failure keeps its distinct code`() {
        assertEquals(
            UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE,
            failureCode(explicit = UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE)
        )
    }

    fun `current framework state supplies a bounded fallback code`() {
        assertEquals(
            USER_TERMS_FAILURE_SERVICE_NOT_CONNECTED,
            failureCode(connected = false, capable = false)
        )
        assertEquals(
            USER_TERMS_FAILURE_API_UNSUPPORTED,
            failureCode(connected = true, capable = false)
        )
        assertEquals(
            USER_TERMS_FAILURE_REMOTE_PUBLISH,
            failureCode(
                connected = true,
                capable = true,
                state = RemoteHookConfigPublishState.FAILED
            )
        )
        assertNull(
            failureCode(
                connected = true,
                capable = true,
                state = RemoteHookConfigPublishState.READY
            )
        )
    }

    private fun failureCode(
        explicit: String? = null,
        connected: Boolean = true,
        capable: Boolean = true,
        state: RemoteHookConfigPublishState = RemoteHookConfigPublishState.READY
    ): String? = resolveUserTermsGateFailureCode(
        explicitFailureCode = explicit,
        frameworkConnected = connected,
        frameworkCapable = capable,
        publishState = state
    )

    fun verifyAllCases() {
        fun runIsolated(block: (UserTermsGateDiagnosticsCases) -> Unit) {
            val s = UserTermsGateDiagnosticsCases()
            block(s)
        }
        runIsolated { s -> s.`android uid encoding resolves primary and secondary users`() }
        runIsolated { s -> s.`same-user result remains unknown until target identity is visible`() }
        runIsolated { s -> s.`remote failure codes are normalized for gated diagnostics`() }
        runIsolated { s -> s.`local finalization failure keeps its distinct code`() }
        runIsolated { s -> s.`current framework state supplies a bounded fallback code`() }
    }
}

private class TermsMirrorFlushAndPublicationAuthorityCases {

    fun `terms mirror coordinator waits boundedly for standard channel without leaking listeners`() {
        val listeners = mutableListOf<RemoteHookConfigPublishListener>()
        var cancelledTimeout = false
        var noRootFinished = false
        var pendingFinishedCount = 0
        var scheduledDelayMs = -1L
        var timeoutAction: (() -> Unit)? = null
        var flushCalls = 0
        var diagnostics = RemoteHookConfigDiagnostics(
            state = RemoteHookConfigPublishState.WAITING_FOR_SERVICE,
            lastAttemptAtEpochMs = 0L,
            lastSuccessAtEpochMs = 0L,
            generation = 0L,
            failureCode = null,
            publishPending = true
        )

        val coordinator = TermsMirrorFlushCoordinator(
            isNoRootEnabled = { false },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { onDone ->
                noRootFinished = true
                onDone()
            },
            addPublishListener = { listeners += it },
            removePublishListener = { listeners -= it },
            requestStandardFlush = { flushCalls++ },
            readDiagnostics = { diagnostics },
            scheduleTimeout = { delayMs, onTimeout ->
                scheduledDelayMs = delayMs
                timeoutAction = onTimeout
                { cancelledTimeout = true }
            },
            runAsync = { it.run() },
            onFinish = { pendingFinishedCount++ }
        )
        coordinator.start()

        assertTrue(noRootFinished)
        assertEquals(TermsMirrorFlushCoordinator.STANDARD_FLUSH_TIMEOUT_MS, scheduledDelayMs)
        assertEquals(3_000L, scheduledDelayMs)
        assertEquals(1, flushCalls)
        assertEquals(1, listeners.size)
        assertEquals(0, pendingFinishedCount)

        // 瞬态未连接失败不能提前结束等待。
        listeners.single().onRemoteHookConfigPublished(
            RemoteHookConfigPublishEvent(
                UserTermsDecision.ACCEPTED,
                RemoteHookConfigPublishResult.Failure("Xposed service is not connected")
            )
        )
        assertEquals(1, listeners.size)
        assertEquals(0, pendingFinishedCount)

        // 真正提交成功后立即解除监听、取消超时并 finish。
        listeners.single().onRemoteHookConfigPublished(
            RemoteHookConfigPublishEvent(
                UserTermsDecision.ACCEPTED,
                RemoteHookConfigPublishResult.Success(generation = 2L, changed = true)
            )
        )
        assertTrue(listeners.isEmpty())
        assertTrue(cancelledTimeout)
        assertEquals(1, pendingFinishedCount)

        // 超时兜底也不得泄漏监听，且 finish 只执行一次。
        timeoutAction?.invoke()
        assertEquals(1, pendingFinishedCount)
    }

    fun `terms mirror coordinator finishes cleanly on timeout bypass or async rejection`() {
        // 1. 3s 超时分支
        val timeoutListeners = mutableListOf<RemoteHookConfigPublishListener>()
        var timeoutCallback: (() -> Unit)? = null
        var timeoutFinishes = 0
        TermsMirrorFlushCoordinator(
            isNoRootEnabled = { false },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { it() },
            addPublishListener = { timeoutListeners += it },
            removePublishListener = { timeoutListeners -= it },
            requestStandardFlush = {},
            readDiagnostics = {
                RemoteHookConfigDiagnostics(
                    RemoteHookConfigPublishState.WAITING_FOR_SERVICE, 0L, 0L, 0L, null, true
                )
            },
            scheduleTimeout = { _, onTimeout ->
                timeoutCallback = onTimeout
                {}
            },
            runAsync = { it.run() },
            onFinish = { timeoutFinishes++ }
        ).start()
        assertEquals(1, timeoutListeners.size)
        assertEquals(0, timeoutFinishes)
        timeoutCallback?.invoke()
        assertTrue(timeoutListeners.isEmpty())
        assertEquals(1, timeoutFinishes)

        // 2. NPatch 选定或 async 拒绝均能收敛并释放 PendingResult
        var asyncRejectedFinishes = 0
        val rejectedListeners = mutableListOf<RemoteHookConfigPublishListener>()
        TermsMirrorFlushCoordinator(
            isNoRootEnabled = { false },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { it() },
            addPublishListener = { rejectedListeners += it },
            removePublishListener = { rejectedListeners -= it },
            requestStandardFlush = {},
            readDiagnostics = { error("unreachable") },
            scheduleTimeout = { _, _ -> {} },
            runAsync = { throw RejectedExecutionException("full") },
            onFinish = { asyncRejectedFinishes++ }
        ).start()
        assertTrue(rejectedListeners.isEmpty())
        assertEquals(1, asyncRejectedFinishes)

        // 3. 免 Root 开关开启时不注册标准通道监听器
        var noRootFinishes = 0
        val noRootListeners = mutableListOf<RemoteHookConfigPublishListener>()
        var awaitNoRootDone: (() -> Unit)? = null
        TermsMirrorFlushCoordinator(
            isNoRootEnabled = { true },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { awaitNoRootDone = it },
            addPublishListener = { noRootListeners += it },
            removePublishListener = { noRootListeners -= it },
            requestStandardFlush = {},
            readDiagnostics = { error("unreachable") },
            scheduleTimeout = { _, _ -> error("no standard timeout expected") },
            runAsync = { error("no standard flush expected") },
            onFinish = { noRootFinishes++ }
        ).start()
        assertTrue(noRootListeners.isEmpty())
        assertEquals(0, noRootFinishes)
        awaitNoRootDone?.invoke()
        assertEquals(1, noRootFinishes)

        // 4. NPATCH_SELECTED bypass 事件也能结束标准等待
        val bypassListeners = mutableListOf<RemoteHookConfigPublishListener>()
        var bypassFinishes = 0
        TermsMirrorFlushCoordinator(
            isNoRootEnabled = { false },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { it() },
            addPublishListener = { bypassListeners += it },
            removePublishListener = { bypassListeners -= it },
            requestStandardFlush = {},
            readDiagnostics = {
                RemoteHookConfigDiagnostics(
                    RemoteHookConfigPublishState.PUBLISHING, 0L, 0L, 0L, null, true
                )
            },
            scheduleTimeout = { _, _ -> {} },
            runAsync = { it.run() },
            onFinish = { bypassFinishes++ }
        ).start()
        bypassListeners.single().onRemoteHookConfigPublished(
            RemoteHookConfigPublishEvent(
                UserTermsDecision.ACCEPTED,
                RemoteHookConfigPublishResult.Failure(StandardPublisherPolicy.NPATCH_SELECTED)
            )
        )
        assertTrue(bypassListeners.isEmpty())
        assertEquals(1, bypassFinishes)
    }

    fun `publication authority store distinguishes direct boot transient faults from persistent faults`() {
        PublicationAuthorityStore.resetFaultStateForTest()
        try {
            val prefsMap = mutableMapOf<String, InMemoryPrefs>()
            val termsPrefs = InMemoryPrefs(
                mutableMapOf(
                    UserTermsConsentStore.KEY_DECISION to UserTermsDecision.ACCEPTED.name,
                    UserTermsConsentStore.KEY_TERMS_VERSION to UserTermsConsentStore.CURRENT_TERMS_VERSION,
                    UserTermsConsentStore.KEY_CONSENT_REVISION to 10L
                )
            )
            prefsMap[UserTermsConsentStore.PREF_FILE] = termsPrefs

            var failBridgeWithIo = false
            val bridgePrefs = InMemoryPrefs(mutableMapOf()) {
                if (failBridgeWithIo) throw RuntimeException(IOException("transient io"))
            }
            val pkg = "com.example.module"
            prefsMap["${pkg}_preferences"] = bridgePrefs

            val fakeContext = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
                override fun getPackageName(): String = pkg
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    prefsMap.getOrPut(name) { InMemoryPrefs(mutableMapOf()) }.instance
            }

            // 1. Direct Boot 未解锁前 fail-closed 返回 null，且不置死故障锁。
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { false }, storageProbe = { true }))
            assertFalse(PublicationAuthorityStore.isStorageFaultedForTest())
            assertFalse(PublicationAuthorityStore.isTransientFaultedForTest())

            // 用户解锁后立刻正常发布，不重启进程。
            val unlockedPub = PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true })
            assertNotNull(unlockedPub)
            assertEquals(UserTermsDecision.ACCEPTED, unlockedPub?.decision)

            // 2. 瞬态 IOException 触发 transientFault，但不永久锁死；恢复健康后自检放行。
            failBridgeWithIo = true
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true }))
            assertFalse(PublicationAuthorityStore.isStorageFaultedForTest())
            assertTrue(PublicationAuthorityStore.isTransientFaultedForTest())

            // 健康探测仍不通过时维持 fail-closed。
            assertFalse(PublicationAuthorityStore.clearStorageFaultIfHealthy(fakeContext, userUnlocked = { true }, storageProbe = { false }))
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { false }))

            // 存储恢复健康后下一次读取自动通过自检并恢复授权。
            failBridgeWithIo = false
            val recoveredPub = PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true })
            assertNotNull(recoveredPub)
            assertFalse(PublicationAuthorityStore.isTransientFaultedForTest())

            // 3. 已解锁状态的持久化 SecurityException/IllegalStateException 锁定持久故障，不能被自检清除。
            var failWithSecurity = false
            val secureBridge = InMemoryPrefs(mutableMapOf()) {
                if (failWithSecurity) throw SecurityException("persistent permission denial")
            }
            prefsMap["${pkg}_preferences"] = secureBridge
            failWithSecurity = true
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true }))
            assertTrue(PublicationAuthorityStore.isStorageFaultedForTest())
            failWithSecurity = false
            assertFalse(PublicationAuthorityStore.clearStorageFaultIfHealthy(fakeContext, userUnlocked = { true }, storageProbe = { true }))
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true }))

            // 4. 显式 stopGrants() 同样保持不可自愈阻断。
            PublicationAuthorityStore.resetFaultStateForTest()
            PublicationAuthorityStore.stopGrants()
            assertFalse(PublicationAuthorityStore.clearStorageFaultIfHealthy(fakeContext, userUnlocked = { true }, storageProbe = { true }))
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true }))
        } finally {
            PublicationAuthorityStore.resetFaultStateForTest()
        }
    }

    fun verifyMirrorCases() {
        fun runIsolated(block: (TermsMirrorFlushAndPublicationAuthorityCases) -> Unit) {
            val s = TermsMirrorFlushAndPublicationAuthorityCases()
            block(s)
        }
        runIsolated { s -> s.`terms mirror coordinator waits boundedly for standard channel without leaking listeners`() }
        runIsolated { s -> s.`terms mirror coordinator finishes cleanly on timeout bypass or async rejection`() }
    }

    private class InMemoryPrefs(
        val values: MutableMap<String, Any?>,
        private val onGetAll: () -> Unit = {}
    ) {
        val instance: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "edit" -> createEditor()
                "getAll" -> {
                    onGetAll()
                    HashMap(values)
                }
                "contains" -> values.containsKey(args?.get(0) as? String)
                "getString" -> (values[args?.get(0) as? String] as? String) ?: (args?.getOrNull(1) as? String)
                "getLong" -> (values[args?.get(0) as? String] as? Long) ?: (args?.getOrNull(1) as? Long ?: 0L)
                "getInt" -> (values[args?.get(0) as? String] as? Int) ?: (args?.getOrNull(1) as? Int ?: 0)
                "getBoolean" -> (values[args?.get(0) as? String] as? Boolean) ?: (args?.getOrNull(1) as? Boolean ?: false)
                else -> null
            }
        } as SharedPreferences

        private fun createEditor(): SharedPreferences.Editor {
            val puts = LinkedHashMap<String, Any?>()
            val removes = mutableListOf<String>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "putLong", "putInt", "putString", "putBoolean" -> {
                        puts[args!![0] as String] = args[1]
                        editor
                    }
                    "remove" -> {
                        removes += args!![0] as String
                        editor
                    }
                    "clear" -> {
                        values.clear()
                        editor
                    }
                    "apply" -> {
                        removes.forEach { values.remove(it) }
                        values.putAll(puts)
                        Unit
                    }
                    "commit" -> {
                        removes.forEach { values.remove(it) }
                        values.putAll(puts)
                        true
                    }
                    else -> editor
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
