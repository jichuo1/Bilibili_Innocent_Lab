package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 插帧处理分辨率：源（可达 4K）在捕获的 GL 绘制中一次缩到此尺寸，推理、账本与呈现都在此尺寸进行，
 * 呈现器再按宿主窗口缩放。只缩小不放大，保持宽高比，宽高取偶数。上限来自：
 * - 屏幕：画面最终显示在面板上，超过面板（长边 × 短边）的像素看不到，处理它们只浪费 GPU；
 * - 能力：引擎按光流档分别实测的可持续像素上限（[Capability]）。自适应光流下 ≤ [STANDARD_FLOW_PIXELS]
 *   用 1/4 光流、以上用 1/8，后者每像素更便宜，所以 1440p 可能比 1080p 还快，两档必须分开判断。
 */
internal object FrameProcessingSize {
    data class Size(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
    }

    /** standard：1/4 光流档（≤ 1080p）可持续的最大像素；high：1/8 光流档（> 1080p），0 表示该档不可用。 */
    data class Capability(val standard: Long, val high: Long)

    /** 与原生 AdaptiveFlowPixels 一致。 */
    const val STANDARD_FLOW_PIXELS = 1920L * 1080L

    fun choose(sourceWidth: Int, sourceHeight: Int, capability: Capability, displayLong: Int, displayShort: Int): Size? {
        val full = fit(sourceWidth, sourceHeight, Long.MAX_VALUE, displayLong, displayShort) ?: return null
        if (full.pixels > STANDARD_FLOW_PIXELS && capability.high > STANDARD_FLOW_PIXELS) {
            val high = fit(sourceWidth, sourceHeight, minOf(full.pixels, capability.high), displayLong, displayShort)
            if (high != null && high.pixels > STANDARD_FLOW_PIXELS) return high
        }
        if (capability.standard <= 0L) return null
        return fit(sourceWidth, sourceHeight, minOf(capability.standard, STANDARD_FLOW_PIXELS), displayLong, displayShort)
    }

    fun fit(sourceWidth: Int, sourceHeight: Int, maxPixels: Long, displayLong: Int, displayShort: Int): Size? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxPixels <= 0L) return null
        val long = maxOf(sourceWidth, sourceHeight).toDouble()
        val short = minOf(sourceWidth, sourceHeight).toDouble()
        var scale = min(1.0, sqrt(maxPixels.toDouble() / (long * short)))
        if (displayLong > 0 && displayShort > 0) scale = min(scale, min(displayLong / long, displayShort / short))
        val widthLimit = if (displayLong > 0 && displayShort > 0)
            min(sourceWidth, if (sourceWidth >= sourceHeight) displayLong else displayShort) else sourceWidth
        val heightLimit = if (displayLong > 0 && displayShort > 0)
            min(sourceHeight, if (sourceWidth >= sourceHeight) displayShort else displayLong) else sourceHeight
        var width = even(sourceWidth * scale, widthLimit)
        var height = even(sourceHeight * scale, heightLimit)
        // 取偶数可能向上取整超出预算：按比例逐步收缩直到满足。
        while (width.toLong() * height > maxPixels && width > 2 && height > 2) {
            scale *= 0.99
            width = even(sourceWidth * scale, widthLimit)
            height = even(sourceHeight * scale, heightLimit)
        }
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) return null
        return Size(width, height)
    }

    private fun even(value: Double, limit: Int): Int =
        (Math.round(value / 2.0) * 2).coerceAtLeast(2L).coerceAtMost((limit.toLong() / 2) * 2).toInt()

    private const val MIN_DIMENSION = 64
}
