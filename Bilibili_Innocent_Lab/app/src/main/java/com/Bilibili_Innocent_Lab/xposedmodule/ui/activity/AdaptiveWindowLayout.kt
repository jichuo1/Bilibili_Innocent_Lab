package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Android 窗口宽度分级（dp 断点：Compact < 600, Medium 600..839, Expanded >= 840）。 */
internal enum class WindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

/** Android 窗口高度分级（dp 断点：Compact < 480, Medium 480..899, Expanded >= 900）。 */
internal enum class WindowHeightClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

/** 模块设置主界面的主导航形态。 */
internal enum class NavigationChromeMode {
    BOTTOM_BAR,
    SIDE_RAIL
}

internal data class AdaptiveWindowMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val widthClass: WindowWidthClass,
    val heightClass: WindowHeightClass
)

/**
 * 折叠屏、平板与多窗口的纯计算自适应排版策略。
 *
 * 与 Android WindowInsets / Render 接口解耦，便于 JVM 直接验证全部屏幕尺寸与分屏组合。
 */
internal object AdaptiveWindowLayout {
    const val COMPACT_WIDTH_MAX_DP = 600
    const val MEDIUM_WIDTH_MAX_DP = 840
    const val COMPACT_HEIGHT_MAX_DP = 480
    const val MEDIUM_HEIGHT_MAX_DP = 900
    const val SIDE_RAIL_MIN_HEIGHT_DP = 340
    const val SIDE_RAIL_LANDSCAPE_MIN_WIDTH_DP = 520
    const val SIDE_RAIL_WIDTH_DP = 76
    const val SIDE_RAIL_OUTER_MARGIN_DP = 12
    const val SIDE_RAIL_CONTENT_GAP_DP = 10
    const val MODAL_MAX_WIDTH_DP = 560

    fun classify(widthDp: Int, heightDp: Int): AdaptiveWindowMetrics {
        val w = widthDp.coerceAtLeast(0)
        val h = heightDp.coerceAtLeast(0)
        val widthClass = when {
            w < COMPACT_WIDTH_MAX_DP -> WindowWidthClass.COMPACT
            w < MEDIUM_WIDTH_MAX_DP -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.EXPANDED
        }
        val heightClass = when {
            h < COMPACT_HEIGHT_MAX_DP -> WindowHeightClass.COMPACT
            h < MEDIUM_HEIGHT_MAX_DP -> WindowHeightClass.MEDIUM
            else -> WindowHeightClass.EXPANDED
        }
        return AdaptiveWindowMetrics(w, h, widthClass, heightClass)
    }

    fun classifyPx(widthPx: Int, heightPx: Int, density: Float): AdaptiveWindowMetrics {
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val widthDp = (widthPx.coerceAtLeast(0) / safeDensity).toInt()
        val heightDp = (heightPx.coerceAtLeast(0) / safeDensity).toInt()
        return classify(widthDp, heightDp)
    }

    /** Medium/Expanded 或横向充足且高度够用时切至左侧悬浮 Navigation Rail，释放纵向阅读空间。 */
    fun navigationChromeMode(metrics: AdaptiveWindowMetrics): NavigationChromeMode {
        val wideEnough = metrics.widthDp >= COMPACT_WIDTH_MAX_DP && metrics.heightDp >= SIDE_RAIL_MIN_HEIGHT_DP
        val landscapePocket = metrics.widthDp >= SIDE_RAIL_LANDSCAPE_MIN_WIDTH_DP &&
            metrics.heightDp in SIDE_RAIL_MIN_HEIGHT_DP until COMPACT_HEIGHT_MAX_DP
        return if (wideEnough || landscapePocket) NavigationChromeMode.SIDE_RAIL else NavigationChromeMode.BOTTOM_BAR
    }

    fun contentMaxWidthDp(metrics: AdaptiveWindowMetrics, multiColumn: Boolean = false): Int = when (metrics.widthClass) {
        WindowWidthClass.COMPACT -> Int.MAX_VALUE
        WindowWidthClass.MEDIUM -> if (multiColumn) 760 else 680
        WindowWidthClass.EXPANDED -> if (multiColumn) 920 else 720
    }

    fun contentMaxWidthPx(metrics: AdaptiveWindowMetrics, density: Float, multiColumn: Boolean = false): Int {
        val dp = contentMaxWidthDp(metrics, multiColumn)
        if (dp == Int.MAX_VALUE) return Int.MAX_VALUE
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        return (dp * safeDensity).toInt().coerceAtLeast(1)
    }

    fun navigationDockMaxWidthDp(metrics: AdaptiveWindowMetrics): Int =
        if (metrics.widthClass == WindowWidthClass.COMPACT) 480 else 520

    fun sideRailClearancePx(mode: NavigationChromeMode, density: Float): Int {
        if (mode != NavigationChromeMode.SIDE_RAIL) return 0
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        return ((SIDE_RAIL_WIDTH_DP + SIDE_RAIL_OUTER_MARGIN_DP + SIDE_RAIL_CONTENT_GAP_DP) * safeDensity).toInt()
    }

    fun horizontalContentPaddingPx(viewportWidthPx: Int, maxWidthPx: Int, minSidePaddingPx: Int = 0): Int {
        if (maxWidthPx == Int.MAX_VALUE) return minSidePaddingPx
        val safeViewport = viewportWidthPx.coerceAtLeast(0)
        return maxOf(minSidePaddingPx, (safeViewport - maxWidthPx) / 2)
    }

    fun gridSpanCount(usableContentWidthDp: Int, minColumnWidthDp: Int = 300, maxSpans: Int = 2): Int {
        if (minColumnWidthDp <= 0 || maxSpans <= 1) return 1
        return (usableContentWidthDp.coerceAtLeast(0) / minColumnWidthDp).coerceIn(1, maxSpans)
    }

    /** 遵循 LCE §15.4：非明确定宽弹窗不超过 560dp，避免在大屏展开下过度拉长。 */
    fun modalMaxWidthPx(
        windowWidthPx: Int,
        density: Float,
        maxWidthDp: Int = MODAL_MAX_WIDTH_DP,
        minSideMarginDp: Int = 32
    ): Int {
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val capPx = (maxWidthDp * safeDensity).toInt().coerceAtLeast(1)
        val availablePx = (windowWidthPx - (minSideMarginDp * 2 * safeDensity).toInt()).coerceAtLeast(1)
        return minOf(capPx, availablePx).coerceAtLeast(1)
    }

    fun modalScrollMaxHeightPx(
        usableWindowHeightPx: Int,
        density: Float,
        capDp: Int,
        windowFraction: Float,
        minFloorDp: Int = 120
    ): Int {
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val safeHeight = usableWindowHeightPx.coerceAtLeast(1)
        val target = minOf((capDp * safeDensity).toInt(), (safeHeight * windowFraction).toInt())
        val floor = minOf((minFloorDp * safeDensity).toInt(), safeHeight)
        return maxOf(target, floor).coerceAtLeast(1)
    }

    fun bubbleMaxWidthPx(usableWindowWidthPx: Int, density: Float): Float {
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val preferred = if (usableWindowWidthPx >= (600f * safeDensity)) 360f * safeDensity else 320f * safeDensity
        val maxAllowed = (usableWindowWidthPx - 24f * safeDensity).coerceAtLeast(240f * safeDensity)
        return minOf(preferred, maxAllowed)
    }
}

/** 读取当前 Activity 窗口真实边界（多窗口/分屏下不取整块物理屏幕 metrics）。 */
internal fun Activity.currentWindowBoundsPx(): Rect {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return Rect(windowManager.currentWindowMetrics.bounds)
    }
    val decor = window?.decorView
    if (decor != null && decor.width > 0 && decor.height > 0) {
        return Rect(0, 0, decor.width, decor.height)
    }
    val display = resources.displayMetrics
    return Rect(0, 0, display.widthPixels.coerceAtLeast(1), display.heightPixels.coerceAtLeast(1))
}

internal fun subtractWindowInsets(
    boundsWidth: Int,
    boundsHeight: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int
): Pair<Int, Int> =
    (boundsWidth - left.coerceAtLeast(0) - right.coerceAtLeast(0)).coerceAtLeast(1) to
        (boundsHeight - top.coerceAtLeast(0) - bottom.coerceAtLeast(0)).coerceAtLeast(1)

/** 当前 Activity 窗口扣除系统栏与刘海/折痕安全区后的可用宽高（px）。 */
internal fun Activity.usableWindowSizePx(): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return subtractWindowInsets(
            boundsWidth = bounds.width(),
            boundsHeight = bounds.height(),
            left = insets.left,
            top = insets.top,
            right = insets.right,
            bottom = insets.bottom
        )
    }
    val bounds = currentWindowBoundsPx()
    val decor = window?.decorView
    val compatInsets = decor?.let(ViewCompat::getRootWindowInsets)
    if (compatInsets != null) {
        val combined = compatInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val cutout = compatInsets.displayCutout
        @Suppress("DEPRECATION")
        val left = maxOf(combined.left, compatInsets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
        @Suppress("DEPRECATION")
        val top = maxOf(combined.top, compatInsets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0)
        @Suppress("DEPRECATION")
        val right = maxOf(combined.right, compatInsets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
        @Suppress("DEPRECATION")
        val bottom = maxOf(combined.bottom, compatInsets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
        return subtractWindowInsets(bounds.width(), bounds.height(), left, top, right, bottom)
    }
    val res = resources
    val multiWindow = isInMultiWindowMode
    val statusId = res.getIdentifier("status_bar_height", "dimen", "android")
    val statusTop = if (!multiWindow && statusId != 0) res.getDimensionPixelSize(statusId).coerceAtLeast(0) else 0
    val hasNavBarId = res.getIdentifier("config_showNavigationBar", "bool", "android")
    val hasNavBar = hasNavBarId != 0 && res.getBoolean(hasNavBarId)
    val navId = res.getIdentifier("navigation_bar_height", "dimen", "android")
    val navBottom = if (!multiWindow && hasNavBar && navId != 0 && decor != null && decor.height > 0) {
        res.getDimensionPixelSize(navId).coerceAtLeast(0)
    } else 0
    return subtractWindowInsets(bounds.width(), bounds.height(), 0, statusTop, 0, navBottom)
}

/** 任意控件所属宿主窗口的当前尺寸（px），分屏或自由窗口下比 displayMetrics 更准确。 */
internal fun View.hostWindowSizePx(): Pair<Int, Int> {
    val root = rootView
    if (root != null && root.width > 0 && root.height > 0) {
        return root.width to root.height
    }
    val display = resources.displayMetrics
    return display.widthPixels.coerceAtLeast(1) to display.heightPixels.coerceAtLeast(1)
}

/** 对齐 LCE §15.4 ModalContainer：明确 `EXACTLY` 宽度按指定执行，`WRAP_CONTENT` / `AT_MOST` 钳制在 `maxWidthPx`。 */
internal class BoundedModalContainer(
    context: Context,
    private val maxWidthPx: Int
) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cappedWidthSpec = if (maxWidthPx > 0) {
            when (MeasureSpec.getMode(widthMeasureSpec)) {
                MeasureSpec.EXACTLY -> widthMeasureSpec
                MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(
                    minOf(MeasureSpec.getSize(widthMeasureSpec), maxWidthPx),
                    MeasureSpec.AT_MOST
                )
                else -> MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST)
            }
        } else {
            widthMeasureSpec
        }
        super.onMeasure(cappedWidthSpec, heightMeasureSpec)
    }
}
