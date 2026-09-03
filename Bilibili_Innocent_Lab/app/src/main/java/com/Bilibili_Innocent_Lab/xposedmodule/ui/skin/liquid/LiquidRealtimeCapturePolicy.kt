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
) {
    val pixels: Long
        get() = width.toLong() * height.toLong()
}

/**
 * 一次采样结果的处理结论。
 *
 * "本帧没有可见玻璃"必须与"采样失败"分开：前者是完全正常的状态（长列表把所有卡片滚出可视区、
 * 转场中 alpha 归零），若按失败计数，连续 4 帧就会永久熔断整个 Activity 的实时效果。
 */
internal enum class LiquidCaptureOutcome {
    /** 抑制遮罩已应用，可直接作为新 backdrop。 */
    SUPPRESSED,

    /** 本帧没有任何可见玻璃表面，截图里也就不含自身反馈，可原样采用且不计失败。 */
    NO_GLASS_VISIBLE,

    /** 真实失败：source 已关闭、root 尺寸非法或稳定底图缺失。 */
    FAILED
}

/**
 * 全屏实时取样的纯策略边界。
 *
 * 源区域始终覆盖完整 Activity 根层；目标位图按"像素预算"而不是固定倍率采样。
 * 三缓冲让 PixelCopy 不写入当前或上一帧仍可能被 RenderThread 引用的 Bitmap。
 */
internal object LiquidRealtimeCapturePolicy {
    const val MAX_TARGET_FRAMES_PER_SECOND = 120f
    const val RETRY_DELAY_MS = 120L
    const val INITIAL_DELAY_MS = 100L
    const val BUFFER_COUNT = 3
    const val MAX_CONSECUTIVE_FAILURES = 4
    const val BASE_SUPPRESSION_ALPHA = 0xDC

    /**
     * 采样像素预算。
     *
     * 旧实现用固定 `0.72` 倍率，在 1440×3200 面板上会产出 2,389,248 像素——正好顶到当时的
     * 240 万上限，单缓冲 9.11 MiB、三缓冲 27.3 MiB，120Hz 下仅 PixelCopy 回读就约 1.07 GiB/s。
     * 倍率固定意味着成本随面板分辨率平方增长，而这张图最终要经过 34dp 折射带、8.5dp 内透镜和
     * 5.5dp 散射的主动扭曲，高频细节本来就会被软化；旧实时档的 1.8dp 色散已在后续校准中关闭。
     *
     * 改为预算制后缓冲尺寸与面板分辨率解耦。为给 120Hz 滑动留出稳定余量，1080×2400
     * 与 1440×3200 都收敛到约 671×1490：单缓冲约 3.81 MiB、三缓冲约 11.44 MiB。
     * [MAX_TARGET_SCALE] 保证低分屏不会被反向放大到超过原有清晰度。
     */
    const val TARGET_SAMPLE_PIXELS = 1_000_000L
    const val MIN_SAMPLE_PIXELS = 240_000L

    private const val MAX_TARGET_SCALE = 0.72f
    private const val DEFAULT_REFRESH_RATE = 60f

    /**
     * 超过该面积的玻璃表面改用 2 抽样散射。
     *
     * 全屏模态在 1440p 上是 4.6 MP，按每像素 7 次 `content.eval` 计算单帧就是 3200 万次纹理
     * 取样；卡片级表面通常不到 1 MP，继续用 4 抽样。阈值取在两者之间。
     */
    private const val REDUCED_SCATTER_AREA_PX = 1_800_000L
    private const val MAX_STRETCH_DISTANCE = 0.18f
    private const val MAX_STRETCH_OPTICAL_BOOST = 0.85f
    private const val MAX_STRETCH_FEEDBACK_BAND_DP = 12f

    fun isSupported(sdkInt: Int, hardwareAccelerated: Boolean): Boolean =
        sdkInt >= 31 && hardwareAccelerated

    /**
     * @param pixelBudget 允许调用方按热状态收紧预算；始终不超过 [MAX_TARGET_SCALE] 对应的尺寸。
     */
    fun resolveSize(
        fullWidth: Int,
        fullHeight: Int,
        pixelBudget: Long = TARGET_SAMPLE_PIXELS
    ): LiquidRealtimeCaptureSize {
        require(fullWidth > 0 && fullHeight > 0) { "Capture dimensions must be positive" }
        val budget = pixelBudget.coerceAtLeast(MIN_SAMPLE_PIXELS)
        var width = (fullWidth * MAX_TARGET_SCALE).roundToInt().coerceAtLeast(1)
        var height = (fullHeight * MAX_TARGET_SCALE).roundToInt().coerceAtLeast(1)
        val pixels = width.toLong() * height.toLong()
        if (pixels > budget) {
            val scale = sqrt(budget.toDouble() / pixels.toDouble())
            width = (width * scale).roundToInt().coerceAtLeast(1)
            height = (height * scale).roundToInt().coerceAtLeast(1)
            while (width.toLong() * height.toLong() > budget) {
                if (width >= height) width -= 1 else height -= 1
            }
        }
        return LiquidRealtimeCaptureSize(width, height)
    }

    /** 大面积表面降低散射抽样数；成本按表面像素面积判定，与后端和档位无关。 */
    fun useReducedScatterTaps(widthPx: Int, heightPx: Int): Boolean =
        widthPx.toLong() * heightPx.toLong() > REDUCED_SCATTER_AREA_PX

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
