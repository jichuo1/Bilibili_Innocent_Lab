package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.*
import org.junit.Assert.*
import org.junit.Test

class SponsorDexWiringContractTest {
    @Test fun sponsorDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("SponsorPlayerLocator.assisted"))
        assertFalse(quick.contains("DexAssistSession("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (sponsorNeedsAssist) addAll(SponsorPlayerLocator.queries)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(background.contains("sponsorBlockEnabled && SponsorPlayerLocator.needsQuery"))
        val installer = SourceContract.read("hook/feature/SponsorBlockFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("registrationStarted.compareAndSet(false, true)"))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("sponsorBlockEnabled = prefs.getBoolean(FeaturePreferences.SPONSORBLOCK_ENABLED, false)"))
        assertTrue(hook.contains("if (sponsorInstaller.requiresAdaptationRetry)"))
        assertTrue(hook.contains("featureInstallCoordinator.installAll(listOf(sponsorInstaller))"))
    }
}
