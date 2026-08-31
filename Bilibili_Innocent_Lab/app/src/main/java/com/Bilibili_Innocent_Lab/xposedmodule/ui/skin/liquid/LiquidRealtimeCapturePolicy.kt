package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Liquid 表面使用的视觉档位；标准档保持既有观感，实时档提高折射与高光强度。 */
internal enum class LiquidEffectProfile {
    STANDARD,
    REALTIME_CAPTURE
}

internal data class LiquidRealtimeCaptureSize(
    val width: Int,
    val height: Int
)

/**
 * 全屏实时取样的纯策略边界。
 *
 * 源区域始终覆盖完整 Activity 根层；目标位图按 0.72 倍采样并受 240 万像素上限保护。
 * 三缓冲让 PixelCopy 不写入当前或上一帧仍可能被 RenderThread 引用的 Bitmap。
 */
internal object LiquidRealtimeCapturePolicy {
    const val MAX_TARGET_FRAMES_PER_SECOND = 120f
    const val RETRY_DELAY_MS = 120L
    const val INITIAL_DELAY_MS = 100L
    const val BUFFER_COUNT = 3
    const val MAX_CONSECUTIVE_FAILURES = 4
    const val BASE_SUPPRESSION_ALPHA = 0xDC

    private const val TARGET_SCALE = 0.72f
    private const val MAX_SAMPLE_PIXELS = 2_400_000L
    private const val DEFAULT_REFRESH_RATE = 60f
    private const val MAX_STRETCH_DISTANCE = 0.18f
    private const val MAX_STRETCH_OPTICAL_BOOST = 0.85f
    private const val MAX_STRETCH_FEEDBACK_BAND_DP = 12f

    fun isSupported(sdkInt: Int, hardwareAccelerated: Boolean): Boolean =
        sdkInt >= 31 && hardwareAccelerated

    fun resolveSize(fullWidth: Int, fullHeight: Int): LiquidRealtimeCaptureSize {
        require(fullWidth > 0 && fullHeight > 0) { "Capture dimensions must be positive" }
        var width = (fullWidth * TARGET_SCALE).roundToInt().coerceAtLeast(1)
        var height = (fullHeight * TARGET_SCALE).roundToInt().coerceAtLeast(1)
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_SAMPLE_PIXELS) {
            val scale = sqrt(MAX_SAMPLE_PIXELS.toDouble() / pixels.toDouble())
            width = (width * scale).roundToInt().coerceAtLeast(1)
            height = (height * scale).roundToInt().coerceAtLeast(1)
            while (width.toLong() * height.toLong() > MAX_SAMPLE_PIXELS) {
                if (width >= height) width -= 1 else height -= 1
            }
        }
        return LiquidRealtimeCaptureSize(width, height)
    }

    /** 优先采用面板真实支持且不超过 120Hz 的最高档，异常 display 信息安全回退到 60Hz。 */
    fun targetRefreshRate(
        currentRefreshRate: Float,
        supportedRefreshRates: Iterable<Float>
    ): Float {
        val supported = supportedRefreshRates
            .filter { it.isFinite() && it > 0f && it <= MAX_TARGET_FRAMES_PER_SECOND + 0.5f }
            .maxOrNull()
        if (supported != null) return supported.coerceAtMost(MAX_TARGET_FRAMES_PER_SECOND)
        return currentRefreshRate
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(MAX_TARGET_FRAMES_PER_SECOND)
            ?: DEFAULT_REFRESH_RATE
    }

    fun frameIntervalNanos(refreshRate: Float): Long {
        val safeRate = refreshRate
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(MAX_TARGET_FRAMES_PER_SECOND)
            ?: DEFAULT_REFRESH_RATE
        return (1_000_000_000.0 / safeRate.toDouble()).roundToLong().coerceAtLeast(1L)
    }

    fun isFrameDue(frameTimeNanos: Long, nextCaptureNanos: Long): Boolean =
        frameTimeNanos >= nextCaptureNanos

    /** 系统 stretch 距离本身连续；smoothstep 只放大强度，不引入新的回弹时长或振荡。 */
    fun stretchOpticalIntensity(distance: Float): Float {
        val normalized = (distance.coerceAtLeast(0f) / MAX_STRETCH_DISTANCE).coerceIn(0f, 1f)
        val eased = normalized * normalized * (3f - 2f * normalized)
        return 1f + MAX_STRETCH_OPTICAL_BOOST * eased
    }

    /**
     * 只抑制真正暴露在 viewport 外沿的反馈。effectPadding 是 Shader 采样安全区，不能再被
     * 当作屏幕可见遮罩宽度，否则会从下一帧 source 中抹掉首尾和左右控件。
     */
    fun stretchFeedbackBandDp(effectPaddingDp: Float): Float =
        effectPaddingDp.coerceIn(0f, MAX_STRETCH_FEEDBACK_BAND_DP)

    fun shouldSuspend(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
}
