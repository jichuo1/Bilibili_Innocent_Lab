package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.view.Surface
import androidx.annotation.Keep
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader

/** Read on the attachment worker while the host decoder still owns the display. */
@Keep
internal object FrameWindowGeometry {
    private val available by lazy {
        try {
            ModuleNativeLoader.load("bil_frame_window")
            true
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
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

    /** Headless Vulkan physical-device capability bitmask; runs Vulkan queries in native C++ only. */
    fun rawGpuCapabilities(): Long {
        if (!available) return 0L
        return runCatching { probeGpuCapabilities() }.getOrDefault(0L)
    }

    private external fun extent(surface: Surface): Long
    private external fun probeGpuCapabilities(): Long
}
