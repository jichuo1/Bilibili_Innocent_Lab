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
    val interiorDistortionDp: Float,
    val chromaticShiftDp: Float,
    val scatteringRadiusDp: Float,
    val scatteringStrength: Float,
    /** 边缘定向高光强度；标准档为 0，行为与旧版完全一致。 */
    val specularStrength: Float,
    /** 掠射角增亮（Fresnel）强度；标准档为 0。 */
    val fresnelStrength: Float,
    /** 焦散随背景亮度的增益；标准档为 0，焦散退回旧版的恒定白。 */
    val causticLuminanceGain: Float,
    /** 折射带内侧的环境遮蔽强度，制造玻璃厚度；标准档为 0。 */
    val innerShadowStrength: Float,
    val saturation: Float,
    val surfaceAlpha: Float,
    val modalSurfaceAlpha: Float,
    val motionSurfaceAlpha: Float,
    val fallbackSurfaceAlpha: Float,
    val fallbackModalSurfaceAlpha: Float,
    val fallbackMotionSurfaceAlpha: Float,
    val highlightWidthDp: Float,
    val highlightAlpha: Float,
    val highlightGlowWidthDp: Float,
    val highlightGlowAlpha: Float,
    /** 定向高光的柔和度；越大越柔，映射为 shader 的镜面指数。 */
    val highlightBlurRadiusDp: Float,
    /**
     * 光源方位角。约定 `L = float2(cos θ, sin θ)`，屏幕 y 轴向下，
     * 因此 `-145°` 指向左上方，外法线朝左/朝上的边缘被点亮。
     */
    val highlightAngleDegrees: Float,
    val effectPaddingDp: Float
)
