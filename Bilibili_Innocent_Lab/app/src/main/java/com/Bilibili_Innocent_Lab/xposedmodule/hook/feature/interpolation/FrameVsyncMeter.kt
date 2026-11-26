package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/**
 * 播放中的实际 vsync 频率：Choreographer 每帧的 frameTimeNanos 间隔取中位数。LTPO 与厂商策略按应用限制刷新率时，
 * 本进程拿到的 vsync 正是系统实际给出的上限。跳过起步的 [warmup] 帧（模式切换需要时间），
 * 之后积累 [samples] 个间隔给出一次结果；丢帧造成的倍数间隔由中位数排除。
 */
internal class FrameVsyncMeter(private val warmup: Int = 60, private val samples: Int = 120) {
    private var last = Long.MIN_VALUE
    private var seen = 0
    private val intervals = LongArray(samples)
    private var count = 0
    private var reported = false

    /** 每个 vsync 调用一次；测量完成时返回一次 Hz，其余返回 null。 */
    fun add(frameTimeNs: Long): Float? {
        val previous = last
        last = frameTimeNs
        if (reported || previous == Long.MIN_VALUE || frameTimeNs <= previous) return null
        if (++seen <= warmup) return null
        intervals[count++] = frameTimeNs - previous
        if (count < samples) return null
        reported = true
        java.util.Arrays.sort(intervals)
        val median = intervals[samples / 2]
        return (1e9 / median).toFloat()
    }
}
