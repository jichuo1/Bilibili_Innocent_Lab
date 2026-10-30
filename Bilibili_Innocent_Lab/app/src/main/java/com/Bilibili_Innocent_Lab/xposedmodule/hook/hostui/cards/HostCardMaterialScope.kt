package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostViewVisibility
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import com.lumen.coacervation.engine.host.LumenFluentControl
import com.lumen.coacervation.engine.host.LumenFluentControlOptions
import com.lumen.coacervation.engine.host.LumenFluentElevation
import com.lumen.coacervation.engine.host.LumenFluentElevationOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 每个内容视口只绑定一个材质场，View / Compose / 动态分组仅裁剪这份共享结果。 */
internal open class HostCardMaterialScope(private val owner: ViewGroup, night: Boolean,
    private val context: Context = owner.context,
    internal val materialKind: HostCardMaterial = HostCardMaterial.ACRYLIC,
    private val gpuScale: Float = .5f) : AutoCloseable,
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private var palette = HostVideoCardSurface.palette(night, context)
    private val frameStrokeDp = if (materialKind == HostCardMaterial.ACRYLIC) .5f else 1f
    private var night = night
    private var themeReadAt = 0L
    private val content = if (materialKind == HostCardMaterial.ACRYLIC)
        HostChromeContentSource(owner, palette.background, includeViewContent = false) else null
    private val cards = WeakHashMap<View, CardPaint>()
    private val paints = WeakHashMap<CardPaint, WeakReference<View>>()
    private var hardwareDirty = true
    private var softwareDirty = true
    private var frame = 0L
    private var refreshedFrame = -1L
    private var hardwareRecordedFrame = -1L
    private var softwareRecordedFrame = -1L
    private var version = Long.MIN_VALUE
    private var visible: Boolean? = null
    private var observer: ViewTreeObserver? = null
    private var hardwareScene: HostCardMaterialScene? = null
    private var softwareScene: HostCardMaterialScene? = null
    private val location = IntArray(2)
    private val visibleBounds = Rect()
    private val mapping = ViewSamplingMatrix()
    private val source = object : View(context) {
        override fun isShown(): Boolean = owner.isShown
        override fun onDraw(canvas: Canvas) {
            content?.drawContent(canvas)
        }
    }.apply { alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val field = object : View(context) {
        override fun isShown(): Boolean = owner.isShown
        override fun invalidate() { super.invalidate(); changed() }
        override fun invalidateDrawable(drawable: Drawable) { super.invalidateDrawable(drawable); changed() }
    }.apply {
        // 引擎的软件目标需要 isShown=true、alpha=1；只裁掉代理自身的可见输出。
        // 显式录制 background 不经过 View 的 clipBounds，不影响采样几何。
        clipBounds = Rect()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val session = if (materialKind == HostCardMaterial.ACRYLIC) LumenSurfaceSession(context, palette, LumenSurfaceSessionOptions(
        maxSurfaces = 1, autoPauseWhenHidden = false, pauseWhenWindowUnfocused = false,
        // 模糊输入和共享混色层降采样；卡片边框、圆角、封面与前景不经过这个层。
        gpuScale = gpuScale, maxGpuSurfacePixels = 8_388_608)) else null
    private val material: LumenFluentControl?
    private val mica: HostCardMicaField?
    var closed = false
        private set
    internal var materialRecordings = 0L
        private set

    init {
        require(materialKind.usesSharedField)
        if (session != null) owner.overlay.add(source)
        owner.overlay.add(field)
        material = if (session != null) LumenFluentControl(field, palette, materialOptions(), session, source) else null
        mica = if (materialKind == HostCardMaterial.MICA) HostCardMicaField(owner, field, palette, ::changed) else null
        owner.addOnAttachStateChangeListener(this)
        if (owner.isAttachedToWindow) observe()
        refresh(night)
    }

    private fun changed() {
        hardwareDirty = true; softwareDirty = true
        owner.postInvalidateOnAnimation()
    }

    private fun observe() {
        val next = owner.viewTreeObserver
        if (observer === next) return
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = next; next.addOnPreDrawListener(this)
    }

    override fun onViewAttachedToWindow(view: View) = HostThreadGuard.run("host.card.material.attach") { observe() }
    override fun onViewDetachedFromWindow(view: View) = HostThreadGuard.run("host.card.material.detach") { close() }
    override fun onPreDraw(): Boolean = HostThreadGuard.call("host.card.material.predraw", true) {
        if (closed) return@call true
        frame++
        refresh(night)
        if (visible != true) return@call true
        // 先更新一次共享节点，已有 View / Compose 显示列表可继续引用同一个节点。
        record(owner.isHardwareAccelerated)
        for ((view, paint) in cards) {
            view.getLocationInWindow(location)
            if (paint.lastX != location[0] || paint.lastY != location[1]) {
                paint.lastX = location[0]; paint.lastY = location[1]
                paint.invalidateSelf()
            }
        }
        true
    }

    fun refresh(night: Boolean) {
        if (closed || refreshedFrame == frame && this.night == night) return
        refreshedFrame = frame
        val shown = HostViewVisibility.visible(owner, visibleBounds)
        if (visible != shown) {
            visible = shown
            if (shown) {
                session?.resume()
                // 隐藏期间卡片可能已缓存了不含共享节点的显示列表；仅刷新代理 View
                // 无法恢复这些引用。返回页面时让原生及 Compose 宿主各自重新录制。
                paints.entries.toList().forEach { (paint, host) ->
                    paint.invalidateSelf()
                    host.get()?.invalidate()
                }
                changed()
            } else session?.pause()
        }
        if (!shown) return
        val now = SystemClock.uptimeMillis()
        if (this.night != night || now - themeReadAt >= 250) {
            this.night = night; themeReadAt = now
            val next = HostVideoCardSurface.palette(night, context)
            if (next != palette) {
                palette = next; content?.windowBaseColor = next.background
                session?.updatePalette(next); material?.updatePalette(next); material?.update(materialOptions())
                mica?.updatePalette(next)
                paints.keys.toList().forEach { it.updatePalette() }
                changed()
            }
        }
        val width = owner.width; val height = owner.height
        if (source.width != width || source.height != height) {
            source.layout(0, 0, width, height); field.layout(0, 0, width, height)
            // 卡片显示列表保留着这个节点的引用，尺寸变化时必须复用节点身份。
            changed()
        }
        mica?.refresh()
        val nextVersion = content?.contentVersion ?: return
        if (version != nextVersion) {
            version = nextVersion; source.invalidate(); session?.notifyContentChanged(); changed()
        }
    }

    private fun materialOptions() = LumenFluentControlOptions(
        acrylic = LumenAcrylicOptions(tintColor = palette.surface, fallbackColor = palette.surface),
        elevation = LumenFluentElevationOptions(shadowEnabled = false, strokeEnabled = false),
        cornerRadiusDp = 0f, stateLayerEnabled = false, focusVisualEnabled = false)

    private fun frameOptions(radius: Float, shadow: Boolean) = LumenFluentControlOptions(
        acrylic = LumenAcrylicOptions(enabled = false, fallbackColor = palette.surface),
        elevation = LumenFluentElevationOptions(level = LumenFluentElevation.CARD,
            shadowEnabled = shadow, strokeWidthDp = frameStrokeDp, pressedLowersElevation = false),
        cornerRadiusDp = radius, stateLayerEnabled = false, focusVisualEnabled = false)

    private fun record(hardware: Boolean): HostCardMaterialScene? {
        if (closed || visible != true || owner.width <= 0 || owner.height <= 0) return null
        val useHardware = hardware && AndroidVersion.isAtLeast(AndroidVersion.Q)
        // 顶栏的软件采样会重画这些卡片，不能因此丢弃屏幕显示列表引用的 GPU 节点。
        val scene = if (useHardware) hardwareScene ?: HostCardMaterialSceneApi29(
            if (materialKind == HostCardMaterial.ACRYLIC) gpuScale else 1f).also { hardwareScene = it }
            else softwareScene ?: HostCardMaterialPicture().also { softwareScene = it }
        if (useHardware) {
            if (!hardwareDirty || hardwareRecordedFrame == frame) return scene
            hardwareDirty = false; hardwareRecordedFrame = frame
        } else {
            if (!softwareDirty || softwareRecordedFrame == frame) return scene
            softwareDirty = false; softwareRecordedFrame = frame
        }
        val background = requireNotNull(mica?.drawable ?: field.background)
        background.setBounds(0, 0, owner.width, owner.height)
        scene.record(background, owner.width, owner.height)
        materialRecordings++
        return scene
    }

    fun apply(view: View, radiusPx: Float): Drawable {
        check(!closed)
        var paint = cards[view]
        if (paint == null || view.background !== paint) {
            paint?.close()
            paint = CardPaint(view, radiusPx, native = true)
            cards[view] = paint; paints[paint] = WeakReference(view)
            view.background = paint
        } else paint.radius(radiusPx)
        return paint
    }

    fun composePaint(view: View, radiusPx: Float): CardPaint =
        CardPaint(view, radiusPx, native = false).also { paints[it] = WeakReference(view) }

    fun release(view: View) { cards.remove(view)?.let { paints.remove(it); it.close() } }
    fun positionChanged(view: View) { cards[view]?.invalidateSelf() }
    internal fun diagnostics(view: View) = if (cards.containsKey(view)) material?.diagnostics() else null
    internal fun samplingDiagnostics() = requireNotNull(session).diagnostics()
    internal fun samplingPerformance() = requireNotNull(session).performanceDiagnostics()
    internal fun micaDiagnostics() = mica?.diagnostics()
    internal fun micaPreparations() = mica?.preparations ?: 0L

    internal inner class CardPaint(view: View, radiusPx: Float, private val native: Boolean) : Drawable(), AutoCloseable {
        private val host = WeakReference(view)
        private val target = if (native) view else View(view.context)
        private var radiusPx = radiusPx
        private val control = LumenFluentControl(target, palette,
            frameOptions(radiusPx / target.resources.displayMetrics.density, native))
        private val border = requireNotNull(target.background)
        private val clip = Path()
        private val inner = RectF()
        private val matrix = Matrix()
        private val composite = Paint()
        private var disposed = false
        private var windowX = 0f
        private var windowY = 0f
        internal var lastX = Int.MIN_VALUE
        internal var lastY = Int.MIN_VALUE
        fun position(x: Float, y: Float) { windowX = x; windowY = y }
        fun radius(value: Float) {
            if (radiusPx == value) return
            radiusPx = value; updatePalette(); onBoundsChange(bounds)
        }
        fun updatePalette() {
            control.updatePalette(palette)
            control.update(frameOptions(radiusPx / target.resources.displayMetrics.density, native))
            invalidateSelf()
        }
        override fun onBoundsChange(bounds: Rect) {
            border.bounds = bounds
            // 与引擎描边共用宽度，避免细描边内侧仍残留一圈较宽的不透明底板。
            val inset = target.resources.displayMetrics.density * frameStrokeDp
            inner.set(bounds); inner.inset(inset, inset)
            clip.reset(); clip.addRoundRect(inner, (radiusPx - inset).coerceAtLeast(0f),
                (radiusPx - inset).coerceAtLeast(0f), Path.Direction.CW)
        }
        override fun draw(canvas: Canvas) = HostThreadGuard.run("host.card.material.draw") {
            if (disposed || composite.alpha == 0) return@run
            // 非默认透明度/滤镜必须作用于整个背景，不能只改变静态描边。
            val layer = if (composite.alpha != 255 || composite.colorFilter != null)
                canvas.saveLayer(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                    bounds.bottom.toFloat(), composite) else canvas.save()
            try {
                border.draw(canvas)
                if (closed || inner.isEmpty) return@run
                val scene = record(canvas.isHardwareAccelerated)
                val view = host.get() ?: return@run
                if (native) { if (!mapping.sourceToTarget(owner, view, matrix)) return@run }
                else { owner.getLocationInWindow(location); matrix.setTranslate(location[0] - windowX, location[1] - windowY) }
                canvas.clipPath(clip); canvas.concat(matrix); scene?.draw(canvas)
            } finally { canvas.restoreToCount(layer) }
        }
        override fun setAlpha(alpha: Int) { composite.alpha = alpha; invalidateSelf() }
        override fun getAlpha() = composite.alpha
        override fun setColorFilter(colorFilter: ColorFilter?) { composite.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getOutline(outline: Outline) = border.getOutline(outline)
        override fun close() {
            if (disposed) return
            disposed = true
            paints.remove(this)
            if (native) host.get()?.let { if (it.background === this) it.background = border }
            control.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
        owner.removeOnAttachStateChangeListener(this)
        paints.keys.toList().forEach { it.close() }; paints.clear(); cards.clear()
        material?.close(); session?.close(); mica?.close()
        hardwareScene?.close(); hardwareScene = null
        softwareScene?.close(); softwareScene = null
        owner.overlay.remove(field)
        if (session != null) owner.overlay.remove(source)
    }
}
