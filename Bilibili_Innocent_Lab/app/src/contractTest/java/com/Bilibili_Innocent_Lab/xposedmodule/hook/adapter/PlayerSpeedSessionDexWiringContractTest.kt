package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.*
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedSessionDexWiringContractTest {
    @Test fun speedSessionDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("PlayerSpeedSessionLocator.assisted"))
        assertFalse(quick.contains("DexAssistSession("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (speedSessionPlanned) addAll(PlayerSpeedSessionLocator.queries)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(background.contains("PlayerSpeedSessionLocator.refreshCache(playerSpeedSessionEnabled,"))
        val installer = SourceContract.read("hook/feature/PlayerSpeedFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("PlayerSpeedSessionLocator.modern(loader, cachedSessionClasses)"))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("cachedSessionClasses = hostAdaptResult?.playerSpeedSession"))
        assertTrue(hook.contains("playerSpeedSessionEnabled = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedConfig.multiplier("))
    }
}
