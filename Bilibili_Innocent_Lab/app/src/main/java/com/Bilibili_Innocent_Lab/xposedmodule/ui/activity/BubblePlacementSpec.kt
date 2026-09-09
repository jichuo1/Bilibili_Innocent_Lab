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
    val tailEdge: BubbleTailEdge
) {
    val isUsable: Boolean
        get() = left.isFinite() && top.isFinite() &&
            width > 0f && maxHeight > 0f &&
            tailCenterX.isFinite() && tailCenterX in 0f..width
}

/**
 * 气泡展开/收起的时间与曲线（纯数值，无 Android 依赖）。
 *
 * 这里**刻意**用 `(0.05, 0.7, 0.1, 1)` —— 同一条曲线用在居中形变上是灾难（真机实测头 26ms
 * 就走掉 41% 行程，长对角线的飞行段快到看不见），但气泡是**短行程 + 缩放**，前重后轻正好给出
 * "啪一下弹出来"的手感。曲线没有好坏，只有配不配得上行程长度。
 */
internal object BubbleMotionSpec {
    const val ENTER_EASING_X1 = 0.05f
    const val ENTER_EASING_Y1 = 0.7f
    const val ENTER_EASING_X2 = 0.1f
    const val ENTER_EASING_Y2 = 1f

    const val CLOSE_EASING_X1 = 0.3f
    const val CLOSE_EASING_Y1 = 0f
    const val CLOSE_EASING_X2 = 0.8f
    const val CLOSE_EASING_Y2 = 0.15f

    const val COMMIT_EASING_X1 = 0f
    const val COMMIT_EASING_Y1 = 0f
    const val COMMIT_EASING_X2 = 0.2f
    const val COMMIT_EASING_Y2 = 1f

    const val ENTER_DURATION_MS = 260L
    const val CLOSE_DURATION_MS = 190L
    const val CANCEL_DURATION_MS = 220L
    const val COMMIT_DURATION_MS = 170L

    /** 起始缩放。比常规弹窗的 0.85 更小一点，配合小角才有"从图标挤出来"的感觉。 */
    const val COLLAPSED_SCALE = 0.72f

    fun scale(expansion: Float): Float =
        COLLAPSED_SCALE + (1f - COLLAPSED_SCALE) * expansion.coerceIn(0f, 1f)

    /** 表面淡入比缩放快得多：气泡要先"在"，再长到位，否则前几帧是半透明的鬼影。 */
    fun surfaceAlpha(expansion: Float): Float =
        (expansion.coerceIn(0f, 1f) / 0.35f).coerceIn(0f, 1f)

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
     * @param tailHalfWidthPx 小角半宽，用于把尖端夹在圆角之外。
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
        if (!anchor.isValid || windowWidth <= 0f || windowHeight <= 0f) return null
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
        val tailCenterX = if (tailMin > tailMax) {
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
            tailEdge = edge
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
