package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import kotlin.math.roundToInt
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
    const val FRAME_INTERVAL_MS = 33L
    const val RETRY_DELAY_MS = 120L
    const val INITIAL_DELAY_MS = 100L
    const val BUFFER_COUNT = 3
    const val MAX_CONSECUTIVE_FAILURES = 4
    const val BASE_SUPPRESSION_ALPHA = 0xDC

    private const val TARGET_SCALE = 0.72f
    private const val MAX_SAMPLE_PIXELS = 2_400_000L

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

    fun nextFrameDelayMs(elapsedMs: Long): Long =
        (FRAME_INTERVAL_MS - elapsedMs.coerceAtLeast(0L)).coerceIn(0L, FRAME_INTERVAL_MS)

    fun shouldSuspend(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
}
