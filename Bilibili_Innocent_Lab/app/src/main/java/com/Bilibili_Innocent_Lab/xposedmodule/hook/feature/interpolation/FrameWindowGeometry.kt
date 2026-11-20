package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.view.Surface
import androidx.annotation.Keep

/** Read on the attachment worker while the host decoder still owns the display. */
@Keep
internal object FrameWindowGeometry {
    private val available by lazy {
        try {
            System.loadLibrary("bil_frame_window")
            true
        } catch (_: LinkageError) {
            false
        }
    }

    fun size(surface: Surface): Pair<Int, Int>? {
        if (!available || !surface.isValid) return null
        val dimensions = extent(surface)
        val width = (dimensions ushr 32).toInt()
        val height = dimensions.toInt()
        return if (width > 0 && height > 0) width to height else null
    }

    private external fun extent(surface: Surface): Long
}
