package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer

/**
 * 能力测量的单档采样：预运行一次，再计时 5 次，取第二快值（保持既有分档统计，不改成真正中位数）。
 *
 * 预运行与每个计时样本都必须返回 OK：失败的快速返回不能进入耗时分布，也不能被判成该档可用。
 * 第一个非 OK 状态立即终止本档并返回 [Result.Failed]，由调用方记录并按「该档不可用 / 整体能力为 0 → 重试」处理。
 *
 * 输入内容初始化（清零）发生在计时之前，但计入准备总时长；计时只包住 `process`。
 */
internal class FrameCapabilityProbe(
    private val arena: FrameScratchArena,
    private val capacityHintBytes: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val process: Step
) {
    fun interface Step {
        fun run(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
                leftId: Long, rightId: Long, flowScale: Int): FrameInterpolationBackend.Status
    }

    sealed class Result {
        /** [robustMs] 为 5 次计时排序后的第二快值；[allMs] 为排序后的全部样本。 */
        class Measured(val robustMs: Double, val allMs: List<Double>) : Result()
        /** [step] 0 为预运行，1..5 为计时样本。 */
        class Failed(val status: FrameInterpolationBackend.Status, val step: Int) : Result()
    }

    fun measure(width: Int, height: Int, flowScale: Int): Result {
        val bytes = width * height * 3
        val hint = maxOf(bytes, capacityHintBytes)
        // 四份输入彼此独立且为零内容（与逐次 allocateDirect 的初始内容一致），一份输出。
        val frames = Array(INPUTS) { arena.zeroed(it, bytes, hint) }
        val output = arena.scratch(INPUTS, bytes, hint)
        // 宿主稳态：每次插帧只有后一帧是新上传的，前一帧按 ID 命中显存缓存。ID 取在账本永远达不到的区间。
        val base = FrameInterpolationEngineHolder.CAPABILITY_ID_BASE + (width.toLong() * 65_536L + height) * 16L + flowScale
        fun step(i: Int): FrameInterpolationBackend.Status = process.run(frames[i % INPUTS], frames[(i + 1) % INPUTS], output,
            width, height, base + i * 0x1_0000_0000L, base + (i + 1) * 0x1_0000_0000L, flowScale)
        val warm = step(0)
        if (warm != FrameInterpolationBackend.Status.OK) return Result.Failed(warm, 0)
        val times = ArrayList<Double>(SAMPLES)
        for (index in 0 until SAMPLES) {
            val started = nanoTime()
            val status = step(index + 1)
            val elapsed = (nanoTime() - started) / 1e6
            if (status != FrameInterpolationBackend.Status.OK) return Result.Failed(status, index + 1)
            times += elapsed
        }
        times.sort()
        return Result.Measured(times[1], times)
    }

    companion object {
        const val INPUTS = 4
        const val SAMPLES = 5
    }
}
