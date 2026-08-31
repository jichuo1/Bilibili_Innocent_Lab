package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters

/** 把纯视觉调参转换为 Liquid renderer 参数，不读取偏好或持有 Context。 */
internal object LiquidTokenResolver {
    fun resolve(
        tuning: LiquidVisualTuning,
        profile: LiquidEffectProfile = LiquidEffectProfile.STANDARD
    ): LiquidParameters {
        val realtime = profile == LiquidEffectProfile.REALTIME_CAPTURE
        return LiquidParameters(
            blurRadiusDp = if (realtime) 26f else 18f,
            refractionHeightDp = if (realtime) 34f else 16f,
            refractionAmountDp = if (realtime) 24f else 9f,
            depthEffect = if (realtime) 0.52f else 0.18f,
            interiorDistortionDp = if (realtime) 8.5f else 0f,
            chromaticShiftDp = if (realtime) 1.8f else 0f,
            scatteringRadiusDp = if (realtime) 5.5f else 0f,
            scatteringStrength = if (realtime) 0.48f else 0f,
            chromaticAberration = realtime,
            saturation = if (realtime) (tuning.saturation + 0.08f).coerceAtMost(2f)
            else tuning.saturation,
            surfaceAlpha = if (realtime) tuning.cardGlassAlpha * 0.72f
            else tuning.cardGlassAlpha,
            modalSurfaceAlpha = if (realtime) tuning.modalGlassAlpha * 0.84f
            else tuning.modalGlassAlpha,
            motionSurfaceAlpha = if (realtime) tuning.motionGlassAlpha * 0.80f
            else tuning.motionGlassAlpha,
            fallbackSurfaceAlpha = tuning.cardFallbackAlpha,
            fallbackModalSurfaceAlpha = tuning.modalFallbackAlpha,
            fallbackMotionSurfaceAlpha = tuning.motionFallbackAlpha,
            highlightWidthDp = if (realtime) 1.7f else 1.1f,
            highlightAlpha = if (realtime) 0.72f else 0.4f,
            highlightGlowWidthDp = if (realtime) 6.8f else 0f,
            highlightGlowAlpha = if (realtime) 0.18f else 0f,
            highlightBlurRadiusDp = 1.5f,
            highlightAngleDegrees = -35f,
            effectPaddingDp = if (realtime) 34f else 18f
        )
    }
}
