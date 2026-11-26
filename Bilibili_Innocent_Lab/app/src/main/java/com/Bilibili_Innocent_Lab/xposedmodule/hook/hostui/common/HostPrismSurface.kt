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
    private class PrismView(context: android.content.Context, val clipping: ClipLease) : View(context), HostDockLayer
    private val original = hostView.background
    private val marker = Color.TRANSPARENT.toDrawable()
    // Surface records live in a WeakHashMap; their values must not retain the View keys or parents.
    private val host = WeakReference(hostView)
    private val parent = WeakReference(hostView.parent as ViewGroup)
    private val clipping: ClipLease
    private val layer: WeakReference<PrismView>
    private val glass: LumenPrismGlass
    private var closed = false

    init {
        val parent = requireNotNull(parent.get())
        // Share clipping ownership with other Prism siblings without a process-level View cache.
        clipping = (0 until parent.childCount).asSequence()
            .mapNotNull { parent.getChildAt(it) as? PrismView }.firstOrNull()?.clipping ?: ClipLease(parent.clipChildren)
        val layer = PrismView(hostView.context, clipping)
        this.layer = WeakReference(layer)
        var leased = false
        glass = try {
            // Let the host parent create its own LayoutParams (including host ConstraintLayout).
            parent.addView(layer, parent.indexOfChild(hostView))
            clipping.users++
            leased = true
            parent.clipChildren = false
            layer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            hostView.background = marker
            LumenPrismGlass(layer, palette, LumenPrismOptions(), session, source)
        } catch (failure: Throwable) {
            if (layer.parent === parent) parent.removeView(layer)
            if (leased && --clipping.users == 0 && !parent.clipChildren) parent.clipChildren = clipping.original
            if (hostView.background === marker) hostView.background = original
            throw failure
        }
    }

    val owns: Boolean get() {
        val host = host.get() ?: return false
        val parent = parent.get() ?: return false
        val layer = layer.get() ?: return false
        return !closed && host.parent === parent && layer.parent === parent && host.background === marker
    }

    fun sync(insetX: Float, insetY: Float) {
        if (closed) return
        val host = host.get() ?: return
        val layer = layer.get() ?: return
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
            layer.get()?.takeIf { it.parent === parent }?.let { parent?.removeView(it) }
            if (--clipping.users == 0 && parent?.clipChildren == false) parent.clipChildren = clipping.original
            host.get()?.takeIf { it.background === marker }?.background = original
        }
    }
}
