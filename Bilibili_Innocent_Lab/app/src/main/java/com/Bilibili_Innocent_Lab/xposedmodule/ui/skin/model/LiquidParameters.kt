package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model

/**
 * Liquid renderer 专属参数结构。
 *
 * M0 只建立契约，不创建 renderer，也不把这些参数混入 Material You 的公共颜色令牌。
 * 具体数值将在 M1 真机渲染和性能验证后由单独的 resolver 提供。
 */
internal data class LiquidParameters(
    val blurRadiusDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val depthEffect: Float,
    val chromaticAberration: Boolean,
    val saturation: Float,
    val surfaceAlpha: Float,
    val modalSurfaceAlpha: Float,
    val motionSurfaceAlpha: Float,
    val fallbackSurfaceAlpha: Float,
    val fallbackModalSurfaceAlpha: Float,
    val fallbackMotionSurfaceAlpha: Float,
    val highlightWidthDp: Float,
    val highlightBlurRadiusDp: Float,
    val highlightAngleDegrees: Float,
    val effectPaddingDp: Float
)
