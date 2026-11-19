package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.*
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedCalculatorDexWiringContractTest {
    @Test fun calculatorDexIsFeatureGatedAndNeverRunsInsideInstallationOrQuickLookup() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("PlayerSpeedLocator.assistedCalculator"))
        assertFalse(quick.contains("DexAssistSession("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (speedCalculatorPlanned) addAll(PlayerSpeedLocator.calculatorQueries)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        val installer = SourceContract.read("hook/feature/PlayerSpeedFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge")); assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("cachedCalculatorPoint?.let { PlayerSpeedLocator.resolveCalculator(loader, it) }"))
        val queries = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
            .after("DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR").before("DexAssistQuery.PLAYER_SPEED_MENU")
        assertTrue(queries.contains("getScriptPace"))
    }
}
