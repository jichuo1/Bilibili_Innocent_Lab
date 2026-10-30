package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.lumen.coacervation.engine.model.LumenPalette

/**
 * 顶部"融合带"的运行时装配：让内容层从容器顶边一路画到窗口顶边，再在顶栏之下铺一层
 * 渐渐消隐的实时模糊（[HostTopStatusFusionView]）。
 *
 * 最大容器顶边作为固定负 topMargin，滚动内容仍能画到窗口顶边。列表只保留胶囊行的固定
 * padding；临近列表起点时，pager 用 translationY 连续退回原布局位置，为搜索/状态栏恢复占位。
 * 同一进度把融合带从模糊变为实色，并降回内容层。距离使用列表子项的局部坐标，视觉位移不会
 * 反过来改变进度，也不会逐帧改 padding、干扰原生滚动与刷新行程。
 */
internal class HostTopFusionBinding private constructor(
    private val container: ViewGroup,
    private val band: HostTopStatusFusionView,
    private val pager: ViewGroup?,
    private val pageActions: HostTopIslandPageActions,
    private val backdrop: HostSurfaceScope?,
    private val basePadding: Int,
    private val density: Float,
    isDark: Boolean
) {
    private val location = IntArray(2)
    private var observer: ViewTreeObserver? = null
    private var preDraw: android.view.ViewTreeObserver.OnPreDrawListener? = null

    /** 固定胶囊行内边距；搜索区的占位由 pager 位移独立承担。 */
    val scrollPadding: Int get() = basePadding

    /**
     * 下拉刷新提示球的行程基准：**不能**跟着内容上延走（`SwipeRefreshLayout` 的 `end` 是距离
     * 不是位置），否则提示球速度按同比例翻倍。见 [HostTopBarFxController.applyScrollPadding]。
     */
    val spinnerTravelBase: Int get() = basePadding

    /** 融合带顶边当前对齐到的屏幕 y。 */
    private var bandTop = Int.MIN_VALUE

    /** 渐隐收口当前对齐到的"内容静止顶边"屏幕 y。 */
    private var restTop = Int.MIN_VALUE

    /** 内容上延的固定位移量 = 观测到的容器最大屏幕顶边。 */
    private var contentOffset = 0
    private var listPosition: HostTopListPosition? = null
    private var dockingProgress = 1f

    /** 融合带当前生效的材质深浅色，见 [refreshMaterial]。 */
    private var materialDark = isDark
    private var materialColors: HostChromeColors? = null
    private var preserveHostArtwork = false
    private var originalLightStatus: Boolean? = null
    private var appliedLightStatus: Boolean? = null

    companion object {
        fun attach(
            container: ViewGroup,
            pager: ViewGroup?,
            pageActions: HostTopIslandPageActions,
            backdrop: HostSurfaceScope?,
            basePadding: Int,
            density: Float,
            statusBarInset: Int,
            palette: LumenPalette,
            isDark: Boolean
        ): HostTopFusionBinding? {
            if (statusBarInset <= 0) return null
            return runCatching {
                val band = HostTopStatusFusionView(
                    context = container.context,
                    density = density,
                    palette = palette,
                    backdrop = backdrop,
                    statusBarInset = statusBarInset,
                    // 先按"容器顶边 = 状态栏下沿"这个下界建带；首次 sync 观测到真实顶边后只增不减地长高。
                    initialHeight = HostTopFusionPolicy.bandHeight(statusBarInset + basePadding)
                )
                // 不传 LayoutParams：让宿主自己的 ConstraintLayout 生成它那一份（跨 ClassLoader）
                container.addView(band)
                val lp = band.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = band.bandHeight
                    lp.leftMargin = 0
                    lp.rightMargin = 0
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                    HostTopBarFxController.setConstraintInt(lp, "topToTop", 0)
                    HostTopBarFxController.setConstraintInt(lp, "startToStart", 0)
                    HostTopBarFxController.setConstraintInt(lp, "endToEnd", 0)
                    band.layoutParams = lp
                }
                // Z 序：低于顶栏胶囊（10f·density）、高于 ViewPager（0）——模糊永远在顶栏之下。
                band.translationZ = 2f * density
                HostTopFusionBinding(container, band, pager, pageActions, backdrop, basePadding, density, isDark).also {
                    it.unclipToWindowTop()
                    it.install()
                }
            }.getOrNull()
        }
    }

    private fun observe() {
        val current = container.viewTreeObserver
        if (observer === current && preDraw != null && current.isAlive) return
        unobserve(container)
        if (!current.isAlive) return
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            HostThreadGuard.call("host.top.fusion.predraw", true) { sync() }
        }
        current.addOnPreDrawListener(listener)
        observer = current
        preDraw = listener
    }

    private fun unobserve(view: View = container) {
        val listener = preDraw
        if (listener != null) {
            observer?.takeIf { it.isAlive }?.let { runCatching { it.removeOnPreDrawListener(listener) } }
            view.viewTreeObserver.takeIf { it.isAlive && it !== observer }?.let {
                runCatching { it.removeOnPreDrawListener(listener) }
            }
        }
        observer = null
        preDraw = null
    }

    private fun install() {
        observe()
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                observe()
                sync()
            }

            override fun onViewDetachedFromWindow(v: View) {
                restoreStatusBar()
                unobserve(v)
            }
        })
        sync()
    }

    /** 原生滚动完成后、绘制之前同步。修复宿主覆盖的 padding 时等待重布局，不画错位帧。 */
    fun sync(): Boolean {
        if (!container.isShown) {
            restoreStatusBar()
            return true
        }
        val top = screenTopOf(container) ?: return true
        if (top != bandTop) {
            val first = bandTop == Int.MIN_VALUE
            bandTop = top
            band.translationY = -top.toFloat()
            // 首帧之前带子先不画：那时它的顶边还没对齐到窗口顶边，画出来会是一块错位的白雾。
            if (first) band.alpha = 1f
            // 表面动了：下一帧重采一次，否则纹理停在旧映射上（静止态纹理本就会随内容位移刷新）。
            backdrop?.onVisualMovement()
        }
        // 渐隐收口锚在"内容静止顶边"：宿主 AppBar 一折叠，第一排卡片就上移，收口必须跟着上移，
        // 否则它会被糊在渐隐尾里（且再也滑不出去）。
        val contentRestTop = HostTopFusionPolicy.contentRestTop(top, basePadding)
        if (contentRestTop != restTop) {
            restTop = contentRestTop
            band.updateFade(contentRestTop)
            backdrop?.onVisualMovement()
        }
        if (top > contentOffset) {
            contentOffset = top
            val lp = pager?.layoutParams
            if (lp is ViewGroup.MarginLayoutParams && lp.topMargin != -top) {
                lp.topMargin = -top
                pager.layoutParams = lp
            }
            if (pager != null) {
                HostTopBarFxController.applyScrollPadding(pager, scrollPadding, density, basePadding)
            }
            ModernHookLog.info("[BIL] 顶栏融合带内容上延: offset=$top, scrollPadding=$scrollPadding")
        }
        val list = pageActions.visibleList()
        if (listPosition?.view !== list) listPosition = list?.let(::HostTopListPosition)
        if (list != null && list.paddingTop != basePadding) {
            HostTopBarFxController.applyScrollPadding(list, basePadding, density, basePadding)
            if (list.paddingTop == basePadding) return false
        }
        // padding/首项锚点在下一次布局才生效，不能用旧坐标更新停靠进度或画出遮挡帧。
        if (list?.isLayoutRequested == true) return false
        listPosition?.edge()?.let { edge ->
            val range = minOf(HostTopFusionPolicy.DOCK_RANGE_DP * density, edge.firstRowExtent)
            dockingProgress = HostTopFusionPolicy.dockingProgress(edge.distance, range)
        }
        val offset = contentOffset * dockingProgress
        if (pager != null && pager.translationY != offset) {
            pager.translationY = offset
            backdrop?.onVisualMovement()
        }
        band.updateDocking(dockingProgress, top)
        // 装扮图上的原生状态栏前景由宿主管理；滚动后背景变成模块玻璃，要切回材质的前景深浅。
        if (preserveHostArtwork && dockingProgress < .5f) {
            ViewCompat.getWindowInsetsController(container)?.let { controller ->
                if (originalLightStatus == null) originalLightStatus = controller.isAppearanceLightStatusBars
                val light = !materialDark
                if (controller.isAppearanceLightStatusBars != light) controller.isAppearanceLightStatusBars = light
                appliedLightStatus = light
            }
        } else restoreStatusBar()
        return true
    }

    private fun restoreStatusBar() {
        val original = originalLightStatus ?: return
        ViewCompat.getWindowInsetsController(container)?.let { controller ->
            // 宿主切页后已设置自己的前景时不覆盖；只恢复本绑定仍持有的状态。
            if (controller.isAppearanceLightStatusBars == appliedLightStatus)
                controller.isAppearanceLightStatusBars = original
        }
        originalLightStatus = null
        appliedLightStatus = null
    }

    /** 深浅色/主题切换后刷新融合带的色罩（采样纹理只带 alpha 曲线，不受影响）。 */
    fun refreshMaterial(colors: HostChromeColors, preserveHostArtwork: Boolean = false) {
        val isDark = colors.dark
        if (colors == materialColors && this.preserveHostArtwork == preserveHostArtwork) return
        materialColors = colors
        materialDark = isDark
        this.preserveHostArtwork = preserveHostArtwork
        band.updateMaterial(
            colors.palette(), preserveHostArtwork
        )
    }

    private fun screenTopOf(view: View): Int? {
        if (!view.isAttachedToWindow) return null
        view.getLocationOnScreen(location)
        return location[1]
    }

    /**
     * 打开"内容画到窗口顶边"这条路：从容器往上到窗口根，逐层关掉 clipChildren/clipToPadding。
     * 只放宽裁剪、不改任何布局参数；这条链上每个宿主视图都没有依赖这些裁剪的子视图。
     */
    private fun unclipToWindowTop() {
        var node: View? = container
        while (node != null) {
            if (node is ViewGroup) {
                if (node.clipChildren) node.clipChildren = false
                if (node.clipToPadding) node.clipToPadding = false
            }
            node = node.parent as? View
        }
    }
}
