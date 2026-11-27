package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/** Only the interpolation temperature pause is optional; all other admission and capacity gates remain. */
internal object FrameThermalControl {
    const val DEFAULT_EARLY_EXIT_HEADROOM = 0.85f

    /** 1.0 means severe throttling; retain the existing 0.85 early-exit boundary. */
    inline fun pauseHeadroom(
        enabled: Boolean,
        threshold: Float = DEFAULT_EARLY_EXIT_HEADROOM,
        query: () -> Float?
    ): Float? {
        if (!enabled) return null
        return query()?.takeIf { it >= threshold }
    }
}
