package com.Bilibili_Innocent_Lab.xposedmodule.hook.config

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences as P
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class LiveHookConfigStateTest {
    private val base = mapOf<String, Any>(HookEntry.PREF_FREE_COPY_ENABLED to true,
        HookEntry.PREF_FREE_COPY_DESC_ENABLED to true, HookEntry.PREF_FREE_COPY_LIGHT_MODE to false,
        HookEntry.PREF_FREE_COPY_AUTO_LIGHT to false, P.DANMAKU_WEIGHT_FILTER_ENABLED to true,
        P.DANMAKU_WEIGHT_FILTER_MINIMUM to 3, P.REMOVE_VIP_COLORFUL_DANMAKU to true,
        P.HOME_RECOMMEND_TITLE_FILTER_ENABLED to true, P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS to "old",
        P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS to 1, P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS to 10,
        P.RECOMMEND_VIDEO_MIN_PLAY_COUNT to 100, P.RECOMMEND_VIDEO_MAX_PLAY_COUNT to 200,
        "structural" to false)
    private val installed = setOf("free_copy_comment_enabled", "free_copy_description_enabled",
        "player_danmaku_weight_filter_enabled", "player_danmaku_vip_colorful_removed",
        "home_recommend_title_filter_enabled", "home_recommend_duration_filter", "home_recommend_play_count_filter")
    private fun snapshot(revision: Long = 1, values: Map<String, Any> = base) =
        RemoteHookConfigSnapshot(revision, 21, true, revision, UserTermsDecision.ACCEPTED, values)
    private fun identity(revision: Long = 1) = PublicationIdentity("installation", 1, revision, revision, "%064x".format(revision))
    private fun state(values: Map<String, Any> = base) = LiveHookConfigState(snapshot(values = values), identity())

    @Test fun acceptedPublicationGenerationIsVisibleToTheAdaptationPlan() {
        val state = state()
        assertEquals(1L, state.revision)
        state.apply(snapshot(2, base + (P.DANMAKU_WEIGHT_FILTER_MINIMUM to 8)), identity(2), installed)
        assertEquals(2L, state.revision)
        state.apply(snapshot(1), identity(1), installed)
        assertEquals(2L, state.revision)
    }

    @Test fun installedValuesUpdateTogetherWhileStructuralChangesRemainDeferred() {
        val state = state()
        val report = state.apply(snapshot(2, base + mapOf(HookEntry.PREF_FREE_COPY_LIGHT_MODE to true,
            P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS to "new", P.DANMAKU_WEIGHT_FILTER_MINIMUM to 8,
            "structural" to true)), identity(2), installed)
        assertEquals(3, report.applied); assertEquals(1, report.deferred)
        assertEquals(setOf("new"), state.home().titles); assertEquals(8, state.danmaku().minimum)
        assertTrue(state.getBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, false))
        assertFalse(state.getBoolean("structural", true)); assertEquals("old", base[P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS])
    }

    @Test fun missingInstallationEvidenceNeverBecomesAnAppliedValue() {
        val state = state()
        val next = snapshot(2, base + (HookEntry.PREF_FREE_COPY_LIGHT_MODE to true))
        assertEquals("RESTART_REQUIRED", state.apply(next, identity(2), emptySet()).status)
        assertFalse(state.getBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, false))
        assertEquals(1, state.apply(next, identity(2), installed).applied)
    }

    @Test fun newHookFamiliesAndAutomaticLightCannotBeEnabledThroughValues() {
        val state = state(base + mapOf(HookEntry.PREF_FREE_COPY_ENABLED to false,
            P.REMOVE_VIP_COLORFUL_DANMAKU to false))
        val report = state.apply(snapshot(2, base + (HookEntry.PREF_FREE_COPY_AUTO_LIGHT to true)), identity(2), installed)
        assertEquals(0, report.applied); assertEquals(3, report.deferred)
        assertFalse(state.getBoolean(HookEntry.PREF_FREE_COPY_ENABLED, true))
        assertFalse(state.danmaku().colorful)
    }

    @Test fun registeredCopyAndColourPathsCanBeDisabledAndRestoredWithoutChangingTheirPlan() {
        val state = state()
        val off = base + mapOf(HookEntry.PREF_FREE_COPY_ENABLED to false, P.REMOVE_VIP_COLORFUL_DANMAKU to false)
        assertEquals(2, state.apply(snapshot(2, off), identity(2), installed).applied)
        assertFalse(state.danmaku().colorful)
        assertEquals(2, state.apply(snapshot(3), identity(3), installed).applied)
        assertTrue(state.getBoolean(HookEntry.PREF_FREE_COPY_ENABLED, false)); assertTrue(state.danmaku().colorful)
    }

    @Test fun invalidRangesAndTypesDoNotPartiallyApply() {
        val state = state()
        val report = state.apply(snapshot(2, base + mapOf(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS to 99,
            P.RECOMMEND_VIDEO_MAX_PLAY_COUNT to "bad", P.DANMAKU_WEIGHT_FILTER_MINIMUM to 11)), identity(2), installed)
        assertEquals(0, report.applied); assertEquals(3, report.deferred)
        assertEquals(1, state.home().duration.minSeconds); assertEquals(200, state.home().plays.maximum)
        assertEquals(3, state.danmaku().minimum)
    }

    @Test fun olderOrConflictingRevisionsCannotUndoNewerValues() {
        val state = state()
        val next = snapshot(3, base + (P.DANMAKU_WEIGHT_FILTER_MINIMUM to 9))
        assertEquals(1, state.apply(next, identity(3), installed).applied)
        assertEquals("UNCHANGED", state.apply(next, identity(3), installed).status)
        assertEquals("REJECTED", state.apply(snapshot(2), identity(2), installed).status)
        assertEquals("REJECTED", state.apply(snapshot(3), identity(3).copy(fingerprint = "f".repeat(64)), installed).status)
        assertEquals(9, state.danmaku().minimum)
    }

    @Test fun changedInstallationConsentVersionOrAuthorizationRequiresColdStart() {
        val state = state()
        val next = snapshot(2, base + (P.DANMAKU_WEIGHT_FILTER_MINIMUM to 9))
        for (id in listOf(identity(2).copy(incarnation = "new"), identity(2).copy(consentRevision = 2),
            identity(2).copy(snapshotRevision = 3))) assertEquals("REJECTED", state.apply(next, id, installed).status)
        for (invalid in listOf(next.copy(deliveryEnabled = false), next.copy(decision = UserTermsDecision.DECLINED),
            next.copy(moduleVersionCode = 22), next.copy(noRootRevision = 0))) {
            assertEquals("REJECTED", state.apply(invalid, identity(2), installed).status)
        }
        assertEquals(3, state.danmaku().minimum)
    }

    @Test fun expirationAtCommitLeavesAllValuesUntouched() {
        val state = state()
        assertEquals("REJECTED", state.apply(snapshot(2, base + (P.DANMAKU_WEIGHT_FILTER_MINIMUM to 9)),
            identity(2), installed) { false }.status)
        assertEquals(3, state.danmaku().minimum)
    }

    @Test fun lspatchUsesItsPublicationRevisionWithoutNpatchOptIn() {
        val initial = snapshot().copy(noRootRevision = 0)
        val state = LiveHookConfigState(initial, identity(), HostConfigChannel.LSPATCH)
        val next = snapshot(7, base + mapOf(HookEntry.PREF_FREE_COPY_LIGHT_MODE to true,
            P.DANMAKU_WEIGHT_FILTER_MINIMUM to 8, "structural" to true)).copy(noRootRevision = 0)
        val report = state.apply(next, identity(7), installed)
        assertEquals("APPLIED", report.status)
        assertEquals(2, report.applied)
        assertEquals(1, report.deferred)
        assertEquals(7L, report.revision)
        assertTrue(state.getBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, false))
        assertEquals(8, state.danmaku().minimum)
        assertFalse(state.getBoolean("structural", true))
        assertEquals(0, state.apply(next, identity(7), installed).applied)
        assertEquals("REJECTED", state.apply(initial, identity(), installed).status)
        assertEquals("REJECTED", state.apply(next, identity(7).copy(fingerprint = "f".repeat(64)), installed).status)
    }

    @Test fun liveChannelCannotAcceptAnotherCarriersDocumentOrStandardFramework() {
        val changed = snapshot(2, base + (HookEntry.PREF_FREE_COPY_LIGHT_MODE to true))
        val lspatch = LiveHookConfigState(snapshot().copy(noRootRevision = 0), identity(), HostConfigChannel.LSPATCH)
        assertEquals("REJECTED", lspatch.apply(changed, identity(2), installed).status)
        assertEquals("REJECTED", state().apply(changed.copy(noRootRevision = 0), identity(2), installed).status)
        val standard = LiveHookConfigState(snapshot(), identity(), HostConfigChannel.STANDARD)
        assertEquals("REJECTED", standard.apply(changed, identity(2), installed).status)
        assertFalse(lspatch.getBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, false))
    }

    @Test fun lspatchKeepsAuthorizationInstallationAndDeadlineBoundaries() {
        val initial = snapshot().copy(noRootRevision = 0)
        val state = LiveHookConfigState(initial, identity(), HostConfigChannel.LSPATCH)
        val next = snapshot(2, base + (HookEntry.PREF_FREE_COPY_LIGHT_MODE to true)).copy(noRootRevision = 0)
        for (id in listOf(identity(2).copy(incarnation = "new"), identity(2).copy(consentRevision = 2),
            identity(2).copy(snapshotRevision = 3))) {
            assertEquals("REJECTED", state.apply(next, id, installed).status)
        }
        for (invalid in listOf(next.copy(deliveryEnabled = false), next.copy(decision = UserTermsDecision.DECLINED),
            next.copy(moduleVersionCode = 22), next.copy(generation = 0))) {
            assertEquals("REJECTED", state.apply(invalid, identity(2), installed).status)
        }
        assertEquals("REJECTED", state.apply(next, identity(2), installed) { false }.status)
        assertEquals("RESTART_REQUIRED", state.apply(next, identity(2), emptySet()).status)
        assertFalse(state.getBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, false))
    }

    @Test fun npatchCannotRegressThePublicationOrderWhileIncreasingSnapshotRevision() {
        val state = LiveHookConfigState(snapshot(5), identity(5))
        val next = snapshot(4, base + (HookEntry.PREF_FREE_COPY_LIGHT_MODE to true)).copy(noRootRevision = 6)
        assertEquals("REJECTED", state.apply(next, identity(4), installed).status)
        assertFalse(state.getBoolean(HookEntry.PREF_FREE_COPY_LIGHT_MODE, false))
        val invalidInitial = LiveHookConfigState(snapshot(), identity(2))
        assertEquals("REJECTED", invalidInitial.apply(snapshot(3), identity(3), installed).status)
    }

    @Test fun concurrentReadersOnlySeeWholeImmutableRuleSets() {
        val state = state()
        val running = AtomicBoolean(true)
        val invalid = AtomicBoolean(false)
        val reader = Thread {
            while (running.get()) {
                val rules = state.home()
                if (rules.duration.maxSeconds - rules.duration.minSeconds != 9) invalid.set(true)
            }
        }
        reader.start()
        try {
            for (revision in 2L..100L) {
                val min = revision.toInt()
                val values = (base + mapOf(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS to min,
                    P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS to min + 9)).toMutableMap()
                state.apply(snapshot(revision, values), identity(revision), installed)
                values[P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS] = -1
            }
        } finally { running.set(false); reader.join(2_000) }
        assertFalse(reader.isAlive); assertFalse(invalid.get()); assertEquals(109, state.home().duration.maxSeconds)
    }
}
