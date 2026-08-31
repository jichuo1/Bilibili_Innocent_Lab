package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.roundToLong

/** 不依赖 Android UI 的矩形，供设置备份页形变计算和 JVM 单测共用。 */
internal data class SettingsBackupMotionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val isValid: Boolean
        get() = left.isFinite() &&
            top.isFinite() &&
            right.isFinite() &&
            bottom.isFinite() &&
            width > 0f &&
            height > 0f
}

/** expansion=0 为来源卡片，expansion=1 为完整备份页面。 */
internal data class SettingsBackupMotionFrame(
    val bounds: SettingsBackupMotionRect,
    val cornerRadiusPx: Float,
    val surfaceAlpha: Float,
    val contentAlpha: Float,
    val contentTranslationYPx: Float,
    val titleX: Float,
    val titleY: Float,
    val titleTextSizePx: Float
)

/** UI 动画热路径复用的帧缓冲；避免每个 progress 创建 Frame 与 Rect。 */
internal class SettingsBackupMotionFrameBuffer {
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set
    var cornerRadiusPx = 0f
        private set
    var surfaceAlpha = 0f
        private set
    var contentAlpha = 0f
        private set
    var contentTranslationYPx = 0f
        private set
    var titleX = 0f
        private set
    var titleY = 0f
        private set
    var titleTextSizePx = 1f
        private set

    internal fun set(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadiusPx: Float,
        surfaceAlpha: Float,
        contentAlpha: Float,
        contentTranslationYPx: Float,
        titleX: Float,
        titleY: Float,
        titleTextSizePx: Float
    ) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
        this.cornerRadiusPx = cornerRadiusPx
        this.surfaceAlpha = surfaceAlpha
        this.contentAlpha = contentAlpha
        this.contentTranslationYPx = contentTranslationYPx
        this.titleX = titleX
        this.titleY = titleY
        this.titleTextSizePx = titleTextSizePx
    }

    internal fun snapshot(): SettingsBackupMotionFrame = SettingsBackupMotionFrame(
        bounds = SettingsBackupMotionRect(left, top, right, bottom),
        cornerRadiusPx = cornerRadiusPx,
        surfaceAlpha = surfaceAlpha,
        contentAlpha = contentAlpha,
        contentTranslationYPx = contentTranslationYPx,
        titleX = titleX,
        titleY = titleY,
        titleTextSizePx = titleTextSizePx
    )
}

internal enum class SettingsBackupContentTiming {
    TIMED,
    PREDICTIVE
}

internal object SettingsBackupMotionSpec {

    /** 普通关闭使用均衡的 Fast-out-slow-in：中段完成主要位移，末尾稳定减速而不拖尾。 */
    const val CLOSE_EASING_X1 = 0.4f
    const val CLOSE_EASING_Y1 = 0f
    const val CLOSE_EASING_X2 = 0.2f
    const val CLOSE_EASING_Y2 = 1f

    /** 预测式返回松手后承接已有速度，只做 Linear-out-slow-in 续播，避免重新缓入的停顿。 */
    const val COMMIT_EASING_X1 = 0f
    const val COMMIT_EASING_Y1 = 0f
    const val COMMIT_EASING_X2 = 0.2f
    const val COMMIT_EASING_Y2 = 1f

    fun closeDurationMs(
        baseDurationMs: Long,
        currentExpansion: Float,
        minimumDurationMs: Long
    ): Long {
        require(baseDurationMs > 0L) { "Base close duration must be positive" }
        val minimum = minimumDurationMs.coerceIn(0L, baseDurationMs)
        return (baseDurationMs * currentExpansion.coerceIn(0f, 1f))
            .roundToLong()
            .coerceIn(minimum, baseDurationMs)
    }

    fun canMoveTitle(
        collapsedLineCount: Int,
        expandedLineCount: Int,
        collapsedIsLeftToRight: Boolean,
        expandedIsLeftToRight: Boolean
    ): Boolean = collapsedLineCount == 1 &&
        expandedLineCount == 1 &&
        collapsedIsLeftToRight &&
        expandedIsLeftToRight

    fun frame(
        expansion: Float,
        collapsedBounds: SettingsBackupMotionRect,
        expandedBounds: SettingsBackupMotionRect,
        collapsedTitleBounds: SettingsBackupMotionRect,
        expandedTitleBounds: SettingsBackupMotionRect,
        collapsedTitleTextSizePx: Float,
        expandedTitleTextSizePx: Float,
        collapsedCornerRadiusPx: Float,
        contentTravelPx: Float,
        contentTiming: SettingsBackupContentTiming = SettingsBackupContentTiming.TIMED
    ): SettingsBackupMotionFrame {
        val buffer = SettingsBackupMotionFrameBuffer()
        fillFrame(
            out = buffer,
            expansion = expansion,
            collapsedBounds = collapsedBounds,
            expandedBounds = expandedBounds,
            collapsedTitleBounds = collapsedTitleBounds,
            expandedTitleBounds = expandedTitleBounds,
            collapsedTitleTextSizePx = collapsedTitleTextSizePx,
            expandedTitleTextSizePx = expandedTitleTextSizePx,
            collapsedCornerRadiusPx = collapsedCornerRadiusPx,
            contentTravelPx = contentTravelPx,
            contentTiming = contentTiming
        )
        return buffer.snapshot()
    }

    internal fun fillFrame(
        out: SettingsBackupMotionFrameBuffer,
        expansion: Float,
        collapsedBounds: SettingsBackupMotionRect,
        expandedBounds: SettingsBackupMotionRect,
        collapsedTitleBounds: SettingsBackupMotionRect,
        expandedTitleBounds: SettingsBackupMotionRect,
        collapsedTitleTextSizePx: Float,
        expandedTitleTextSizePx: Float,
        collapsedCornerRadiusPx: Float,
        contentTravelPx: Float,
        contentTiming: SettingsBackupContentTiming = SettingsBackupContentTiming.TIMED
    ) {
        require(collapsedBounds.isValid) { "Collapsed bounds must be valid" }
        require(expandedBounds.isValid) { "Expanded bounds must be valid" }
        require(collapsedTitleBounds.isValid) { "Collapsed title bounds must be valid" }
        require(expandedTitleBounds.isValid) { "Expanded title bounds must be valid" }

        val fraction = expansion.coerceIn(0f, 1f)
        val contentFraction = contentFraction(fraction, contentTiming)
        out.set(
            left = lerp(collapsedBounds.left, expandedBounds.left, fraction),
            top = lerp(collapsedBounds.top, expandedBounds.top, fraction),
            right = lerp(collapsedBounds.right, expandedBounds.right, fraction),
            bottom = lerp(collapsedBounds.bottom, expandedBounds.bottom, fraction),
            cornerRadiusPx = lerp(collapsedCornerRadiusPx.coerceAtLeast(0f), 0f, fraction),
            // 精确收拢时让下层真实卡片接管，避免最后一帧出现同名标题双影。
            surfaceAlpha = smoothStep(0f, 0.1f, fraction),
            contentAlpha = contentFraction,
            contentTranslationYPx = lerp(contentTravelPx.coerceAtLeast(0f), 0f, contentFraction),
            titleX = lerp(collapsedTitleBounds.left, expandedTitleBounds.left, fraction),
            titleY = lerp(collapsedTitleBounds.top, expandedTitleBounds.top, fraction),
            titleTextSizePx = lerp(
                collapsedTitleTextSizePx.coerceAtLeast(1f),
                expandedTitleTextSizePx.coerceAtLeast(1f),
                fraction
            )
        )
    }

    internal fun contentFraction(
        expansion: Float,
        timing: SettingsBackupContentTiming
    ): Float = when (timing) {
        // 定时动画先让容器与共享标题完成主要位移，再显现正文；否则来源卡片较靠下时，
        // 首张内容卡会在动画中段穿过仍在上移的标题，真机录屏可见明显叠字。
        SettingsBackupContentTiming.TIMED -> smoothStep(0.86f, 0.985f, expansion)
        // 预测式返回的 expansion 已经过强非线性手势映射，需要更宽的正文区间；否则正文会在
        // BackEvent 原始 progress 的最初约 1% 内近乎瞬间消失。
        SettingsBackupContentTiming.PREDICTIVE -> smoothStep(0.22f, 0.72f, expansion)
    }

    internal fun smoothStep(edgeStart: Float, edgeEnd: Float, value: Float): Float {
        if (edgeStart >= edgeEnd) return if (value < edgeStart) 0f else 1f
        val fraction = ((value - edgeStart) / (edgeEnd - edgeStart)).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }

    /** 只在形变接近来源卡片时渐显其边框，避免全屏阶段出现无意义的外框。 */
    internal fun collapsedChromeFraction(expansion: Float): Float =
        1f - smoothStep(0f, 0.28f, expansion)

    /** 遮罩仅在抵达来源端的最后一小段交给下层真实入口，避免提前消失或终点双层叠色。 */
    internal fun transitionSurfaceAlpha(
        expansion: Float,
        handoffExpansion: Float
    ): Float = smoothStep(
        0f,
        handoffExpansion.coerceIn(0.001f, 1f),
        expansion
    )

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction
}
