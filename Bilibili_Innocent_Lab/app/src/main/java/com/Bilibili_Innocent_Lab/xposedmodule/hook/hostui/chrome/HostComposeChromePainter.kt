package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeViewportSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGlowView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostViewVisibility
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.lumen.coacervation.engine.host.LumenPrismGlass
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenPrismShadowOptions
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
        override fun isShown() = visibility == VISIBLE && owner.get()?.let(HostViewVisibility::shown) == true
        // It is an engine lifecycle/coordinate target, never a second visible or interactive layer.
        @SuppressLint("MissingSuperCall") // Compose draws this background exactly once in its local canvas.
        override fun draw(canvas: Canvas) = Unit
        override fun invalidate() { super.invalidate(); wake?.invoke() }
    }
    private val owner = WeakReference(owner)
    private val theme = HostChromeTheme(owner.context)
    private val signal = api.signal()
    private val compositionSignal by lazy(LazyThreadSafetyMode.NONE) { api.signal() }
    private var compositionObserved = false
    private var compositionDirty = false
    private data class Original(val value: Any, val layer: HostChromeLayer)
    private val modifiers = mutableMapOf<HostChromeLayer, WeakHashMap<Any, WeakReference<Any>>>()
    private val originals = WeakHashMap<Any, Original>()
    private val nativeOwners = HostChromeModifierOwnership()
    private val rootClip = Path()
    private var rootClipWidth = 0f
    private var rootClipHeight = 0f
    private var tabCount = 1
    private var layoutWidth = -1
    private var layoutTabs = -1
    private var x = 0f
    private var y = 0f
    private var rootX = 0f
    private var rootY = 0f
    private var rootRelative = false
    private var width = 0
    private var height = 0
    private var positioned = false
    private var posted = false
    private var activeTouch = false
    private var failed = false
    private var replacementReady = false
    private var capsule: HostChromeTopCapsule? = null
    private val underlay = Paint()
    private var headerId = 0
    private val float = if (bottom == null) HostKhomeTopFloat() else null
    private var staleFrames = 0
    private val header = HostChromeHeaderBackdrop(object : HostChromeHeaderBackdrop.Ops<View, ViewGroup> {
        override fun alpha(header: View) = header.alpha
        override fun setAlpha(header: View, alpha: Float) { header.alpha = alpha }
        override fun background(root: ViewGroup): Any? = root.background
        override fun setBackground(root: ViewGroup, value: Any?) { root.background = value as? ColorDrawable }
        override fun newFill(color: Int): Any = ColorDrawable(color)
        override fun recolor(fill: Any, color: Int) { (fill as ColorDrawable).let { if (it.color != color) it.color = color } }
    })
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
    private val backdropRecovery = HostChromeBackdropRecovery<ViewGroup>()
    private var probeAt: Long? = null
    private val visibleBounds = Rect()
    private val sourceBounds = Rect()
    private val recyclerTypes = HostChromeRecyclerTypes()
    private val location = IntArray(2)
    private val sourceUsable: (ViewGroup) -> Boolean = {
        it.isAttachedToWindow && HostViewVisibility.visible(it, visibleBounds)
    }
    private val locateSource: () -> ViewGroup? = { this.owner.get()?.let(::independentContent) }
    /** 守卫要看见失败，计数和日志才有效：先按 [failure] 降级，再把异常交还守卫。 */
    private inline fun guarded(key: String, block: () -> Unit) = HostThreadGuard.run(key) {
        try { block() } catch (error: Throwable) { failure(error); throw error }
    }
    private val frameWake = Runnable {
        posted = false
        guarded("host.chrome.wake") {
            this.owner.get()?.takeIf { it.isAttachedToWindow }?.let {
                if (compositionDirty) { compositionDirty = false; compositionSignal.invalidate() }
                signal.invalidate(); it.invalidate()
            }
        }
    }
    private val backdropProbe = Runnable {
        probeAt = null
        guarded("host.chrome.backdrop") { wake() }
    }
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener {
        float?.markDirty()
        guarded("host.chrome.layout") { requestBackdropRefresh() }
    }
    private val scrollChanged = ViewTreeObserver.OnScrollChangedListener {
        guarded("host.chrome.scroll") { requestBackdropRefresh() }
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        var proceed = true
        runCatching {
            val currentOwner = this.owner.get() ?: return@runCatching
            trackOwner(currentOwner)
            // A frame drawn before the relayout would show the feed at its old place; bound the retries.
            if (syncTop(currentOwner) && staleFrames < 2) { staleFrames++; proceed = false } else staleFrames = 0
            val visible = actuallyVisible(currentOwner)
            target?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            val shouldPause = !visible
            if (paused != shouldPause) {
                paused = shouldPause
                if (shouldPause) session?.pause() else session?.resume()
            }
            if (!visible) {
                cancelBackdropProbe()
                return@runCatching
            }
            val nextContent = selectContent()
            val currentContent = source?.coordinateView?.takeUnless { it === currentOwner }
            if (target != null && nextContent !== currentContent) {
                release()?.let { throw it }
                build(currentOwner, nextContent)
                wake()
            }
            scheduleBackdropProbe(currentOwner)
            if (viewport?.sync() == true) session?.notifyPositionChanged()
            val next = viewport?.contentVersion
            if (next != version) { version = next; session?.notifyContentChanged() }
        }.onFailure(::failure)
        proceed
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = guarded("host.chrome.attach") { wake() }
        override fun onViewDetachedFromWindow(v: View) = guarded("host.chrome.detach") {
            backdropRecovery.clear(); activeTouch = false
            release(dispose = true)?.let { throw it }
        }
    }

    init { owner.addOnAttachStateChangeListener(attach) }

    internal val backdropActive: Boolean
        get() {
            if (!glass || failed) return false
            // Each image/draw layer observes the same host Snapshot state as the surface.
            // Plain booleans alone leave an already recorded transparent layer cached on failure.
            signal.read()
            return replacementReady
        }

    internal val omitBackdropImage: Boolean
        get() {
            if (!glass || failed) return false
            compositionObserved = true
            compositionSignal.read()
            return replacementReady
        }

    /** No hierarchy search in a Hook callback; the next visible pre-draw performs the bounded probe. */
    fun requestBackdropRefresh() {
        if (failed) return
        if (backdropRecovery.request(SystemClock.uptimeMillis())) {
            owner.get()?.takeIf { it.isAttachedToWindow }?.let(::scheduleBackdropProbe)
        }
    }

    private fun selectContent(): ViewGroup? = backdropRecovery.select(SystemClock.uptimeMillis(), sourceUsable, locateSource)

    private fun cancelBackdropProbe() {
        if (probeAt == null) return
        owner.get()?.removeCallbacks(backdropProbe)
        probeAt = null
    }

    private fun scheduleBackdropProbe(owner: View) {
        val now = SystemClock.uptimeMillis()
        val delay = backdropRecovery.retryAfter(now) ?: run { cancelBackdropProbe(); return }
        val due = now + delay
        if (probeAt?.let { it <= due } == true) return
        cancelBackdropProbe()
        if (owner.postDelayed(backdropProbe, delay)) probeAt = due
    }

    fun updateTabs(count: Int) {
        tabCount = count.coerceAtLeast(1)
        val view = owner.get() ?: return
        if (view.width != layoutWidth || tabCount != layoutTabs) {
            layoutWidth = view.width; layoutTabs = tabCount
            modifiers.values.forEach { it.clear() }
        }
    }

    fun layout(): Pair<Float, Float>? {
        val config = bottom?.takeIf { glass && (it.compact || it.iconOnly) } ?: return null
        val owner = owner.get() ?: return null
        val density = owner.resources.displayMetrics.density
        val available = owner.width.takeIf { it > 0 } ?: owner.resources.displayMetrics.widthPixels
        return config.heightDp to config.horizontalMarginDp(available / density, tabCount)
    }

    fun modifier(supplied: Any, layer: HostChromeLayer): Any {
        // Register in the real row's own restart scope, after native defaults and dirty flags.
        // Sampling/position frames use a separate draw signal and never recompose this row.
        if (glass && layer == HostChromeLayer.SURFACE) {
            compositionObserved = true
            compositionSignal.read()
        }
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
        nativeOwners.record(node, modifier, original.value)
    }

    /** Clips the original outer content once; the Row draws the material above that content's backdrop. */
    fun clipRoot(dimensions: () -> Long, canvas: () -> Canvas, content: () -> Unit) {
        var target: Canvas? = null
        var saved = -1
        if (!failed) runCatching {
            val packed = dimensions()
            val w = Float.fromBits((packed ushr 32).toInt()); val h = Float.fromBits(packed.toInt())
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

    fun position(x: Float, y: Float) = moveTo(x, y)

    /** [x]/[y] are relative to the owner AndroidComposeView; [trackOwner] adds its live window location. */
    fun rootPosition(x: Float, y: Float) {
        rootRelative = true; rootX = x; rootY = y
        owner.get()?.let(::trackOwner)
    }

    /**
     * AppBarLayout offsets and View translations move the owner without a Compose layout pass, so
     * the positioned callback stays silent. A stale position makes the glass sample the feed below
     * the row and lets the visibility test fail, which skips the base under the suppressed native row.
     */
    private fun trackOwner(owner: View) {
        if (!rootRelative) return
        owner.getLocationInWindow(location)
        moveTo(location[0] + rootX, location[1] + rootY)
    }

    private fun moveTo(x: Float, y: Float) {
        if (positioned && this.x == x && this.y == y) return
        runCatching {
            positioned = true; this.x = x; this.y = y
            syncTarget(); session?.notifyPositionChanged(); prism?.notifyPositionChanged(); wake()
        }.onFailure(::failure)
    }

    fun draw(dimensions: () -> Long, canvas: () -> Canvas) {
        runCatching {
            signal.read()
            val owner = owner.get() ?: return
            if (failed || !owner.isAttachedToWindow || !owner.isShown || !positioned) return
            val packed = dimensions()
            val w = Float.fromBits((packed ushr 32).toInt()); val h = Float.fromBits(packed.toInt())
            if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) return
            val nextW = w.roundToInt(); val nextH = h.roundToInt()
            if (width != nextW || height != nextH) {
                width = nextW; height = nextH
                release()?.let { throw it }
            }
            trackOwner(owner)
            if (!actuallyVisible(owner)) {
                // Native layers may still be recorded as suppressed; never expose the Window there.
                if (replacementReady) paintBase(owner, canvas(), w, h)
                return
            }
            val nextColors = theme.read()
            if (colors != nextColors) {
                colors = nextColors; source?.windowBaseColor = sampleBase()
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
                paintBase(owner, destination, w, h)
                val inset = capsule
                if (inset == null) background.setBounds(0, 0, width, height)
                else {
                    val dx = inset.insetX.roundToInt(); val dy = inset.insetY.roundToInt()
                    background.setBounds(dx, dy, width - dx, height - dy)
                }
                background.draw(destination)
                drawn = true
                if (!replacementReady) {
                    replacementReady = true
                    if (compositionObserved) { compositionDirty = true; wake() }
                }
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
        if (!owner.isAttachedToWindow || !HostViewVisibility.shown(owner) || !owner.getLocalVisibleRect(visibleBounds)) return false
        owner.getLocationInWindow(location)
        visibleBounds.offset(location[0], location[1])
        return x < visibleBounds.right && x + width > visibleBounds.left &&
            y < visibleBounds.bottom && y + height > visibleBounds.top
    }

    private fun radiusDp(owner: View) = (height / owner.resources.displayMetrics.density / 2f).coerceIn(0f, 128f)

    private fun prismOptions(owner: View): LumenPrismOptions {
        val density = owner.resources.displayMetrics.density
        val fitted = if (bottom == null) HostChromeTopCapsule.fit(width.toFloat(), height.toFloat(), density) else null
        capsule = fitted
        fitted ?: return LumenPrismOptions(cornerRadiusDp = radiusDp(owner))
        // The engine's 24dp default shadow lands entirely outside the row and is cut flat there.
        return LumenPrismOptions(cornerRadiusDp = (fitted.cornerRadius(height.toFloat()) / density).coerceIn(0f, 128f),
            shadow = LumenPrismShadowOptions(enabled = fitted.shadowRadius > 0f, radiusDp = fitted.shadowRadius / density,
                offsetYDp = 0f, opacity = HostChromeTopCapsule.SHADOW_OPACITY))
    }

    /**
     * The suppressed native row was the only paint there: the host leaves the Window (black)
     * behind it. Restore the scene base unless the module wallpaper shows through.
     */
    private fun paintBase(owner: View, destination: Canvas, w: Float, h: Float) {
        // A floating capsule shows the feed through the glass; only an empty row needs the base.
        if (bottom != null || float?.floating == true || moduleWallpaperBehind(owner)) return
        underlay.color = pageBase()
        destination.drawRect(0f, 0f, w, h, underlay)
    }

    /** The feed's own base (`Ga1`), so the row and the uncovered header blend into the page. */
    private fun pageBase(): Int = theme.pageBase(colors.dark) ?: (colors.palette().background or 0xFF000000.toInt())

    /** The top glass samples the same page base the feed shows; the bottom bar keeps its palette. */
    private fun sampleBase(): Int = if (bottom == null) pageBase() else colors.palette().background

    /**
     * khome only, while the capsule actually replaces the row: hide the native header base
     * (`main_top_layout`) and float the feed under the AppBar. An equipped garb keeps its header
     * artwork, like the native top bar path; a module wallpaper stays uncovered. Returns true when
     * host layout changed in this pre-draw.
     */
    private fun syncTop(owner: View): Boolean {
        val float = float ?: return false
        val active = glass && !failed && replacementReady
        if (headerId == 0) headerId = owner.resources.getIdentifier("main_top_layout", "id", owner.context.packageName)
        // owner is the row's AndroidComposeView, one level inside its ComposeView.
        val frame = if (active) HostKhomeTopFloat.locate(owner, headerId) else null
        val wallpaper = frame != null && moduleWallpaperBehind(owner)
        val target = frame?.header?.takeIf { frame.appBar.visibility == View.VISIBLE && colors.garbAccent == null }
        if (target == null) header.restore() else header.apply(target, frame.root, if (wallpaper) null else pageBase())
        return float.sync(frame, active, if (wallpaper) null else pageBase())
    }

    private fun moduleWallpaperBehind(owner: View): Boolean {
        var node: View? = owner
        var depth = 0
        while (node != null && depth++ < 128) {
            if (node.background is HostBackgroundDrawable) return true
            node = node.parent as? View
        }
        return false
    }

    private fun build(owner: View, content: ViewGroup? = selectContent()) {
        val root = owner.rootView as? ViewGroup ?: return
        val target = Target(owner).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
        this.target = target; overlay = root
        try {
            root.overlay.add(target)
            syncTarget()
            source = HostChromeContentSource(content ?: owner, sampleBase(),
                includeViewContent = content != null, composeWindowBase = bottom == null)
            viewport = HostChromeViewportSource(requireNotNull(source), compositeView = appearance.material == HostGlassMaterial.PRISM) {
                it(target, 0f, 0f, 72f * owner.resources.displayMetrics.density)
            }.also { it.sync() }
            val active = LumenSurfaceSession(owner.context, colors.palette(), LumenSurfaceSessionOptions(pauseWhenWindowUnfocused = false))
            session = active
            if (appearance.material == HostGlassMaterial.PRISM) {
                prism = LumenPrismGlass(target, colors.palette(), prismOptions(owner), active, viewport?.coordinateView)
            } else {
                binding = active.bindSource(target, HostSurfaceStyle.floating(colors.dark, radiusDp(owner), appearance.compatible(Build.VERSION.SDK_INT)), viewport)
            }
            target.wake = ::wake
            observer = owner.viewTreeObserver.takeIf { it.isAlive }?.also {
                it.addOnPreDrawListener(preDraw); it.addOnGlobalLayoutListener(layoutChanged)
                it.addOnScrollChangedListener(scrollChanged)
            }
            scheduleBackdropProbe(owner)
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
        val halo = (72f * owner.resources.displayMetrics.density).roundToInt()
        var seen = 0
        fun visit(group: ViewGroup, depth: Int): ViewGroup? {
            if (depth > 24 || ++seen > 2048 || !group.isShown || group.alpha <= 0f || group is HostDockLayer) return null
            val name = group.javaClass.name
            if (name.contains("biliplayer", true) || name.contains("player", true)) return null
            if (recyclerTypes.matches(group.javaClass) && HostViewVisibility.visible(group, sourceBounds)) {
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
        owner.postOnAnimation(frameWake)
    }

    internal fun failure(error: Throwable) {
        if (failed) return
        failed = true
        backdropRecovery.clear()
        release(dispose = true)?.takeUnless { it === error }?.let { runCatching { error.addSuppressed(it) } }
        val owned = nativeOwners.drain()
        // The surface and every native backdrop keep separate identity leases.
        if (owned.isNotEmpty()) runCatching {
            owner.get()?.post {
                HostThreadGuard.run("host.chrome.restore") {
                    owned.forEach { (node, expected, original) -> runCatching { api.restore(node, expected, original) } }
                }
            }
        }
        if (compositionObserved) compositionDirty = true
        wake()
        runCatching { onError(error) }
    }

    /**
     * Clear ownership first, then release every resource even when one close operation fails.
     * A rebuild (new sample source or row size) keeps the host layout it is about to need again;
     * only [dispose] (failure, detach) gives the header and the feed layout back.
     */
    private fun release(dispose: Boolean = false): Throwable? {
        val currentOwner = owner.get()
        val hadProbe = probeAt != null
        probeAt = null; posted = false
        val oldObserver = observer; observer = null
        val oldTarget = target; target = null
        val oldOverlay = overlay; overlay = null
        val oldPrism = prism; prism = null
        val oldBinding = binding; binding = null
        val oldSession = session; session = null
        val oldViewport = viewport; viewport = null
        if (replacementReady && compositionObserved) compositionDirty = true
        source = null; version = null; paused = false; replacementReady = false; capsule = null
        oldTarget?.wake = null
        var failure: Throwable? = null
        fun close(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) runCatching { previous.addSuppressed(error) }
            }
        }
        if (hadProbe) close { currentOwner?.removeCallbacks(backdropProbe) }
        close { currentOwner?.removeCallbacks(frameWake) }
        close { oldObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw) }
        close { oldObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutChanged) }
        close { oldObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollChanged) }
        close { oldPrism?.close() }
        close { oldBinding?.close() }
        close { oldSession?.close() }
        close { oldViewport?.close() }
        close { oldTarget?.let { oldOverlay?.overlay?.remove(it) } }
        close { glow?.resetGestureState() }
        if (dispose) {
            close { header.restore() }
            close { float?.restore() }
        }
        return failure
    }
}
