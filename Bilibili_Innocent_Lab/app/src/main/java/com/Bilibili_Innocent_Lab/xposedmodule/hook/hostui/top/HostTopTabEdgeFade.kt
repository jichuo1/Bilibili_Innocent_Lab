package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import androidx.core.view.updatePadding
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostChromeValueLedger
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGlowView
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import io.github.jichuo1.meguviz.android.extensions.childOrNull
import io.github.jichuo1.meguviz.android.extensions.firstChildOrNull
import io.github.jichuo1.meguviz.android.extensions.parentOrNull
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 实际滚动上限。canScrollHorizontally 的 extent 是含内边距的 View 宽度，滚动尾部 paddingRight 这段会
 * 被判成到底；渐隐把右缘越出部分并入内边距后，凡要判断还能不能滚都以这里为准。
 */
internal fun HorizontalScrollView.tabScrollRange(): Int {
    val child = firstChildOrNull<View>() ?: return 0
    val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
    return HostTopTabFadePolicy.maxScroll(child.width + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0),
        width, paddingLeft, paddingRight)
}

/** View-owned listeners retain bindings; this registry never retains a detached hierarchy. */
internal object NativeTopTabsEdgeFade {
    private val bindings = WeakHashMap<ViewGroup, WeakReference<HostTopTabEdgeFade>>()

    fun attach(dock: ViewGroup, tabs: ViewGroup?, centering: () -> Boolean = { false }): HostTopTabEdgeFade? {
        tabs ?: return null
        val previous = synchronized(bindings) { bindings[dock]?.get() }
        if (previous?.matches(tabs) == true) { previous.sync(); return previous }
        previous?.close()
        return HostTopTabEdgeFade(dock, tabs, centering).also { synchronized(bindings) { bindings[dock] = WeakReference(it) } }
    }
}

/**
 * 多标签条（宿主 HomePagerSlidingTabStrip 即 HorizontalScrollView）在菜单/搜索左侧或胶囊右端前裁切，
 * 两端按外围胶囊的端帽圆弧渐隐。越出的部分并入右内边距，滚动范围随之延长；手势滑动、惯性与点击
 * 全部仍是宿主原生行为。遮罩画在标签条自己的 ViewOverlay 里，硬件层把擦除限定在标签内容上。
 * 监听器与遮罩都跑在宿主的栈上，一律经 [HostThreadGuard] 兜底。
 */
internal class HostTopTabEdgeFade internal constructor(
    private val dock: ViewGroup,
    private val tabs: ViewGroup,
    /** True while the short-strip centering owns the paddings; its restore is our baseline. */
    private val centering: () -> Boolean = { false }
) : AutoCloseable {
    /** Drawn in the strip's content coordinates, i.e. already translated by its scrollX. */
    private inner class Mask : Drawable() {
        override fun draw(canvas: Canvas) = HostThreadGuard.run("host.top.fade.draw") {
            val scroll = owned ?: return@run
            // DST_OUT 没有独立图层就会把胶囊玻璃一起擦掉；图层被改掉时宁可不渐隐。
            if (scroll.layerType != View.LAYER_TYPE_HARDWARE) return@run
            val x = scroll.scrollX
            val max = scroll.tabScrollRange()
            val fade = fadeLength.toFloat()
            val leading = fade * HostTopTabFadePolicy.leadingStrength(x, fade)
            val trailing = fade * HostTopTabFadePolicy.trailingStrength(x, max, fade)
            arc.draw(canvas, x.toFloat(), 0f, (x + scroll.width).toFloat(), scroll.height.toFloat(), centerY, radius,
                (x + scroll.paddingLeft).toFloat(), (x + scroll.width - scroll.paddingRight).toFloat(), leading, trailing, density)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private val density = dock.resources.displayMetrics.density
    private val fadeLength = (HostTopTabFadePolicy.FADE_DP * density).roundToInt()
    private val actionGap = (HostTopTabFadePolicy.ACTION_GAP_DP * density).roundToInt()
    private val arc = HostTopTabArcMask()
    private val mask = Mask()
    private val paddings = HostChromeValueLedger<HorizontalScrollView, Int>({ it.paddingRight },
        { view, value -> view.updatePadding(right = value) })
    private val clips = HostChromeValueLedger<HorizontalScrollView, Boolean>({ it.clipToPadding },
        { view, value -> view.clipToPadding = value })
    private val layers = HostChromeValueLedger<HorizontalScrollView, Int>({ it.layerType },
        { view, value -> view.setLayerType(value, null) })
    private val bounds = Rect()
    private val rectangle = Rect()
    /** 外围胶囊在标签条坐标中的中线与端帽半径。 */
    private var centerY = 0f
    private var radius = 0f
    private var owned: HorizontalScrollView? = null
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { HostThreadGuard.run("host.top.fade.layout") { sync() } }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = HostThreadGuard.run("host.top.fade.attach") { observe(); sync() }
        override fun onViewDetachedFromWindow(view: View) = HostThreadGuard.run("host.top.fade.detach") { unobserve(); restore() }
    }

    init {
        dock.addOnAttachStateChangeListener(attachment)
        if (dock.isAttachedToWindow) { observe(); sync() }
    }

    internal fun matches(candidate: ViewGroup): Boolean = !closed && tabs === candidate

    private fun observe() {
        val current = dock.viewTreeObserver
        if (observer === current && current.isAlive) return
        unobserve()
        if (current.isAlive) { current.addOnGlobalLayoutListener(layout); observer = current }
    }
    private fun unobserve() {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
        observer = null
    }
    private fun restore() {
        val scroll = owned ?: return
        owned = null
        scroll.overlay.remove(mask)
        paddings.restore(scroll)
        clips.restore(scroll)
        layers.restore(scroll)
        scroll.invalidate()
    }

    /** Runs after a real layout; assignments are idempotent and never happen from a draw callback. */
    internal fun sync() {
        if (closed || !dock.isAttachedToWindow || dock.width <= 0) return
        val scroll = target() ?: run { restore(); return }
        if (owned !== scroll) restore()
        val strip = scroll.firstChildOrNull<ViewGroup>() ?: run { restore(); return }
        var items = 0
        for (index in 0 until strip.childCount) {
            val item = strip.childOrNull(index) ?: continue
            if (item.visibility != View.GONE && !HostTopBarFxController.isUnderlineIndicator(item)) items++
        }
        if (items < HostTopTabFadePolicy.MIN_ITEMS || centering()) { restore(); return }
        bounds.set(scroll.scrollX, scroll.scrollY, scroll.scrollX + scroll.width, scroll.scrollY + scroll.height)
        runCatching { dock.offsetDescendantRectToMyCoords(scroll, bounds) }.getOrElse { restore(); return }
        val limit = trailingLimit(scroll) ?: run { restore(); return }
        // 账本以宿主的内边距为基准：宿主改写后下一次布局自动换基准，交还时也只写回仍是我们的值。
        paddings.apply(scroll) { base -> HostTopTabFadePolicy.endPadding(base, bounds.right, limit) }
        clips.apply(scroll) { true }
        // DST_OUT 只能擦除同一图层里的内容；硬件层把标签条与胶囊玻璃隔开。放得下时不渐隐，也不占这层。
        if (scroll.tabScrollRange() > 0) layers.apply(scroll) { View.LAYER_TYPE_HARDWARE } else layers.restore(scroll)
        // 顶栏对齐用 translationY 垂直居中子项；布局坐标不含它，圆心要一并扣除。
        var shift = 0f
        var node: View = scroll
        while (node !== dock) { shift += node.translationY; node = node.parentOrNull() ?: break }
        val nextCenter = dock.height / 2f - bounds.top - shift
        val nextRadius = dock.height / 2f
        if (owned == null || nextCenter != centerY || nextRadius != radius) {
            centerY = nextCenter; radius = nextRadius
            scroll.invalidate()
        }
        if (owned == null) { owned = scroll; scroll.overlay.add(mask) }
        // Overlay 只按边界做脏区；覆盖整个内容宽度，滚到任何位置都会重画遮罩。
        val contentWidth = maxOf(strip.width, scroll.width) + scroll.paddingLeft + scroll.paddingRight
        if (mask.bounds.right != contentWidth || mask.bounds.bottom != scroll.height) mask.setBounds(0, 0, contentWidth, scroll.height)
    }

    private fun target(): HorizontalScrollView? {
        var ancestor: View? = tabs
        while (ancestor != null && ancestor !== dock) {
            if (ancestor is HorizontalScrollView) return ancestor.takeIf { it.childCount == 1 && it.width > 0 }
            ancestor = ancestor.parentOrNull()
        }
        return null
    }

    /** 胶囊右端内缘，或标签条右侧第一个可点击功能控件左缘减空隙，取较近者（dock 坐标）。 */
    private fun trailingLimit(scroll: HorizontalScrollView): Int? {
        var limit = dock.width - dock.paddingRight
        var visited = 0
        fun visit(view: View): Boolean {
            if (view.visibility != View.VISIBLE || view is HostDockLayer || view is HostGlowView) return true
            if (++visited > 64) return false
            if (view.isClickable || view.hasOnClickListeners()) {
                rectangle.set(0, 0, view.width, view.height)
                dock.offsetDescendantRectToMyCoords(view, rectangle)
                if (rectangle.width() > 0 && rectangle.bottom > bounds.top && rectangle.top < bounds.bottom &&
                    rectangle.left * 2 >= dock.width) limit = minOf(limit, rectangle.left - actionGap)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) if (!visit(view.childOrNull(index) ?: continue)) return false
            }
            return true
        }
        var branch: View = scroll
        while (branch !== dock) {
            val parent = branch.parentOrNull() ?: return null
            for (index in 0 until parent.childCount) {
                val sibling = parent.childOrNull(index) ?: continue
                if (sibling !== branch && !visit(sibling)) return null
            }
            branch = parent
        }
        // 限位不能把视口压到连一个渐隐长度都放不下。
        return limit.takeIf { it - bounds.left > fadeLength * 2 }
    }

    override fun close() {
        if (closed) return
        closed = true
        unobserve(); dock.removeOnAttachStateChangeListener(attachment); restore()
    }
}
