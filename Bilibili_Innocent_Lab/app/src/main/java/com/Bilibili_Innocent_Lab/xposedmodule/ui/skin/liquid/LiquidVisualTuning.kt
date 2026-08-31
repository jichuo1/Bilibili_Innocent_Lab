package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole

/**
 * Liquid 的纯视觉调参结果。
 *
 * 背景色洗和表面透明度集中在同一策略中，避免 backdrop、卡片与模态层各自硬编码后再次
 * 出现过饱和背景或过重实色遮罩。该结构不依赖 Android 对象，便于 JVM 测试约束范围。
 */
internal data class LiquidVisualTuning(
    val primaryWashAlpha: Int,
    val secondaryWashAlpha: Int,
    val cardGlassAlpha: Float,
    val modalGlassAlpha: Float,
    val motionGlassAlpha: Float,
    val cardFallbackAlpha: Float,
    val modalFallbackAlpha: Float,
    val motionFallbackAlpha: Float,
    val saturation: Float
) {
    init {
        require(primaryWashAlpha in 0..255)
        require(secondaryWashAlpha in 0..255)
        require(cardGlassAlpha in 0f..1f)
        require(modalGlassAlpha in 0f..1f)
        require(motionGlassAlpha in 0f..1f)
        require(cardFallbackAlpha in 0f..1f)
        require(modalFallbackAlpha in 0f..1f)
        require(motionFallbackAlpha in 0f..1f)
        require(cardFallbackAlpha >= cardGlassAlpha)
        require(modalFallbackAlpha >= modalGlassAlpha)
        require(motionFallbackAlpha >= motionGlassAlpha)
        require(saturation in 0f..2f)
    }
}

/** 深浅色只改变强度，不改变 underlay 的固定空间布局，保证重建结果确定。 */
internal object LiquidVisualTuningPolicy {
    fun resolve(dark: Boolean): LiquidVisualTuning = if (dark) {
        LiquidVisualTuning(
            primaryWashAlpha = 0x19,
            secondaryWashAlpha = 0x14,
            cardGlassAlpha = 0.24f,
            modalGlassAlpha = 0.38f,
            motionGlassAlpha = 0.34f,
            cardFallbackAlpha = 0.72f,
            modalFallbackAlpha = 0.88f,
            motionFallbackAlpha = 0.78f,
            saturation = 1.06f
        )
    } else {
        LiquidVisualTuning(
            primaryWashAlpha = 0x16,
            secondaryWashAlpha = 0x10,
            cardGlassAlpha = 0.20f,
            modalGlassAlpha = 0.36f,
            motionGlassAlpha = 0.30f,
            cardFallbackAlpha = 0.78f,
            modalFallbackAlpha = 0.92f,
            motionFallbackAlpha = 0.82f,
            saturation = 1.03f
        )
    }
}

/** 普通、模态与形变表面在 GPU/fallback 下的透明度映射，集中为可穷举测试的纯策略。 */
internal object LiquidSurfaceAlphaPolicy {
    fun resolve(
        role: SurfaceRole,
        translucentFallback: Boolean,
        parameters: LiquidParameters
    ): Float = when {
        role == SurfaceRole.MODAL && translucentFallback ->
            parameters.fallbackModalSurfaceAlpha
        role == SurfaceRole.MODAL -> parameters.modalSurfaceAlpha
        role == SurfaceRole.MOTION_SURFACE && translucentFallback ->
            parameters.fallbackMotionSurfaceAlpha
        role == SurfaceRole.MOTION_SURFACE -> parameters.motionSurfaceAlpha
        translucentFallback -> parameters.fallbackSurfaceAlpha
        else -> parameters.surfaceAlpha
    }
}
