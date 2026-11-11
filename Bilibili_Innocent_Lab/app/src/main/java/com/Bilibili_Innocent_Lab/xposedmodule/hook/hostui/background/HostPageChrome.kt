package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopStatusFusionView
import io.github.jichuo1.meguviz.android.extensions.rootStatusBarInsetTopOrNull
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 页面顶部的语义层。只采样祖先壁纸，保留原生点击、筛选和已有卡片材质。 */
internal class HostPageChrome(root: ViewGroup, private val owned: MutableMap<View, Drawable>) : AutoCloseable {
    private enum class Role { TOOLBAR, SEARCH, FILTER, AUTHORS }
    private class Target(val role: Role) {
        var binding: LumenSurfaceBinding? = null
    }

    private class Style(night: Boolean) {
        val palette = LumenPalette.neutral(night)
        val full = options(night, 1f)
        val end = options(night, 0f)
        val authorsEnd = options(night, .15f)

        private fun options(night: Boolean, fadeHold: Float) = LumenSurfaceOptions(
            material = LumenSurfaceMaterial.FROSTED, role = SurfaceRole.TOP_BAR, radiusDp = 0f,
            color = if (night) 0xFF181A20.toInt() else 0xFFF8F9FC.toInt(),
            tintOpacity = .42f, fallbackTintOpacity = .42f, backdropOpacity = 1f,
            edgeEnabled = false,
            sampling = LumenSurfaceSampling(refractionEnabled = false, blurRadiusDp = 18f,
                softwareBlurRadiusDp = 12f, maxSoftwarePixels = 48_000,
                fadeEnabled = true, fadeHold = fadeHold, fadeEnd = 1f)
        )
    }

    private val root = WeakReference(root)
    private val targets = WeakHashMap<View, Target>()
    private var session: LumenSurfaceSession? = null
    private var source: HostChromeContentSource? = null
    private var dark: Boolean? = null
    private var version: Long? = null
    private var statusBand: View? = null
    private var statusBinding: LumenSurfaceBinding? = null
    private val location = IntArray(2)
    private val visibleBounds = Rect()

    fun discover() {
        val root = root.get() ?: return
        val found = HashMap<View, Role>()
        val protectedRoot = HostBackgroundPages.protectedAncestor(root)
        val externalOwner = HostBackgroundPages.owner(root)?.let { it !== root } == true
        fun visit(view: View, depth: Int) {
            // 祖先已由递归入口检查；不再为每个子节点从头扫描整条父链。
            if (depth > 24 || protectedRoot || HostBackgroundPages.protected(view)) return
            if (view !== root && (externalOwner || HostBackgroundPages.pageRoot(view))) return
            val name = name(view)
            val role = when (name) {
                "nav_top_bar" -> if (HostTopStatusFusionView.ownsToolbar(view)) null else Role.TOOLBAR
                "search_input_layout" -> Role.SEARCH
                "tab_box", "container_strip", "header_container" -> Role.FILTER
                "top_container" -> if (view is ViewGroup && (0 until view.childCount).any {
                    name(view.getChildAt(it)) == "tab_layout"
                }) Role.FILTER else null
                "dy_recycler" -> Role.AUTHORS
                else -> null
            }
            if (role != null) { found[view] = role; return }
            // ViewPager2 内部的 RecyclerView 承载页面，收藏筛选栏位于其中；
            // 普通内容列表仍不进入，避免遍历卡片或给卡片子组件套顶栏材质。
            if (view !is ViewGroup || !view.javaClass.name.startsWith("androidx.viewpager2.") &&
                generateSequence(view.javaClass as Class<*>?) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" }) return
            for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        for (view in targets.keys.toList()) if (view !in found) {
            targets.remove(view)?.binding?.close()
            owned.remove(view)
        }
        for ((view, role) in found) targets.getOrPut(view) { Target(role) }
    }

    fun apply(night: Boolean) {
        val root = root.get() ?: return
        if (targets.isEmpty()) { hideStatusBar(); return }
        val style = if (night) darkStyle else lightStyle
        val palette = style.palette
        val changed = dark != night
        val active = session ?: LumenSurfaceSession(root.context, palette,
            LumenSurfaceSessionOptions(maxSurfaces = 8, pauseWhenWindowUnfocused = false)).also {
            session = it
            // includeViewContent=false 不会把栏位自身、媒体或其他玻璃录入采样。
            source = HostChromeContentSource(root, palette.background, includeViewContent = false)
        }
        if (changed) {
            active.updatePalette(palette)
            source?.windowBaseColor = palette.background
            dark = night
        }
        for ((view, target) in targets) {
            if (!view.isShown) continue
            // 搜索栏始终保持完整材质，其它栏位只在整组最下沿渐隐。
            val options = when {
                target.role == Role.SEARCH || continuesBelow(view) -> style.full
                target.role == Role.AUTHORS -> style.authorsEnd
                else -> style.end
            }
            val binding = active.ensureSource(view, options, source)
            if (binding !== target.binding) {
                target.binding = binding
                view.background?.let { owned[view] = it }
            }
        }
        syncStatusBar(active, style.full)
        val next = source?.contentVersion
        if (version != next || changed) { version = next; active.notifyContentChanged() }
    }

    private fun hideStatusBar() {
        statusBinding?.close(); statusBinding = null
        statusBand?.visibility = View.GONE
    }

    private fun syncStatusBar(active: LumenSurfaceSession, options: LumenSurfaceOptions) {
        val root = root.get() ?: return
        // 只有窗口根能够绘制系统图标背后的区域；子 Fragment 不能重复加一层。
        if (root !== root.rootView || root !is FrameLayout) return
        val inset = root.rootStatusBarInsetTopOrNull() ?: 0
        if (inset <= 0) { hideStatusBar(); return }
        val tolerance = root.resources.displayMetrics.density * 2f
        val touchesStatusBar = targets.keys.any { view ->
            if (!visibleChrome(view, root)) false
            else {
                view.getLocationInWindow(location)
                location[1] <= inset + tolerance && location[1] + view.height > inset
            }
        }
        if (!touchesStatusBar) { hideStatusBar(); return }
        val band = statusBand ?: View(root.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.addView(this, FrameLayout.LayoutParams(-1, inset, Gravity.TOP))
        }.also { statusBand = it }
        if (band.layoutParams.height != inset) {
            band.layoutParams = band.layoutParams.apply { height = inset }
        }
        band.visibility = View.VISIBLE
        // 与相邻栏共用源、模糊半径和色罩，保留壁纸的窗口坐标，系统图标由系统照常绘制。
        statusBinding = active.ensureSource(band, options, source)
    }

    private fun visibleChrome(view: View, root: ViewGroup): Boolean {
        if (!view.isShown || !view.getGlobalVisibleRect(visibleBounds) || visibleBounds.width() < root.width * .9f) return false
        // 首页栏可能仍挂在离屏分页里，或由祖先 alpha 隐藏；isShown 本身不排除这两种状态。
        var node: View? = view
        while (node != null && node !== root) {
            if (node.alpha <= .01f || node.width <= 0 || node.height <= 0) return false
            node = node.parent as? View
        }
        return true
    }

    private fun continuesBelow(view: View): Boolean {
        val root = root.get() ?: return false
        view.getLocationInWindow(location)
        val bottom = location[1] + view.height
        val tolerance = view.resources.displayMetrics.density * 2f
        return targets.keys.any { next ->
            if (next === view || !visibleChrome(next, root)) false
            else {
                next.getLocationInWindow(location)
                kotlin.math.abs(location[1] - bottom) <= tolerance && next.height > 0
            }
        }
    }

    override fun close() {
        hideStatusBar()
        statusBand?.let { (it.parent as? ViewGroup)?.removeView(it) }
        statusBand = null
        session?.close(); session = null
        source = null; dark = null; version = null
        for ((view, target) in targets) {
            owned.remove(view)
            target.binding = null
        }
    }

    private fun name(view: View) = HostViewResourceNames.name(view)

    private companion object {
        val lightStyle = Style(night = false)
        val darkStyle = Style(night = true)
    }
}
