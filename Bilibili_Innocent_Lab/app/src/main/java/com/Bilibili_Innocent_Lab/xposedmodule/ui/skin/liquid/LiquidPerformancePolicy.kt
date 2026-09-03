package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

/**
 * 实时 Liquid 的热状态调度策略。
 *
 * 数值与 Android 29+ PowerManager thermal status 对齐，但纯策略不引用高 API 类型，便于 JVM
 * 测试和 API 27–28 安全加载。热压力只降低截图节奏与采样分辨率，不修改折射、散射或玻璃透明度。
 */
internal object LiquidPerformancePolicy {
    const val THERMAL_STATUS_NONE = 0
    const val THERMAL_STATUS_LIGHT = 1
    const val THERMAL_STATUS_MODERATE = 2
    const val THERMAL_STATUS_SEVERE = 3
    const val THERMAL_STATUS_CRITICAL = 4
    const val THERMAL_STATUS_EMERGENCY = 5
    const val THERMAL_STATUS_SHUTDOWN = 6

    private const val MODERATE_MAX_FRAMES_PER_SECOND = 90f
    private const val SEVERE_MAX_FRAMES_PER_SECOND = 60f
    private const val CRITICAL_MAX_FRAMES_PER_SECOND = 30f
    private const val FALLBACK_FRAMES_PER_SECOND = 60f

    /**
     * 采样分辨率的热降载系数。
     *
     * 只降帧率仅减少"做几次"，每次的回读与纹理上传量不变；同时降分辨率才能真正压住带宽。
     * 系数单调不增，且实际尺寸仍由 [LiquidRealtimeCapturePolicy.resolveSize] 的下限兜底。
     */
    private const val MODERATE_PIXEL_SCALE = 0.78f
    private const val SEVERE_PIXEL_SCALE = 0.58f
    private const val CRITICAL_PIXEL_SCALE = 0.42f

    fun normalizeThermalStatus(status: Int): Int =
        status.takeIf { it in THERMAL_STATUS_NONE..THERMAL_STATUS_SHUTDOWN }
            ?: THERMAL_STATUS_NONE

    fun targetRefreshRate(requestedRefreshRate: Float, thermalStatus: Int): Float {
        val requested = requestedRefreshRate
            .takeIf { it.isFinite() && it > 0f }
            ?: FALLBACK_FRAMES_PER_SECOND
        val normalizedStatus = normalizeThermalStatus(thermalStatus)
        val thermalLimit = when {
            normalizedStatus >= THERMAL_STATUS_CRITICAL -> CRITICAL_MAX_FRAMES_PER_SECOND
            normalizedStatus >= THERMAL_STATUS_SEVERE -> SEVERE_MAX_FRAMES_PER_SECOND
            normalizedStatus >= THERMAL_STATUS_MODERATE -> MODERATE_MAX_FRAMES_PER_SECOND
            else -> LiquidRealtimeCapturePolicy.MAX_TARGET_FRAMES_PER_SECOND
        }
        return requested.coerceAtMost(thermalLimit)
    }

    /** 热压力下的采样像素预算；较低的基础预算永远不会被反向抬高。 */
    fun samplePixelBudget(
        basePixelBudget: Long = LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS,
        thermalStatus: Int
    ): Long {
        val base = basePixelBudget.coerceAtLeast(LiquidRealtimeCapturePolicy.MIN_SAMPLE_PIXELS)
        val normalizedStatus = normalizeThermalStatus(thermalStatus)
        val scale = when {
            normalizedStatus >= THERMAL_STATUS_CRITICAL -> CRITICAL_PIXEL_SCALE
            normalizedStatus >= THERMAL_STATUS_SEVERE -> SEVERE_PIXEL_SCALE
            normalizedStatus >= THERMAL_STATUS_MODERATE -> MODERATE_PIXEL_SCALE
            else -> 1f
        }
        return (base * scale.toDouble())
            .toLong()
            .coerceIn(LiquidRealtimeCapturePolicy.MIN_SAMPLE_PIXELS, base)
    }
}
