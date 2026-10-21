package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeViewportSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGlowView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.lumen.coacervation.engine.host.LumenPrismGlass
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** A draw-only target: the host ComposeView and its native layout/input never move. */
internal class HostComposeChromePainter(
    owner: View, val glass: Boolean, private val touchGlow: Boolean,
    private val appearance: HostChromeAppearance, private val bottom: HostBottomBarFxConfig?, private val api: HostComposeChromeApi,
    private val onApplied: () -> Unit, private val onError: (Throwable) -> Unit
) {
    private class Target(owner: View) : View(owner.context), HostDockLayer {
        private val owner = WeakReference(owner)
        var wake: (() -> Unit)? = null
        override fun isShown() = visibility == VISIBLE && owner.get()?.let { it.isShown && it.alpha > 0f } == true
        // It is an engine lifecycle/coordinate target, never a second visible or interactive layer.
        @SuppressLint("MissingSuperCall") // Compose draws this background exactly once in its local canvas.
        override fun draw(canvas: Canvas) = Unit
        override fun invalidate() { super.invalidate(); wake?.invoke() }
    }
    private val owner = WeakReference(owner)
    private val theme = HostChromeTheme(owner.context)
    private val signal = api.signal()
    private data class Original(val value: Any, val layer: HostChromeLayer)
    private data class Owned(val node: WeakReference<Any>, val modifier: WeakReference<Any>, val original: Any)
    private val modifiers = mutableMapOf<HostChromeLayer, WeakHashMap<Any, WeakReference<Any>>>()
    private val originals = WeakHashMap<Any, Original>()
    private val nativeOwners = mutableMapOf<HostChromeLayer, Owned>()
    private val rootClip = Path()
    private var rootClipWidth = 0f
    private var rootClipHeight = 0f
    private var tabCount = 1
    private var layoutKey: Pair<Int, Int>? = null
    private var x = 0f
    private var y = 0f
    private var width = 0
    private var height = 0
    private var positioned = false
    private var posted = false
    private var activeTouch = false
    private var failed = false
    private var target: Target? = null
    private var overlay: ViewGroup? = null
    private var session: LumenSurfaceSession? = null
    private var binding: LumenSurfaceBinding? = null
    private var prism: LumenPrismGlass? = null
    private var viewport: HostChromeViewportSource? = null
    private var source: HostChromeContentSource? = null
    private var colors = theme.read()
    private var glow: HostGlowView? = null
    private var observer: ViewTreeObserver? = null
    private var version: Long? = null
    private var paused = false
    private val visibleBounds = Rect()
    private val location = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        runCatching {
            val currentOwner: View? = this.owner.get()
            val content = source?.coordinateView
            if (content != null && content !== currentOwner &&
                (!content.isAttachedToWindow || !content.isShown || content.alpha <= 0f || !content.getGlobalVisibleRect(visibleBounds))) {
                release()?.let { throw it }
                wake()
                return@runCatching
            }
            val visible = currentOwner?.let(::actuallyVisible) == true
            target?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            val shouldPause = !visible
            if (paused != shouldPause) {
                paused = shouldPause
                if (shouldPause) session?.pause() else session?.resume()
            }
            if (!visible) return@runCatching
            if (viewport?.sync() == true) session?.notifyPositionChanged()
            val next = viewport?.contentVersion
            if (next != version) { version = next; session?.notifyContentChanged() }
        }.onFailure(::failure)
        true
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = wake()
        override fun onViewDetachedFromWindow(v: View) { release()?.let(::failure); activeTouch = false }
    }

    init { owner.addOnAttachStateChangeListener(attach) }

    fun updateTabs(count: Int) {
        tabCount = count.coerceAtLeast(1)
        val view = owner.get() ?: return
        val key = view.width to tabCount
        if (key != layoutKey) { layoutKey = key; modifiers.values.forEach { it.clear() } }
    }

    fun layout(): Pair<Float, Float>? {
        val config = bottom?.takeIf { glass && (it.compact || it.iconOnly) } ?: return null
        val owner = owner.get() ?: return null
        val density = owner.resources.displayMetrics.density
        val available = owner.width.takeIf { it > 0 } ?: owner.resources.displayMetrics.widthPixels
        return config.heightDp to config.horizontalMarginDp(available / density, tabCount)
    }

    fun modifier(supplied: Any, layer: HostChromeLayer): Any {
        val original = originals[supplied]?.value ?: supplied
        if (failed) return original
        val cache = modifiers.getOrPut(layer) { WeakHashMap() }
        return cache[original]?.get() ?: api.decorate(original, this, layer).also {
            if (it !== original) {
                cache[original] = WeakReference(it); originals[it] = Original(original, layer)
                HostComposeChromeHooks.track(it, this)
            }
        }
    }

    fun nativeApplied(node: Any, modifier: Any) {
        val original = originals[modifier] ?: return
        nativeOwners[original.layer] = Owned(WeakReference(node), WeakReference(modifier), original.value)
    }

    /** Clips the original outer content once; the Row draws the material above that content's backdrop. */
    fun clipRoot(dimensions: () -> Pair<Float, Float>, canvas: () -> Canvas, content: () -> Unit) {
        var target: Canvas? = null
        var saved = -1
        if (!failed) runCatching {
            val (w, h) = dimensions()
            if (w.isFinite() && h.isFinite() && w > 0 && h > 0) {
                if (rootClipWidth != w || rootClipHeight != h) {
                    rootClipWidth = w; rootClipHeight = h
                    rootClip.reset(); rootClip.addRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, Path.Direction.CW)
                }
                target = canvas()
                saved = requireNotNull(target).save()
                requireNotNull(target).clipPath(rootClip)
            }
        }.onFailure { error ->
            if (saved >= 0) runCatching { target?.restoreToCount(saved) }
            saved = -1
            failure(error)
        }
        try { content() } finally {
            if (saved >= 0) runCatching { target?.restoreToCount(saved) }.onFailure(::failure)
        }
    }

    fun position(x: Float, y: Float) {
        if (positioned && this.x == x && this.y == y) return
        runCatching {
            positioned = true; this.x = x; this.y = y
            syncTarget(); session?.notifyPositionChanged(); prism?.notifyPositionChanged(); wake()
        }.onFailure(::failure)
    }

    fun draw(dimensions: () -> Pair<Float, Float>, canvas: () -> Canvas) {
        runCatching {
            signal.read()
            val owner = owner.get() ?: return
            if (failed || !owner.isAttachedToWindow || !owner.isShown || !positioned) return
            val (w, h) = dimensions()
            if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) return
            val nextW = w.roundToInt(); val nextH = h.roundToInt()
            if (width != nextW || height != nextH) {
                width = nextW; height = nextH
                release()?.let { throw it }
            }
            if (!actuallyVisible(owner)) return
            val nextColors = theme.read()
            if (colors != nextColors) {
                colors = nextColors; source?.windowBaseColor = colors.palette().background
                session?.updatePalette(colors.palette()); prism?.updatePalette(colors.palette())
                binding?.update(HostSurfaceStyle.floating(colors.dark, radiusDp(owner), appearance.compatible(Build.VERSION.SDK_INT)))
                glow?.recolor(colors.accent)
            }
            if (glass && target == null) build(owner)
            val destination = canvas()
            var drawn = false
            if (glass) {
                syncTarget()
                val target = target ?: return
                val background = target.background ?: return
                background.setBounds(0, 0, width, height)
                background.draw(destination)
                drawn = true
            }
            if (touchGlow && activeTouch) {
                glow?.draw(destination)
                drawn = true
            }
            if (drawn) onApplied()
        }.onFailure(::failure)
    }

    /** Observation only; dispatchTouchEvent and all native pointer/click handlers retain control. */
    fun touch(view: View, event: MotionEvent) {
        runCatching { touchInternal(view, event) }.onFailure(::failure)
    }

    private fun touchInternal(view: View, event: MotionEvent) {
        if (!touchGlow || width <= 0 || height <= 0 || !positioned) return
        view.getLocationInWindow(location)
        val localX = event.x + location[0] - x
        val localY = event.y + location[1] - y
        val inside = localX in 0f..width.toFloat() && localY in 0f..height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (inside) activeTouch = true else return
            MotionEvent.ACTION_MOVE -> if (!activeTouch) return
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (!activeTouch) return; activeTouch = false; glow?.resetGestureState(); wake(); return }
            else -> return
        }
        val owner = owner.get() ?: return
        val glow = glow ?: HostGlowView(owner.context, colors.accent, owner.resources.displayMetrics.density, 0f).also {
            this.glow = it; it.layout(0, 0, width, height)
        }
        glow.layout(0, 0, width, height)
        glow.updateGesture(1f, 0f, 0f, localX, localY, width, height)
        wake()
    }

    private fun actuallyVisible(owner: View): Boolean {
        if (!owner.isAttachedToWindow || !owner.isShown || owner.alpha <= 0f || !owner.getLocalVisibleRect(visibleBounds)) return false
        owner.getLocationInWindow(location)
        visibleBounds.offset(location[0], location[1])
        return x < visibleBounds.right && x + width > visibleBounds.left &&
            y < visibleBounds.bottom && y + height > visibleBounds.top
    }

    private fun radiusDp(owner: View) = (height / owner.resources.displayMetrics.density / 2f).coerceIn(0f, 128f)

    private fun build(owner: View) {
        val root = owner.rootView as? ViewGroup ?: return
        val target = Target(owner).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
        this.target = target; overlay = root
        try {
            root.overlay.add(target)
            syncTarget()
            val content = independentContent(owner)
            source = HostChromeContentSource(content ?: owner, colors.palette().background, includeViewContent = content != null)
            viewport = HostChromeViewportSource(requireNotNull(source), compositeView = appearance.material == HostGlassMaterial.PRISM) {
                it(target, 0f, 0f, 72f * owner.resources.displayMetrics.density)
            }.also { it.sync() }
            val active = LumenSurfaceSession(owner.context, colors.palette(), LumenSurfaceSessionOptions(pauseWhenWindowUnfocused = false))
            session = active
            if (appearance.material == HostGlassMaterial.PRISM) {
                prism = LumenPrismGlass(target, colors.palette(), LumenPrismOptions(cornerRadiusDp = radiusDp(owner)), active, viewport?.coordinateView)
            } else {
                binding = active.bindSource(target, HostSurfaceStyle.floating(colors.dark, radiusDp(owner), appearance.compatible(Build.VERSION.SDK_INT)), viewport)
            }
            target.wake = ::wake
            observer = owner.viewTreeObserver.takeIf { it.isAlive }?.also { it.addOnPreDrawListener(preDraw) }
        } catch (failure: Throwable) { release()?.takeUnless { it === failure }?.let(failure::addSuppressed); throw failure }
    }

    private fun syncTarget() {
        val target = target ?: return
        val root = overlay ?: return
        root.getLocationInWindow(location)
        val left = (x - location[0]).roundToInt(); val top = (y - location[1]).roundToInt()
        if (target.width != width || target.height != height)
            target.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        if (target.left != left || target.top != top || target.width != width || target.height != height)
            target.layout(left, top, left + width, top + height)
    }

    /** An independently drawn feed can be sampled; a full-screen Compose ancestor never can. */
    private fun independentContent(owner: View): ViewGroup? {
        val root = owner.rootView as? ViewGroup ?: return null
        val bounds = Rect()
        val halo = (72f * owner.resources.displayMetrics.density).roundToInt()
        var seen = 0
        fun visit(group: ViewGroup, depth: Int): ViewGroup? {
            if (depth > 24 || ++seen > 2048 || !group.isShown || group is HostDockLayer) return null
            val name = group.javaClass.name
            if (name.contains("biliplayer", true) || name.contains("player", true)) return null
            val recycler = generateSequence(group.javaClass as Class<*>?) { it.superclass }.take(12)
                .any { it.name == "androidx.recyclerview.widget.RecyclerView" }
            if (recycler && group.getGlobalVisibleRect(bounds)) {
                group.getLocationInWindow(location)
                if (location[0] < x + width + halo && location[0] + group.width > x - halo &&
                    location[1] < y + height + halo && location[1] + group.height > y - halo) return group
            }
            for (i in 0 until group.childCount) (group.getChildAt(i) as? ViewGroup)?.let { visit(it, depth + 1)?.let { found -> return found } }
            return null
        }
        return visit(root, 0)
    }

    private fun wake() {
        if (posted) return
        val owner = owner.get()?.takeIf { it.isAttachedToWindow } ?: return
        posted = true
        owner.postOnAnimation {
            posted = false
            if (owner.isAttachedToWindow) { runCatching { signal.invalidate(); owner.invalidate() }.onFailure(::failure) }
        }
    }

    internal fun failure(error: Throwable) {
        if (failed) return
        failed = true
        release()?.takeUnless { it === error }?.let { runCatching { error.addSuppressed(it) } }
        val owned = nativeOwners.values.mapNotNull { entry ->
            val node = entry.node.get(); val modifier = entry.modifier.get()
            if (node != null && modifier != null) Triple(node, modifier, entry.original) else null
        }
        // Both outer geometry and foreground Row must restore; each keeps its own identity lease.
        if (owned.isNotEmpty()) runCatching {
            owner.get()?.post { owned.forEach { (node, expected, original) -> runCatching { api.restore(node, expected, original) } } }
        }
        runCatching { onError(error) }
    }

    /** Clear ownership first, then release every resource even when one close operation fails. */
    private fun release(): Throwable? {
        val oldObserver = observer; observer = null
        val oldTarget = target; target = null
        val oldOverlay = overlay; overlay = null
        val oldPrism = prism; prism = null
        val oldBinding = binding; binding = null
        val oldSession = session; session = null
        val oldViewport = viewport; viewport = null
        source = null; version = null; paused = false
        oldTarget?.wake = null
        var failure: Throwable? = null
        fun close(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) runCatching { previous.addSuppressed(error) }
            }
        }
        close { oldObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw) }
        close { oldPrism?.close() }
        close { oldBinding?.close() }
        close { oldSession?.close() }
        close { oldViewport?.close() }
        close { oldTarget?.let { oldOverlay?.overlay?.remove(it) } }
        close { glow?.resetGestureState() }
        return failure
    }
}
