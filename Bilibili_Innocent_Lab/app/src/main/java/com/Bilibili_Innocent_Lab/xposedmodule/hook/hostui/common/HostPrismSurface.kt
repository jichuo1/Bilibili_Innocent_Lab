package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.view.View
import android.view.ViewGroup
import com.lumen.coacervation.engine.host.LumenPrismGlass
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** 独立真实 View 持有棱镜；缩岛改变采样几何，外阴影不受宿主自己的 Outline 裁剪。 */
internal class HostPrismSurface(
    hostView: View,
    palette: LumenPalette,
    session: LumenSurfaceSession,
    source: View?
) : AutoCloseable {
    private class ClipLease(val original: Boolean, var users: Int = 0)
    private class PrismView(context: android.content.Context, val clipping: ClipLease) : View(context), HostDockLayer {
        var owner: HostPrismSurface? = null
    }
    private var original: android.graphics.drawable.Drawable? = hostView.background
    private val marker = Color.TRANSPARENT.toDrawable()
    // Surface records live in a WeakHashMap; their values must not retain the View keys or parents.
    private val host = WeakReference(hostView)
    private val parent = WeakReference(hostView.parent as ViewGroup)
    private val clipping: ClipLease?
    private val layer: WeakReference<PrismView>?
    private val glass: LumenPrismGlass
    private var closed = false

    init {
        val parent = requireNotNull(parent.get())
        if (isWhitelistedSiblingParent(parent)) {
            // Share clipping ownership with other Prism siblings without a process-level View cache.
            val lease = (0 until parent.childCount).asSequence()
                .mapNotNull { parent.getChildAt(it) as? PrismView }.firstOrNull()?.clipping ?: ClipLease(parent.clipChildren)
            clipping = lease
            val layerView = PrismView(hostView.context, lease).also { it.owner = this }
            layer = WeakReference(layerView)
            var leased = false
            glass = try {
                // Let the host parent create its own LayoutParams (including host ConstraintLayout).
                parent.addView(layerView, parent.indexOfChild(hostView))
                lease.users++
                leased = true
                parent.clipChildren = false
                layerView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                hostView.background = marker
                LumenPrismGlass(layerView, palette, LumenPrismOptions(), session, source)
            } catch (failure: Throwable) {
                layerView.owner = null
                if (layerView.parent === parent) parent.removeView(layerView)
                if (leased && --lease.users == 0 && !parent.clipChildren) parent.clipChildren = lease.original
                val restored = original
                original = null
                if (hostView.background === marker) hostView.background = restored
                throw failure
            }
        } else {
            // 非定位/流式父容器（如 LinearLayout 或单子滚动容器）禁止插入兄弟节点，直接降级挂载到宿主 View。
            clipping = null
            layer = null
            glass = LumenPrismGlass(hostView, palette, LumenPrismOptions(), session, source)
        }
    }

    val owns: Boolean get() {
        val host = host.get() ?: return false
        val parent = parent.get() ?: return false
        if (closed || host.parent !== parent) return false
        val layerRef = layer ?: return !glass.isClosed && host.background !== original
        val layerView = layerRef.get() ?: return false
        return layerView.parent === parent && host.background === marker
    }

    fun sync(insetX: Float, insetY: Float) {
        if (closed) return
        val host = host.get() ?: return
        val layer = layer?.get() ?: return
        val x = insetX.coerceIn(0f, (host.width - 1).coerceAtLeast(0) / 2f).roundToInt()
        val y = insetY.coerceIn(0f, (host.height - 1).coerceAtLeast(0) / 2f).roundToInt()
        val width = (host.width - x * 2).coerceAtLeast(1)
        val height = (host.height - y * 2).coerceAtLeast(1)
        if (layer.measuredWidth != width || layer.measuredHeight != height)
            layer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        val left = host.left + x
        val top = host.top + y
        val right = left + width
        val bottom = top + height
        if (layer.left != left || layer.top != top || layer.right != right || layer.bottom != bottom)
            layer.layout(left, top, right, bottom)
        layer.pivotX = host.pivotX - x
        layer.pivotY = host.pivotY - y
        layer.translationX = host.translationX
        layer.translationY = host.translationY
        layer.translationZ = host.z
        layer.scaleX = host.scaleX
        layer.scaleY = host.scaleY
        layer.rotation = host.rotation
        layer.rotationX = host.rotationX
        layer.rotationY = host.rotationY
        layer.alpha = host.alpha
        layer.visibility = if (host.isShown && host.width > 0 && host.height > 0) View.VISIBLE else View.INVISIBLE
    }

    fun updatePalette(palette: LumenPalette) = glass.updatePalette(palette)
    fun notifyPositionChanged() = glass.notifyPositionChanged()
    fun diagnostics() = glass.diagnostics()

    override fun close() {
        if (closed) return
        closed = true
        try { glass.close() } finally {
            val parent = parent.get()
            layer?.get()?.let {
                it.owner = null
                if (it.parent === parent) parent?.removeView(it)
            }
            clipping?.let { lease ->
                if (--lease.users == 0 && parent?.clipChildren == false) parent.clipChildren = lease.original
            }
            val restored = original
            original = null
            host.get()?.takeIf { it.background === marker }?.background = restored
        }
    }

    companion object {
        private val disallowedFlowOrSingleChildNames = setOf(
            "LinearLayout",
            "ScrollView",
            "HorizontalScrollView",
            "NestedScrollView",
            "AdapterView",
            "RecyclerView",
            "ViewPager",
            "ViewPager2"
        )
        private val allowedSiblingParents = setOf(
            "android.widget.FrameLayout",
            "android.widget.RelativeLayout",
            "androidx.coordinatorlayout.widget.CoordinatorLayout",
            "androidx.constraintlayout.widget.ConstraintLayout",
            "FrameLayout",
            "RelativeLayout",
            "CoordinatorLayout",
            "ConstraintLayout"
        )

        internal fun isWhitelistedSiblingParent(parent: ViewGroup): Boolean {
            val hierarchy = buildList {
                var current: Class<*>? = parent.javaClass
                while (current != null && ViewGroup::class.java.isAssignableFrom(current)) {
                    add(current.name)
                    current = current.superclass
                }
            }
            return isWhitelistedSiblingParentNames(hierarchy)
        }

        internal fun isWhitelistedSiblingParentNames(classNames: List<String>): Boolean {
            if (classNames.isEmpty()) return false
            for (name in classNames) {
                val simple = name.substringAfterLast('.').substringAfterLast('$')
                if (simple in disallowedFlowOrSingleChildNames) return false
            }
            return classNames.any { name ->
                val simple = name.substringAfterLast('.').substringAfterLast('$')
                name in allowedSiblingParents || simple in allowedSiblingParents
            }
        }
    }
}
