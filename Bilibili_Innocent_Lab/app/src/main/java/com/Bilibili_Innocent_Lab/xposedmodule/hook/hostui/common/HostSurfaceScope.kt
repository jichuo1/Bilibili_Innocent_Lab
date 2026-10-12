package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import android.os.Build
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceDiagnostics
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.host.LumenSurfaceLegibilityController
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 一个注入区域的局部会话（适配标准 §16）。不读取引擎偏好、不接管 Activity 或窗口。
 * detach 关闭全部资源，重新 attach 重建绑定；主题变化只更新配色。
 */
internal class HostSurfaceScope(anchor: View, colors: HostChromeColors,
    private val samplingBackend: LumenSurfaceBackend = LumenSurfaceBackend.AUTO,
    private val appearance: HostChromeAppearance = HostChromeAppearance()) {
    private class Surface(var options: LumenSurfaceOptions) {
        var binding: LumenSurfaceBinding? = null
        var background: Drawable? = null
        var insetX = 0f
        var insetY = 0f
        var shapeWidth = -1
        var shapeHeight = -1
        var appliedX = Float.NaN
        var appliedY = Float.NaN
    }

    private val anchor = WeakReference(anchor)
    private val surfaces = WeakHashMap<View, Surface>()
    private var palette: LumenPalette = colors.palette()
    private var dark = colors.dark
    private var session: LumenSurfaceSession? = null
    private var content: View? = null
    private var contentSource: HostChromeContentSource? = null
    private var legibility: LumenSurfaceLegibilityController? = null
    private var closed = false
    private var focusObserver: ViewTreeObserver? = null
    private var backdropVersion: Long? = null
    private val backdropPreDraw = ViewTreeObserver.OnPreDrawListener {
        val version = contentSource?.contentVersion
        if (version != backdropVersion) {
            backdropVersion = version
            // A parent background changes outside the content View's invalidation chain. Wake
            // the hosts too, so their cached display lists adopt the updated scene this frame.
            session?.notifyContentChanged()
        }
        true
    }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        // Memory pressure can release graphics while backgrounded; restore them on return.
        if (focused && !closed) session?.resume()
    }

    fun attach(surface: View, explicitContent: View? = null) {
        if (closed) return
        content = explicitContent ?: HostBackdropLocator.find(surface)
        contentSource = content?.let { HostChromeContentSource(it, palette.background) }
        if (session == null) session = LumenSurfaceSession(surface.context, palette,
            // Recents snapshots are drawn after focus is lost. Keep the glass in that frame.
            LumenSurfaceSessionOptions(pauseWhenWindowUnfocused = false))
        observeFocus(surface)
        resetLegibility()
        surfaces.forEach { (view, spec) -> bind(view, spec) }
    }

    fun surface(view: View, options: LumenSurfaceOptions) {
        if (closed) return
        val existing = surfaces[view]
        if (existing == null) {
            val spec = Surface(options)
            surfaces[view] = spec
            if (session == null) anchor.get()?.let { attach(it) }
            if (spec.binding?.isBound != true) bind(view, spec)
        } else {
            existing.options = options
            if (existing.binding?.isBound == true && view.background === existing.background) {
                existing.binding?.update(effectiveOptions(options))
                existing.binding?.let { legibility?.bind(view, it, effectiveOptions(options)) }
            } else bind(view, existing)
        }
    }

    fun floating(view: View, dark: Boolean, radiusDp: Float) =
        surface(view, HostSurfaceStyle.floating(dark, radiusDp, appearance.compatible(Build.VERSION.SDK_INT)))

    fun owns(view: View): Boolean = surfaces[view]?.let {
        it.binding?.isBound == true && view.background === it.background
    } == true

    fun updatePalette(colors: HostChromeColors) {
        val next = colors.palette()
        if (palette == next) return
        palette = next
        contentSource?.windowBaseColor = next.background
        dark = colors.dark
        session?.updatePalette(next)
        resetLegibility()
        surfaces.forEach { (view, spec) -> spec.binding?.let { legibility?.bind(view, it, effectiveOptions(spec.options)) } }
    }

    fun revalidate(explicitContent: View? = null) {
        if (closed) return
        val anchor = anchor.get() ?: return
        val next = explicitContent ?: content?.takeIf { it.isAttachedToWindow && it.parent != null }
            ?: HostBackdropLocator.find(anchor)
        if (session == null) { attach(anchor, next); return }
        val changed = content !== next
        content = next
        if (changed) contentSource = next?.let { HostChromeContentSource(it, palette.background) }
        if (changed) resetLegibility()
        surfaces.forEach { (view, spec) ->
            if (changed || spec.binding?.isBound != true || view.background !== spec.background) bind(view, spec)
        }
    }

    /** 几何只通过公开输入更新，不在宿主 Canvas 中代画引擎背景。 */
    fun shape(view: View, insetX: Float, insetY: Float) {
        val spec = surfaces[view] ?: return
        spec.insetX = insetX
        spec.insetY = insetY
        applyShape(view, spec)
    }

    private fun applyShape(view: View, spec: Surface) {
        if (view.width <= 0 || view.height <= 0) return
        val x = spec.insetX.coerceIn(0f, (view.width - 1f) / 2f)
        val y = spec.insetY.coerceIn(0f, (view.height - 1f) / 2f)
        if (spec.shapeWidth == view.width && spec.shapeHeight == view.height && spec.appliedX == x && spec.appliedY == y) return
        spec.shapeWidth = view.width
        spec.shapeHeight = view.height
        spec.appliedX = x
        spec.appliedY = y
        if (x == 0f && y == 0f) spec.binding?.clearCustomShapes()
        else spec.binding?.setShapesPixels(x, y, view.width - 2 * x, view.height - 2 * y,
            0f, 0f, 0f, 0f, false)
    }

    private fun bind(view: View, spec: Surface) {
        val active = session ?: return
        spec.binding?.close()
        spec.binding = active.bindSource(view, effectiveOptions(spec.options), contentSource.takeIf { spec.options.sampling.enabled })
        spec.background = view.background
        spec.binding?.let { legibility?.bind(view, it, effectiveOptions(spec.options)) }
        spec.shapeWidth = -1
        applyShape(view, spec)
    }

    fun onVisualMovement() = session?.notifyPositionChanged() ?: Unit

    private fun effectiveOptions(options: LumenSurfaceOptions): LumenSurfaceOptions {
        val backend = if (samplingBackend != LumenSurfaceBackend.AUTO) samplingBackend
            else if (appearance.compatible(Build.VERSION.SDK_INT).effectiveRenderer == HostGlassRenderer.SOFTWARE)
                LumenSurfaceBackend.SOFTWARE else options.sampling.backend
        return if (backend == options.sampling.backend) options
            else options.copy(sampling = options.sampling.copy(backend = backend))
    }

    fun diagnostics(): LumenSurfaceDiagnostics? = session?.diagnostics()

    private fun observeFocus(view: View) {
        val observer = view.viewTreeObserver
        if (focusObserver === observer && observer.isAlive) return
        focusObserver?.takeIf { it.isAlive }?.let {
            it.removeOnWindowFocusChangeListener(focusListener)
            it.removeOnPreDrawListener(backdropPreDraw)
        }
        if (observer.isAlive) {
            observer.addOnWindowFocusChangeListener(focusListener)
            observer.addOnPreDrawListener(backdropPreDraw)
            focusObserver = observer
        }
    }

    private fun resetLegibility() {
        legibility?.close()
        legibility = content?.takeIf { dark }?.let {
            LumenSurfaceLegibilityController(it, palette.surface, 0xFF9EA3AB.toInt())
        }
    }

    fun detach() {
        focusObserver?.takeIf { it.isAlive }?.let {
            it.removeOnWindowFocusChangeListener(focusListener)
            it.removeOnPreDrawListener(backdropPreDraw)
        }
        anchor.get()?.viewTreeObserver?.takeIf { it.isAlive && it !== focusObserver }
            ?.let {
                it.removeOnWindowFocusChangeListener(focusListener)
                it.removeOnPreDrawListener(backdropPreDraw)
            }
        focusObserver = null
        backdropVersion = null
        legibility?.close()
        legibility = null
        session?.close()
        session = null
        content = null
        contentSource = null
        surfaces.values.forEach { it.binding = null; it.background = null }
    }

    fun close() {
        if (closed) return
        detach()
        closed = true
        surfaces.clear()
    }
}
