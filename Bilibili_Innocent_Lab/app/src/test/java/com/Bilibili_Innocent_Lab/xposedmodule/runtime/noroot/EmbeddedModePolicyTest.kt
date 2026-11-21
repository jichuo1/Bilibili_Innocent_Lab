package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionContract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionSourcePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostRuntimeProbeTracker.ReplyResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import io.github.libxposed.service.XposedService
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedModePolicyTest {
    fun disabledSupportBlocksBothEmbeddedLoadersEvenWithTheOtherManagerPresent() {
        for (nPatch in NPatchMode.entries) for (lspatch in LspatchMode.entries) {
            assertEquals("$nPatch / $lspatch",
                nPatch == NPatchMode.EMBEDDED || lspatch == LspatchMode.EMBEDDED,
                EmbeddedModePolicy.isBlocked(nPatch, lspatch, supported = false))
        }
    }

    fun enablingSupportRestoresEveryExistingMode() {
        for (nPatch in NPatchMode.entries) for (lspatch in LspatchMode.entries) {
            assertFalse(EmbeddedModePolicy.isBlocked(nPatch, lspatch, supported = true))
        }
    }

    fun buildFlagDrivesSupportPolicy() {
        assertEquals(BuildConfig.EMBEDDED_MODE_SUPPORTED, EmbeddedModePolicy.SUPPORTED)
    }

    fun currentBuildDisablesEmbeddedSupportWithoutBlockingManagerOrUnpatchedHosts() {
        assertTrue(EmbeddedModePolicy.isBlocked(NPatchMode.EMBEDDED, LspatchMode.UNKNOWN))
        assertTrue(EmbeddedModePolicy.isBlocked(NPatchMode.UNKNOWN, LspatchMode.EMBEDDED))
        assertFalse(EmbeddedModePolicy.isBlocked(NPatchMode.MANAGER, LspatchMode.UNKNOWN))
        assertFalse(EmbeddedModePolicy.isBlocked(NPatchMode.UNKNOWN, LspatchMode.MANAGER))
        assertFalse(EmbeddedModePolicy.isBlocked(NPatchMode.UNKNOWN, LspatchMode.UNKNOWN))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (EmbeddedModePolicyTest) -> Unit) {
            val s = EmbeddedModePolicyTest()
            block(s)
        }
        runIsolated { s -> s.disabledSupportBlocksBothEmbeddedLoadersEvenWithTheOtherManagerPresent() }
        runIsolated { s -> s.enablingSupportRestoresEveryExistingMode() }
        runIsolated { s -> s.buildFlagDrivesSupportPolicy() }
        runIsolated { s -> s.currentBuildDisablesEmbeddedSupportWithoutBlockingManagerOrUnpatchedHosts() }
    
        HostConfigChannelCases().verifyAllCases()
        HostRuntimeProbeSessionCases().verifyAllCases()
        HostRuntimeProbeTrackerCases().verifyAllCases()
        IXposedServiceTransactionCodesCases().verifyAllCases()
        LiveRequestIdentityCases().verifyAllCases()
    }
}

private class HostConfigChannelCases {
    private val npatch = ModernFrameworkStatus(true, true, "NPatch", 102, properties = XposedService.PROP_CAP_REMOTE)
    private val lspatch = npatch.copy(name = "LSPatch")
    private val root = npatch.copy(name = "LSPosed", properties = XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM)

    fun anotherPatchManagerCannotChangeInstalledLspatchAdmission() {
        val selected = HostConfigChannels.select(npatch, false, true)
        assertEquals(HostConfigChannel.LSPATCH, selected)
        assertEquals(selected, HostConfigChannels.select(lspatch, false, true))
        assertEquals(HostAdmissionContract.DIRECT, HostAdmissionSourcePolicy.select(
            noRootSelected = false, normalNoRootRevision = 0, expectedFingerprint = "current",
            normalFingerprint = null, allowDirect = true, directEnabled = false,
            nPatchChannel = selected == HostConfigChannel.NPATCH, lspatchChannel = selected == HostConfigChannel.LSPATCH))
    }

    fun anotherPatchManagerCannotBypassInstalledNpatchDisableGate() {
        val selected = HostConfigChannels.select(lspatch, true, false)
        assertEquals(HostConfigChannel.NPATCH, selected)
        assertEquals(selected, HostConfigChannels.select(npatch, true, false))
        assertNull(HostAdmissionSourcePolicy.select(false, 0, "current", null, true, false,
            nPatchChannel = selected == HostConfigChannel.NPATCH, lspatchChannel = selected == HostConfigChannel.LSPATCH))
    }

    fun rootServiceRetainsPriorityOverBothInstalledPatchMarkers() {
        for ((npatch, lspatch) in listOf(true to false, false to true, true to true)) {
            val selected = HostConfigChannels.select(root, npatch, lspatch)
            assertEquals(HostConfigChannel.STANDARD, selected)
        }
    }

    fun unavailableServicesUseInstalledProfilesAndUnknownHostsKeepExistingServiceRouting() {
        val unavailable = npatch.copy(connected = false, capable = false)
        assertEquals(HostConfigChannel.LSPATCH, HostConfigChannels.select(unavailable, false, true))
        assertEquals(HostConfigChannel.NPATCH, HostConfigChannels.select(unavailable, true, false))
        assertEquals(HostConfigChannel.STANDARD, HostConfigChannels.select(unavailable, false, false))
        assertEquals(HostConfigChannel.NPATCH, HostConfigChannels.select(npatch, false, false))
        assertEquals(HostConfigChannel.LSPATCH, HostConfigChannels.select(lspatch, false, false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostConfigChannelCases) -> Unit) {
            val s = HostConfigChannelCases()
            block(s)
        }
        runIsolated { s -> s.anotherPatchManagerCannotChangeInstalledLspatchAdmission() }
        runIsolated { s -> s.anotherPatchManagerCannotBypassInstalledNpatchDisableGate() }
        runIsolated { s -> s.rootServiceRetainsPriorityOverBothInstalledPatchMarkers() }
        runIsolated { s -> s.unavailableServicesUseInstalledProfilesAndUnknownHostsKeepExistingServiceRouting() }
    }
}

private class HostRuntimeProbeSessionCases {
    private var now = 0L
    private val deliveries = mutableListOf<() -> Unit>()
    private val session = HostRuntimeProbeSession({ now }) { deliveries += it }

    fun anExpiredPositiveReplySettlesAllObserversOnceWithoutWaitingForTheTimer() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("resume", false, 1_500, results::add)
        now = 100
        session.request("verify", true, 15_000, results::add)
        now = 15_101
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("resume", true))
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, session.status)
        assertFalse(session.hasPendingRequest)
        assertFalse(session.onTimeout("resume"))
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("resume", true))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.NOT_RUNNING, HostRuntimeProbeStatus.NOT_RUNNING), results)
    }

    fun anExpiredNegativeReplyCannotChangeAnOrdinaryTimeoutIntoAVerifiedFailure() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("resume", false, 1_500, results::add)
        now = 1_500
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("resume", false))
        assertEquals(HostRuntimeProbeStatus.UNCONFIRMED, session.status)
        assertFalse(session.hasPendingRequest)
        assertFalse(session.onTimeout("resume"))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.UNCONFIRMED), results)
    }

    fun explicitVerificationUpgradesAResumeProbeAndKeepsBothCallbacks() {
        val results = mutableListOf<Pair<String, HostRuntimeProbeStatus>>()
        val resume = session.request("resume", false, 1_500) { results += "resume" to it }
        now = 100
        val verify = session.request("verify", true, 15_000) { results += "verify" to it }
        assertEquals(resume.nonce, verify.nonce)
        assertFalse(verify.newWindow)
        assertTrue(verify.scheduleTimeout)
        now = 1_500
        assertFalse(session.onTimeout(resume.nonce))
        assertTrue(deliveries.isEmpty())
        now = 15_100
        assertTrue(session.onTimeout(verify.nonce))
        assertTrue(results.isEmpty()) // 与生产一致，回调交给主线程队列执行。
        drain()
        assertEquals(listOf("resume" to HostRuntimeProbeStatus.NOT_RUNNING,
            "verify" to HostRuntimeProbeStatus.NOT_RUNNING), results)
    }

    fun aReplyAfterTheOriginalShortDeadlineConfirmsAllRequestsOnce() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("resume", false, 1_500, results::add)
        now = 100
        session.request("verify", true, 15_000, results::add)
        now = 2_000
        assertEquals(HostRuntimeProbeTracker.ReplyResult.CONFIRMED, session.onReply("resume", true))
        assertFalse(session.isPending("resume"))
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("resume", true))
        now = 15_100
        assertFalse(session.onTimeout("resume"))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.CONFIRMED, HostRuntimeProbeStatus.CONFIRMED), results)
    }

    fun aResumeProbeCannotShortenAnActiveVerificationOrReplaceItsCallback() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("verify", true, 15_000, results::add)
        now = 100
        val joined = session.request("resume", false, 1_500, results::add)
        assertEquals("verify", joined.nonce)
        assertFalse(joined.newWindow)
        assertFalse(joined.scheduleTimeout)
        now = 1_600
        assertFalse(session.onTimeout(joined.nonce))
        now = 15_000
        assertTrue(session.onTimeout(joined.nonce))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.NOT_RUNNING, HostRuntimeProbeStatus.NOT_RUNNING), results)
    }

    fun repeatedOrdinaryProbesDoNotKeepAnUnresponsiveHostPendingForever() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("first", false, 1_500, results::add)
        now = 1_000
        val joined = session.request("second", false, 1_500, results::add)
        assertFalse(joined.scheduleTimeout)
        now = 1_500
        assertTrue(session.onTimeout("first"))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.UNCONFIRMED, HostRuntimeProbeStatus.UNCONFIRMED), results)
    }

    fun anExpiredWindowSettlesBeforeItsReplacementAndRejectsLateReplies() {
        val results = mutableListOf<Pair<String, HostRuntimeProbeStatus>>()
        session.request("expired", false, 1_500) { results += "expired" to it }
        now = 2_000
        val replacement = session.request("current", true, 15_000) { results += "current" to it }
        assertTrue(replacement.newWindow)
        assertTrue(replacement.scheduleTimeout)
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("expired", true))
        assertFalse(session.onTimeout("expired"))
        assertTrue(session.isPending("current"))
        assertTrue(session.hasPendingRequest)
        session.onHeartbeat()
        drain()
        assertEquals(listOf("expired" to HostRuntimeProbeStatus.UNCONFIRMED,
            "current" to HostRuntimeProbeStatus.CONFIRMED), results)
    }

    fun mergingVerificationPreservesTheAliveWithoutHooksEvidence() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("resume", false, 1_500, results::add)
        assertEquals(HostRuntimeProbeTracker.ReplyResult.PENDING, session.onReply("resume", false))
        now = 100
        session.request("verify", true, 15_000, results::add)
        now = 15_100
        assertTrue(session.onTimeout("resume"))
        drain()
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, session.status)
        assertEquals(2, results.size)
    }

    private fun drain() {
        val pending = deliveries.toList()
        deliveries.clear()
        pending.forEach { it() }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostRuntimeProbeSessionCases) -> Unit) {
            val s = HostRuntimeProbeSessionCases()
            block(s)
        }
        runIsolated { s -> s.anExpiredPositiveReplySettlesAllObserversOnceWithoutWaitingForTheTimer() }
        runIsolated { s -> s.anExpiredNegativeReplyCannotChangeAnOrdinaryTimeoutIntoAVerifiedFailure() }
        runIsolated { s -> s.explicitVerificationUpgradesAResumeProbeAndKeepsBothCallbacks() }
        runIsolated { s -> s.aReplyAfterTheOriginalShortDeadlineConfirmsAllRequestsOnce() }
        runIsolated { s -> s.aResumeProbeCannotShortenAnActiveVerificationOrReplaceItsCallback() }
        runIsolated { s -> s.repeatedOrdinaryProbesDoNotKeepAnUnresponsiveHostPendingForever() }
        runIsolated { s -> s.anExpiredWindowSettlesBeforeItsReplacementAndRejectsLateReplies() }
        runIsolated { s -> s.mergingVerificationPreservesTheAliveWithoutHooksEvidence() }
    }
}

private class HostRuntimeProbeTrackerCases {

    private var now = 0L
    private val tracker = HostRuntimeProbeTracker { now }

    fun expiredRepliesAreRejectedBeforeTheDelayedTimerRunsWithoutChangingTimeoutEvidence() {
        for (verify in listOf(false, true)) for (installed in listOf(false, true)) for (offset in listOf(0L, 1L)) {
            var time = 0L
            val current = HostRuntimeProbeTracker { time }
            val timeout = if (verify) 15_000L else 1_500L
            assertTrue(current.begin("request", verify, timeout))
            time = timeout + offset
            assertEquals(ReplyResult.REJECTED, current.onReply("request", installed))
            assertEquals(if (verify) HostRuntimeProbeStatus.NOT_RUNNING else HostRuntimeProbeStatus.UNCONFIRMED,
                current.onTimeout("request"))
        }
    }

    fun aReplyImmediatelyBeforeTheDeadlineStillConfirms() {
        tracker.begin("request", true, 15_000)
        now = 14_999
        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("request", true))
        now = 15_000
        assertNull(tracker.onTimeout("request"))
    }

    fun `a plain probe that times out stays unconfirmed`() {
        assertTrue(tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)

        now = 1_500L
        assertEquals(HostRuntimeProbeStatus.UNCONFIRMED, tracker.onTimeout("nonce-1"))
    }

    fun `a user verify that times out reports not running`() {
        assertTrue(tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L))

        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))
    }

    fun `a reply carrying hooks installed confirms the module is live`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("nonce-1", hooksInstalled = true))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
    }

    fun `a reply without hooks is kept pending and times out as not running`() {
        tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L)

        assertEquals(ReplyResult.PENDING, tracker.onReply("nonce-1", hooksInstalled = false))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)

        now = 1_500L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))
    }

    fun `a heartbeat settles the probe window`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        tracker.onHeartbeat()
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
        assertFalse(tracker.isPending("nonce-1"))
    }

    fun `a reply for another window is ignored`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        assertEquals(ReplyResult.REJECTED, tracker.onReply("stale-nonce", hooksInstalled = true))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)
    }

    fun `concurrent probes merge into the in-flight window`() {
        assertTrue(tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L))
        assertFalse(tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L))
    }

    fun `a verified failure survives later non-verify probes`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))

        // 回到设置页的顺手探测既不闪回“确认中”，也不能把“未生效”降级成“未确认”。
        tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L)
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.status)
        now = 16_500L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-2"))
    }

    fun `a later confirmation clears the verified failure`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        now = 15_000L
        tracker.onTimeout("nonce-1")

        tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L)
        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("nonce-2", hooksInstalled = true))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
    }

    fun `a new probe keeps the previous confirmation until it settles`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        tracker.onReply("nonce-1", hooksInstalled = true)

        // 不把已确认闪回“确认中”；只有窗口收不到新证据时才降级。
        tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L)
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)

        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.UNCONFIRMED, tracker.onTimeout("nonce-2"))
    }

    fun `a completed verify timer cannot end a later verify window`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        tracker.onReply("nonce-1", hooksInstalled = true)
        now = 14_000L
        tracker.begin("nonce-2", verify = true, timeoutMs = 15_000L)

        now = 15_000L
        assertNull(tracker.onTimeout("nonce-1"))
        assertTrue(tracker.isPending("nonce-2"))
        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("nonce-2", hooksInstalled = true))
    }

    fun `a delayed timer cannot settle a replacement window even after its deadline`() {
        tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L)
        now = 2_000L
        tracker.begin("nonce-2", verify = true, timeoutMs = 15_000L)

        now = 17_000L
        assertNull(tracker.onTimeout("nonce-1"))
        assertTrue(tracker.isPending("nonce-2"))
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-2"))
        assertNull(tracker.onTimeout("nonce-2"))
    }

    fun `the current timer cannot settle before its deadline`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        now = 14_999L
        assertNull(tracker.onTimeout("nonce-1"))
        assertTrue(tracker.isPending("nonce-1"))
        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))
    }

    fun `a reply without hooks after a prior confirmation waits for timeout`() {
        tracker.onHeartbeat()
        tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L)

        assertEquals(ReplyResult.PENDING, tracker.onReply("nonce-1", hooksInstalled = false))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
        assertTrue(tracker.isPending("nonce-1"))
        now = 1_500L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))
    }

    fun `a pending reply after prior confirmation can still confirm hook installation`() {
        tracker.onHeartbeat()
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        assertEquals(ReplyResult.PENDING, tracker.onReply("nonce-1", hooksInstalled = false))
        now = 2_000L
        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("nonce-1", hooksInstalled = true))
        assertFalse(tracker.isPending("nonce-1"))
        now = 15_000L
        assertNull(tracker.onTimeout("nonce-1"))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostRuntimeProbeTrackerCases) -> Unit) {
            val s = HostRuntimeProbeTrackerCases()
            block(s)
        }
        runIsolated { s -> s.expiredRepliesAreRejectedBeforeTheDelayedTimerRunsWithoutChangingTimeoutEvidence() }
        runIsolated { s -> s.aReplyImmediatelyBeforeTheDeadlineStillConfirms() }
        runIsolated { s -> s.`a plain probe that times out stays unconfirmed`() }
        runIsolated { s -> s.`a user verify that times out reports not running`() }
        runIsolated { s -> s.`a reply carrying hooks installed confirms the module is live`() }
        runIsolated { s -> s.`a reply without hooks is kept pending and times out as not running`() }
        runIsolated { s -> s.`a heartbeat settles the probe window`() }
        runIsolated { s -> s.`a reply for another window is ignored`() }
        runIsolated { s -> s.`concurrent probes merge into the in-flight window`() }
        runIsolated { s -> s.`a verified failure survives later non-verify probes`() }
        runIsolated { s -> s.`a later confirmation clears the verified failure`() }
        runIsolated { s -> s.`a new probe keeps the previous confirmation until it settles`() }
        runIsolated { s -> s.`a completed verify timer cannot end a later verify window`() }
        runIsolated { s -> s.`a delayed timer cannot settle a replacement window even after its deadline`() }
        runIsolated { s -> s.`the current timer cannot settle before its deadline`() }
        runIsolated { s -> s.`a reply without hooks after a prior confirmation waits for timeout`() }
        runIsolated { s -> s.`a pending reply after prior confirmation can still confirm hook installation`() }
    }
}

private class IXposedServiceTransactionCodesCases {
    /** `interface-102.0.0` 的 AIDL 显式编号 + `FIRST_CALL_TRANSACTION`(1)。 */
    private val expected = linkedMapOf(
        "TRANSACTION_getApiVersion" to 2,
        "TRANSACTION_getFrameworkName" to 3,
        "TRANSACTION_getFrameworkVersion" to 4,
        "TRANSACTION_getFrameworkVersionCode" to 5,
        "TRANSACTION_getFrameworkProperties" to 6,
        "TRANSACTION_getScope" to 11,
        "TRANSACTION_requestScope" to 12,
        "TRANSACTION_removeScope" to 13,
        "TRANSACTION_getRunningTargets" to 14,
        "TRANSACTION_hotReloadModule" to 15,
        "TRANSACTION_requestRemotePreferences" to 21,
        "TRANSACTION_updateRemotePreferences" to 22,
        "TRANSACTION_deleteRemotePreferences" to 23,
        "TRANSACTION_listRemoteFiles" to 31,
        "TRANSACTION_openRemoteFile" to 32,
        "TRANSACTION_deleteRemoteFile" to 33
    )

    private fun stubConstants(resource: String = "io/github/libxposed/service/IXposedService\$Stub.class"): Map<String, Int> {
        val stream = IXposedServiceTransactionCodesCases::class.java.classLoader.getResourceAsStream(resource)
        assertNotNull("$resource must be on the unit-test runtime classpath", stream)
        return stream!!.use { readIntConstants(it.readBytes()) }
            .filterKeys { it.startsWith("TRANSACTION_") }
    }

    fun `AIDL transaction table of the pinned libxposed interface is unchanged`() {
        val actual = stubConstants()
        assertEquals(
            "IXposedService methods or numbering changed; re-verify every transaction the gateway issues",
            expected.toSortedMap(),
            actual.toSortedMap()
        )
    }

    fun `scope callback transaction table of the pinned libxposed interface is unchanged`() {
        assertEquals(
            mapOf("TRANSACTION_onScopeRequestApproved" to 2, "TRANSACTION_onScopeRequestFailed" to 3).toSortedMap(),
            stubConstants("io/github/libxposed/service/IXposedScopeCallback\$Stub.class").toSortedMap()
        )
    }

    fun `gateway constants match the generated stubs`() {
        val actual = stubConstants()
        val callback = stubConstants("io/github/libxposed/service/IXposedScopeCallback\$Stub.class")
        assertEquals(actual.getValue("TRANSACTION_requestRemotePreferences"), NPatchRemoteGateway.TRANSACTION_REQUEST_REMOTE_PREFERENCES)
        assertEquals(actual.getValue("TRANSACTION_updateRemotePreferences"), NPatchRemoteGateway.TRANSACTION_UPDATE_REMOTE_PREFERENCES)
        assertEquals(actual.getValue("TRANSACTION_getScope"), NPatchRemoteGateway.TRANSACTION_GET_SCOPE)
        assertEquals(actual.getValue("TRANSACTION_requestScope"), NPatchRemoteGateway.TRANSACTION_REQUEST_SCOPE)
        assertEquals(actual.getValue("TRANSACTION_listRemoteFiles"), NPatchRemoteGateway.TRANSACTION_LIST_REMOTE_FILES)
        assertEquals(actual.getValue("TRANSACTION_openRemoteFile"), NPatchRemoteGateway.TRANSACTION_OPEN_REMOTE_FILE)
        assertEquals(actual.getValue("TRANSACTION_deleteRemoteFile"), NPatchRemoteGateway.TRANSACTION_DELETE_REMOTE_FILE)
        assertEquals(callback.getValue("TRANSACTION_onScopeRequestApproved"), NPatchRemoteGateway.CALLBACK_TRANSACTION_SCOPE_APPROVED)
        assertEquals(callback.getValue("TRANSACTION_onScopeRequestFailed"), NPatchRemoteGateway.CALLBACK_TRANSACTION_SCOPE_FAILED)
    }

    fun `class constant reader decodes long and double slots without shifting later fields`() {
        // Hand-assembled class: two constant-pool slots for a long, then an int field with ConstantValue.
        val bytes = syntheticClassWithLongBeforeIntField()
        assertEquals(mapOf("TRANSACTION_x" to 7), readIntConstants(bytes))
    }

    private fun syntheticClassWithLongBeforeIntField(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutput(out).apply {
            u4(0xCAFEBABE.toInt()); u2(0); u2(52)
            // constant pool: 1=Utf8 "TRANSACTION_x", 2=Utf8 "I", 3=Utf8 "ConstantValue", 4=Long(5) [4,5], 6=Integer(7)
            u2(7)
            u1(1); utf("TRANSACTION_x")
            u1(1); utf("I")
            u1(1); utf("ConstantValue")
            u1(5); u4(0); u4(5)
            u1(3); u4(7)
            u2(0x0021); u2(0); u2(0) // access, this, super
            u2(0) // interfaces
            u2(1) // fields
            u2(0x0018); u2(1); u2(2) // static final, name=#1, desc=#2
            u2(1) // attributes
            u2(3); u4(2); u2(6) // ConstantValue -> #6
            u2(0) // methods
            u2(0) // class attributes
        }
        return out.toByteArray()
    }

    private class DataOutput(private val out: java.io.ByteArrayOutputStream) {
        private val data = java.io.DataOutputStream(out)
        fun u1(v: Int) = data.writeByte(v)
        fun u2(v: Int) = data.writeShort(v)
        fun u4(v: Int) = data.writeInt(v)
        fun utf(s: String) = data.writeUTF(s)
    }

    internal companion object {
        /** 只返回 `static final int`（描述符 `I`）且带 `ConstantValue` 的字段。 */
        fun readIntConstants(classBytes: ByteArray): Map<String, Int> {
            val input = DataInputStream(ByteArrayInputStream(classBytes))
            check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val ints = arrayOfNulls<Int>(count)
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    3 -> ints[index] = input.readInt()
                    4 -> input.readInt()
                    5, 6 -> { input.readLong(); index++ }
                    7, 8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 10, 11, 12, 17, 18 -> input.readInt()
                    15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                    else -> error("unsupported constant pool tag $tag at #$index")
                }
                index++
            }
            input.readUnsignedShort() // access
            input.readUnsignedShort() // this
            input.readUnsignedShort() // super
            repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
            val result = linkedMapOf<String, Int>()
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                val name = utf8[input.readUnsignedShort()]
                val descriptor = utf8[input.readUnsignedShort()]
                repeat(input.readUnsignedShort()) {
                    val attribute = utf8[input.readUnsignedShort()]
                    val length = input.readInt()
                    if (attribute == "ConstantValue" && descriptor == "I" && length == 2) {
                        val value = ints[input.readUnsignedShort()]
                        if (name != null && value != null) result[name] = value
                    } else {
                        input.skipBytes(length)
                    }
                }
            }
            return result
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (IXposedServiceTransactionCodesCases) -> Unit) {
            val s = IXposedServiceTransactionCodesCases()
            block(s)
        }
        runIsolated { s -> s.`AIDL transaction table of the pinned libxposed interface is unchanged`() }
        runIsolated { s -> s.`scope callback transaction table of the pinned libxposed interface is unchanged`() }
        runIsolated { s -> s.`gateway constants match the generated stubs`() }
        runIsolated { s -> s.`class constant reader decodes long and double slots without shifting later fields`() }
    }
}

private class LiveRequestIdentityCases {
    private val module = BuildConfig.APPLICATION_ID
    fun systemSenderAndProofBindTheSameUidWhenCreatorPackageIsFiltered() {
        assertTrue(LiveRequestIdentity.accepts(null, 123, null, module, 123, true))
        assertFalse(LiveRequestIdentity.accepts(null, 123, null, null, 123, true))
    }
    fun virtualizedPackageInfoCannotOverrideAuthoritativeModernSenderIdentity() {
        assertTrue(LiveRequestIdentity.accepts(module, 123, 999, module, 123, true))
    }
    fun aDifferentCreatorUidCannotBorrowTheCurrentSendersPackage() {
        assertFalse(LiveRequestIdentity.accepts(module, 999, 999, module, 123, true))
    }
    fun aDifferentSenderPackageCannotBorrowTheModulesProof() {
        assertFalse(LiveRequestIdentity.accepts(module, 123, 123, "other", 123, true))
    }
    fun visibleCreatorPackagesMustMatchEvenWhenTheSystemSenderMatches() {
        assertFalse(LiveRequestIdentity.accepts("other", 123, 123, module, 123, true))
    }
    fun olderSystemsRequireARealCreatorPackageAndMatchObservableUid() {
        assertTrue(LiveRequestIdentity.accepts(module, 123, null, null, -1, false))
        assertTrue(LiveRequestIdentity.accepts(module, 123, 123, null, -1, false))
        assertFalse(LiveRequestIdentity.accepts(null, 123, 123, module, 123, false))
        assertFalse(LiveRequestIdentity.accepts(module, 123, 999, null, -1, false))
    }
    fun invalidUidsNeverAuthorizeARequest() {
        for (uid in listOf(-1, 0)) {
            assertFalse(LiveRequestIdentity.accepts(module, uid, uid, module, uid, true))
            assertFalse(LiveRequestIdentity.accepts(module, uid, uid, null, -1, false))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiveRequestIdentityCases) -> Unit) {
            val s = LiveRequestIdentityCases()
            block(s)
        }
        runIsolated { s -> s.systemSenderAndProofBindTheSameUidWhenCreatorPackageIsFiltered() }
        runIsolated { s -> s.virtualizedPackageInfoCannotOverrideAuthoritativeModernSenderIdentity() }
        runIsolated { s -> s.aDifferentCreatorUidCannotBorrowTheCurrentSendersPackage() }
        runIsolated { s -> s.aDifferentSenderPackageCannotBorrowTheModulesProof() }
        runIsolated { s -> s.visibleCreatorPackagesMustMatchEvenWhenTheSystemSenderMatches() }
        runIsolated { s -> s.olderSystemsRequireARealCreatorPackageAndMatchObservableUid() }
        runIsolated { s -> s.invalidUidsNeverAuthorizeARequest() }
    }
}
