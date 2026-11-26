package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/**
 * 源帧节奏：由相邻 PTS 间隔的中位数估计源帧率，并统计缺帧（间隔 > 1.5 倍中位数）。
 * 60 Hz 目标只在 60 是源帧率整数倍（±2%，含 29.97）时才能让原帧落在槽位上；24/25 fps 等需要每秒
 * 35–48 次推理，真机宿主内做不到（约 22 ms/次），应保持原生播放，避免插帧引入不均匀节奏。
 */
internal class FrameSourceCadence(
    private val targetFps: Int = 60,
    private val minSamples: Int = 12,
    /** 正式播放为 true：源已达目标帧率（倍率 1）时没有可补的帧，接管只多出捕获与呈现开销，判为不适合。 */
    private val interpolationRequired: Boolean = false
) {
    enum class Verdict { PENDING, SUITABLE, UNSUITABLE }

    private val intervals = LongArray(WINDOW)
    private val scratch = LongArray(WINDOW)
    private var size = 0
    private var cursor = 0
    private var cachedMedianUs = -1L
    private var lastPts = Long.MIN_VALUE
    private var gaps = 0L
    private var frames = 0L

    @Synchronized fun add(ptsUs: Long) {
        frames++
        val previous = lastPts
        lastPts = ptsUs
        if (previous == Long.MIN_VALUE || ptsUs <= previous) return
        val interval = ptsUs - previous
        val median = medianUs()
        if (median != null && interval > median * 3 / 2) {
            gaps++
            return
        }
        intervals[cursor] = interval
        cursor = (cursor + 1) % WINDOW
        if (size < WINDOW) size++
        System.arraycopy(intervals, 0, scratch, 0, size)
        java.util.Arrays.sort(scratch, 0, size)
        cachedMedianUs = scratch[size / 2]
    }

    /** 重新开始（seek/不连续）：间隔历史作废，缺帧计数保留供诊断。 */
    @Synchronized fun reset() { lastPts = Long.MIN_VALUE }

    @Synchronized fun sourceFps(): Double? = medianUs()?.let { 1_000_000.0 / it }

    @Synchronized fun verdict(): Verdict {
        if (size < minSamples) return Verdict.PENDING
        val fps = sourceFps() ?: return Verdict.PENDING
        val ratio = targetFps / fps
        val nearest = kotlin.math.round(ratio)
        val minimum = if (interpolationRequired) 2.0 else 1.0
        return if (nearest >= minimum && kotlin.math.abs(ratio - nearest) / nearest <= TOLERANCE) Verdict.SUITABLE
            else Verdict.UNSUITABLE
    }

    @Synchronized fun describe(): String = "%.2ffps frames=%d gaps=%d".format(sourceFps() ?: 0.0, frames, gaps)

    private fun medianUs(): Long? = if (size == 0) null else cachedMedianUs

    private companion object {
        const val WINDOW = 31
        const val TOLERANCE = 0.02
    }
}
