package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

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

internal enum class SettingsBackupContentTiming {
    TIMED,
    PREDICTIVE
}

internal object SettingsBackupMotionSpec {

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
        require(collapsedBounds.isValid) { "Collapsed bounds must be valid" }
        require(expandedBounds.isValid) { "Expanded bounds must be valid" }
        require(collapsedTitleBounds.isValid) { "Collapsed title bounds must be valid" }
        require(expandedTitleBounds.isValid) { "Expanded title bounds must be valid" }

        val fraction = expansion.coerceIn(0f, 1f)
        val contentFraction = contentFraction(fraction, contentTiming)
        return SettingsBackupMotionFrame(
            bounds = SettingsBackupMotionRect(
                left = lerp(collapsedBounds.left, expandedBounds.left, fraction),
                top = lerp(collapsedBounds.top, expandedBounds.top, fraction),
                right = lerp(collapsedBounds.right, expandedBounds.right, fraction),
                bottom = lerp(collapsedBounds.bottom, expandedBounds.bottom, fraction)
            ),
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

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction
}
