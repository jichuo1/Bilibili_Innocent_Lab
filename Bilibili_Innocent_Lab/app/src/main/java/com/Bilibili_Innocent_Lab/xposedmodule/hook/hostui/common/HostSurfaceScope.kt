package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.os.Build
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
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
        var prism = false
        var prismSurface: HostPrismSurface? = null
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
    private var garbAccent = colors.garbAccent
    private var session: LumenSurfaceSession? = null
    private var content: View? = null
    private var contentSource: HostChromeContentSource? = null
    private var viewportSource: HostChromeViewportSource? = null
    private var legibility: LumenSurfaceLegibilityController? = null
    private var closed = false
    private var focusObserver: ViewTreeObserver? = null
    private var backdropVersion: Long? = null
    private val backdropPreDraw = ViewTreeObserver.OnPreDrawListener { HostThreadGuard.call("host.surface.backdrop.predraw", true) {
        surfaces.forEach { (_, spec) -> spec.prismSurface?.sync(spec.insetX, spec.insetY) }
        if (viewportSource?.sync() == true) session?.notifyPositionChanged()
        // Snapshot child invalidations before the software legibility probe can draw the
        // content and consume their dirty flags later in this same pre-draw dispatch.
        val version = (viewportSource ?: contentSource)?.contentVersion
        if (version != backdropVersion) {
            backdropVersion = version
            // Ancestor backgrounds and retained child updates can bypass the owner's dirty
            // flag. Wake the hosts too so both backends adopt the updated scene this frame.
            session?.notifyContentChanged()
        }
        true
    } }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        // Memory pressure can release graphics while backgrounded; restore them on return.
        HostThreadGuard.run("host.surface.focus") { if (focused && !closed) session?.resume() }
    }

    fun attach(surface: View, explicitContent: View? = null) {
        if (closed) return
        val previous = viewportSource
        content = explicitContent ?: HostBackdropLocator.find(surface)
        contentSource = content?.let { HostChromeContentSource(it, palette.background) }
        viewportSource = createViewportSource()
        if (session == null) session = LumenSurfaceSession(surface.context, palette,
            // Recents snapshots are drawn after focus is lost. Keep the glass in that frame.
            LumenSurfaceSessionOptions(pauseWhenWindowUnfocused = false))
        observeFocus(surface)
        resetLegibility()
        surfaces.forEach { (view, spec) -> bind(view, spec) }
        previous?.close()
    }

    fun surface(view: View, options: LumenSurfaceOptions) = surface(view, options, prism = false)

    private fun surface(view: View, options: LumenSurfaceOptions, prism: Boolean) {
        if (closed) return
        val existing = surfaces[view]
        if (existing == null) {
            val spec = Surface(options)
            spec.prism = prism
            surfaces[view] = spec
            if (session == null) anchor.get()?.let { attach(it) }
            if (!owns(view)) bind(view, spec)
        } else {
            existing.options = options
            if (existing.prism && existing.prismSurface?.owns == true) return
            if (existing.binding?.isBound == true && view.background === existing.background) {
                existing.binding?.update(effectiveOptions(options))
                existing.binding?.let { legibility?.bind(view, it, effectiveOptions(options)) }
            } else bind(view, existing)
        }
    }

    fun floating(view: View, dark: Boolean, radiusDp: Float) =
        surface(view, HostSurfaceStyle.floating(dark, radiusDp, appearance.compatible(Build.VERSION.SDK_INT)),
            prism = appearance.material == HostGlassMaterial.PRISM)

    fun owns(view: View): Boolean = surfaces[view]?.let {
        it.prismSurface?.owns == true || (it.binding?.isBound == true && view.background === it.background)
    } == true

    fun updatePalette(colors: HostChromeColors) {
        val next = colors.palette()
        if (palette == next) return
        palette = next
        contentSource?.windowBaseColor = next.background
        dark = colors.dark
        garbAccent = colors.garbAccent
        session?.updatePalette(next)
        surfaces.values.forEach { spec -> spec.binding?.update(effectiveOptions(spec.options)) }
        surfaces.values.forEach { spec -> spec.prismSurface?.updatePalette(next) }
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
        val previous = viewportSource
        if (changed) {
            contentSource = next?.let { HostChromeContentSource(it, palette.background) }
            viewportSource = createViewportSource()
        }
        if (changed) resetLegibility()
        surfaces.forEach { (view, spec) ->
            if (changed || !owns(view)) bind(view, spec)
        }
        if (changed) previous?.close()
    }

    /** 几何只通过公开输入更新，不在宿主 Canvas 中代画引擎背景。 */
    fun shape(view: View, insetX: Float, insetY: Float) {
        val spec = surfaces[view] ?: return
        spec.insetX = insetX
        spec.insetY = insetY
        spec.prismSurface?.sync(insetX, insetY)
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
        spec.binding = null
        spec.prismSurface?.close()
        spec.prismSurface = null
        viewportSource?.sync()
        if (spec.prism) {
            val source = viewportSource?.takeIf { it.availability == com.lumen.coacervation.engine.host.LumenSourceAvailability.READY }
                ?.coordinateView
            if (view.parent !is ViewGroup) return
            spec.prismSurface = HostPrismSurface(view, palette, active, source).also { it.sync(spec.insetX, spec.insetY) }
            return
        }
        spec.binding = active.bindSource(view, effectiveOptions(spec.options),
            (viewportSource ?: contentSource).takeIf { spec.options.sampling.enabled })
        spec.background = view.background
        spec.binding?.let { legibility?.bind(view, it, effectiveOptions(spec.options)) }
        spec.shapeWidth = -1
        applyShape(view, spec)
    }

    fun onVisualMovement() = session?.notifyPositionChanged() ?: Unit

    private fun effectiveOptions(options: LumenSurfaceOptions): LumenSurfaceOptions {
        val styled = garbAccent?.let { accent ->
            val rim = HostGarbColors.blend(accent, 0xFFFFFFFF.toInt(), .65f) and 0xFFFFFF
            options.copy(edgeTopColor = ((if (dark) 80 else 150) shl 24) or rim,
                edgeBottomColor = (24 shl 24) or rim)
        } ?: options
        val backend = if (samplingBackend != LumenSurfaceBackend.AUTO) samplingBackend
            else if (appearance.compatible(Build.VERSION.SDK_INT).effectiveRenderer == HostGlassRenderer.SOFTWARE)
                LumenSurfaceBackend.SOFTWARE else styled.sampling.backend
        return if (backend == styled.sampling.backend) styled
            else styled.copy(sampling = styled.sampling.copy(backend = backend))
    }

    fun diagnostics(): LumenSurfaceDiagnostics? = session?.diagnostics()

    internal fun samplingPerformance() = session?.performanceDiagnostics()

    internal fun prismDiagnostics(view: View) = surfaces[view]?.prismSurface?.diagnostics()

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
        legibility = (viewportSource?.coordinateView ?: content)?.takeIf { dark && appearance.material != HostGlassMaterial.PRISM }?.let {
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
        surfaces.values.forEach { it.prismSurface?.close(); it.prismSurface = null }
        session?.close()
        session = null
        viewportSource?.close(); viewportSource = null
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

    private fun createViewportSource(): HostChromeViewportSource? {
        val source = contentSource ?: return null
        if (source.coordinateView !is ViewGroup) return null
        return HostChromeViewportSource(source, compositeView = appearance.material == HostGlassMaterial.PRISM) { consume ->
            for ((view, spec) in surfaces) {
                val sampling = effectiveOptions(spec.options).sampling
                if (!spec.options.sampling.enabled) continue
                val blur = if (spec.prism) 8f else maxOf(sampling.blurRadiusDp, sampling.softwareBlurRadiusDp ?: sampling.blurRadiusDp)
                val padding = maxOf(blur * 4f, 20f * sampling.refractionStrength) * view.resources.displayMetrics.density + 4f
                consume(view, spec.insetX, spec.insetY, padding)
            }
        }.also { it.sync() }
    }
}
