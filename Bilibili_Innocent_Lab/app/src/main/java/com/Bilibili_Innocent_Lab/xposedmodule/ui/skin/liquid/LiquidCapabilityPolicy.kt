package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import kotlin.math.ceil
import kotlin.math.sqrt

/** 不依赖 Android 对象的 Liquid 后端候选策略，便于 JVM 单测覆盖完整兼容矩阵。 */
internal object LiquidCapabilityPolicy {
    const val REFRACTION_MIN_API = 33
    const val BLUR_MIN_API = 31

    /**
     * RuntimeShader 与 RenderEffect 都只在硬件加速 Canvas 上启用。
     * 软件 Canvas 直接使用零图形资源依赖的半透明后端。
     */
    fun candidateOrder(
        sdkInt: Int,
        hardwareAccelerated: Boolean
    ): List<LiquidRenderBackend> = buildList {
        if (hardwareAccelerated && sdkInt >= REFRACTION_MIN_API) {
            add(LiquidRenderBackend.REFRACTION)
        }
        if (hardwareAccelerated && sdkInt >= BLUR_MIN_API) {
            add(LiquidRenderBackend.BLUR)
        }
        add(LiquidRenderBackend.TRANSLUCENT)
    }
}

/**
 * 一次 Activity 会话的单向降级游标。
 *
 * 已失败的高阶后端在当前会话内不会再次尝试，避免厂商图形实现持续抛错造成重绘循环。
 */
internal class LiquidBackendFallbackPlan(
    candidates: List<LiquidRenderBackend>
) {
    private val orderedCandidates = candidates.distinct()
    private var index = 0

    init {
        require(orderedCandidates.isNotEmpty()) { "Liquid backend candidates must not be empty" }
        require(orderedCandidates.last() == LiquidRenderBackend.TRANSLUCENT) {
            "Liquid backend candidates must end with the translucent fallback"
        }
    }

    val current: LiquidRenderBackend?
        get() = orderedCandidates.getOrNull(index)

    /** 只接受当前后端的失败；旧后端迟到失败不能跳过新的降级档。 */
    fun advanceAfterFailure(failed: LiquidRenderBackend): LiquidRenderBackend? {
        if (current != failed) return current
        index++
        return current
    }
}

internal data class LiquidBackdropSize(
    val width: Int,
    val height: Int
) {
    val byteCount: Long
        get() = width.toLong() * height.toLong() * LiquidBackdropSizingPolicy.BYTES_PER_PIXEL
}

/** 静态 backdrop 固定 0.25x 采样，并把单个 ARGB_8888 缓冲限制在 2 MiB 内。 */
internal object LiquidBackdropSizingPolicy {
    const val SAMPLE_SCALE = 0.25
    const val MAX_BUFFER_BYTES = 2L * 1024L * 1024L
    const val BYTES_PER_PIXEL = 4L
    private const val MAX_PIXELS = MAX_BUFFER_BYTES / BYTES_PER_PIXEL

    fun resolve(fullWidth: Int, fullHeight: Int): LiquidBackdropSize {
        require(fullWidth > 0 && fullHeight > 0) { "Backdrop dimensions must be positive" }
        var width = ceil(fullWidth * SAMPLE_SCALE).toInt().coerceAtLeast(1)
        var height = ceil(fullHeight * SAMPLE_SCALE).toInt().coerceAtLeast(1)
        val sampledPixels = width.toLong() * height.toLong()
        if (sampledPixels > MAX_PIXELS) {
            val scale = sqrt(MAX_PIXELS.toDouble() / sampledPixels.toDouble())
            width = (width * scale).toInt().coerceAtLeast(1)
            height = (height * scale).toInt().coerceAtLeast(1)
            while (width.toLong() * height.toLong() > MAX_PIXELS) {
                if (width >= height && width > 1) width-- else if (height > 1) height-- else break
            }
        }
        return LiquidBackdropSize(width, height)
    }
}
