package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom

import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer

/** 配置项参数 */
internal data class HostBottomBarFxConfig(
    val liquidGlass: Boolean = true,
    val touchGlow: Boolean = true,
    val compact: Boolean = false,
    val iconOnly: Boolean = false,
    val appearance: HostChromeAppearance = HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE),
    val mineGarbDim: Boolean = true
) {
    val heightDp: Float
        get() = when {
            !liquidGlass -> HostNavigationMotion.BAR_HEIGHT_DP.toFloat()
            compact && iconOnly -> 44f
            iconOnly -> 48f
            compact -> 52f
            else -> HostNavigationMotion.BAR_HEIGHT_DP.toFloat()
        }

    /** 窄屏缩放的高度下限；各档上下限互不重叠，缩小后大小底栏仍可区分。 */
    val minHeightDp: Float
        get() = when {
            !liquidGlass -> HostNavigationMotion.BAR_HEIGHT_DP.toFloat()
            compact && iconOnly -> 42f
            iconOnly -> 46f
            compact -> 50f
            else -> 56f
        }

    fun horizontalMarginDp(parentWidthDp: Float, tabCount: Int): Float {
        if (!liquidGlass) return 0f
        val baseMargin = 16f
        if (!iconOnly) return baseMargin
        val normalWidth = (parentWidthDp - baseMargin * 2f).coerceAtLeast(0f)
        // 收窄实际布局，让图标、滑块与触控坐标一起适配；窄屏仍保留每项 44dp 的空间。
        val minimumWidth = tabCount.coerceAtLeast(1) * 44f + HostNavigationMotion.INSET_DP * 2f
        val widthFraction = if (compact) 0.84f else 0.88f
        val width = (normalWidth * widthFraction).coerceAtLeast(minimumWidth).coerceAtMost(normalWidth)
        return (parentWidthDp - width) / 2f
    }
}

/**
 * 拖动滑块的落点语义：**落回当前页不算操作**。
 *
 * 模块自己的设置 pager 同页选中没有副作用，宿主不同——宿主点击当前 tab 是"刷新"（首页即刷新推荐流）。
 * 手势收尾给的是"离预览位置最近的页"，所以拖开又落回原页会拿回原页索引；那种情况必须什么都不做，
 * 只有真的落到别的页才把点击交回宿主。
 */
internal object HostBottomBarScrubRelease {
    /** [target] 手势落点页；[scrubbed] 表示这次是横向拖动滑块收尾；[currentPage] 是宿主当前页。 */
    fun selectableTarget(target: Int?, scrubbed: Boolean, currentPage: Int): Int? =
        target?.takeUnless { scrubbed && it == currentPage }

    /** activate 返回是否选中了页面；发布动作和未被处理的点击都保留原页面。 */
    fun activateTarget(target: Int?, scrubbed: Boolean, currentPage: Int, activate: (Int) -> Boolean): Int {
        val index = selectableTarget(target, scrubbed, currentPage) ?: return currentPage
        return if (activate(index)) index else currentPage
    }
}
