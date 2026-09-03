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
            // 色散会给每个像素增加两次纹理采样，也会在高对比边缘形成突兀彩边。
            chromaticShiftDp = 0f,
            scatteringRadiusDp = if (realtime) 5.5f else 0f,
            scatteringStrength = if (realtime) 0.48f else 0f,
            specularStrength = if (realtime) 0.20f else 0f,
            fresnelStrength = if (realtime) 0.08f else 0f,
            causticLuminanceGain = if (realtime) 0.55f else 0f,
            innerShadowStrength = if (realtime) 0.08f else 0f,
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
            // 实时档由 shader 提供连续高光，Canvas 只保留一条低强度轮廓线。
            highlightWidthDp = if (realtime) 1.0f else 1.1f,
            highlightAlpha = if (realtime) 0.22f else 0.4f,
            // 屏幕 y 轴向下；-145° => (-0.819, -0.574)，光源位于左上方。
            highlightAngleDegrees = -145f,
            effectPaddingDp = if (realtime) 34f else 18f
        )
    }
}
