package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.SnapshotHookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import org.junit.Assert.*
import org.junit.Test

class AudioRescueConfigTest {
    fun `defaults are off and unknown channel mode does not activate audio hooks`() {
        for (value in listOf(-1, 0, 3, Int.MAX_VALUE)) {
            val config = readAudioRescueConfig(SnapshotHookConfigSource(mapOf(FeaturePreferences.PLAYER_AUDIO_CHANNELS to value)))
            assertFalse(config.enabled)
            assertEquals(0, config.channels)
        }
    }
    fun `each control activates independently`() {
        for (key in listOf(FeaturePreferences.PLAYER_AUDIO_PEAK_GUARD, FeaturePreferences.PLAYER_AUDIO_NOISE_REDUCTION, FeaturePreferences.PLAYER_AUDIO_VOICE_FOCUS)) {
            assertTrue(readAudioRescueConfig(SnapshotHookConfigSource(mapOf(key to true))).enabled)
        }
        for (mode in 1..2) assertTrue(AudioRescueConfig(channels = mode).enabled)
    }
    fun `audio settings are typed backed up and delivered through catalog`() {
        val specs = SettingsCatalog.specs.filter { it.id.startsWith("player.audio.") }
        assertEquals(4, specs.size)
        // Peak guard and channels landed first (v81); speech controls followed with the model (v82).
        assertEquals(setOf(81, 82), specs.map { it.introducedCatalogVersion }.toSet())
        assertEquals(81, specs.single { it.id == "player.audio.peak_guard" }.introducedCatalogVersion)
        assertEquals(82, specs.single { it.id == "player.audio.voice_focus" }.introducedCatalogVersion)
        assertEquals(3, specs.count { it.defaultValue == SettingValue.Bool(false) })
        assertEquals(SettingValue.IntValue(0), specs.single { it.id == "player.audio.channels" }.defaultValue)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AudioRescueConfigTest) -> Unit) {
            val s = AudioRescueConfigTest()
            block(s)
        }
        runIsolated { s -> s.`defaults are off and unknown channel mode does not activate audio hooks`() }
        runIsolated { s -> s.`each control activates independently`() }
        runIsolated { s -> s.`audio settings are typed backed up and delivered through catalog`() }
    }
}
