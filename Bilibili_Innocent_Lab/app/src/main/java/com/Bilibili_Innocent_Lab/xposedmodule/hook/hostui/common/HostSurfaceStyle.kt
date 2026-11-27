package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.os.Build
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.model.SurfaceRole

/** 顶栏与底栏的公共视觉参数；这里只声明外观，不实现采样、Shader 或 Drawable。 */
internal object HostSurfaceStyle {
    private const val MIN_RENDER_TARGET_BACKDROP_SDK_FULL = 3_700_002

    fun floating(dark: Boolean, radiusDp: Float, appearance: HostChromeAppearance = HostChromeAppearance()): LumenSurfaceOptions {
        val material = if (appearance.material == HostGlassMaterial.LIQUID) LumenSurfaceMaterial.LIQUID else LumenSurfaceMaterial.FROSTED
        val refraction = appearance.material == HostGlassMaterial.LIQUID
        return LumenSurfacePresets.floating(material).copy(
            radiusDp = radiusDp,
            tintOpacity = (if (dark) 120 else 112) / 255f,
            fallbackTintOpacity = (if (dark) 120 else 112) / 255f,
            // 模块柔光的 sampleAlpha 会归一化到完整帧透明度。色罩在采样之上单独合成，
            // 再乘 (1 - tint) 会让未模糊的宿主内容从底下漏出，不能靠增大 blur 修正。
            backdropOpacity = if (appearance.material == HostGlassMaterial.SOFT) 1f
            else (255 - if (dark) 120 else 112) / 255f,
            edgeWidthDp = .65f,
            edgeTopColor = ((if (dark) 56 else 140) shl 24) or 0xFFFFFF,
            edgeBottomColor = ((if (dark) 16 else 32) shl 24) or 0xFFFFFF,
            sampling = LumenSurfacePresets.floating().sampling.copy(
                blurRadiusDp = 17f,
                softwareBlurRadiusDp = 10f,
                refractionEnabled = refraction,
                backend = if (appearance.effectiveRenderer == HostGlassRenderer.SOFTWARE) LumenSurfaceBackend.SOFTWARE else LumenSurfaceBackend.AUTO
            )
        )
    }

    fun supportsRenderTargetBackdrop(): Boolean =
        Build.VERSION.SDK_INT >= 37 && RenderTargetBackdropGateApi36.isSdk37Point2OrLater()

    internal fun supportsRenderTargetBackdrop(sdkInt: Int, sdkIntFull: Int): Boolean =
        sdkInt >= 37 && sdkIntFull >= MIN_RENDER_TARGET_BACKDROP_SDK_FULL

    fun isRenderTargetBackdropEligible(options: LumenSurfaceOptions, hasViewContentSource: Boolean): Boolean =
        hasViewContentSource &&
            options.enabled &&
            options.material == LumenSurfaceMaterial.FROSTED &&
            options.role == SurfaceRole.FLOATING &&
            options.clipBackground &&
            options.opacity >= 1f &&
            options.backdropOpacity >= 1f &&
            options.sampling.enabled &&
            options.sampling.backend != LumenSurfaceBackend.SOFTWARE &&
            options.sampling.backend != LumenSurfaceBackend.STATIC &&
            !options.sampling.refractionEnabled &&
            !options.sampling.fadeEnabled

    fun enableRenderTargetBackdropIfEligible(
        binding: LumenSurfaceBinding?,
        options: LumenSurfaceOptions,
        hasViewContentSource: Boolean
    ) = syncRenderTargetBackdrop(binding, options, hasViewContentSource)

    fun syncRenderTargetBackdrop(
        binding: LumenSurfaceBinding?,
        options: LumenSurfaceOptions,
        hasViewContentSource: Boolean
    ) {
        if (binding == null) return
        if (!supportsRenderTargetBackdrop()) return
        runCatching {
            if (!binding.isBound) return
            val eligible = isRenderTargetBackdropEligible(options, hasViewContentSource)
            val requested = binding.renderTargetBackdropState()?.requested ?: false
            if (eligible) {
                if (!requested) binding.setRenderTargetBackdropEnabled(true)
            } else if (requested) {
                binding.setRenderTargetBackdropEnabled(false)
            }
        }
    }

    @RequiresApi(36)
    private object RenderTargetBackdropGateApi36 {
        @DoNotInline
        fun isSdk37Point2OrLater(): Boolean =
            supportsRenderTargetBackdrop(Build.VERSION.SDK_INT, Build.VERSION.SDK_INT_FULL)
    }

    fun selection(dark: Boolean, radiusDp: Float = 28f) = LumenSurfaceOptions(
        material = LumenSurfaceMaterial.STATIC, role = SurfaceRole.SELECTED_ITEM,
        radiusDp = radiusDp, tintOpacity = (if (dark) 218 else 210) / 255f,
        fallbackTintOpacity = (if (dark) 218 else 210) / 255f, edgeWidthDp = .65f,
        edgeTopColor = ((if (dark) 16 else 60) shl 24) or 0xFFFFFF,
        edgeBottomColor = ((if (dark) 5 else 12) shl 24) or 0xFFFFFF,
        sampling = LumenSurfacePresets.staticPanel().sampling
    )

    fun fusion(dark: Boolean, hold: Float, end: Float) = LumenSurfacePresets.fadingBand(hold, end).copy(
        tintOpacity = (if (dark) 146 else 132) / 255f, fallbackTintOpacity = (if (dark) 146 else 132) / 255f,
        backdropOpacity = (255 - if (dark) 146 else 132) / 255f,
        sampling = LumenSurfacePresets.fadingBand(hold, end).sampling.copy(blurRadiusDp = 17f, softwareBlurRadiusDp = 10f)
    )
}
