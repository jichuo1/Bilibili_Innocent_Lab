package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeBootstrapEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.AuthorityPublication
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeRestartPolicyTest {

    @Test
    fun verifyAllCases() {
        HostRestartCommandsCases().verifyAllCases()
        RestartConfigBarrierCases().verifyAllCases()
        RestartProbeStateCases().verifyAllCases()
        RestartRoutePolicyCases().verifyAllCases()
        RootRestartAccessCases().verifyAllCases()
        ShizukuAvailabilityCases().verifyAllCases()
        RestartReceiptPolicyCases().verifyAllCases()
    }
}

private class HostRestartCommandsCases {
    private class Fake {
        var time = 1L
        var running = true
        var started = false
        var failStop = false
        var keepOld = false
        var respawns = 0
        var startOutput = "Status: ok"
        val commands = mutableListOf<List<String>>()
        val backend = HostRestartCommands(1_010_123, run = { args ->
            commands += args
            when (args[0]) {
                "/system/bin/ps" -> HostRestartCommands.Output(0, "UID PID NAME\n" +
                    "10123 99 tv.danmaku.bili\n" + // 其它 Android 用户不能混入目标进程。
                    if (running) "1010123 ${if (started) 200 else 100} tv.danmaku.bili\n1010123 101 tv.danmaku.bili:web\n" else "")
                else -> if (args[1] == "force-stop") {
                    if (!keepOld && !failStop) running = false
                    if (respawns > 0) { running = true; respawns-- }
                    HostRestartCommands.Output(if (failStop) 1 else 0, "")
                } else { running = true; started = true; HostRestartCommands.Output(0, startOutput) }
            }
        }, now = { time }, pause = { time += 100 })
    }

    fun stopChecksAllProcessesAndStartsOnlyTheSameAndroidUser() {
        val fake = Fake()
        val stop = fake.backend.stop()
        assertEquals(listOf(100, 101), stop.oldPids)
        assertEquals(200, fake.backend.start(stop.token))
        assertTrue(fake.commands.contains(listOf("/system/bin/am", "force-stop", "--user", "10", "tv.danmaku.bili")))
        assertTrue(fake.commands.contains(listOf("/system/bin/am", "start", "--user", "10", "-W", "-n", "tv.danmaku.bili/.MainActivityV2")))
        assertFalse(fake.commands.flatten().any { it == "sh" || it == "settings" || it == "monkey" })
    }

    fun commandFailureNeverIssuesAStart() {
        val fake = Fake().apply { failStop = true }
        assertThrows(IllegalStateException::class.java) { fake.backend.stop() }
        assertFalse(fake.commands.any { it.getOrNull(1) == "start" })
    }

    fun survivingSubprocessBlocksRestartEvenWhenAmReturnsSuccess() {
        val fake = Fake().apply { keepOld = true }
        assertThrows(IllegalStateException::class.java) { fake.backend.stop() }
        assertFalse(fake.commands.any { it.getOrNull(1) == "start" })
    }

    fun romRespawnIsStoppedAgainBeforeIssuingASingleLaunch() {
        val fake = Fake().apply { respawns = 1 }
        val stopped = fake.backend.stop()
        assertEquals(2, fake.commands.count { it.getOrNull(1) == "force-stop" })
        assertTrue("Exit must remain stable before launching", fake.time >= 301)
        assertEquals(200, fake.backend.start(stopped.token))
        assertEquals(1, fake.commands.count { it.getOrNull(1) == "start" })
    }

    fun repeatedRomRespawnHasAFixedRetryBoundAndNeverLaunches() {
        val fake = Fake().apply { respawns = 20 }
        assertThrows(IllegalStateException::class.java) { fake.backend.stop() }
        assertEquals(3, fake.commands.count { it.getOrNull(1) == "force-stop" })
        assertFalse(fake.commands.any { it.getOrNull(1) == "start" })
    }

    fun consumedTokenCannotTriggerAnotherStart() {
        val fake = Fake()
        val token = fake.backend.stop().token
        fake.backend.start(token)
        assertThrows(IllegalStateException::class.java) { fake.backend.start(token) }
        assertEquals(1, fake.commands.count { it.getOrNull(1) == "start" })
    }

    fun expiredAndWrongTokensCannotStart() {
        val expired = Fake()
        val token = expired.backend.stop().token
        expired.time += 15_000
        assertThrows(IllegalStateException::class.java) { expired.backend.start(token) }
        val wrong = Fake()
        wrong.backend.stop()
        assertThrows(IllegalStateException::class.java) { wrong.backend.start("wrong-token") }
        assertFalse((expired.commands + wrong.commands).any { it.getOrNull(1) == "start" })
    }

    fun errorTextCannotBeMistakenForSuccessfulLaunch() {
        val fake = Fake().apply { startOutput = "Error: Activity class does not exist." }
        val token = fake.backend.stop().token
        assertThrows(IllegalStateException::class.java) { fake.backend.start(token) }
    }

    fun hostRestartedElsewhereDoesNotGetAnExtraLaunch() {
        val fake = Fake()
        val token = fake.backend.stop().token
        fake.running = true
        assertThrows(IllegalStateException::class.java) { fake.backend.start(token) }
        assertFalse(fake.commands.any { it.getOrNull(1) == "start" })
    }

    fun malformedProcessOutputFailsClosed() {
        assertThrows(IllegalStateException::class.java) { HostRestartCommands.parseProcesses("PID NAME\n100 tv.danmaku.bili", 10123) }
        assertThrows(IllegalStateException::class.java) { HostRestartCommands.parseProcesses("UID PID NAME\n10123 invalid tv.danmaku.bili", 10123) }
        assertEquals(mapOf("tv.danmaku.bili" to 20), HostRestartCommands.parseProcesses(
            "UID PID NAME\n10123 20 tv.danmaku.bili\n10124 21 tv.danmaku.bili:web\n10123 22 tv.danmaku.bili.fake\n", 10123))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostRestartCommandsCases) -> Unit) {
            val s = HostRestartCommandsCases()
            block(s)
        }
        runIsolated { s -> s.stopChecksAllProcessesAndStartsOnlyTheSameAndroidUser() }
        runIsolated { s -> s.commandFailureNeverIssuesAStart() }
        runIsolated { s -> s.survivingSubprocessBlocksRestartEvenWhenAmReturnsSuccess() }
        runIsolated { s -> s.romRespawnIsStoppedAgainBeforeIssuingASingleLaunch() }
        runIsolated { s -> s.repeatedRomRespawnHasAFixedRetryBoundAndNeverLaunches() }
        runIsolated { s -> s.consumedTokenCannotTriggerAnotherStart() }
        runIsolated { s -> s.expiredAndWrongTokensCannotStart() }
        runIsolated { s -> s.errorTextCannotBeMistakenForSuccessfulLaunch() }
        runIsolated { s -> s.hostRestartedElsewhereDoesNotGetAnExtraLaunch() }
        runIsolated { s -> s.malformedProcessOutputFailsClosed() }
    }
}

private class RestartConfigBarrierCases {
    private val publication = AuthorityPublication(PublicationIdentity("install", 1, 1, 1, "config"),
        UserTermsDecision.ACCEPTED, emptyMap(), pending = false, directAllowed = true)

    fun standardCompatibilityCanRestartWhenRemoteServiceIsDisconnected() {
        val steps = mutableListOf<String>()
        assertTrue(persistStandardRestartConfig(
            persistLocal = { steps += "persist"; true },
            capture = { steps += "capture"; publication },
            lspatchDirect = { false },
            publishRemote = { steps += "remote-unavailable"; false },
            stillCurrent = { steps += "revalidate"; it === publication }
        ))
        assertEquals(listOf("persist", "capture", "remote-unavailable", "revalidate"), steps)
    }

    fun aTransportExceptionStillAllowsAnAuthorizedCurrentDirectDocument() {
        assertTrue(save(publish = { error("service disconnected") }))
    }

    fun ordinaryStandardConfigStillRequiresRemotePublication() {
        val standard = publication.copy(directAllowed = false)
        assertFalse(save(value = standard, publish = { false }))
        assertTrue(save(value = standard, publish = { true }))
    }

    fun lspatchDirectConfigDoesNotAttemptToWriteTheHostOwnedStore() {
        assertTrue(save(value = publication.copy(directAllowed = false), lspatch = true,
            publish = { fail("LSPatch must use its current direct document"); false }))
    }

    fun localWriteFailureCannotCaptureOrPublishAConfiguration() {
        assertFalse(persistStandardRestartConfig(
            persistLocal = { false },
            capture = { fail("A failed local write cannot create a publication"); publication },
            lspatchDirect = { fail("No transport may run after a failed save"); false },
            publishRemote = { fail("No transport may run after a failed save"); true },
            stillCurrent = { true }
        ))
    }

    fun missingOrUnauthorizedPublicationsNeverReachATransport() {
        for (value in listOf(null, publication.copy(decision = UserTermsDecision.UNDECIDED))) {
            assertFalse(save(value = value, lspatch = true,
                publish = { fail("Authorization must precede publication"); true }))
        }
    }

    fun aChangedPublicationCannotPassEitherSaveChannel() {
        assertFalse(save(current = false))
        assertFalse(save(value = publication.copy(directAllowed = false), publish = { true }, current = false))
        assertFalse(save(lspatch = true, current = false))
    }

    private fun save(value: AuthorityPublication? = publication, lspatch: Boolean = false,
                     publish: (AuthorityPublication) -> Boolean = { false }, current: Boolean = true): Boolean =
        persistStandardRestartConfig({ true }, { value }, { lspatch }, publish, { current })

    fun verifyAllCases() {
        fun runIsolated(block: (RestartConfigBarrierCases) -> Unit) {
            val s = RestartConfigBarrierCases()
            block(s)
        }
        runIsolated { s -> s.standardCompatibilityCanRestartWhenRemoteServiceIsDisconnected() }
        runIsolated { s -> s.aTransportExceptionStillAllowsAnAuthorizedCurrentDirectDocument() }
        runIsolated { s -> s.ordinaryStandardConfigStillRequiresRemotePublication() }
        runIsolated { s -> s.lspatchDirectConfigDoesNotAttemptToWriteTheHostOwnedStore() }
        runIsolated { s -> s.localWriteFailureCannotCaptureOrPublishAConfiguration() }
        runIsolated { s -> s.missingOrUnauthorizedPublicationsNeverReachATransport() }
        runIsolated { s -> s.aChangedPublicationCannotPassEitherSaveChannel() }
    }
}

private class RestartProbeStateCases {
    private val absent = RootRestartAccess.Snapshot(RootRestartAccess.Status.MISSING)
    private val root = RootRestartAccess.Snapshot(RootRestartAccess.Status.READY, "/system/bin/su")
    private val shizuku = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.READY, 2000)

    fun openingDialogDuringWarmupJoinsTheSameFlightAndWaitsForRoot() {
        val state = RestartProbeState()
        val token = state.begin(100)!!
        repeat(20) { assertNull(state.begin(101, force = true)) }
        assertTrue(state.shizuku(token, shizuku, 110))
        assertTrue(state.snapshot.checking)
        assertNull(state.snapshot.preferred)
        assertTrue(state.root(token, root, 120))
        assertEquals(RestartRoute.ROOT, state.snapshot.preferred)
        assertFalse(state.snapshot.checking)
    }

    fun completedWarmupIsReusedForFiveSecondsButNotAcrossAnExplicitRefresh() {
        val state = RestartProbeState()
        val token = state.begin(0)!!
        state.root(token, absent, 10)
        state.shizuku(token, shizuku, 20)
        assertNull(state.begin(5_019))
        assertNotNull(state.begin(5_020))
        assertTrue(state.snapshot.checking)
        assertNull(state.snapshot.preferred)
    }

    fun foregroundRefreshDiscardsTheCachedChoiceUntilBothChecksFinish() {
        val state = RestartProbeState()
        val first = state.begin(0)!!
        state.root(first, root, 1)
        state.shizuku(first, shizuku, 2)
        assertNotNull(state.begin(3, force = true))
        assertNull(state.snapshot.preferred)
    }

    fun timeoutPreservesCompletedShizukuButLeavesRootUnknownAndRejectsLateSuccess() {
        val state = RestartProbeState()
        val token = state.begin(0)!!
        state.shizuku(token, shizuku, 10)
        assertTrue(state.timeout(token, 2_000))
        assertEquals(RootRestartAccess.Status.UNKNOWN, state.snapshot.root?.status)
        assertEquals(RestartRoute.SHIZUKU, state.snapshot.preferred)
        assertFalse(state.root(token, root, 2_001))
        assertFalse(state.timeout(token, 2_002))
        assertEquals(RestartRoute.SHIZUKU, state.snapshot.preferred)
    }

    fun binderDeathInvalidatesInFlightResultsEvenWhenTheNewProbeFinishesFirst() {
        val state = RestartProbeState()
        val old = state.begin(0)!!
        state.root(old, absent, 1)
        state.invalidate()
        val current = state.begin(2, force = true)!!
        state.root(current, absent, 3)
        state.shizuku(current, ShizukuAvailability.Snapshot(ShizukuAvailability.Status.STOPPED), 4)
        assertFalse(state.shizuku(old, shizuku, 5))
        assertEquals(RestartRoute.MANUAL, state.snapshot.preferred)
    }

    fun duplicateOrOldChannelCallbacksCannotOverwriteCurrentResults() {
        val state = RestartProbeState()
        val first = state.begin(0)!!
        assertTrue(state.root(first, absent, 1))
        assertFalse(state.root(first, root, 2))
        state.timeout(first, 2_000)
        val next = state.begin(2_001, force = true)!!
        assertFalse(state.shizuku(first, shizuku, 2_002))
        state.root(next, root, 2_003)
        state.shizuku(next, shizuku, 2_004)
        assertEquals(RestartRoute.ROOT, state.snapshot.preferred)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RestartProbeStateCases) -> Unit) {
            val s = RestartProbeStateCases()
            block(s)
        }
        runIsolated { s -> s.openingDialogDuringWarmupJoinsTheSameFlightAndWaitsForRoot() }
        runIsolated { s -> s.completedWarmupIsReusedForFiveSecondsButNotAcrossAnExplicitRefresh() }
        runIsolated { s -> s.foregroundRefreshDiscardsTheCachedChoiceUntilBothChecksFinish() }
        runIsolated { s -> s.timeoutPreservesCompletedShizukuButLeavesRootUnknownAndRejectsLateSuccess() }
        runIsolated { s -> s.binderDeathInvalidatesInFlightResultsEvenWhenTheNewProbeFinishesFirst() }
        runIsolated { s -> s.duplicateOrOldChannelCallbacksCannotOverwriteCurrentResults() }
    }
}

private class RestartRoutePolicyCases {
    private val root = RootRestartAccess.Snapshot(RootRestartAccess.Status.READY, "/system/bin/su")
    private val absent = RootRestartAccess.Snapshot(RootRestartAccess.Status.MISSING)
    private val shizuku = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.READY, 2000)
    private val missing = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.MISSING)

    fun rootPrecedesShizukuWithoutAnyFrameworkNameOrNoRootFlag() {
        for (status in listOf(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.Status.READY)) {
            for (permission in ShizukuAvailability.Status.entries) {
                assertEquals(RestartRoute.ROOT, RestartRoutePolicy.preferred(root.copy(status = status), shizuku.copy(status = permission)))
            }
        }
    }

    fun rootFailureOffersShizukuBeforeManual() {
        for (status in listOf(RootRestartAccess.Status.MISSING, RootRestartAccess.Status.DENIED, RootRestartAccess.Status.UNKNOWN)) {
            assertEquals(RestartRoute.SHIZUKU, RestartRoutePolicy.preferred(root.copy(status = status), shizuku))
            assertEquals(RestartRoute.MANUAL, RestartRoutePolicy.preferred(root.copy(status = status), missing))
        }
    }

    fun pendingRootBlocksFallbackButConfirmedRootDoesNotWaitForLowerPriorityChecks() {
        assertNull(RestartRoutePolicy.preferred(null, shizuku))
        assertEquals(RestartRoute.ROOT, RestartRoutePolicy.preferred(root, null))
        assertNull(RestartRoutePolicy.preferred(null, null))
    }

    fun selectedConfigAndPendingDisableRemainIndependentOfRootPriority() {
        for (state in NoRootDisplayState.entries) {
            assertTrue(RestartRoutePolicy.usesNoRootConfig(true, state))
            assertTrue(RestartRoutePolicy.usesNoRootConfig(false, state, nPatchCarrier = true))
            assertEquals(state in setOf(NoRootDisplayState.DISABLE_RESTART_REQUIRED,
                NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE), RestartRoutePolicy.usesNoRootConfig(false, state))
            assertEquals(RestartRoute.ROOT, RestartRoutePolicy.preferred(root, shizuku))
        }
    }

    fun explicitShizukuAuthorizationIsAnOptionButDeniedOrStoppedIsNotAReadyFallback() {
        assertEquals(RestartRoute.SHIZUKU, RestartRoutePolicy.preferred(absent, shizuku.copy(status = ShizukuAvailability.Status.UNAUTHORIZED)))
        for (status in listOf(ShizukuAvailability.Status.DENIED, ShizukuAvailability.Status.STOPPED, ShizukuAvailability.Status.UNSUPPORTED)) {
            assertEquals(RestartRoute.MANUAL, RestartRoutePolicy.preferred(absent, shizuku.copy(status = status)))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RestartRoutePolicyCases) -> Unit) {
            val s = RestartRoutePolicyCases()
            block(s)
        }
        runIsolated { s -> s.rootPrecedesShizukuWithoutAnyFrameworkNameOrNoRootFlag() }
        runIsolated { s -> s.rootFailureOffersShizukuBeforeManual() }
        runIsolated { s -> s.pendingRootBlocksFallbackButConfirmedRootDoesNotWaitForLowerPriorityChecks() }
        runIsolated { s -> s.selectedConfigAndPendingDisableRemainIndependentOfRootPriority() }
        runIsolated { s -> s.explicitShizukuAuthorizationIsAnOptionButDeniedOrStoppedIsNotAReadyFallback() }
    }
}

private class RootRestartAccessCases {
    fun passiveDetectionSearchesAbsolutePathEntriesWithoutExecutingSu() {
        val checked = mutableListOf<String>()
        assertEquals("/vendor/su folder/su", RootRestartAccess.findPath("relative::/vendor/su folder:/system/bin") {
            checked += it
            it == "/vendor/su folder/su"
        })
        assertFalse(checked.any { it.startsWith("relative") || it == "/su" })
        assertEquals(checked.distinct(), checked)
        assertNull(RootRestartAccess.findPath(null) { false })
    }

    fun candidateFilesAndOldOrDifferentPathGrantsCannotClaimRootIsReady() {
        val path = "/system/bin/su"
        assertEquals(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.classify(path, null, null, null).status)
        assertEquals(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.classify(path, "/old/su", true, 1).status)
        assertEquals(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.classify(path, path, true, 30_001).status)
        assertEquals(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.classify(path, path, true, -1).status)
        assertEquals(RootRestartAccess.Status.READY, RootRestartAccess.classify(path, path, true, 30_000).status)
        assertEquals(RootRestartAccess.Status.DENIED, RootRestartAccess.classify(path, path, false, 1).status)
    }

    fun successfulExitWithoutUidZeroCannotAuthorizeRoot() {
        assertTrue(RootRestartAccess.acceptsIdentity(HostRestartCommands.Output(0, "0\n")))
        for (text in listOf("2000", "10001", "", "uid=0(root)", "error\n0", "0\n2000")) {
            assertFalse(RootRestartAccess.acceptsIdentity(HostRestartCommands.Output(0, text)))
        }
        assertFalse(RootRestartAccess.acceptsIdentity(HostRestartCommands.Output(1, "0")))
    }

    fun rootCommandOutputRequiresTheCurrentUniqueIdentityMarker() {
        val marker = "BIL_ROOT_test123"
        assertNull(RootRestartAccess.stripIdentity(HostRestartCommands.Output(0, "UID PID NAME"), marker))
        assertNull(RootRestartAccess.stripIdentity(HostRestartCommands.Output(0, "$marker\n$marker\nUID PID NAME"), marker))
        assertNull(RootRestartAccess.stripIdentity(HostRestartCommands.Output(0, "BIL_ROOT_old\nUID PID NAME"), marker))
        assertEquals(HostRestartCommands.Output(1, "command failed"), RootRestartAccess.stripIdentity(
            HostRestartCommands.Output(1, "manager banner\n$marker\ncommand failed"), marker))
    }

    fun rootTransportAllowsOnlyFixedHostCommandsAndValidAndroidUsers() {
        val marker = "BIL_ROOT_test123"
        val command = listOf("/system/bin/am", "start", "--user", "10", "-W", "-n", "tv.danmaku.bili/.MainActivityV2")
        val wrapped = RootRestartAccess.wrap("/system/bin/su", command, marker)
        assertEquals(listOf("/system/bin/su", "-c"), wrapped.take(2))
        assertTrue(wrapped[2].indexOf("/system/bin/id -u") < wrapped[2].indexOf("exec "))
        listOf(
            listOf("/system/bin/sh", "-c", "arbitrary"),
            command.map { if (it == "10") "10;reboot" else it },
            command.map { if (it == "10") "-1" else it },
            command.map { if (it == "tv.danmaku.bili/.MainActivityV2") "another.package/.Main" else it },
            listOf("/system/bin/am", "force-stop", "--user", "0", "another.package")
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { RootRestartAccess.wrap("su", invalid, marker) } }
        assertThrows(IllegalArgumentException::class.java) { RootRestartAccess.wrap("su", command, "bad';reboot") }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RootRestartAccessCases) -> Unit) {
            val s = RootRestartAccessCases()
            block(s)
        }
        runIsolated { s -> s.passiveDetectionSearchesAbsolutePathEntriesWithoutExecutingSu() }
        runIsolated { s -> s.candidateFilesAndOldOrDifferentPathGrantsCannotClaimRootIsReady() }
        runIsolated { s -> s.successfulExitWithoutUidZeroCannotAuthorizeRoot() }
        runIsolated { s -> s.rootCommandOutputRequiresTheCurrentUniqueIdentityMarker() }
        runIsolated { s -> s.rootTransportAllowsOnlyFixedHostCommandsAndValidAndroidUsers() }
    }
}

private class ShizukuAvailabilityCases {
    fun missingManagerAndBinderHideTheEntry() {
        val result = ShizukuAvailability.classify(false, false)
        assertEquals(ShizukuAvailability.Status.MISSING, result.status)
        assertFalse(result.visible)
        assertFalse(result.recommended)
    }

    fun installedManagerWithoutLiveServiceRemainsVisibleButDisabled() {
        val result = ShizukuAvailability.classify(true, false, 13, 2000, ShizukuAvailability.Permission.GRANTED)
        assertEquals(ShizukuAvailability.Status.STOPPED, result.status)
        assertTrue(result.visible)
        assertFalse(result.recommended)
    }

    fun staleApiAndUnexpectedIdentityCannotBeRecommendedEvenWhenGranted() {
        listOf(12 to 2000, 13 to 10001, 13 to -1).forEach { (version, uid) ->
            val result = ShizukuAvailability.classify(true, true, version, uid, ShizukuAvailability.Permission.GRANTED)
            assertEquals(ShizukuAvailability.Status.UNSUPPORTED, result.status)
            assertTrue(result.visible)
            assertFalse(result.recommended)
        }
    }

    fun liveSupportedServiceIsRecommendedBeforeAndAfterExplicitAuthorization() {
        ShizukuAvailability.Permission.entries.forEach { permission ->
            val result = ShizukuAvailability.classify(true, true, 13, 2000, permission)
            assertTrue(result.visible)
            assertTrue(result.recommended)
            assertEquals(2000, result.uid)
        }
    }

    fun availableSuiBinderDoesNotRequireTheShizukuManagerPackage() {
        val result = ShizukuAvailability.classify(false, true, 13, 0, ShizukuAvailability.Permission.GRANTED)
        assertEquals(ShizukuAvailability.Status.READY, result.status)
        assertTrue(result.recommended)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ShizukuAvailabilityCases) -> Unit) {
            val s = ShizukuAvailabilityCases()
            block(s)
        }
        runIsolated { s -> s.missingManagerAndBinderHideTheEntry() }
        runIsolated { s -> s.installedManagerWithoutLiveServiceRemainsVisibleButDisabled() }
        runIsolated { s -> s.staleApiAndUnexpectedIdentityCannotBeRecommendedEvenWhenGranted() }
        runIsolated { s -> s.liveSupportedServiceIsRecommendedBeforeAndAfterExplicitAuthorization() }
        runIsolated { s -> s.availableSuiBinderDoesNotRequireTheShizukuManagerPackage() }
    }
}

private class RestartReceiptPolicyCases {
    private val identity = PublicationIdentity("current-install", 1, 2, 3, "current-config")
    private val bootstrap = HostRuntimeBootstrapEvidence(configState = HostConfigState.ACCEPTED,
        installChainState = HostInstallChainState.COMPLETED, admission = identity)
    private val snapshot = HostRuntimeDiagnosticsSnapshot(100, "tv.danmaku.bili", emptyList(), bootstrap)
    private fun accepts(
        value: HostRuntimeDiagnosticsSnapshot? = snapshot,
        ready: Boolean = true,
        pid: Int? = 200,
        started: Long? = 101,
        installed: Boolean = true
    ) = RestartReceiptPolicy.accepts(identity, value, ready, 200, pid, started, 100, installed)

    fun currentOnlineReceiptFromNewProcessIsAccepted() { assertTrue(accepts()) }
    fun oldProcessWithIdenticalConfigurationIsRejected() { assertFalse(accepts(pid = 199)) }
    fun oldSessionCannotConfirmEvenIfPidIsReused() { assertFalse(accepts(started = 99)) }
    fun cachedOrMissingReceiptCannotConfirm() { assertFalse(accepts(ready = false)); assertFalse(accepts(value = null)) }
    fun staleConfigurationAndPreviousInstallAreRejected() {
        listOf(identity.copy(snapshotRevision = 2), identity.copy(incarnation = "previous-install"),
            identity.copy(consentRevision = 0), identity.copy(fingerprint = "old-config")).forEach {
            assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(admission = it))))
        }
    }
    fun partialInstallAndRejectedConfigurationCannotConfirm() {
        assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(installChainState = HostInstallChainState.STARTED))))
        assertFalse(accepts(snapshot.copy(bootstrap = bootstrap.copy(configState = HostConfigState.REJECTED))))
        assertFalse(accepts(installed = false))
    }
    fun missingSessionIsUnknown() { assertFalse(accepts(pid = null)); assertFalse(accepts(started = null)) }

    fun verifyAllCases() {
        fun runIsolated(block: (RestartReceiptPolicyCases) -> Unit) {
            val s = RestartReceiptPolicyCases()
            block(s)
        }
        runIsolated { s -> s.currentOnlineReceiptFromNewProcessIsAccepted() }
        runIsolated { s -> s.oldProcessWithIdenticalConfigurationIsRejected() }
        runIsolated { s -> s.oldSessionCannotConfirmEvenIfPidIsReused() }
        runIsolated { s -> s.cachedOrMissingReceiptCannotConfirm() }
        runIsolated { s -> s.staleConfigurationAndPreviousInstallAreRejected() }
        runIsolated { s -> s.partialInstallAndRejectedConfigurationCannotConfirm() }
        runIsolated { s -> s.missingSessionIsUnknown() }
    }
}
