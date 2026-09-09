package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/**
 * 锚定气泡的摆放计算（纯数学，无 Android 依赖，可 JVM 直测）。
 *
 * 目标形态：面板贴在来源图标下方（或上方），并伸出一个小角指向图标中心——像那个图标发出来的
 * 一条消息。**与 [IconAnchoredMotionSpec] 的居中形变是两条完全不同的路子**：那条是"来源行 →
 * 屏幕中央的大卡片"，适合宽度接近整屏的来源；工具栏上的 27dp 小图标飞到屏幕正中会显得莫名，
 * 所以走这条。
 */
internal enum class BubbleTailEdge {
    /** 小角在气泡顶边，气泡位于锚点下方。 */
    TOP,

    /** 小角在气泡底边，气泡位于锚点上方（下方空间不足时）。 */
    BOTTOM
}

internal data class BubblePlacement(
    /** 气泡整体（**含**小角占用的那条）在窗口内的左上角。 */
    val left: Float,
    val top: Float,
    val width: Float,
    /** 气泡整体可用的最大高度；内容超出时由调用方自行滚动或截断。 */
    val maxHeight: Float,
    /** 小角尖端的横坐标，**相对气泡自身左边**。 */
    val tailCenterX: Float,
    val tailEdge: BubbleTailEdge,
    /** 根部避开圆角，尖端仍指向图标；贴边时形成轻微斜角。 */
    val tailBaseCenterX: Float = tailCenterX
) {
    val isUsable: Boolean
        get() = left.isFinite() && top.isFinite() &&
            width > 0f && maxHeight > 0f &&
            tailCenterX.isFinite() && tailCenterX in 0f..width
}

/**
 * 气泡展开/收起的时间与曲线（纯数值，无 Android 依赖）。
 *
 * 完整地从图标中心展开，并沿同一路径收回；不再在 72% 尺寸时淡出。
 * 入场曲线单调并在展开端减速到零，打断则由 continuation 接续实时速度。
 */
internal object BubbleMotionSpec {
    const val ENTER_EASING_X1 = 0.2f
    const val ENTER_EASING_Y1 = 0f
    const val ENTER_EASING_X2 = 0.2f
    const val ENTER_EASING_Y2 = 1f

    const val CLOSE_EASING_X1 = 0.4f
    const val CLOSE_EASING_Y1 = 0f
    const val CLOSE_EASING_X2 = 0.2f
    const val CLOSE_EASING_Y2 = 1f

    const val COMMIT_EASING_X1 = 0f
    const val COMMIT_EASING_Y1 = 0f
    const val COMMIT_EASING_X2 = 0.2f
    const val COMMIT_EASING_Y2 = 1f

    const val ENTER_DURATION_MS = 245L
    const val CLOSE_DURATION_MS = 280L
    const val CANCEL_DURATION_MS = 220L
    const val COMMIT_DURATION_MS = 220L

    /** 零尺寸端精确落在图标中心，不能提前停在一张仍很大的透明卡片上。 */
    const val COLLAPSED_SCALE = 0f

    fun scale(expansion: Float): Float =
        COLLAPSED_SCALE + (1f - COLLAPSED_SCALE) * expansion.coerceIn(0f, 1f)

    /** 宽高轻微差速但共同到位；两轴单调，打断倒走不会再经过一个回弹峰值。 */
    fun scaleX(expansion: Float, entryShape: Boolean): Float {
        val t = expansion.coerceIn(0f, 1f)
        val base = if (entryShape) growth(t) else t
        return base + axisDifference(base)
    }

    fun scaleY(expansion: Float, entryShape: Boolean): Float {
        val t = expansion.coerceIn(0f, 1f)
        val base = if (entryShape) growth(t) else t
        return base - axisDifference(base)
    }

    private fun growth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        // Hermite：初速适中，到达展开尺寸时速度归零。
        // 同一 Hermite 多项式的正项形式，避免展开端大数相减造成浮点反向微动。
        return t + t * (1f - t) * (0.4f + 0.6f * t)
    }

    private fun axisDifference(base: Float): Float {
        // 首尾差值为零；横纵比例偏差小于 2%，不把文字和小角拉成先扁后长的形状。
        val remaining = 1f - base
        return 0.06f * base * base * remaining * remaining
    }

    /** 表面淡入比缩放快得多：气泡要先"在"，再长到位，否则前几帧是半透明的鬼影。 */
    fun surfaceAlpha(expansion: Float): Float {
        val t = (expansion.coerceIn(0f, 1f) / 0.12f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 手势从被打断的当前帧开始，而不是强行从完全展开端开始。 */
    fun predictiveExpansion(start: Float, progress: Float): Float =
        start.coerceIn(0f, 1f) * (1f - progress.coerceIn(0f, 1f))

}

internal object BubblePlacementSpec {

    /**
     * @param anchor 来源图标在窗口内的矩形。
     * @param windowWidth/[windowHeight] 弹窗窗口尺寸。
     * @param desiredWidth 内容期望宽度（通常是卡片的 minimumWidth）。
     * @param sideMarginPx 气泡与屏幕左右边缘的最小间距。
     * @param edgeMarginPx 气泡与屏幕上下边缘的最小间距。
     * @param gapPx 小角尖端与图标之间留的缝。
     * @param tailHeightPx 小角高度；气泡整体高度里包含这一条。
     * @param tailHalfWidthPx 小角半宽，用于把根部夹在圆角之外，尖端仍对准图标。
     * @param cornerRadiusPx 气泡圆角。
     * @return 不可用时返回 null（窗口太小、锚点非法等），调用方应回退到居中弹窗。
     */
    fun place(
        anchor: SettingsBackupMotionRect,
        windowWidth: Float,
        windowHeight: Float,
        desiredWidth: Float,
        maxWidthPx: Float,
        sideMarginPx: Float,
        edgeMarginPx: Float,
        gapPx: Float,
        tailHeightPx: Float,
        tailHalfWidthPx: Float,
        cornerRadiusPx: Float
    ): BubblePlacement? {
        if (!anchor.isValid || !windowWidth.isFinite() || !windowHeight.isFinite() ||
            !desiredWidth.isFinite() || !maxWidthPx.isFinite() ||
            !sideMarginPx.isFinite() || !edgeMarginPx.isFinite() || !gapPx.isFinite() ||
            !tailHeightPx.isFinite() || !tailHalfWidthPx.isFinite() || !cornerRadiusPx.isFinite() ||
            windowWidth <= 0f || windowHeight <= 0f || sideMarginPx < 0f || edgeMarginPx < 0f ||
            gapPx < 0f || tailHeightPx < 0f || tailHalfWidthPx < 0f || cornerRadiusPx < 0f
        ) return null
        val available = windowWidth - 2f * sideMarginPx
        if (available <= 0f) return null
        val width = desiredWidth.coerceAtMost(maxWidthPx).coerceAtMost(available)
        if (width <= 0f) return null

        // 下方空间不够就翻到上方；两边都不够时取较大的一侧，由 maxHeight 交给内容自己收敛。
        val spaceBelow = windowHeight - anchor.bottom - gapPx - edgeMarginPx
        val spaceAbove = anchor.top - gapPx - edgeMarginPx
        val edge = if (spaceBelow >= spaceAbove) BubbleTailEdge.TOP else BubbleTailEdge.BOTTOM
        val maxHeight = (if (edge == BubbleTailEdge.TOP) spaceBelow else spaceAbove)
        if (maxHeight <= tailHeightPx) return null

        val anchorCenterX = (anchor.left + anchor.right) / 2f
        // 先让气泡对准图标中心，再夹回屏幕内；小角随后按夹完的结果重新定位，
        // 所以贴边时小角仍然精确指向图标，不会跟着气泡一起被推走。
        val rawLeft = anchorCenterX - width / 2f
        val left = rawLeft.coerceIn(sideMarginPx, (windowWidth - sideMarginPx - width))
        val top = if (edge == BubbleTailEdge.TOP) {
            anchor.bottom + gapPx
        } else {
            // 上方气泡的 top 由调用方按实测高度回填；这里给出可用区域的上界。
            anchor.top - gapPx - maxHeight
        }

        val tailMin = cornerRadiusPx + tailHalfWidthPx
        val tailMax = width - cornerRadiusPx - tailHalfWidthPx
        val tailCenterX = (anchorCenterX - left).coerceIn(0f, width)
        val tailBaseCenterX = if (tailMin > tailMax) {
            width / 2f
        } else {
            (anchorCenterX - left).coerceIn(tailMin, tailMax)
        }

        return BubblePlacement(
            left = left,
            top = top,
            width = width,
            maxHeight = maxHeight,
            tailCenterX = tailCenterX,
            tailEdge = edge,
            tailBaseCenterX = tailBaseCenterX
        ).takeIf { it.isUsable }
    }

    /**
     * 气泡按实测高度收敛后的最终 top。
     *
     * 向下的气泡 top 不变；向上的气泡要按真实高度贴住锚点，否则会浮在半空。
     */
    fun resolveTop(
        placement: BubblePlacement,
        anchor: SettingsBackupMotionRect,
        measuredHeight: Float,
        gapPx: Float,
        edgeMarginPx: Float
    ): Float = when (placement.tailEdge) {
        BubbleTailEdge.TOP -> placement.top
        BubbleTailEdge.BOTTOM ->
            (anchor.top - gapPx - measuredHeight).coerceAtLeast(edgeMarginPx)
    }
}
