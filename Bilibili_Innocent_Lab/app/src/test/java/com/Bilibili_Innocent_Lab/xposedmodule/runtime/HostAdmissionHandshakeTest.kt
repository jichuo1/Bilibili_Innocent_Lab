package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test

class HostAdmissionHandshakeTest {
    private val nonce = "00000000-0000-0000-0000-000000000001"
    private val challenge = "00000000-0000-0000-0000-000000000002"
    private fun snapshot(revision: Long = 0L, decision: UserTermsDecision = UserTermsDecision.ACCEPTED) =
        RemoteHookConfigSnapshot(1L, BuildConfig.VERSION_CODE.toLong(), true, 0L, decision,
            RemoteHookConfigContract.resolveSourceValues(mapOf(RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to revision)))
    private val normal = snapshot()
    private fun identity(config: RemoteHookConfigSnapshot = normal) = PublicationIdentity(nonce, 1L, 1L, 1L,
        RemoteHookConfigContract.contentFingerprint(config))
    private fun reply(status: String, config: RemoteHookConfigSnapshot = normal) = HostAdmissionHandshake.Reply(
        HostAdmissionContract.VERSION, BuildConfig.VERSION_CODE.toLong(), nonce, status,
        identity(config), HostAdmissionContract.NORMAL, challenge)

    private fun exchange(prepared: HostAdmissionHandshake.Reply, granted: HostAdmissionHandshake.Reply = reply("granted"),
        config: RemoteHookConfigSnapshot? = normal, direct: Boolean = false, npatch: Boolean = false) = HostAdmissionHandshake.exchange(
        "provider", nonce, config, direct, 100L, { 0L }, { prepared }, { value, _ ->
            assertEquals(challenge, value)
            granted
        }, nPatchRuntime = npatch)

    @Test fun currentNpatchCloseRevisionSurvivesBothHandshakePhasesOnlyWithAValidEnvelope() {
        val closed = reply("manager_sync_required").copy(disabledRevision = 42L)
        for (result in listOf(exchange(closed), exchange(reply("prepared"), closed))) {
            assertNull(result.grant)
            assertEquals(42L, result.disabledRevision)
            assertEquals(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, result.reason)
        }
        for (unbound in listOf(closed.copy(nonce = "wrong-nonce-value"), closed.copy(moduleVersion = -1))) {
            assertEquals(0L, exchange(unbound).disabledRevision)
            assertEquals(0L, exchange(reply("prepared"), unbound).disabledRevision)
        }
    }

    @Test fun npatchRequiresANoRootRevisionWhileStandardAdmissionKeepsItsOriginalContract() {
        assertNotNull(exchange(reply("prepared")).grant)
        val stale = exchange(reply("prepared"), npatch = true)
        assertNull(stale.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, stale.reason)
        val current = normal.copy(noRootRevision = 42L)
        assertEquals(current, exchange(reply("prepared", current), reply("granted", current), current, npatch = true).grant?.snapshot)
    }

    @Test fun completeCurrentSnapshotRequiresBoundPrepareAndConfirm() {
        val result = exchange(reply("prepared"))
        assertEquals(normal, result.grant?.snapshot)
        assertEquals("confirm", result.diagnostics.single().phase)
        for (wrong in listOf(reply("granted").copy(nonce = "another-nonce-value"),
            reply("granted").copy(moduleVersion = -1), reply("granted").copy(source = HostAdmissionContract.DIRECT),
            reply("granted").copy(identity = identity().copy(consentRevision = 2L)))) {
            assertNull(exchange(reply("prepared"), wrong).grant)
        }
    }

    @Test fun denialRequiresCurrentVersionAndNonceInBothPhases() {
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, exchange(reply("denied")).reason)
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, exchange(reply("prepared"), reply("denied")).reason)
        for (bad in listOf(reply("denied").copy(version = -1), reply("denied").copy(moduleVersion = -1),
            reply("denied").copy(nonce = "wrong-nonce-value"))) {
            assertEquals(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, exchange(bad).reason)
            assertEquals(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, exchange(reply("prepared"), bad).reason)
        }
    }

    @Test fun missingChallengeOrIdentityIsAResponseFailureWithoutTermsDenial() {
        for (bad in listOf(reply("prepared").copy(identity = null), reply("prepared").copy(challenge = null),
            reply("prepared").copy(challenge = "short"), reply("prepared").copy(source = "unknown"))) {
            val result = exchange(bad)
            assertNull(result.grant)
            assertEquals(HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, result.reason)
            assertFalse(HostAdmissionRouteClassifier.latchesDenial(result.reason))
        }
    }

    @Test fun newerConfigRequiresResyncAndRecoversWithANewCompleteSnapshot() {
        val newer = snapshot(5L)
        val old = exchange(reply("prepared", newer))
        assertEquals(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, old.reason)
        assertFalse(old.diagnostics.single().fingerprintMatches!!)
        assertTrue(HostAdmissionRouteClassifier.blocksFallback(old.reason))
        val recovered = exchange(reply("prepared", newer), reply("granted", newer), newer)
        assertEquals(newer, recovered.grant?.snapshot)
        assertEquals(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, exchange(reply("manager_sync_required")).reason)
        assertEquals(HostAdmissionRouteClassifier.REASON_CONSENT_PENDING, exchange(reply("consent_commit_failed")).reason)
    }

    @Test fun directPathRetainsCompleteDocumentAndConsentVerification() {
        fun document(config: RemoteHookConfigSnapshot) = RemoteHookConfigContract.encode(config.generation,
            config.moduleVersionCode, config.deliveryEnabled, config.noRootRevision, config.decision, config.values)
        fun prepared(config: RemoteHookConfigSnapshot) = reply("prepared", config).copy(source = HostAdmissionContract.DIRECT,
            document = HostAdmissionContract.encode(document(config)))
        val granted = reply("granted").copy(source = HostAdmissionContract.DIRECT)
        assertNotNull(exchange(prepared(normal), granted, direct = true).grant)
        assertNull(exchange(prepared(normal), granted, direct = false).grant)
        val corrupt = document(normal).toMutableMap().apply { put(RemoteHookConfigContract.KEY_DIGEST, "0".repeat(64)) }
        for (bad in listOf(prepared(normal).copy(document = "invalid"),
            prepared(normal).copy(document = HostAdmissionContract.encode(corrupt)), prepared(snapshot(decision = UserTermsDecision.DECLINED)))) {
            val result = exchange(bad, granted, direct = true)
            assertNull(result.grant)
            assertEquals(HostAdmissionRouteClassifier.REASON_CONFIG_INVALID, result.reason)
            assertTrue(HostAdmissionRouteClassifier.blocksFallback(result.reason))
        }
    }

    @Test fun lateConfirmCannotInstallAndDiagnosticsCannotEchoUntrustedText() {
        var now = 0L
        val result = HostAdmissionHandshake.exchange("provider", nonce, normal, false, 100L, { now },
            { reply("prepared") }, { _, _ -> now = 100L; reply("granted") })
        assertNull(result.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_TIMEOUT, result.reason)
        val secret = "private-value-that-must-not-be-logged"
        val summary = HostAdmissionDiagnostic(secret, secret, secret).summary()
        assertFalse(summary.contains(secret))
        assertTrue(summary.contains("status=unknown"))
    }

    @Test fun verifiedNegativePrepareAtTheDeadlineStillBlocksFallback() {
        for ((status, reason) in mapOf("denied" to HostAdmissionRouteClassifier.REASON_DENIED,
            "manager_sync_required" to HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            "consent_commit_failed" to HostAdmissionRouteClassifier.REASON_CONSENT_PENDING)) {
            var now = 0L
            val result = HostAdmissionHandshake.exchange("provider", nonce, normal, false, 100L, { now },
                { now = 100L; reply(status) }, { _, _ -> fail("negative prepare must not confirm"); null })
            assertNull(result.grant)
            assertEquals(reason, result.reason)
            assertTrue(HostAdmissionRouteClassifier.blocksFallback(result.reason))
        }
    }

    @Test fun verifiedNegativeConfirmAtTheDeadlineStillBlocksFallback() {
        for ((status, reason) in mapOf("denied" to HostAdmissionRouteClassifier.REASON_DENIED,
            "manager_sync_required" to HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            "consent_commit_failed" to HostAdmissionRouteClassifier.REASON_CONSENT_PENDING)) {
            var now = 0L
            val result = HostAdmissionHandshake.exchange("provider", nonce, normal, false, 100L, { now },
                { reply("prepared") }, { _, _ -> now = 100L; reply(status) })
            assertNull(result.grant)
            assertEquals(reason, result.reason)
        }
    }

    @Test fun latePreparedResponseCannotStartAConfirmation() {
        var now = 0L
        val result = HostAdmissionHandshake.exchange("provider", nonce, normal, false, 100L, { now },
            { now = 100L; reply("prepared") }, { _, _ -> fail("late prepare must not confirm"); null })
        assertNull(result.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_TIMEOUT, result.reason)
    }
}
