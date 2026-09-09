package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.HomeRecommendFilterCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsDestinationRegistry
import org.junit.Assert.*
import org.junit.Test

class ReleaseHighlightsTest {
    @Test fun freshInstallEstablishesBaselineWithoutClaimingItWasShown() {
        val state = ReleaseHighlightsPolicy.bootstrap(5,false)
        assertEquals(5,state.baseline); assertEquals(0,state.presented)
        assertNull(ReleaseHighlightsPolicy.pendingFrom(state,5))
        assertEquals(5,ReleaseHighlightsPolicy.pendingFrom(state,6))
    }
    @Test fun firstRolloutOnExistingInstallShowsOnlyCurrentBatchNotAnInventedHistory() {
        val state = ReleaseHighlightsPolicy.bootstrap(5,true)
        assertEquals(4,ReleaseHighlightsPolicy.pendingFrom(state,5))
        assertEquals(0,state.presented)
    }
    @Test fun actualPresentationIsMonotonicAcrossReopenDowngradeAndSameContentChannels() {
        val pending = ReleaseHighlightsState(0,0)
        assertEquals(0,ReleaseHighlightsPolicy.pendingFrom(pending,1))
        val shown = ReleaseHighlightsPolicy.presented(pending,1)
        assertNull(ReleaseHighlightsPolicy.pendingFrom(shown,1))
        assertEquals(shown,ReleaseHighlightsPolicy.presented(shown,0))
        assertEquals(1,ReleaseHighlightsPolicy.pendingFrom(shown,3))
        assertNull(ReleaseHighlightsPolicy.pendingFrom(ReleaseHighlightsState(1,9),4))
    }
    @Test fun installDatesAreOnlyConservativeBootstrapEvidence() {
        assertFalse(ReleaseHighlightsPolicy.upgraded(null,null))
        assertFalse(ReleaseHighlightsPolicy.upgraded(0,9))
        assertFalse(ReleaseHighlightsPolicy.upgraded(10,10))
        assertFalse(ReleaseHighlightsPolicy.upgraded(10,9))
        assertTrue(ReleaseHighlightsPolicy.upgraded(10,11))
    }
    @Test fun skippedReleasesCombineAndDeduplicateButDoNotAutomaticallyShowMaintenance() {
        fun entry(id: String, res: Int) = ReleaseHighlight(id,HighlightKind.NEW,res,standaloneTitleRes=1)
        val batches = listOf(ReleaseHighlightsBatch(1,listOf(entry("a",1))),
            ReleaseHighlightsBatch(2,listOf(entry("a",2),entry("b",3))),
            ReleaseHighlightsBatch(3,listOf(entry("maintenance",4)),automatic=false))
        assertEquals(listOf("a","b"),ReleaseHighlightsPolicy.entriesAfter(batches,0,true).map { it.id })
        assertEquals(2,ReleaseHighlightsPolicy.entriesAfter(batches,0,true).first().descriptionRes)
        assertEquals(listOf("maintenance"),ReleaseHighlightsPolicy.entriesAfter(batches,2,false).map { it.id })
        val state = ReleaseHighlightsPolicy.skipAutomatic(ReleaseHighlightsState(0,2),3)
        assertEquals(2,state.presented)
        assertNull(ReleaseHighlightsPolicy.pendingFrom(state,3))
    }
    @Test fun termsFocusLayoutAndOtherDialogsAllGateAutomaticPresentation() {
        assertTrue(ReleaseHighlightsPolicy.canShow(true,true,true,true,false,false))
        assertFalse(ReleaseHighlightsPolicy.canShow(false,true,true,true,false,false))
        assertFalse(ReleaseHighlightsPolicy.canShow(true,false,true,true,false,false))
        assertFalse(ReleaseHighlightsPolicy.canShow(true,true,false,true,false,false))
        assertFalse(ReleaseHighlightsPolicy.canShow(true,true,true,false,false,false))
        assertFalse(ReleaseHighlightsPolicy.canShow(true,true,true,true,true,false))
        assertFalse(ReleaseHighlightsPolicy.canShow(true,true,true,true,false,true))
    }
    @Test fun invalidOrFutureStoreFormatsNeverTurnIntoAnUnreadLoopOrDowngrade() {
        assertNull(ReleaseHighlightsStore.decode(emptyMap<String,Any>()))
        assertNull(ReleaseHighlightsStore.decode(mapOf("schema" to 2,"baseline" to 0,"presented" to 99)))
        assertNull(ReleaseHighlightsStore.decode(mapOf("schema" to 1,"baseline" to "0","presented" to 1)))
        assertNull(ReleaseHighlightsStore.decode(mapOf("schema" to 1,"baseline" to -1,"presented" to 1)))
        assertEquals(ReleaseHighlightsState(2,100),
            ReleaseHighlightsStore.decode(mapOf("schema" to 1,"baseline" to 2,"presented" to 100)))
    }
    @Test fun stableDestinationsAreIndependentOfTitlesIndicesAndObjectRecreation() {
        val registry = SettingsDestinationRegistry<Any>()
        val old = Any(); val current = Any()
        registry.bind("feature.stable",old); registry.bind("feature.stable",old)
        assertSame(old,registry.resolve("feature.stable") { true })
        registry.bind("feature.stable",current)
        assertNull(registry.resolve("feature.stable") { true })
        assertSame(current,registry.resolve("feature.stable") { it === current })
        registry.bind("another.feature",current)
        assertEquals(setOf("feature.stable","another.feature"),registry.idsFor(current))
        registry.clear()
        assertNull(registry.resolve("feature.stable") { true })
    }
    @Test fun bundledHighlightsAreBoundedLocalizedAndReferenceRealSettings() {
        assertEquals("Review bundled highlights when changing the release version",
            BuildConfig.VERSION_CODE,ReleaseHighlightsCatalog.REVIEWED_VERSION_CODE)
        val batches = ReleaseHighlightsCatalog.batches
        assertEquals(batches.size,batches.map { it.revision }.distinct().size)
        assertTrue(batches.all { it.revision > 0 })
        val entries = batches.flatMap { it.entries }
        assertTrue(entries.size in 1..64)
        assertEquals(entries.size,entries.map { it.id }.distinct().size)
        entries.forEach { assertTrue(it.titleRes != 0); assertTrue(it.descriptionRes != 0) }
        val targets = entries.mapNotNull { it.destination }
        assertTrue(targets.all { it.settingId in SettingsCatalog.byId })
        assertTrue(targets.filter { it.homeFilterOption }.all {
            SettingsCatalog.byId.getValue(it.settingId).storageKey in HomeRecommendFilterCatalog.preferenceKeys
        })
        // New settings must be reviewed for announcement/navigation; never infer changes from titles.
        val newer = SettingsCatalog.specs.filter {
            it.introducedCatalogVersion > ReleaseHighlightsCatalog.SETTINGS_BASELINE_VERSION
        }.map { it.id }.toSet()
        assertTrue(targets.map { it.settingId }.toSet().containsAll(newer))
    }
    @Test fun presentationStateIsNotAnExportedOrHostConfigurationSetting() {
        assertFalse(SettingsCatalog.specs.any { it.storageKey.startsWith("highlights_") })
        assertFalse(RemoteHookConfigContract.persistedKeys.any { it.startsWith("highlights_") })
        assertFalse(ReleaseHighlightsStore.PREF_FILE in RemoteHookConfigContract.persistedKeys)
    }
    @Test fun compactAndLargeFontLayoutsNeverReserveMoreThanTheWindowBudget() {
        assertEquals(ReleaseHighlightsLayout.Budget(380,false),
            ReleaseHighlightsLayout.budget(800,44,140,12,380,96))
        assertEquals(ReleaseHighlightsLayout.Budget(252,true),
            ReleaseHighlightsLayout.budget(296,44,160,12,380,96))
        assertEquals(ReleaseHighlightsLayout.Budget(1,true),
            ReleaseHighlightsLayout.budget(20,44,200,12,380,96))
    }
    @Test fun outstandingUpdateRequestsHavePriorityEvenBeforeTheirResultDialogExists() {
        val coordinator = com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateCheckCoordinator()
        val channel = com.Bilibili_Innocent_Lab.xposedmodule.runtime.GitHubReleaseChecker.UpdateChannel.STABLE
        coordinator.submit(com.Bilibili_Innocent_Lab.xposedmodule.runtime.UpdateCheckCoordinator.Request(channel,true))
        assertTrue(coordinator.isBusy())
        assertFalse(ReleaseHighlightsPolicy.canShow(true,true,true,true,false,false,coordinator.isBusy()))
        coordinator.complete(channel,channel)
        assertFalse(coordinator.isBusy())
        assertTrue(ReleaseHighlightsPolicy.canShow(true,true,true,true,false,false,coordinator.isBusy()))
    }
}
