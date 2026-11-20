package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 补帧目标帧率（帧率上限自适应）：取下列上限中最小者，再取源帧率的最大整数倍（至少 2 倍）：
 * - 用户上限（设置项，默认 60）；
 * - 系统实际给出的上限：面板最高模式，以及播放时实测的 vsync 频率（LTPO/厂商策略常把视频应用限在 60；
 *   root 改策略后可达 120/144/185，实测会如实反映）；
 * - 本机推理能力：每秒可持续的补帧次数（按当前处理尺寸实测的单次耗时）。
 * 目标还须能被某个显示模式整除地呈现（如 30→90 需要 90 Hz 或 180 Hz 模式），否则节奏不均。
 * 25 fps → 50 是已验收的例外（60 Hz 面板上亦可接受）。
 */
internal object FrameRateTarget {
    /** 允许的用户上限。 */
    val CEILINGS = listOf(60, 90, 120, 144, 165, 185)

    /** 可能产生的目标：24/25/30 fps 源在 185 以内的整数倍（≥ 2 倍）。 */
    val TARGETS: Set<Int> = buildSet {
        for (source in listOf(24, 25, 30)) for (k in 2..7) (source * k).takeIf { it <= 185 }?.let(::add)
    }

    /** QCOM generates one midpoint per adjacent source pair. */
    fun midpoint(sourceFps: Float, ceiling: Int, displayModes: List<Float>, measuredLimit: Float?): Int? {
        val source = normalizeSource(sourceFps) ?: return null
        val target = source * 2
        val limit = minOf(ceiling.toFloat(), displayModes.maxOrNull() ?: 60f, measuredLimit ?: Float.MAX_VALUE)
        return target.takeIf { it <= limit * 1.01f && presentable(it, displayModes) }
    }

    private fun presentable(target: Int, displayModes: List<Float>) = target == 50 || displayModes.isEmpty() ||
        displayModes.any { mode -> mode >= target - 1 && abs(mode / target - (mode / target).roundToInt()) < 0.02 }

    /**
     * 宿主内持续补帧可用的 GPU 时间比例：其余留给捕获、呈现与宿主 UI。须与引擎档位上限一致：1080p 1/4 档
     * 接受到 26 ms，30→60 每 33.3 ms 一次补帧即占 78%；此前用 0.75 时 25–26 ms 的档位被选中却又被这里拒绝，
     * 宿主真机 1080p 视频因此整段保持原生 30 fps（bridge.skip rate）。
     */
    private const val BUSY_FRACTION = 0.8

    /**
     * @param sourceFps 源帧率提示（ijk operating-rate），null 按 30 处理
     * @param ceiling 用户上限
     * @param displayModes 面板支持的刷新率
     * @param measuredLimit 实测的实际 vsync 频率；null 表示尚未测得
     * @param inferenceMs 当前处理尺寸的单次补帧耗时
     * @return 目标帧率；null 表示不值得接管（源已达上限或能力不足）
     */
    fun choose(sourceFps: Float?, ceiling: Int, displayModes: List<Float>, measuredLimit: Float?, inferenceMs: Double): Int? {
        val source = normalizeSource(sourceFps ?: 30f) ?: return null
        val panel = displayModes.maxOrNull() ?: 60f
        val limit = minOf(ceiling.toFloat(), panel, measuredLimit ?: Float.MAX_VALUE)
        val perSecond = if (inferenceMs > 0) BUSY_FRACTION * 1000.0 / inferenceMs else 0.0
        var k = floor(limit * 1.01 / source).toInt()
        while (k >= 2) {
            val target = source * k
            if (target in TARGETS && presentable(target, displayModes) && target - source <= perSecond) return target
            k--
        }
        return null
    }

    /** 29.97 → 30、23.976 → 24、25 → 25；其他源帧率不在支持范围。 */
    fun normalizeSource(fps: Float): Int? = listOf(24, 25, 30).firstOrNull { abs(fps - it) / it <= 0.02f }

    /** 实测 vsync 频率吸附到最近的面板模式（±3%），减少测量抖动；没有接近的模式时原样返回。 */
    fun snap(measured: Float, displayModes: List<Float>): Float =
        displayModes.minByOrNull { abs(it - measured) }?.takeIf { abs(it - measured) / it <= 0.03f } ?: measured
}
