package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material

import com.lumen.coacervation.engine.motion.LumenAnimator

import android.os.Build
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowBackdropTarget
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowChromeGlassApi31
import android.graphics.Bitmap
import android.animation.ValueAnimator
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.AmbientBackdropScene
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngine
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngineCallbacks
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidBackdropSizingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidMotionSurfaceFrameProvider
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.SamplingMatrixMath
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ScrollSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfig
import com.lumen.coacervation.engine.host.LumenPreparedBackdropCache
import com.lumen.coacervation.engine.host.LumenBackdropReadiness
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/**
 * Static ambient frost for every surface, plus a live lens sample for floating chrome.
 * 柔光材质的 [GlowEngine] 实现；高级材质会话里它同时是休眠的回退引擎。
 *
 * 静态部分只采窗口底图（不含文字、其他窗口）；悬浮/顶栏表面另由 [LiveBackdropSampler] 对宿主用
 * [bindContentSource] 指定的内容层做低分辨率透镜采样，让从胶囊下方穿过的内容被模糊与折射。
 */
internal class FrostedMaterialRenderer(
    private val palette: MonetColors,
    private val density: Float,
    backgroundContext: Context? = null
) : GlowEngine {
    override val skin: SkinId get() = SkinId.MATERIAL_YOU

    private val dark = ColorUtils.calculateLuminance(palette.background) < .5
    private val backgroundContext = backgroundContext?.applicationContext
    private var root: View? = null
    /** 显示底图与可丢弃的模糊采样分开；内存降级不能抹掉用户背景。 */
    private var rootBackdrop: Bitmap? = null
    private var frame: ModernBackdropFrame? = null
    private var sampleShader: BitmapShader? = null
    internal var revealFraction = 0f
        private set
    private var revealAnimator: ValueAnimator? = null
    private val shaderMatrix = Matrix()
    private val samplingMatrices = ViewSamplingMatrix()
    private val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val movedTransform = FloatArray(9)
    private val sourceTransform = FloatArray(9)
    private class SurfaceTransform {
        val target = FloatArray(9)
        val source = FloatArray(9)
    }
    private val surfaces = WeakHashMap<View, SurfaceTransform>()
    private class RefreshWindow(
        val observer: WeakReference<ViewTreeObserver>,
        val batch: FrostedRefreshBatch,
        val scroll: ViewTreeObserver.OnScrollChangedListener,
        val preDraw: ViewTreeObserver.OnPreDrawListener,
        val draw: ViewTreeObserver.OnDrawListener
    )
    private val windows = WeakHashMap<View, RefreshWindow>()
    private val lifecycle = FrostedMaterialLifecycle()
    private val generation: Long get() = lifecycle.generation
    private val closed: Boolean get() = lifecycle.isClosed
    private var width = 0
    private var height = 0
    private var failedWidth = 0
    private var failedHeight = 0
    private var workerStarted = false
    private val backdropPalette = listOf(palette.primary, palette.onPrimary, palette.secondary,
        palette.tertiary, palette.surface, palette.background, palette.surfaceVariant)
    private data class BackdropKey(val palette: List<Int>, val config: LiquidBackgroundConfig,
        val width: Int, val height: Int, val density: Float)
    private var pendingBackdropKey: BackdropKey? = null
    private var motionBackdropRequested = false
    private companion object {
        val preparedBackdrops = LumenPreparedBackdropCache<BackdropKey, ModernBackdropFrame>(
            LiquidBackdropSizingPolicy.MAX_PRESENTATION_PIXELS * LiquidBackdropSizingPolicy.BYTES_PER_PIXEL +
                LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES
        )
    }
    private var work: Future<*>? = null
    private val worker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "BIL-SoftFrost").apply { isDaemon = true } } }
    private val handler = Handler(Looper.getMainLooper())
    // 与透镜采集共用矩阵工具：采集录制里每张卡片的静态磨砂映射都走这里，采集期间的祖先备忘才能生效。
    private val live = LiveBackdropSampler(density, samplingMatrices)
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> requestBackdrop(r - l, b - t) }

    /**
     * 悬浮栏宿主 → 内容节点玻璃（C 期，API 31+，见 [FrostedChromeGlassApi31]）。登记后该栏不再走
     * [LiveBackdropSampler] 的主线程录制 + 后台软件模糊。glass 为 GlowChromeGlassApi31，声明成
     * Any 以免 API 31 以下加载本类时解析它。
     */
    private class ChromeGlass(val target: GlowBackdropTarget, val glass: Any)
    private val chromeGlass = WeakHashMap<View, ChromeGlass>()
    private val chromeTransform = Matrix()
    private val chromeBounds = Rect()
    /** 节点路径任一次出错即永久停用，退回软件透镜。 */
    private var chromeGlassBroken = false

    /** 悬浮表面下方的内容层（表面的兄弟，不含表面自身）；未绑定则悬浮表面只有静态磨砂。 */
    override fun bindContentSource(view: View) {
        if (closed) return
        live.bindSource(view)
        surfaces.keys.forEach(View::invalidate)
    }

    /** 柔光不会失败：没有健康确认，也没有致命回退。 */
    override fun bindRoot(root: View, callbacks: GlowEngineCallbacks): Boolean = bindRoot(root)

    fun bindRoot(view: View): Boolean {
        if (closed || (root != null && root !== view)) return false
        if (root === view) return true
        root = view
        view.addOnLayoutChangeListener(layoutListener)
        view.background = object : Drawable(), LumenBackdropReadiness {
            override val isReadyForMotion: Boolean
                get() = closed || pendingBackdropKey == null || frame != null || failedWidth > 0
            override fun prepareForMotion() {
                motionBackdropRequested = true
                if (pendingBackdropKey == null) return
                revealAnimator?.cancel()
                revealFraction = 1f
                view.invalidate()
            }
            private var drawingAlpha = 255
            override fun draw(canvas: Canvas) {
                val current = rootBackdrop
                rootPaint.color = ColorUtils.setAlphaComponent(palette.background, drawingAlpha)
                canvas.drawRect(bounds, rootPaint)
                if (current != null) {
                    rootPaint.alpha = (drawingAlpha * revealFraction).toInt()
                    canvas.drawBitmap(current, null, bounds, rootPaint)
                }
            }
            override fun setAlpha(alpha: Int) { drawingAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
            override fun getAlpha() = drawingAlpha
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        requestBackdrop(view.width, view.height)
        return true
    }

    override fun surface(fallbackColor: Int, radiusDp: Float, role: SurfaceRole): Drawable =
        ModernSurfaceDrawable(this, fallbackColor, radiusDp * density, density, ModernMaterialPolicy.surface(role, dark))

    private fun requestBackdrop(newWidth: Int, newHeight: Int) {
        if (!lifecycle.canWork || newWidth <= 0 || newHeight <= 0 || (width == newWidth && height == newHeight && (frame != null || work != null))) return
        if (failedWidth == newWidth && failedHeight == newHeight) return
        width = newWidth; height = newHeight
        val token = lifecycle.beginRequest() ?: return
        work?.cancel(true)
        val recipient = WeakReference(this)
        val colors = palette
        val scale = density
        val completion = handler
        val context = backgroundContext
        // 返回已有页面或改变尺寸时重读配置；后台只接收本次不可变快照。
        val config = context?.let { LiquidBackgroundStore.read(it).config }
        val key = config?.takeIf { it.mode == LiquidBackgroundMode.CUSTOM }
            ?.let { BackdropKey(backdropPalette, it, newWidth, newHeight, scale) }
        pendingBackdropKey = key
        if (key == null) preparedBackdrops.clear()
        val prepared = key?.let(preparedBackdrops::get)
        if (prepared != null) {
            acceptBackdrop(token, prepared, animateReveal = false)
            return
        }
        val isDark = dark
        workerStarted = true
        work = worker.submit {
            val result = runCatching {
                ModernBackdropFactory.create(colors, newWidth, newHeight, scale) { targetWidth, targetHeight ->
                    if (context == null || config?.mode != LiquidBackgroundMode.CUSTOM) null
                    else LiquidBackgroundStore.decodeBackdrop(context, config, targetWidth, targetHeight,
                        colors.background, isDark)
                }
            }.getOrNull()
            completion.post { recipient.get()?.acceptBackdrop(token, result) }
        }
    }

    private fun acceptBackdrop(token: Long, result: ModernBackdropFrame?, animateReveal: Boolean = true) {
        if (!lifecycle.accepts(token)) return
        work = null
        if (result == null) {
            failedWidth = width; failedHeight = height
            return // A readable neutral surface remains; retry only on resize or a later foreground session.
        }
        frame = result
        pendingBackdropKey?.takeIf { result.custom }?.let { key ->
            preparedBackdrops.put(key, result,
                result.original.allocationByteCount.toLong() + result.blurred.allocationByteCount)
        }
        rootBackdrop = result.original
        sampleShader = BitmapShader(result.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        samplePaint.shader = sampleShader
        revealAnimator?.cancel()
        revealFraction = if (animateReveal && !(motionBackdropRequested && pendingBackdropKey != null) &&
            ValueAnimator.areAnimatorsEnabled()) 0f else 1f
        if (revealFraction == 0f) {
            revealAnimator = LumenAnimator.ofFloat(0f, 1f).apply {
                duration = 160L
                addUpdateListener {
                    if (!closed && generation == token) {
                        revealFraction = it.animatedValue as Float
                        root?.invalidate(); surfaces.keys.forEach(View::invalidate)
                    }
                }
                start()
            }
        }
        root?.invalidate()
        surfaces.keys.forEach(View::invalidate)
    }

    internal fun drawSample(canvas: Canvas, bounds: RectF, radius: Float, view: View?, opacity: Int): Boolean {
        if (closed || view == null) return false
        val current = frame ?: return false
        val sourceRoot = root ?: return false
        if (!samplingMatrices.bitmapToTarget(sourceRoot, view, current.blurred.width, current.blurred.height, shaderMatrix)) return false
        sampleShader?.setLocalMatrix(shaderMatrix)
        samplePaint.alpha = opacity
        canvas.drawRoundRect(bounds, radius, radius, samplePaint)
        return true
    }

    internal fun drawLiveSample(canvas: Canvas, bounds: RectF, radius: Float, view: View?, opacity: Int): Boolean {
        if (closed || view == null || !lifecycle.canWork) return false
        val chrome = chromeGlass[view]
        if (chrome != null && canvas.isHardwareAccelerated && drawChromeGlass(chrome, canvas, bounds, radius, view, opacity)) {
            // 节点玻璃接管后，软件采样不能再为这个栏录制。
            live.unregister(view)
            return true
        }
        live.register(view)
        return live.draw(canvas, bounds, radius, view, opacity)
    }

    override val supportsSurfaceBackdrop: Boolean
        get() = !closed && !chromeGlassBroken && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override val supportsDescendantSurfaceBackdrop: Boolean
        get() = supportsSurfaceBackdrop

    override fun setSurfaceBackdrop(host: View, target: GlowBackdropTarget?) {
        if (closed) return
        if (target == null || !supportsSurfaceBackdrop || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            chromeGlass.remove(host)?.let { entry ->
                closeChromeGlass(entry)
                host.invalidate()
            }
            return
        }
        val existing = chromeGlass[host]
        if (existing?.target === target) return
        existing?.let(::closeChromeGlass)
        // 构造里会编译 AGSL：失败即永久停用节点路径，不能抛进调用方的 pre-draw。
        val glass = runCatching { FrostedChromeGlassApi31.create(density) }.getOrElse { error ->
            chromeGlassBroken = true
            Log.w(TAG, "chrome glass unavailable", error)
            chromeGlass.remove(host)
            host.invalidate()
            return
        }
        chromeGlass[host] = ChromeGlass(target, glass)
        host.invalidate()
    }

    private fun closeChromeGlass(entry: ChromeGlass) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (entry.glass as GlowChromeGlassApi31).close()
    }

    /** 返回 false 表示本次不可用（内容节点尚未录制、宿主脱离容器），调用方照常走软件透镜。 */
    private fun drawChromeGlass(
        entry: ChromeGlass,
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        view: View,
        opacity: Int
    ): Boolean {
        if (chromeGlassBroken || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val content = entry.target.recordedCapture() ?: return false
        if (!entry.target.matrixTo(view, chromeTransform)) return false
        bounds.roundOut(chromeBounds)
        return runCatching {
            (entry.glass as GlowChromeGlassApi31).draw(
                canvas = canvas,
                bounds = chromeBounds,
                radiusPx = radius,
                content = content,
                contentOffsetX = 0f,
                contentOffsetY = 0f,
                alpha = opacity / 255f,
                opticalIntensity = 1f,
                stretchDirY = 0f,
                underlay = null,
                contentToHost = chromeTransform
            )
        }.onFailure { error ->
            chromeGlassBroken = true
            Log.w(TAG, "chrome glass disabled", error)
            chromeGlass.values.forEach(::closeChromeGlass)
            chromeGlass.clear()
        }.isSuccess
    }

    internal fun register(view: View) {
        if (!lifecycle.canWork) return
        val position = surfaces[view] ?: SurfaceTransform().also { surfaces[view] = it }
        // 与批量比对使用相同的自上而下乘法次序，避免浮点结合顺序差异把静止表面误判为移动。
        samplingMatrices.withAncestorMemo {
            if (!samplingMatrices.localToScreen(view, position.target)) position.target.fill(Float.NaN)
            val source = root
            if (source == null || !samplingMatrices.localToScreen(source, position.source)) position.source.fill(Float.NaN)
        }
        registerRefreshWindow(view.rootView)
    }

    private fun registerRefreshWindow(windowRoot: View) {
        val observer = windowRoot.viewTreeObserver
        val existing = windows[windowRoot]
        if (existing?.observer?.get() === observer && observer.isAlive) return
        existing?.let(::removeRefreshWindow)
        val reference = WeakReference(windowRoot)
        val batch = FrostedRefreshBatch()
        val scroll = ViewTreeObserver.OnScrollChangedListener {
            live.invalidate()
            if (lifecycle.canWork && batch.request()) reference.get()?.let(::flushPositionChanges)
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (batch.beforeDraw() && lifecycle.canWork) reference.get()?.let(::flushPositionChanges)
            true
        }
        val draw = ViewTreeObserver.OnDrawListener { batch.drawn() }
        windows[windowRoot] = RefreshWindow(WeakReference(observer), batch, scroll, preDraw, draw)
        observer.addOnScrollChangedListener(scroll)
        observer.addOnPreDrawListener(preDraw)
        observer.addOnDrawListener(draw)
    }

    private fun removeRefreshWindow(state: RefreshWindow) {
        state.batch.clear()
        state.observer.get()?.takeIf { it.isAlive }?.let { observer ->
            observer.removeOnScrollChangedListener(state.scroll)
            observer.removeOnPreDrawListener(state.preDraw)
            observer.removeOnDrawListener(state.draw)
        }
    }

    /**
     * 显式动画/形变的位移通知（`ActivitySkinSession.notifyPositionChanged` 转发）。
     *
     * 与滚动走同一条路径、同一个采样节奏：显式动画期曾经整段冻结透镜采样，顶栏/底栏
     * 透出的背景要等动画播完才跳变（2026-09-23 用户报告）。尖刺改由后台线程消化。
     */
    override fun notifyPositionChanged() = onPositionChanged()

    override fun notifyScrollPositionChanged(scrollHost: View) {
        if (!lifecycle.canWork || root == null || surfaces.isEmpty() ||
            !scrollHost.isAttachedToWindow || !scrollHost.isShown) return
        val windowRoot = scrollHost.rootView
        if (!windows.containsKey(windowRoot)) return
        live.invalidate()
        flushPositionChanges(windowRoot, scrollHost)
    }

    private fun onPositionChanged() {
        if (!lifecycle.canWork) return
        live.invalidate()
        val iterator = windows.entries.iterator()
        while (iterator.hasNext()) {
            val (windowRoot, state) = iterator.next()
            if (!windowRoot.isAttachedToWindow) {
                removeRefreshWindow(state)
                iterator.remove()
            } else if (state.batch.request()) {
                // 手风琴等可能在本监听之后的 pre-draw 才写回属性；同帧补刷，绝不推迟一帧。
                flushPositionChanges(windowRoot)
            }
        }
    }

    private fun flushPositionChanges(windowRoot: View, scrollHost: View? = null) {
        if (!lifecycle.canWork) return
        // 本段只读几何；所有可见表面共用祖先前缀，备忘严格不跨回调保存。
        samplingMatrices.withAncestorMemo {
            val source = root
            val sourceValid = source != null && samplingMatrices.localToScreen(source, sourceTransform)
            val iterator = surfaces.entries.iterator()
            while (iterator.hasNext()) {
                val (view, position) = iterator.next()
                if (!view.isAttachedToWindow) { iterator.remove(); continue }
                if (view.rootView !== windowRoot || !view.isShown) continue
                if (scrollHost != null && !ScrollSurfaceScope.contains(view, scrollHost) { it.parent as? View }) continue
                if (!sourceValid || !samplingMatrices.localToScreen(view, movedTransform) ||
                    !SamplingMatrixMath.equal(position.target, movedTransform) ||
                    !SamplingMatrixMath.equal(position.source, sourceTransform)) view.invalidate()
            }
        }
    }

    fun releaseMemory() {
        lifecycle.invalidate()
        windows.values.forEach { it.batch.clear() }
        work?.cancel(true); work = null
        revealAnimator?.cancel(); revealAnimator = null
        if (!lifecycle.canWork) {
            rootBackdrop = null
            revealFraction = 0f
        } else if (rootBackdrop != null) {
            // 前台只释放模糊与实时透镜，继续显示受同一像素预算约束的稳定底图。
            revealFraction = 1f
        }
        // Never recycle a bitmap that can still be referenced by a hardware display list.
        frame = null; sampleShader = null; samplePaint.shader = null
        live.releaseAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            chromeGlass.values.forEach { (it.glass as GlowChromeGlassApi31).releaseDisplayList() }
        }
        root?.invalidate(); surfaces.keys.forEach(View::invalidate)
    }

    fun stop() {
        lifecycle.stop()
        live.suspend()
        releaseMemory()
    }

    fun resume() {
        if (!lifecycle.resume()) return
        failedWidth = 0; failedHeight = 0
        live.resume()
        root?.let { requestBackdrop(it.width, it.height) }
    }

    // 作为高级材质的休眠回退时同样跟随生命周期：Liquid 可能在本次前台会话中途失败并切过来。
    override fun onStart() = resume()
    override fun onStop() = stop()

    // 阈值沿用会话层原实现；API 34 起平台不再下发 RUNNING_* 级别，更高的级别照样满足条件。
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { preparedBackdrops.clear(); releaseMemory() }
    }

    override fun onLowMemory() { preparedBackdrops.clear(); releaseMemory() }

    override fun close() {
        if (closed) return
        lifecycle.close()
        releaseMemory()
        live.close()
        root?.removeOnLayoutChangeListener(layoutListener)
        windows.values.forEach(::removeRefreshWindow)
        windows.clear(); surfaces.clear(); root = null
        chromeGlass.values.forEach(::closeChromeGlass)
        chromeGlass.clear()
        if (workerStarted) worker.shutdownNow()
    }
}

private const val TAG = "BIL-SoftFrost"

/** Prepared allows bind-before-onStart. Stopped and closed states cannot create or accept work. */
internal class FrostedMaterialLifecycle {
    private var stopped = false
    var isClosed = false
        private set
    var generation = 0L
        private set
    val canWork: Boolean get() = !stopped && !isClosed

    fun beginRequest(): Long? {
        if (!canWork) return null
        return ++generation
    }

    fun accepts(token: Long): Boolean = canWork && generation == token
    fun invalidate() { generation++ }
    fun stop() { stopped = true; invalidate() }
    fun resume(): Boolean {
        if (isClosed) return false
        stopped = false
        return true
    }
    fun close() { isClosed = true; stopped = true; invalidate() }
}

private data class ModernBackdropFrame(val original: Bitmap, val blurred: Bitmap, val custom: Boolean)

private object ModernBackdropFactory {
    fun create(
        palette: MonetColors, width: Int, height: Int, density: Float,
        customBackground: ((Int, Int) -> Bitmap?)? = null
    ): ModernBackdropFrame {
        val (w, h) = ModernMaterialPolicy.sampleSize(width, height)
        // 自定义资产解码/哈希只在已有后台线程执行；失败只回退自动背景。
        val presentation = LiquidBackdropSizingPolicy.resolvePresentation(width, height)
        val custom = runCatching { customBackground?.invoke(presentation.width, presentation.height) }.getOrNull()
        val original = custom ?: createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                val dark = ColorUtils.calculateLuminance(palette.background) < .5
                val canvas = Canvas(bitmap)
                AmbientBackdropScene.paint(canvas, palette, w, h, dark)
            }
        val blurred = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            Canvas(blurred).drawBitmap(original, null, Rect(0, 0, w, h),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
            val pixels = IntArray(w * h)
            blurred.getPixels(pixels, 0, w, 0, 0, w, h)
            val blurredPixels = ModernBackdropBlur.blur(pixels, w, h, ModernMaterialPolicy.blurRadius(w, width, density))
            blurred.setPixels(blurredPixels, 0, w, 0, 0, w, h)
        } catch (failure: Throwable) {
            original.recycle(); blurred.recycle()
            throw failure
        }
        // 自定义显示图与磨砂副本分别保留显示和光学采样的分辨率。
        original.prepareToDraw(); blurred.prepareToDraw()
        return ModernBackdropFrame(original, blurred, custom != null)
    }
}

/** A direct background keeps its View callback, including in separately hosted Dialog windows. */
private class ModernSurfaceDrawable(
    private val renderer: FrostedMaterialRenderer?,
    private val color: Int,
    private val radius: Float,
    private val density: Float,
    private val style: ModernSurfaceStyle,
    private val tintOnly: Boolean = false
) : Drawable() {
    private val rect = RectF()
    private val edgeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = density.coerceAtLeast(1f) * .65f }
    private val edgeShader = LinearGradient(0f, 0f, 0f, 1f,
        ColorUtils.setAlphaComponent(Color.WHITE, style.upperEdgeAlpha),
        ColorUtils.setAlphaComponent(Color.WHITE, style.lowerEdgeAlpha), Shader.TileMode.CLAMP)
    private val edgeMatrix = Matrix()
    private var edgeTop = Float.NaN
    private var edgeBottom = Float.NaN
    private var drawingAlpha = 255

    init { edge.shader = edgeShader }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        val motionProvider = view as? LiquidMotionSurfaceFrameProvider
        var drawRadius = radius
        var drawColor = color
        if (motionProvider != null) {
            motionProvider.copyLiquidMotionBounds(rect)
            drawRadius = motionProvider.liquidMotionCornerRadiusPx()
            drawColor = motionProvider.liquidMotionFallbackColor()
        } else rect.set(bounds)
        // An uninitialized or collapsed motion frame must never fall back to the full View rectangle.
        if (rect.isEmpty || !rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite() || !drawRadius.isFinite()) return
        drawRadius = drawRadius.coerceIn(0f, minOf(rect.width(), rect.height()) * .5f)
        // ARGB belongs to every caller, including ordinary diagnostic entry Views without a motion provider.
        val frameAlpha = FrostedMotionSurfaceAlpha.frameAlpha(drawColor, drawingAlpha)
        if (frameAlpha <= 0) return
        // register 记录的是"上一次**硬件**绘制时"的位置，供位移通知判断要不要重录显示列表。
        // 透镜采集的离屏软件回放不能写它：既是每表面 60–90µs 的重复矩阵计算，又会用当前位置
        // 覆盖掉显示列表真正录制时的位置——滚动通知在 pre-draw 之后才发，比对就会误判"没动"。
        if (view != null && canvas.isHardwareAccelerated) renderer?.register(view)
        val tintAlpha = if (tintOnly) style.tintAlpha
            else (255 + (style.tintAlpha - 255) * (renderer?.revealFraction ?: 0f)).toInt()
        val overlayAlpha = tintAlpha * frameAlpha / 255
        // Preserve the host's ARGB opacity without allocating an offscreen saveLayer for each frame.
        val sampleAlpha = FrostedMotionSurfaceAlpha.sampleAlpha(frameAlpha, overlayAlpha)
        var sampled = tintOnly || renderer?.drawSample(canvas, rect, drawRadius, view, sampleAlpha) == true
        // 悬浮表面再叠一层实时透镜采样：静态磨砂在下，穿过胶囊的内容在上，色罩最后。
        if (style.live && renderer?.drawLiveSample(canvas, rect, drawRadius, view, sampleAlpha) == true) sampled = true
        fill.color = ColorUtils.setAlphaComponent(drawColor, if (sampled) overlayAlpha else frameAlpha)
        canvas.drawRoundRect(rect, drawRadius, drawRadius, fill)
        // Motion hosts own their collapsing stroke. A second full-opacity edge would flash at handoff.
        if (motionProvider == null) {
            edgeRect.set(rect)
            edgeRect.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
            if (edgeTop != rect.top || edgeBottom != rect.bottom) {
                edgeTop = rect.top; edgeBottom = rect.bottom
                edgeMatrix.setScale(1f, rect.height().coerceAtLeast(1f))
                edgeMatrix.postTranslate(0f, rect.top)
                edgeShader.setLocalMatrix(edgeMatrix)
            }
            edge.alpha = frameAlpha
            canvas.drawRoundRect(edgeRect, (drawRadius - edge.strokeWidth / 2).coerceAtLeast(0f),
                (drawRadius - edge.strokeWidth / 2).coerceAtLeast(0f), edge)
        }
    }

    override fun setAlpha(alpha: Int) { drawingAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = drawingAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; edge.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    /**
     * 静态几何报告真实圆角；缺省实现是无半径矩形，会让弹性长按高光与 View 阴影
     * 都按方形裁剪。运动变形帧（motionProvider）只存在于 draw，outline 用静态
     * bounds + 构造半径即可——高光在 DOWN 时一次性取样。
     */
    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) outline.setEmpty()
        else outline.setRoundRect(bounds, radius)
    }
}

/** A source-over tint and sample together retain the exact caller-owned motion opacity. */
internal object FrostedMotionSurfaceAlpha {
    fun frameAlpha(color: Int, drawableAlpha: Int): Int =
        (color ushr 24) * drawableAlpha.coerceIn(0, 255) / 255

    fun sampleAlpha(frameAlpha: Int, overlayAlpha: Int): Int {
        val frame = frameAlpha.coerceIn(0, 255)
        val overlay = overlayAlpha.coerceIn(0, frame)
        if (overlay == 255) return 0
        return ((frame - overlay) * 255f / (255 - overlay)).roundToInt().coerceIn(0, 255)
    }
}

internal object ModernMaterialDrawables {
    /** 胶囊内部只叠着色和边框，继承父胶囊的实时光学图，不另采静态/旧帧背景。 */
    fun chromeOverlay(color: Int, radiusPx: Float, density: Float, role: SurfaceRole, dark: Boolean): Drawable =
        ModernSurfaceDrawable(null, color, radiusPx, density, ModernMaterialPolicy.surface(role, dark), tintOnly = true)

    // 与 ModernBackdropFactory 同一套色阶语言：顶部向 surface 轻抬、底部沉向
    // surfaceVariant——条款同意页等无皮肤兜底背景也不再是一块纯色。
    fun neutralWindow(palette: MonetColors): Drawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            ColorUtils.blendARGB(palette.background, palette.surface, .34f),
            palette.background,
            ColorUtils.blendARGB(palette.background, palette.surfaceVariant, .3f)
        ))

    fun fallback(color: Int, radiusPx: Float, density: Float, role: SurfaceRole, dark: Boolean): Drawable =
        ModernSurfaceDrawable(null, color, radiusPx, density, ModernMaterialPolicy.surface(role, dark))
}
