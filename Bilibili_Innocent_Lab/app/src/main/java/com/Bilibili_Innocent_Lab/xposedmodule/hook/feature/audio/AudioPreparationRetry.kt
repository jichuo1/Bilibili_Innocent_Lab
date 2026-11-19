package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

/** Recovery for delayed framework publication, with finite worker lifetime and no busy loop. */
internal object AudioPreparationRetry {
    fun delay(attempt: Int, nativeLoadingStarted: Boolean): Long? =
        if (nativeLoadingStarted) null else listOf(1_000L, 4_000L, 15_000L, 45_000L).getOrNull(attempt)
}
