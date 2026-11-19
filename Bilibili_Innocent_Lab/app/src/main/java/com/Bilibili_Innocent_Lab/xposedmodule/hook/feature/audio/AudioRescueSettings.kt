package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.HookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences

internal fun readAudioRescueConfig(source: HookConfigSource) = AudioRescueConfig(
    source.getBoolean(FeaturePreferences.PLAYER_AUDIO_PEAK_GUARD, false),
    source.getInt(FeaturePreferences.PLAYER_AUDIO_CHANNELS, 0).takeIf { it in 0..2 } ?: 0,
    source.getBoolean(FeaturePreferences.PLAYER_AUDIO_NOISE_REDUCTION, false),
    source.getBoolean(FeaturePreferences.PLAYER_AUDIO_VOICE_FOCUS, false)
)
