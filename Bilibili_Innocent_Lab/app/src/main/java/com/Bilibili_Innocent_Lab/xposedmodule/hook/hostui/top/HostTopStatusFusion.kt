package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostViewResourceNames

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.roundToInt
import java.lang.ref.WeakReference
import java.util.WeakHashMap
internal object HostTopFusionPolicy {

    /** 回顶前最后一段手势距离；进度直接跟手，两端的速度均收敛到零。 */
    const val DOCK_RANGE_DP = 208f

    fun dockingProgress(distance: Float, range: Float): Float {
        if (!distance.isFinite()) return 0f
        if (range <= 0f) return if (distance <= 0f) 1f else 0f
        val t = (1f - distance / range).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 渐隐收尾处离"内容静止顶边"的留白（dp）。留白比 smoothstep 的收口更靠上，
     * 静止态的卡片顶边落在权重严格为 0 的一侧，不存在"第一排永远带一点糊"的情况。
     */
    const val CONTENT_GAP_DP = 8f

    /** 带子高度相对"内容静止顶边"的余量（px）：顶边本身取整后仍能盖住渐隐收尾。 */
    const val HEIGHT_SLACK_PX = 2

    /** 满强度区：状态栏整段（挖孔/异形屏也计入）吃满模糊。 */
    fun holdFraction(statusBarInset: Int, bandHeight: Int): Float {
        if (bandHeight <= 0) return 1f
        return (statusBarInset.toFloat() / bandHeight).coerceIn(0f, 1f)
    }

    /** 内容静止顶边在屏幕上的位置。 */
    fun contentRestTop(containerTop: Int, basePadding: Int): Int = containerTop + basePadding

    /** 融合带高度：内容静止顶边（+ 取整余量），保证渐隐能在带内收干净。 */
    fun bandHeight(contentRestTop: Int): Int = contentRestTop + HEIGHT_SLACK_PX

    /**
     * 渐隐收尾位置的归一化值：内容静止顶边往上留 [CONTENT_GAP_DP]，并夹在满强度区之后。
     * 夹到 [hold] 意味着"渐隐在满强度区结束处就收完"，绝不会越过内容静止顶边。
     */
    fun fadeEndFraction(contentRestTop: Int, density: Float, statusBarInset: Int, bandHeight: Int): Float {
        if (bandHeight <= 0) return 1f
        val gap = (CONTENT_GAP_DP * density).roundToInt()
        val end = ((contentRestTop - gap).toFloat() / bandHeight).coerceIn(0f, 1f)
        return end.coerceAtLeast(holdFraction(statusBarInset, bandHeight))
    }

    /** 归一化高度 → alpha 权重；与采样纹理的逐像素渐隐共用同一条曲线。 */
    fun fadeWeight(fraction: Float, hold: Float, end: Float): Float {
        if (fraction <= hold) return 1f
        if (fraction >= end || end <= hold) return 0f
        val t = ((fraction - hold) / (end - hold)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }
}

/** 两个引擎表面交叉淡入：滚动玻璃与回顶后的静态承托；宿主仅管理位置和进度。 */
@SuppressLint("ViewConstructor")
internal class HostTopStatusFusionView(
    context: Context,
    private val density: Float,
    palette: LumenPalette,
    private val backdrop: HostSurfaceScope?,
    private val statusBarInset: Int,
    initialHeight: Int
) : FrameLayout(context), HostDockLayer {
    companion object {
        private val windows = WeakHashMap<View, WeakReference<HostTopStatusFusionView>>()

        /** 首页搜索栏和状态栏由同一条渐隐带负责，通用页面材质不能再次覆盖。 */
        fun ownsToolbar(view: View): Boolean =
            (view.parent as? View)?.let { HostViewResourceNames.name(it) == "app_bar" } == true &&
                windows[view.rootView]?.get()?.isAttachedToWindow == true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windows[rootView] = WeakReference(this)
    }

    override fun onDetachedFromWindow() {
        if (windows[rootView]?.get() === this) windows.remove(rootView)
        super.onDetachedFromWindow()
    }

    var bandHeight = initialHeight
        private set
    private var palette = palette
    private var dark = palette.background == LumenPalette.neutral(true).background
    private var fadeEnd = 1f
    private var dockingBottom = 0
    private var wallpaper = false
    private var preserveHostArtwork = false
    private val glass = View(context)
    private val docked = View(context)
    val profile: LumenSurfaceSampling get() = options().sampling

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isHapticFeedbackEnabled = false
        alpha = 0f
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(docked, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        glass.alpha = 0f
        updateSurfaces()
    }

    private fun liveDensity(): Float = resources?.displayMetrics?.density?.takeIf { it > 0f } ?: density

    private fun options() = HostSurfaceStyle.fusion(dark,
        HostTopFusionPolicy.holdFraction(statusBarInset, bandHeight).coerceAtMost(fadeEnd), fadeEnd)

    private fun updateSurfaces() {
        backdrop?.surface(glass, options())
        val hold = (dockingBottom.toFloat() / bandHeight.coerceAtLeast(1)).coerceIn(0f, fadeEnd)
        backdrop?.surface(docked, HostSurfaceStyle.fusion(dark, hold, fadeEnd).copy(
            material = LumenSurfaceMaterial.STATIC,
            color = if (dark) palette.surface else 0xFFFFFFFF.toInt(),
            // 回顶后透出模块壁纸或宿主装扮图；滚动时仍由实时玻璃承托前景。
            tintOpacity = if (wallpaper || preserveHostArtwork) 0f else 1f,
            fallbackTintOpacity = if (wallpaper || preserveHostArtwork) 0f else 1f,
            backdropOpacity = 0f
        ))
    }

    fun updateDocking(progress: Float, bottom: Int) {
        glass.alpha = 1f - progress
        docked.alpha = progress
        translationZ = 2f * liveDensity() * (1f - progress)
        val hasWallpaper = HostBackgroundPages.contains(this)
        if (bottom != dockingBottom || hasWallpaper != wallpaper) {
            dockingBottom = bottom
            wallpaper = hasWallpaper
            updateSurfaces()
        }
    }

    fun updateMaterial(palette: LumenPalette, preserveHostArtwork: Boolean = false) {
        if (this.palette == palette && this.preserveHostArtwork == preserveHostArtwork) return
        this.palette = palette
        this.preserveHostArtwork = preserveHostArtwork
        dark = palette.background == LumenPalette.neutral(true).background
        updateSurfaces()
    }

    fun updateFade(contentRestTop: Int) {
        val height = HostTopFusionPolicy.bandHeight(contentRestTop).coerceAtLeast(1)
        val heightChanged = height != bandHeight
        if (heightChanged) {
            bandHeight = height
            layoutParams?.let {
                if (it.height != height) {
                    it.height = height
                    layoutParams = it
                }
            }
        }
        val next = HostTopFusionPolicy.fadeEndFraction(contentRestTop, liveDensity(), statusBarInset, bandHeight)
        if (next == fadeEnd && !heightChanged) return
        fadeEnd = next
        updateSurfaces()
    }
}
