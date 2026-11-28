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

    private class PrismView(
        context: android.content.Context,
        val clipping: ClipLease,
        hostView: View
    ) : View(context), HostDockLayer {
        val hostRef = WeakReference(hostView)
        var owner: HostPrismSurface? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val active = owner
            if (active == null) {
                setMeasuredDimension(0, 0)
            } else {
                // FrameLayout/ConstraintLayout 若提前重排，也只按宿主当前有效尺寸测量，绝不回弹满屏。
                setMeasuredDimension(active.desiredWidth(), active.desiredHeight())
            }
        }
    }

    private class HostAttachAnchor(var owner: HostPrismSurface?) : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            owner?.notifyPositionChanged()
        }

        override fun onViewDetachedFromWindow(v: View) = Unit
    }

    private var original: android.graphics.drawable.Drawable? = null
    private val marker = Color.TRANSPARENT.toDrawable()
    // Surface records live in a WeakHashMap; their values must not retain the View keys or parents.
    private val host = WeakReference(hostView)
    private val parent = WeakReference(hostView.parent as ViewGroup)
    private val clipping: ClipLease?
    private val layer: WeakReference<PrismView>?
    private var hostAnchor: HostAttachAnchor? = null
    private val glass: LumenPrismGlass
    private var lastInsetX = 0f
    private var lastInsetY = 0f
    private var closed = false

    init {
        val parent = requireNotNull(parent.get())
        if (isWhitelistedSiblingParent(parent)) {
            removeStaleSiblingLayers(parent, hostView)
            original = hostView.background
            // Share clipping ownership with other Prism siblings without a process-level View cache.
            val lease = (0 until parent.childCount).asSequence()
                .mapNotNull { parent.getChildAt(it) as? PrismView }.firstOrNull()?.clipping ?: ClipLease(parent.clipChildren)
            clipping = lease
            val layerView = PrismView(hostView.context, lease, hostView).also { it.owner = this }
            layer = WeakReference(layerView)
            var leased = false
            glass = try {
                // Let the host parent create its own LayoutParams (including host ConstraintLayout),
                // then switch size to WRAP_CONTENT so orphaned layouts never fall back to MATCH_PARENT.
                parent.addView(layerView, parent.indexOfChild(hostView).coerceAtLeast(0))
                layerView.layoutParams = (layerView.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
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
            original = hostView.background
            clipping = null
            layer = null
            val anchor = HostAttachAnchor(this)
            hostAnchor = anchor
            hostView.addOnAttachStateChangeListener(anchor)
            glass = try {
                LumenPrismGlass(hostView, palette, LumenPrismOptions(), session, source)
            } catch (failure: Throwable) {
                anchor.owner = null
                hostAnchor = null
                hostView.removeOnAttachStateChangeListener(anchor)
                throw failure
            }
        }
    }

    val owns: Boolean get() {
        val host = host.get() ?: return false
        val parent = parent.get() ?: return false
        if (closed || host.parent !== parent) return false
        val layerRef = layer ?: return !glass.isClosed && hostAnchor?.owner === this && host.background !== original
        val layerView = layerRef.get() ?: return false
        return layerView.parent === parent && layerView.owner === this && host.background === marker
    }

    private fun clampInset(total: Int, inset: Float): Int =
        inset.coerceIn(0f, (total - 1).coerceAtLeast(0) / 2f).roundToInt()

    internal fun desiredWidth(host: View? = this.host.get()): Int =
        if (host == null) 0 else desiredWidthForHost(host.width, host.height)

    internal fun desiredHeight(host: View? = this.host.get()): Int =
        if (host == null) 0 else desiredHeightForHost(host.width, host.height)

    internal fun desiredWidthForHost(hostWidth: Int, hostHeight: Int): Int {
        if (closed || hostWidth <= 0 || hostHeight <= 0) return 0
        return (hostWidth - clampInset(hostWidth, lastInsetX) * 2).coerceAtLeast(1)
    }

    internal fun desiredHeightForHost(hostWidth: Int, hostHeight: Int): Int {
        if (closed || hostWidth <= 0 || hostHeight <= 0) return 0
        return (hostHeight - clampInset(hostHeight, lastInsetY) * 2).coerceAtLeast(1)
    }

    fun sync(insetX: Float, insetY: Float) {
        lastInsetX = insetX
        lastInsetY = insetY
        if (closed) return
        val host = host.get() ?: return
        val layer = layer?.get() ?: return
        val width = desiredWidth(host)
        val height = desiredHeight(host)
        val x = if (width > 0) clampInset(host.width, insetX) else 0
        val y = if (height > 0) clampInset(host.height, insetY) else 0
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
            val attachedHost = host.get()
            hostAnchor?.let { anchor ->
                anchor.owner = null
                attachedHost?.removeOnAttachStateChangeListener(anchor)
            }
            hostAnchor = null
            layer?.get()?.let {
                it.owner = null
                if (parent != null && parent.indexOfChild(it) >= 0) parent.removeView(it)
            }
            clipping?.let { lease ->
                if (--lease.users == 0 && parent?.clipChildren == false) parent.clipChildren = lease.original
            }
            val restored = original
            original = null
            attachedHost?.takeIf { it.background === marker }?.background = restored
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

        internal fun removeStaleSiblingLayers(parent: ViewGroup, hostView: View) {
            for (index in parent.childCount - 1 downTo 0) {
                if (index >= parent.childCount) continue
                val child = parent.getChildAt(index) as? PrismView ?: continue
                val childHost = child.hostRef.get()
                if (childHost != null && childHost !== hostView) continue
                val staleOwner = child.owner
                if (staleOwner != null && !staleOwner.closed) {
                    staleOwner.close()
                    if (parent.indexOfChild(child) >= 0) parent.removeView(child)
                } else {
                    child.owner = null
                    parent.removeView(child)
                    val childLease = child.clipping
                    if (childLease.users > 0 && --childLease.users == 0 && !parent.clipChildren) {
                        parent.clipChildren = childLease.original
                    }
                }
            }
        }
    }
}
