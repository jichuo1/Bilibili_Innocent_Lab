package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import kotlin.math.roundToLong

/**
 * 实时截图吞吐的纯策略边界。
 *
 * 请求面板支持的最高刷新率并不意味着 `PixelCopy` 能跟上：回读是严格单飞的 GPU→CPU 同步操作，
 * 完成节奏受回读带宽、抑制 pass 与纹理上传共同限制。当 UI 以 120Hz 移动、背景每 N 帧才更新一次
 * 时，玻璃里的内容就会以不规则步长滞后再跳回，这正是快速滑动下的抖动来源之一；同时还白白多渲染
 * 了一倍的帧。
 *
 * 因此按**实测吞吐**收敛刷新率请求，而不是按芯片厂商猜测——同代 Adreno / Mali 之间的差异比跨厂
 * 差异更大，`Build.HARDWARE` 一类判定既不可靠也无法覆盖定制 ROM。
 *
 * 本策略不引用任何 Android 类型，便于 JVM 完整覆盖。
 */
internal object LiquidCaptureThroughputPolicy {
    /** 吞吐自适应的下限：再慢也不因吞吐把窗口压到 60Hz 以下，热降载有它自己的更低档位。 */
    const val MIN_ADAPTIVE_FPS = 60f

    /** 前若干次完成包含缓冲建立与首帧预热，不参与统计。 */
    const val WARMUP_SAMPLES = 4

    /** 指数滑动平均系数；越小越平滑、对瞬时抖动越不敏感。 */
    const val INTERVAL_SMOOTHING = 0.25f

    /** 实测吞吐低于当前目标的该比例才算"跟不上"。 */
    const val STEP_DOWN_RATIO = 0.72f

    /** 连续多少次采样满足条件才真正降档，避免单次卡顿触发切换。 */
    const val VOTES_TO_STEP_DOWN = 8

    /** 超过该间隔视为异常样本（页面切换、长时间挂起后的第一帧），直接丢弃。 */
    const val MAX_VALID_INTERVAL_NANOS = 500_000_000L

    fun framesPerSecond(intervalNanos: Long): Float =
        if (intervalNanos <= 0L) 0f else 1_000_000_000f / intervalNanos.toFloat()

    fun intervalNanos(framesPerSecond: Float): Long =
        if (framesPerSecond <= 0f) 0L
        else (1_000_000_000.0 / framesPerSecond.toDouble()).roundToLong()

    fun smoothInterval(previousNanos: Long, sampleNanos: Long): Long {
        if (previousNanos <= 0L) return sampleNanos
        val smoothed = previousNanos + (sampleNanos - previousNanos) * INTERVAL_SMOOTHING.toDouble()
        return smoothed.roundToLong().coerceAtLeast(1L)
    }

    /**
     * 在面板支持档位中选出不高于 [measuredFps] 的最高档。
     *
     * 只降不升：一旦降档，本会话内不再自动升回。升档需要先请求更高的刷新率才能观察到它是否可行，
     * 而"试探→失败→降回"会在两个相邻档位之间来回切换，肉眼可见。会话重建（Activity 重新进入）、
     * 热状态变化与缓冲重建都会重置统计，届时自然重新从最高档开始。
     */
    fun stepDownTarget(
        currentTargetFps: Float,
        measuredFps: Float,
        supportedRefreshRates: Iterable<Float>
    ): Float {
        if (!currentTargetFps.isFinite() || currentTargetFps <= 0f) return currentTargetFps
        if (!measuredFps.isFinite() || measuredFps <= 0f) return currentTargetFps
        val candidates = supportedRefreshRates
            .filter { it.isFinite() && it > 0f && it < currentTargetFps - 0.5f }
            .filter { it >= MIN_ADAPTIVE_FPS - 0.5f }
        val next = candidates.filter { it <= measuredFps + 0.5f }.maxOrNull()
            ?: candidates.minOrNull()
            ?: return currentTargetFps
        return next.coerceAtMost(currentTargetFps)
    }

    fun isKeepingUp(currentTargetFps: Float, measuredFps: Float): Boolean {
        if (!currentTargetFps.isFinite() || currentTargetFps <= 0f) return true
        if (!measuredFps.isFinite() || measuredFps <= 0f) return true
        return measuredFps >= currentTargetFps * STEP_DOWN_RATIO
    }
}

/**
 * 采集吞吐跟踪器：只统计**连续成功完成**之间的间隔。
 *
 * 失败、熔断、挂起或缓冲重建都会 [reset]，避免把一次异常拉长的间隔算进稳态吞吐。
 * 该类不持有任何 Android 对象，可直接由 JVM 测试驱动。
 */
internal class LiquidCaptureThroughputTracker {
    private var lastCompletionNanos = 0L
    private var smoothedIntervalNanos = 0L
    private var sampleCount = 0
    private var stepDownVotes = 0

    val measuredFramesPerSecond: Float
        get() = if (sampleCount < LiquidCaptureThroughputPolicy.WARMUP_SAMPLES) 0f
        else LiquidCaptureThroughputPolicy.framesPerSecond(smoothedIntervalNanos)

    fun reset() {
        lastCompletionNanos = 0L
        smoothedIntervalNanos = 0L
        sampleCount = 0
        stepDownVotes = 0
    }

    /**
     * @return true 表示已连续多次判定跟不上当前目标，调用方应当降档并 [reset]。
     */
    fun onCaptureCompleted(nowNanos: Long, currentTargetFps: Float): Boolean {
        val previous = lastCompletionNanos
        lastCompletionNanos = nowNanos
        if (previous <= 0L) return false
        val interval = nowNanos - previous
        if (interval <= 0L || interval > LiquidCaptureThroughputPolicy.MAX_VALID_INTERVAL_NANOS) {
            // 异常样本不参与平滑，也不清空已有统计。
            return false
        }
        smoothedIntervalNanos = LiquidCaptureThroughputPolicy.smoothInterval(
            smoothedIntervalNanos,
            interval
        )
        if (sampleCount < Int.MAX_VALUE) sampleCount += 1
        if (sampleCount < LiquidCaptureThroughputPolicy.WARMUP_SAMPLES) return false
        val measured = LiquidCaptureThroughputPolicy.framesPerSecond(smoothedIntervalNanos)
        stepDownVotes = if (LiquidCaptureThroughputPolicy.isKeepingUp(currentTargetFps, measured)) {
            0
        } else {
            stepDownVotes + 1
        }
        return stepDownVotes >= LiquidCaptureThroughputPolicy.VOTES_TO_STEP_DOWN
    }
}
