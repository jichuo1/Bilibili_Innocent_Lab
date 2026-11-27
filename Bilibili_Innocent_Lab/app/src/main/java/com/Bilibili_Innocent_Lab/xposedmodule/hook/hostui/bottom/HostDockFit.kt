package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockClearance
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockWindow
import kotlin.math.max

/** [contentScale] 只在窄屏放不下最小槽位时小于 1；高度不会低于所在档位的下限。 */
internal data class HostDockFit(
    val heightDp: Float,
    val marginDp: Float,
    val bottomDp: Float,
    val contentScale: Float,
    val marginStartDp: Float = marginDp,
    val marginEndDp: Float = marginDp
)

internal object HostDockFitPolicy {
    /** 下方是实色条、三键导航或宿主自留空间时，留出明显的空气感。 */
    const val SOLID_GAP_DP = 12f
    /** 透明手势条本身已是空白区，只需浮在小横条上方。 */
    const val HANDLE_GAP_DP = 6f
    const val MIN_MARGIN_DP = 8f
    const val LABELED_ITEM_DP = 56f
    const val ICON_ITEM_DP = 44f
    const val MIN_LABELED_SCALE = .86f
    const val MIN_ICON_SCALE = .9f
    const val MAX_FONT_SCALE = 1.5f
    const val FONT_HEIGHT_GROWTH_DP = 10f
    const val FONT_WIDTH_GROWTH_FACTOR = 0.35f

    /** 超宽窗口里每个槽位的最大宽度；各档递减，限宽后大小底栏仍有区分。 */
    fun maxItemDp(config: HostBottomBarFxConfig): Float = when {
        config.compact && config.iconOnly -> 64f
        config.iconOnly -> 72f
        config.compact -> 88f
        else -> 96f
    }

    /** 按文本方向映射左右侧安全边距，确保 LTR 与 RTL 下 start/end 对称语义一致。 */
    fun directionalSideClearance(clearance: HostDockClearance, isRtl: Boolean): Pair<Float, Float> {
        val left = clearance.sideLeftDp.coerceAtLeast(0f)
        val right = clearance.sideRightDp.coerceAtLeast(0f)
        return if (isRtl) right to left else left to right
    }

    fun bottomDp(clearance: HostDockClearance): Float {
        // 容器若仍压在导航栏区域上（edge-to-edge），先把没让出的那段补上。
        val uncovered = (clearance.navigationDp - clearance.reservedBelowDp).coerceAtLeast(0f)
        val handleOnly = clearance.navigationDp > 0f && clearance.tappableDp <= 0f && clearance.reservedBelowDp <= 0f
        return uncovered + if (handleOnly) HANDLE_GAP_DP else SOLID_GAP_DP
    }

    fun fit(
        config: HostBottomBarFxConfig,
        parentWidthDp: Float,
        tabCount: Int,
        clearance: HostDockClearance,
        fontScale: Float = 1f,
        isRtl: Boolean = false
    ): HostDockFit {
        val count = tabCount.coerceIn(1, HostNavigationMotion.MAX_ITEMS)
        val bottom = bottomDp(clearance)
        val textScale = if (!config.iconOnly && fontScale.isFinite()) fontScale.coerceIn(1f, MAX_FONT_SCALE) else 1f
        val extraHeightDp = if (config.liquidGlass && !config.iconOnly) (textScale - 1f) * FONT_HEIGHT_GROWTH_DP else 0f
        val (startSideRaw, endSideRaw) = directionalSideClearance(clearance, isRtl)
        if (!config.liquidGlass || !parentWidthDp.isFinite() || parentWidthDp <= 0f) {
            val baseMargin = config.horizontalMarginDp(parentWidthDp, count)
            return HostDockFit(config.heightDp + extraHeightDp, baseMargin, bottom, 1f, baseMargin, baseMargin)
        }
        val side = max(startSideRaw, endSideRaw) + HostDockWindow.SIDE_GAP_DP
        val preferred = max(config.horizontalMarginDp(parentWidthDp, count), side)
        val floor = max(MIN_MARGIN_DP, side).coerceAtMost(preferred)
        val itemWidthDp = if (config.iconOnly) ICON_ITEM_DP else LABELED_ITEM_DP * (1f + (textScale - 1f) * FONT_WIDTH_GROWTH_FACTOR)
        val needed = count * itemWidthDp + HostNavigationMotion.INSET_DP * 2f
        // 先压边距，压到安全下限仍放不下才整体缩小内容。
        val fitted = if (parentWidthDp - preferred * 2f >= needed) preferred
            else ((parentWidthDp - needed) / 2f).coerceIn(floor, preferred)
        // 超宽窗口按槽位上限收窄并居中；手机宽度不受影响，少标签时也保持原有铺满。
        val maxSlotWidthDp = maxItemDp(config) * (1f + (textScale - 1f) * (FONT_WIDTH_GROWTH_FACTOR * 0.5f))
        val margin = if (parentWidthDp < HostDockWindow.WIDE_WINDOW_DP) fitted
            else max(fitted, (parentWidthDp - (count * maxSlotWidthDp + HostNavigationMotion.INSET_DP * 2f)) / 2f)
        val room = parentWidthDp - margin * 2f
        val minScale = if (config.iconOnly) MIN_ICON_SCALE else MIN_LABELED_SCALE
        val scale = if (room >= needed) 1f else (room / needed).coerceIn(minScale, 1f)
        val baseHeight = max(config.minHeightDp, config.heightDp * scale)
        return HostDockFit(baseHeight + extraHeightDp, margin, bottom, scale, margin, margin)
    }
}
