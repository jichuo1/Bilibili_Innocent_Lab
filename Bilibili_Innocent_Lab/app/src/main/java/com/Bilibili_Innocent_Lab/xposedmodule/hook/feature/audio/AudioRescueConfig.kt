package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

internal data class AudioRescueConfig(
    val peakGuard: Boolean = false,
    val channels: Int = 0,
    val noiseReduction: Boolean = false,
    val voiceFocus: Boolean = false
) {
    val enabled get() = peakGuard || channels in 1..2 || noiseReduction || voiceFocus

    companion object {
        const val ORIGINAL = 0
        const val MONO = 1
        const val AUTO_REPAIR = 2
    }
}
