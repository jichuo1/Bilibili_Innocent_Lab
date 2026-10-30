package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockClearance
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockWindow
import kotlin.math.max

/** [contentScale] 只在窄屏放不下最小槽位时小于 1；高度不会低于所在档位的下限。 */
internal data class HostDockFit(val heightDp: Float, val marginDp: Float, val bottomDp: Float, val contentScale: Float)

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

    /** 超宽窗口里每个槽位的最大宽度；各档递减，限宽后大小底栏仍有区分。 */
    fun maxItemDp(config: HostBottomBarFxConfig): Float = when {
        config.compact && config.iconOnly -> 64f
        config.iconOnly -> 72f
        config.compact -> 88f
        else -> 96f
    }

    fun bottomDp(clearance: HostDockClearance): Float {
        // 容器若仍压在导航栏区域上（edge-to-edge），先把没让出的那段补上。
        val uncovered = (clearance.navigationDp - clearance.reservedBelowDp).coerceAtLeast(0f)
        val handleOnly = clearance.navigationDp > 0f && clearance.tappableDp <= 0f && clearance.reservedBelowDp <= 0f
        return uncovered + if (handleOnly) HANDLE_GAP_DP else SOLID_GAP_DP
    }

    fun fit(config: HostBottomBarFxConfig, parentWidthDp: Float, tabCount: Int, clearance: HostDockClearance): HostDockFit {
        val count = tabCount.coerceIn(1, HostNavigationMotion.MAX_ITEMS)
        val bottom = bottomDp(clearance)
        if (!config.liquidGlass || !parentWidthDp.isFinite() || parentWidthDp <= 0f) {
            return HostDockFit(config.heightDp, config.horizontalMarginDp(parentWidthDp, count), bottom, 1f)
        }
        val side = max(clearance.sideLeftDp, clearance.sideRightDp).coerceAtLeast(0f) + HostDockWindow.SIDE_GAP_DP
        val preferred = max(config.horizontalMarginDp(parentWidthDp, count), side)
        val floor = max(MIN_MARGIN_DP, side).coerceAtMost(preferred)
        val needed = count * (if (config.iconOnly) ICON_ITEM_DP else LABELED_ITEM_DP) + HostNavigationMotion.INSET_DP * 2f
        // 先压边距，压到安全下限仍放不下才整体缩小内容。
        val fitted = if (parentWidthDp - preferred * 2f >= needed) preferred
            else ((parentWidthDp - needed) / 2f).coerceIn(floor, preferred)
        // 超宽窗口按槽位上限收窄并居中；手机宽度不受影响，少标签时也保持原有铺满。
        val margin = if (parentWidthDp < HostDockWindow.WIDE_WINDOW_DP) fitted
            else max(fitted, (parentWidthDp - (count * maxItemDp(config) + HostNavigationMotion.INSET_DP * 2f)) / 2f)
        val room = parentWidthDp - margin * 2f
        val minScale = if (config.iconOnly) MIN_ICON_SCALE else MIN_LABELED_SCALE
        val scale = if (room >= needed) 1f else (room / needed).coerceIn(minScale, 1f)
        return HostDockFit(max(config.minHeightDp, config.heightDp * scale), margin, bottom, scale)
    }
}
