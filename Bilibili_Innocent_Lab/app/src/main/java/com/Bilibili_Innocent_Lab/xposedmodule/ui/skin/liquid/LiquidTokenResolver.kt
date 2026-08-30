package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters

/** 把纯视觉调参转换为 Liquid renderer 参数，不读取偏好或持有 Context。 */
internal object LiquidTokenResolver {
    fun resolve(tuning: LiquidVisualTuning): LiquidParameters = LiquidParameters(
        blurRadiusDp = 18f,
        refractionHeightDp = 16f,
        refractionAmountDp = 9f,
        depthEffect = 0.18f,
        chromaticAberration = false,
        saturation = tuning.saturation,
        surfaceAlpha = tuning.cardGlassAlpha,
        modalSurfaceAlpha = tuning.modalGlassAlpha,
        fallbackSurfaceAlpha = tuning.cardFallbackAlpha,
        fallbackModalSurfaceAlpha = tuning.modalFallbackAlpha,
        highlightWidthDp = 1.1f,
        highlightBlurRadiusDp = 1.5f,
        highlightAngleDegrees = -35f,
        effectPaddingDp = 18f
    )
}
