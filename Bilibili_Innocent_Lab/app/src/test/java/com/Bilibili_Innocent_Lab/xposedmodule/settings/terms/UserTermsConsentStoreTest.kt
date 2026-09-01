package com.Bilibili_Innocent_Lab.xposedmodule.settings.terms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTermsConsentStoreTest {

    @Test
    fun `current terms version is a positive locked protocol value`() {
        assertEquals(1, UserTermsConsentStore.CURRENT_TERMS_VERSION)
        assertTrue(UserTermsConsentStore.CURRENT_TERMS_VERSION > 0)
        assertEquals(1_787_909_100_000L, UserTermsConsentStore.LEGACY_ROLLOUT_CUTOFF_EPOCH_MS)
    }

    @Test
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

    @Test
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

    @Test
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

    @Test
    fun `records from another terms version require a new decision`() {
        val otherVersions = listOf(-1, 0, UserTermsConsentStore.CURRENT_TERMS_VERSION + 1)
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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
}
