package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** 动态形变表面向 Liquid Drawable 暴露当前帧，不把 renderer 泄漏给业务 View。 */
internal interface LiquidMotionSurfaceFrameProvider {
    fun copyLiquidMotionBounds(outBounds: RectF)
    fun liquidMotionCornerRadiusPx(): Float
    fun liquidMotionFallbackColor(): Int
}

/** Surface 的真实 Drawable 几何；只在已有对象上更新，实时反馈遮罩逐帧零分配。 */
private class LiquidSurfaceFootprint {
    var left = 0
    var top = 0
    var right = 0
    var bottom = 0
    var radiusPx = 0f

    /**
     * 上一次录制 display list 时该表面在屏幕上的位置。
     *
     * `backdropOrigin` 这个 uniform 是在 draw 里按当时的 `getLocationOnScreen` 写入的，会被
     * Skia 快照进 display list。视图只是被移动（滚动改 RenderNode 位置、translation 动画）
     * 而没有失效时，display list 会带着**旧原点**重放，玻璃里的背景于是停在旧位置，直到下一次
     * 失效才突然对齐——这就是慢速滑动时控件内背景抖动的来源。记录原点是为了只失效真正移动过的
     * 表面。
     */
    var originX = Int.MIN_VALUE
        private set
    var originY = Int.MIN_VALUE
        private set

    val hasOrigin: Boolean
        get() = originX != Int.MIN_VALUE && originY != Int.MIN_VALUE

    fun update(bounds: Rect, radiusPx: Float, originX: Int, originY: Int) {
        left = bounds.left
        top = bounds.top
        right = bounds.right
        bottom = bounds.bottom
        this.radiusPx = radiusPx
        this.originX = originX
        this.originY = originY
    }

    fun matchesOrigin(x: Int, y: Int): Boolean = originX == x && originY == y
}

/** 记录 stretch viewport 上一次录制时的屏幕原点，判定同 [LiquidSurfaceFootprint]。 */
private class LiquidViewportOrigin {
    var x = Int.MIN_VALUE
        private set
    var y = Int.MIN_VALUE
        private set

    val hasOrigin: Boolean
        get() = x != Int.MIN_VALUE && y != Int.MIN_VALUE

    fun update(x: Int, y: Int) {
        this.x = x
        this.y = y
    }

    fun matches(x: Int, y: Int): Boolean = this.x == x && this.y == y
}

/**
 * MainActivity 首批使用的 Activity 级 Liquid renderer。
 *
 * 每个实例持有一个稳定 root underlay；用户启用高负载模式后，Surface Drawable 可改采三缓冲
 * PixelCopy source。Bitmap、RuntimeShader、RenderEffect 都在绑定/切换路径创建，draw 只更新位置
 * 和 uniform。
 */
internal class LiquidActivityRenderer(
    private val activity: AppViewsActivity,
    private val palette: MonetColors
) : AutoCloseable {
    private val density = activity.resources.displayMetrics.density
    private val darkPalette = ColorUtils.calculateLuminance(palette.surface) < 0.5
    private val hardwareAccelerated = activity.isHardwareAccelerationRequested()
    private val realtimeCaptureRequested = LiquidRealtimeCaptureStore.isEnabled(activity)
    private val realtimeCaptureSupported = LiquidRealtimeCapturePolicy.isSupported(
        sdkInt = AndroidVersion.code,
        hardwareAccelerated = hardwareAccelerated
    )
    private val effectProfile = if (realtimeCaptureRequested && realtimeCaptureSupported) {
        LiquidEffectProfile.REALTIME_CAPTURE
    } else LiquidEffectProfile.STANDARD
    private val visualTuning = LiquidVisualTuningPolicy.resolve(
        dark = darkPalette
    )
    private val backgroundConfig = LiquidBackgroundStore.read(activity).config
    private val backgroundWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liquid-background-loader").apply { isDaemon = true }
    }
    private val parameters: LiquidParameters = LiquidTokenResolver.resolve(
        tuning = visualTuning,
        profile = effectProfile
    )
    private val backendCandidates = LiquidCapabilityPolicy.candidateOrder(
        sdkInt = AndroidVersion.code,
        hardwareAccelerated = hardwareAccelerated
    )
    private val fallbackPlan = LiquidBackendFallbackPlan(backendCandidates)
    private val preparedDrivers = linkedMapOf<LiquidRenderBackend, LiquidBackendDriver>()
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = parameters.highlightWidthDp * density
    }
    private val rootFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.background
    }
    private val surfaceViews = WeakHashMap<View, LiquidSurfaceFootprint>()
    private val stretchViewports = WeakHashMap<View, Unit>()
    private val retiredBackdropSources = LinkedHashSet<LiquidBackdropSource>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val performanceController =
        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
            LiquidPerformanceController(activity, ::onThermalStatusChanged)
        } else null
    private val realtimeCaptureCanvas = Canvas()
    private val realtimeCaptureMask = Path()
    private val realtimeCaptureBounds = Rect()
    private val suppressionScaleBounds = Rect()
    private var realtimeSamplePixelBudget = LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS
    private val realtimeCaptureSourceRect = Rect()
    private val realtimeRootLocation = IntArray(2)
    private val movedSurfaceLocation = IntArray(2)

    /**
     * 抑制遮罩是否已按**发起截图那一刻**的几何构建完成。
     *
     * `PixelCopy` 读的是最近一次已合成的帧，而回调最快也要下一帧才到。原实现在回调里用**当时**
     * 的 `getLocationOnScreen` 建遮罩，快速滑动时位置已经比截图内容前进了几十像素：
     * 一部分上一帧的玻璃没被抑制、原样留在截图里被再次折射（反馈残影），一部分干净背景反而被
     * 抹成底图。两条错位带每帧随滚动移动，就是"快速滑动仍抖动"。慢速滑动时错位只有几像素，
     * 所以看不出来。改为在发起截图时用当帧已绘制的 footprint 几何构建，工作量不变、时机对齐。
     */
    private var realtimeMaskReady = false

    /** 实测采集吞吐；只统计连续成功完成之间的间隔，失败/熔断/重建都会重置。 */
    private val captureThroughput = LiquidCaptureThroughputTracker()

    /** 吞吐自适应给出的刷新率上限；`null` 表示尚未降档。会话内只降不升。 */
    private var throughputRefreshRateCap: Float? = null

    /**
     * 预缩放到截图尺寸的稳定底图，供反馈抑制按 1:1 填充。
     *
     * 抑制原本用 0.25 倍的稳定底图逐帧**双线性放大**填进截图（1440p 上是 360×800 → 671×1490，
     * 约 2.9 倍面积），这是主线程上的软件光栅化，夹在 GPU→CPU 回读与纹理上传之间。预缩放一次后
     * 逐帧只剩 1:1 的 alpha 混合，输出内容不变（同一双线性滤波、同一源，只是重采样从每帧一次变成
     * 尺寸变化时一次）。代价是一张截图尺寸的位图（1,000,000 px 约 3.81 MiB），内存压力下释放。
     */
    private var suppressionUnderlay: Bitmap? = null
    private var suppressionUnderlayShader: BitmapShader? = null
    private var suppressionUnderlaySource: LiquidBackdropSource? = null
    private val suppressionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stretchViewportOrigins = WeakHashMap<View, LiquidViewportOrigin>()
    private val stretchBounds = Rect()
    private val stretchBoundaryPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val stretchLocation = IntArray(2)
    private val stretchEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var backendDriver: LiquidBackendDriver? = null

    /**
     * 当前 backdrop 只绑定到**正在使用**的后端。
     *
     * 旧实现每帧遍历 `preparedDrivers` 全量绑定：API 33 上 BLUR 后端永远不会被绘制，却仍在
     * 每帧 `discardDisplayList()` + `beginRecording()` 重录一个引用整张截图的 display list。
     * 现在改为记录"已绑定的 source"，切换后端时再补绑。
     */
    private val driverBoundSources = HashMap<LiquidRenderBackend, LiquidBackdropSource>()
    private var backdropSource: LiquidBackdropSource? = null
    private var realtimeBackdropSource: LiquidBackdropSource? = null
    private var realtimeCaptureSources: List<LiquidBackdropSource> = emptyList()
    private var realtimeCaptureNextIndex = 0
    private var realtimeCaptureInFlight: LiquidBackdropSource? = null
    private var realtimeCaptureFailureCount = 0
    private var realtimeCaptureSuspended = false
    private var realtimeFrameCallbackPosted = false
    private var realtimeNextCaptureNanos = Long.MAX_VALUE
    private var realtimeFrameIntervalNanos =
        LiquidRealtimeCapturePolicy.frameIntervalNanos(60f)
    private var realtimeTargetRefreshRate = 60f
    private var originalPreferredRefreshRate: Float? = null
    private var appliedPreferredRefreshRate: Float? = null
    private var originalPreferredDisplayModeId: Int? = null
    private var appliedPreferredDisplayModeId: Int? = null
    private var stretchOpticalIntensity = 1f
    private var activityVisible = false
    private var boundRoot: View? = null
    private var rootDrawable: LiquidRootDrawable? = null
    private var rootLayoutListener: View.OnLayoutChangeListener? = null
    private var rootScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var backdropRebuildPosted = false
    private var customBackdropFuture: Future<*>? = null
    private var customBackdropRequest: String? = null
    private var customBackdropLoadGeneration = 0L
    private var customBackdropFailed = false
    private var onFirstVisibleDraw: (() -> Unit)? = null
    private var onFatalFailure: (() -> Unit)? = null
    private var successfulDraw = false
    private var healthPosted = false
    private var fatalPosted = false
    private var closed = false
    private val rootScreenLocation = IntArray(2)
    private val realtimeFrameCallback = Choreographer.FrameCallback(::onRealtimeFrame)
    private val pixelCopyFinishedListener = PixelCopy.OnPixelCopyFinishedListener { result ->
        handleRealtimeCaptureResult(result)
    }

    val backend: LiquidRenderBackend?
        get() = backendDriver?.backend ?: fallbackPlan.current

    init {
        backendCandidates.forEach { candidate ->
            runCatching { createBackend(candidate) }
                .getOrNull()
                ?.let { preparedDrivers[candidate] = it }
        }
        selectCurrentPreparedBackend()
    }

    @MainThread
    fun bindRoot(
        root: View,
        onFirstVisibleDraw: () -> Unit,
        onFatalFailure: () -> Unit
    ): Boolean {
        if (closed) return false
        val existingRoot = boundRoot
        if (existingRoot != null && existingRoot !== root) return false
        this.onFirstVisibleDraw = onFirstVisibleDraw
        this.onFatalFailure = onFatalFailure
        if (existingRoot === root) return true

        boundRoot = root
        root.getLocationOnScreen(rootScreenLocation)
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
                                                            oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = right - left != oldRight - oldLeft
            val heightChanged = bottom - top != oldBottom - oldTop
            if (widthChanged || heightChanged) scheduleBackdropRebuild(root)
            root.getLocationOnScreen(rootScreenLocation)
        }
        rootLayoutListener = layoutListener
        root.addOnLayoutChangeListener(layoutListener)
        // 滚动监听不能省。`backdropOrigin` 在 draw 时写入并被快照进 display list，滚动只移动
        // RenderNode 而不重录，采样原点因此会滞留在旧位置；实时档的采样回调只有在 PixelCopy
        // 真正完成时才失效表面，而单飞回读的完成节奏远低于 UI 帧率，两者错开就表现为控件内
        // 背景抖动。这里保留监听，但只失效**位置真的变了**的表面，比原来的无条件全量失效更省。
        val scrollListener = ViewTreeObserver.OnScrollChangedListener {
            invalidateMovedSurfaces()
        }
        rootScrollListener = scrollListener
        root.viewTreeObserver.addOnScrollChangedListener(scrollListener)

        rebuildBackdrop(root)
        val drawable = LiquidRootDrawable(this, palette.background)
        rootDrawable = drawable
        root.background = drawable

        root.invalidate()
        configureRealtimeRefreshRate(root)
        if (activityVisible) scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
        return true
    }

    @MainThread
    fun onActivityStarted() {
        if (closed) return
        activityVisible = true
        // 新会话重新从设备最高档开始探测；只降不升的策略靠会话边界自愈。
        captureThroughput.reset()
        throughputRefreshRateCap = null
        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE &&
            !realtimeCaptureSuspended
        ) {
            performanceController?.start(realtimeFrameIntervalNanos)
            boundRoot?.let(::configureRealtimeRefreshRate)
        }
        scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
    }

    @MainThread
    fun onActivityStopped() {
        activityVisible = false
        removeRealtimeFrameCallback()
        performanceController?.stop()
        restorePreferredRefreshRate()
    }

    fun createSurfaceDrawable(
        fallbackColor: Int,
        radiusDp: Float,
        role: SurfaceRole
    ): Drawable = LiquidSurfaceDrawable(
        renderer = this,
        fallbackColor = fallbackColor,
        radiusPx = radiusDp.coerceAtLeast(0f) * density,
        role = role
    )

    /** 将现有滚动容器包进透明 stretch viewport；失败时保持原层级，不上报皮肤失败。 */
    @MainThread
    @SuppressLint("ReplaceWithAndroidVersion")
    fun installStretchViewport(
        scrollTarget: View,
        isStretchAllowed: () -> Boolean
    ): View? {
        if (closed || Build.VERSION.SDK_INT < 31 ||
            !activity.isHardwareAccelerationRequested()
        ) {
            return null
        }
        return runCatching {
            LiquidStretchViewport.installAround(
                scrollTarget = scrollTarget,
                isStretchAllowed = isStretchAllowed,
                drawBoundaryUnderlay = ::drawStretchBoundaryUnderlay,
                drawBoundaryHighlight = ::drawStretchBoundaryHighlight,
                onStretchDistance = ::onStretchDistanceChanged
            )
        }.getOrNull()
    }

    @MainThread
    fun finishStretchViewport(view: View?) {
        (view as? LiquidStretchViewport)?.finishStretch()
    }

    /**
     * 为整个可滚动 viewport 绘制一圈采样折射；它与 child 位于同一前景 RenderNode，系统
     * stretch 会一起形变，而 Activity 稳定底图仍完全静止。
     */
    private fun drawStretchBoundaryUnderlay(
        canvas: Canvas,
        viewport: View,
        topDistance: Float,
        bottomDistance: Float
    ) {
        if (closed || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            !canvas.isHardwareAccelerated || viewport.width <= 0 || viewport.height <= 0 ||
            backdropSource == null
        ) {
            return
        }
        stretchViewports[viewport] = Unit
        stretchBounds.set(0, 0, viewport.width, viewport.height)
        viewport.getLocationOnScreen(stretchLocation)
        (stretchViewportOrigins[viewport] ?: LiquidViewportOrigin().also {
            stretchViewportOrigins[viewport] = it
        }).update(stretchLocation[0], stretchLocation[1])
        val bandPx = (parameters.effectPaddingDp * density).coerceAtMost(
            minOf(viewport.width, viewport.height) * 0.32f
        )
        val radiusPx = (18f * density).coerceAtMost(
            minOf(viewport.width, viewport.height) * 0.5f
        )
        stretchBoundaryPath.rewind()
        stretchBoundaryPath.fillType = Path.FillType.EVEN_ODD
        stretchBoundaryPath.addRoundRect(
            0f,
            0f,
            viewport.width.toFloat(),
            viewport.height.toFloat(),
            radiusPx,
            radiusPx,
            Path.Direction.CW
        )
        if (viewport.width > bandPx * 2f && viewport.height > bandPx * 2f) {
            stretchBoundaryPath.addRoundRect(
                bandPx,
                bandPx,
                viewport.width - bandPx,
                viewport.height - bandPx,
                (radiusPx - bandPx).coerceAtLeast(0f),
                (radiusPx - bandPx).coerceAtLeast(0f),
                Path.Direction.CW
            )
        }
        val intensity = maxOf(
            stretchOpticalIntensity,
            LiquidRealtimeCapturePolicy.stretchOpticalIntensity(
                maxOf(topDistance, bottomDistance)
            )
        )
        val saveCount = canvas.save()
        try {
            canvas.clipPath(stretchBoundaryPath)
            drawWithFallback { driver ->
                if (driver.backend != LiquidRenderBackend.TRANSLUCENT) {
                    driver.drawBackdrop(
                        canvas = canvas,
                        bounds = stretchBounds,
                        radiusPx = radiusPx,
                        viewX = stretchLocation[0] - rootScreenLocation[0],
                        viewY = stretchLocation[1] - rootScreenLocation[1],
                        opticalIntensity = intensity
                    )
                }
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /** 控件上方只画透明高光；不采样或覆盖实时背景，因此不会遮住四边内容。 */
    private fun drawStretchBoundaryHighlight(
        canvas: Canvas,
        viewport: View,
        topDistance: Float,
        bottomDistance: Float
    ) {
        if (closed || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            !canvas.isHardwareAccelerated || viewport.width <= 0 || viewport.height <= 0 ||
            backdropSource == null
        ) {
            return
        }
        val radiusPx = (18f * density).coerceAtMost(
            minOf(viewport.width, viewport.height) * 0.5f
        )
        val intensity = maxOf(
            stretchOpticalIntensity,
            LiquidRealtimeCapturePolicy.stretchOpticalIntensity(
                maxOf(topDistance, bottomDistance)
            )
        )
        // 多层内收亮边随系统距离连续增强，在 stretch 最陡处遮住前景与静态底图的接缝。
        // 层数与 alpha 梯度是遮缝的关键，收窄时只动线宽，不减层、不改衰减公式。
        val boost = ((intensity - 1f) / 0.85f).coerceIn(0f, 1f)
        repeat(STRETCH_EDGE_LAYERS) { layer ->
            val layerAlpha = ((0.11f + 0.13f * boost) * 255f / (layer + 1f))
                .toInt()
                .coerceIn(0, 255)
            stretchEdgePaint.color = ColorUtils.setAlphaComponent(Color.WHITE, layerAlpha)
            stretchEdgePaint.strokeWidth =
                (STRETCH_EDGE_BASE_WIDTH_DP + layer * STRETCH_EDGE_WIDTH_STEP_DP) * density
            val inset = stretchEdgePaint.strokeWidth * 0.5f
            canvas.drawRoundRect(
                inset,
                inset,
                viewport.width - inset,
                viewport.height - inset,
                (radiusPx - inset).coerceAtLeast(0f),
                (radiusPx - inset).coerceAtLeast(0f),
                stretchEdgePaint
            )
        }
    }

    private fun onStretchDistanceChanged(distance: Float) {
        if (closed || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE) return
        val next = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(distance)
        if (abs(next - stretchOpticalIntensity) < 0.002f) return
        stretchOpticalIntensity = next
        invalidateRegisteredSurfaces()
    }

    @MainThread
    fun onTrimMemory(level: Int) {
        if (closed || !LiquidMemoryPolicy.shouldReleaseGraphics(level)) return
        releaseGraphicsForMemoryPressure()
    }

    @MainThread
    fun onLowMemory() {
        if (closed) return
        releaseGraphicsForMemoryPressure()
    }

    private fun releaseGraphicsForMemoryPressure() {
        releaseSuppressionUnderlay()
        suspendRealtimeCapture(releaseBuffers = true)
        // 高阶折射/模糊和实时三缓冲可以在压力下永久降级，但最多 2 MiB 的稳定 underlay
        // 仍是用户可见背景本身。释放它会让当前 Activity 无重建地退回纯色，表现为自定义
        // 图片“过一段时间丢失”；保留稳定 source，同时切到零额外资源的 TRANSLUCENT 表面。
        advanceToTranslucent()
        boundRoot?.invalidate()
        invalidateRegisteredSurfaces()
    }

    internal fun drawRoot(
        canvas: Canvas,
        bounds: Rect,
        alpha: Int,
        viewX: Int,
        viewY: Int,
        fallbackColor: Int
    ) {
        rootScreenLocation[0] = viewX
        rootScreenLocation[1] = viewY
        if (closed || fatalPosted) {
            rootFallbackPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRect(bounds, rootFallbackPaint)
            return
        }
        val source = backdropSource
        if (source != null && !source.isClosed) {
            source.drawRoot(canvas, bounds, alpha)
        } else {
            rootFallbackPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRect(bounds, rootFallbackPaint)
        }
    }

    internal fun drawSurface(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        alpha: Int,
        viewX: Int,
        viewY: Int,
        fallbackColor: Int,
        role: SurfaceRole
    ) {
        val effectiveRadiusPx = radiusPx.coerceIn(
            0f,
            minOf(bounds.width(), bounds.height()).coerceAtLeast(0) * 0.5f
        )
        if (closed || fatalPosted) {
            overlayPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRoundRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
                effectiveRadiusPx, effectiveRadiusPx, overlayPaint
            )
            return
        }

        // Bitmap 截图等一次性软件 Canvas 只使用本次 fallback，不得永久销毁窗口的 GPU 后端。
        if (!canvas.isHardwareAccelerated) {
            drawSurfaceLayers(
                canvas = canvas,
                bounds = bounds,
                radiusPx = effectiveRadiusPx,
                alpha = alpha,
                fallbackColor = fallbackColor,
                role = role,
                translucentFallback = true
            )
            return
        }

        drawWithFallback { driver ->
            if (driver.backend != LiquidRenderBackend.TRANSLUCENT) {
                checkNotNull(realtimeBackdropSource ?: backdropSource) {
                    "GPU Liquid backend has no backdrop source"
                }
                driver.drawBackdrop(
                    canvas,
                    bounds,
                    effectiveRadiusPx,
                    viewX - rootScreenLocation[0],
                    viewY - rootScreenLocation[1],
                    if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
                        stretchOpticalIntensity
                    } else 1f
                )
            }
            drawSurfaceLayers(
                canvas = canvas,
                bounds = bounds,
                radiusPx = effectiveRadiusPx,
                alpha = alpha,
                fallbackColor = fallbackColor,
                role = role,
                translucentFallback = driver.backend == LiquidRenderBackend.TRANSLUCENT
            )
        }
        scheduleHealthConfirmationAfterDraw()
    }

    private fun drawSurfaceLayers(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        alpha: Int,
        fallbackColor: Int,
        role: SurfaceRole,
        translucentFallback: Boolean
    ) {
        val surfaceFraction = LiquidSurfaceAlphaPolicy.resolve(
            role = role,
            translucentFallback = translucentFallback,
            parameters = parameters
        )
        val surfaceAlpha = (surfaceFraction * alpha).toInt().coerceIn(0, 255)
        // 高阶玻璃使用中性的 surface 轻染色；fallback 才恢复业务传入的实色以保证可读性。
        val tintColor = if (translucentFallback) fallbackColor else palette.surface
        overlayPaint.color = ColorUtils.setAlphaComponent(tintColor, surfaceAlpha)
        canvas.drawRoundRect(
            bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(),
            radiusPx, radiusPx, overlayPaint
        )
        // 标准档保持既有 0x66 高光；实时档只留低强度轮廓，连续高光交给 shader。
        outlinePaint.color = ColorUtils.setAlphaComponent(
            Color.WHITE,
            (parameters.highlightAlpha * 255f * alpha / 255f).toInt().coerceIn(0, 255)
        )
        val inset = outlinePaint.strokeWidth * 0.5f
        canvas.drawRoundRect(
            bounds.left + inset, bounds.top + inset,
            bounds.right - inset, bounds.bottom - inset,
            (radiusPx - inset).coerceAtLeast(0f),
            (radiusPx - inset).coerceAtLeast(0f),
            outlinePaint
        )
    }

    private inline fun drawWithFallback(draw: (LiquidBackendDriver) -> Unit) {
        while (!closed) {
            val driver = backendDriver ?: if (selectCurrentPreparedBackend()) backendDriver else null
            if (driver == null) {
                dispatchFatalFailure()
                return
            }
            val result = runCatching { draw(driver) }
            if (result.isSuccess) {
                successfulDraw = true
                return
            }
            if (!advanceAfterFailure(driver.backend)) {
                dispatchFatalFailure()
                return
            }
        }
    }

    private fun scheduleBackdropRebuild(root: View) {
        if (closed || backdropRebuildPosted) return
        backdropRebuildPosted = true
        root.postOnAnimation {
            backdropRebuildPosted = false
            if (!closed && boundRoot === root) rebuildBackdrop(root)
        }
    }

    private fun rebuildBackdrop(root: View) {
        if (closed) return
        val width = root.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = root.height.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val existing = backdropSource
        if (existing != null && existing.fullWidth == width && existing.fullHeight == height) {
            scheduleCustomBackdropIfNeeded(root, width, height)
            return
        }

        val targetSize = LiquidBackdropSizingPolicy.resolve(width, height)
        if (existing != null &&
            existing.bitmap.width == targetSize.width &&
            existing.bitmap.height == targetSize.height
        ) {
            existing.updateFullSize(width, height)
            bindPreparedBackendsToBackdrop(existing)
            root.invalidate()
            invalidateRegisteredSurfaces()
            scheduleCustomBackdropIfNeeded(root, width, height)
            return
        }

        // 先创建并绑定新 source，再让下一次 traversal 接收全部 invalidation 后断开旧 source；
        // close 只释放 Java 所有权而不调用 Bitmap.recycle()，不能把帧回调误当作 GPU fence。
        val created = runCatching {
            LiquidBackdropSource.create(palette, visualTuning, width, height)
        }.getOrNull()
        if (created == null) {
            if (existing == null) advanceToTranslucent()
            return
        }
        backdropSource = created
        bindPreparedBackendsToBackdrop(created)
        root.invalidate()
        invalidateRegisteredSurfaces()
        if (existing != null) retireBackdropAfterFrame(root, existing)
        scheduleCustomBackdropIfNeeded(root, width, height)
    }

    /**
     * 外部图片解码永远离开主线程和 Drawable.draw；首帧先使用自动 Monet source，完成后再原子
     * 切换。图片资产失败只保留自动 source，不触发 Liquid renderer 的后端/皮肤回滚。
     */
    private fun scheduleCustomBackdropIfNeeded(root: View, width: Int, height: Int) {
        if (closed || customBackdropFailed || backgroundConfig.mode != LiquidBackgroundMode.CUSTOM) {
            return
        }
        val assetId = requireNotNull(backgroundConfig.assetId)
        val existing = backdropSource
        if (existing?.customAssetId == assetId &&
            existing.fullWidth == width && existing.fullHeight == height
        ) {
            return
        }
        val target = LiquidBackdropSizingPolicy.resolve(width, height)
        val request = "$assetId:${target.width}x${target.height}:$width:$height"
        if (customBackdropRequest == request && customBackdropFuture?.isDone == false) return

        customBackdropFuture?.cancel(true)
        val generation = ++customBackdropLoadGeneration
        customBackdropRequest = request
        customBackdropFuture = backgroundWorker.submit {
            val bitmap = runCatching {
                LiquidBackgroundStore.decodeBackdrop(
                    context = activity,
                    config = backgroundConfig,
                    targetWidth = target.width,
                    targetHeight = target.height,
                    backgroundColor = palette.background,
                    dark = darkPalette
                )
            }.getOrNull()
            if (bitmap == null) {
                root.post {
                    if (!closed && generation == customBackdropLoadGeneration) {
                        customBackdropRequest = null
                        customBackdropFailed = true
                    }
                }
                return@submit
            }
            val posted = root.post {
                if (closed || generation != customBackdropLoadGeneration || boundRoot !== root) {
                    bitmap.recycle()
                    return@post
                }
                val currentWidth = root.width.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                val currentHeight = root.height.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
                val currentTarget = LiquidBackdropSizingPolicy.resolve(currentWidth, currentHeight)
                if (currentWidth != width || currentHeight != height || currentTarget != target) {
                    bitmap.recycle()
                    customBackdropRequest = null
                    scheduleBackdropRebuild(root)
                    return@post
                }
                val source = runCatching {
                    LiquidBackdropSource.fromCustomBitmap(
                        bitmap = bitmap,
                        assetId = assetId,
                        fullWidth = width,
                        fullHeight = height
                    )
                }.getOrElse {
                    bitmap.recycle()
                    customBackdropFailed = true
                    customBackdropRequest = null
                    return@post
                }
                val previous = backdropSource
                backdropSource = source
                customBackdropRequest = null
                bindPreparedBackendsToBackdrop(source)
                root.invalidate()
                invalidateRegisteredSurfaces()
                if (previous != null) retireBackdropAfterFrame(root, previous)
            }
            if (!posted) bitmap.recycle()
        }
    }

    private fun retireBackdropAfterFrame(root: View, source: LiquidBackdropSource) {
        retiredBackdropSources += source
        root.postOnAnimation {
            if (retiredBackdropSources.remove(source)) source.close()
        }
    }

    /** Surface Drawable 在 draw 时更新真实几何；弱键避免 renderer 反向延长 View 生命周期。 */
    internal fun registerSurfaceView(
        view: View,
        bounds: Rect,
        radiusPx: Float,
        originX: Int,
        originY: Int
    ) {
        if (closed) return
        val footprint = surfaceViews[view] ?: LiquidSurfaceFootprint().also {
            surfaceViews[view] = it
        }
        footprint.update(bounds, radiusPx, originX, originY)
    }

    /**
     * 只失效采样原点已经过期的表面。
     *
     * 每次滚动回调做的是 O(表面数 × 层级) 的 `getLocationOnScreen` 比对，没有移动的表面不会被
     * 重录，也不会重跑折射 shader。
     */
    private fun invalidateMovedSurfaces() {
        if (closed) return
        val surfaceIterator = surfaceViews.entries.iterator()
        while (surfaceIterator.hasNext()) {
            val entry = surfaceIterator.next()
            val view = entry.key
            if (!view.isAttachedToWindow) {
                surfaceIterator.remove()
                continue
            }
            if (!view.isShown) continue
            view.getLocationOnScreen(movedSurfaceLocation)
            if (entry.value.matchesOrigin(movedSurfaceLocation[0], movedSurfaceLocation[1])) {
                continue
            }
            view.invalidate()
        }
        val stretchIterator = stretchViewports.keys.iterator()
        while (stretchIterator.hasNext()) {
            val view = stretchIterator.next()
            if (!view.isAttachedToWindow) {
                stretchIterator.remove()
                continue
            }
            // viewport 自身的边界光学环随它一起移动，位置变化同样要重录。
            if (!view.isShown) continue
            view.getLocationOnScreen(movedSurfaceLocation)
            if (stretchViewportOrigins[view]?.matches(
                    movedSurfaceLocation[0],
                    movedSurfaceLocation[1]
                ) == true
            ) continue
            view.invalidate()
        }
    }

    private fun invalidateRegisteredSurfaces() {
        val surfaceIterator = surfaceViews.keys.iterator()
        while (surfaceIterator.hasNext()) {
            val view = surfaceIterator.next()
            if (!view.isAttachedToWindow) surfaceIterator.remove()
            else if (view.isShown) view.invalidate()
        }
        val stretchIterator = stretchViewports.keys.iterator()
        while (stretchIterator.hasNext()) {
            val view = stretchIterator.next()
            if (!view.isAttachedToWindow) stretchIterator.remove()
            else if (view.isShown) view.invalidate()
        }
    }

    /** 根据 display mode 与热状态请求窗口刷新率，并同步 ADPF 目标周期。 */
    @Suppress("DEPRECATION")
    private fun configureRealtimeRefreshRate(
        root: View,
        thermalStatus: Int = performanceController?.currentThermalStatus
            ?: LiquidPerformancePolicy.THERMAL_STATUS_NONE
    ) {
        if (effectProfile != LiquidEffectProfile.REALTIME_CAPTURE) return
        val display = root.display ?: return
        val currentMode = display.mode
        val matchingModes = display.supportedModes.asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .toList()
        val supportedRates = matchingModes.asSequence()
            .map { it.refreshRate }
            .toList()
        val requestedRefreshRate = LiquidRealtimeCapturePolicy.targetRefreshRate(
            currentRefreshRate = display.refreshRate,
            supportedRefreshRates = supportedRates
        )
        val thermalLimited = LiquidPerformancePolicy.targetRefreshRate(
            requestedRefreshRate = requestedRefreshRate,
            thermalStatus = thermalStatus
        )
        // 吞吐上限与热上限取更严格的一方；两者都只收紧、不放宽设备原始能力。
        realtimeTargetRefreshRate = throughputRefreshRateCap
            ?.let { minOf(thermalLimited, it) }
            ?: thermalLimited
        realtimeFrameIntervalNanos = LiquidRealtimeCapturePolicy.frameIntervalNanos(
            realtimeTargetRefreshRate
        )
        performanceController?.updateTargetWorkDuration(realtimeFrameIntervalNanos)
        val attributes = activity.window.attributes
        if (originalPreferredRefreshRate == null) {
            originalPreferredRefreshRate = attributes.preferredRefreshRate
            originalPreferredDisplayModeId = attributes.preferredDisplayModeId
        }
        val targetMode = matchingModes
            .filter { abs(it.refreshRate - realtimeTargetRefreshRate) <= 0.5f }
            .maxByOrNull { it.refreshRate }
        val targetModeId = targetMode?.modeId ?: 0
        if (abs(attributes.preferredRefreshRate - realtimeTargetRefreshRate) >= 0.01f ||
            attributes.preferredDisplayModeId != targetModeId
        ) {
            attributes.preferredRefreshRate = realtimeTargetRefreshRate
            attributes.preferredDisplayModeId = targetModeId
            activity.window.attributes = attributes
        }
        appliedPreferredRefreshRate = realtimeTargetRefreshRate
        appliedPreferredDisplayModeId = targetModeId
    }

    private fun onThermalStatusChanged(status: Int) {
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        val root = boundRoot ?: return
        captureThroughput.reset()
        configureRealtimeRefreshRate(root, status)
        // 只降帧率仅减少"做几次"；同时降采样分辨率才能压住每次的回读与纹理上传量。
        val budget = LiquidPerformancePolicy.samplePixelBudget(thermalStatus = status)
        if (budget != realtimeSamplePixelBudget) {
            realtimeSamplePixelBudget = budget
            releaseRealtimeCaptureSources(rebindStableBackdrop = true)
        }
        realtimeNextCaptureNanos = System.nanoTime() + realtimeFrameIntervalNanos
    }

    /** 由 VSync 驱动目标最高 120Hz；PixelCopy 始终单飞，慢设备自然按完成速度降频。 */
    private fun scheduleRealtimeCapture(delayMs: Long) {
        if (boundRoot == null) return
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        realtimeNextCaptureNanos = System.nanoTime() +
            delayMs.coerceAtLeast(0L) * NANOS_PER_MILLISECOND
        postRealtimeFrameCallback()
    }

    private fun postRealtimeFrameCallback() {
        if (realtimeFrameCallbackPosted || closed || !activityVisible ||
            realtimeCaptureSuspended || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        realtimeFrameCallbackPosted = true
        choreographer.postFrameCallback(realtimeFrameCallback)
    }

    private fun removeRealtimeFrameCallback() {
        if (!realtimeFrameCallbackPosted) return
        realtimeFrameCallbackPosted = false
        choreographer.removeFrameCallback(realtimeFrameCallback)
    }

    private fun onRealtimeFrame(frameTimeNanos: Long) {
        realtimeFrameCallbackPosted = false
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        if (realtimeCaptureInFlight == null &&
            LiquidRealtimeCapturePolicy.isFrameDue(frameTimeNanos, realtimeNextCaptureNanos)
        ) {
            requestRealtimeCapture(frameTimeNanos)
        }
        postRealtimeFrameCallback()
    }

    private fun requestRealtimeCapture(frameTimeNanos: Long) {
        val root = boundRoot ?: return
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            realtimeCaptureInFlight != null
        ) {
            return
        }
        if (!root.isAttachedToWindow || !root.isShown ||
            root.windowVisibility != View.VISIBLE ||
            (surfaceViews.isEmpty() && stretchViewports.isEmpty())
        ) {
            realtimeNextCaptureNanos = frameTimeNanos +
                LiquidRealtimeCapturePolicy.RETRY_DELAY_MS * NANOS_PER_MILLISECOND
            return
        }
        val captureSources = ensureRealtimeCaptureSources(root) ?: return
        val sourceIndex = realtimeCaptureNextIndex.mod(captureSources.size)
        val captureSource = captureSources[sourceIndex]
        realtimeCaptureNextIndex = (sourceIndex + 1).mod(captureSources.size)

        root.getLocationInWindow(realtimeRootLocation)
        realtimeCaptureSourceRect.set(
            realtimeRootLocation[0],
            realtimeRootLocation[1],
            realtimeRootLocation[0] + root.width,
            realtimeRootLocation[1] + root.height
        )
        // 必须在发起截图前构建：此刻 footprint 里保存的是最近一次绘制的位置，正是 PixelCopy
        // 即将读到的那一帧的几何。放到回调里构建会与截图内容错位。
        realtimeMaskReady = buildSuppressionMask(root, captureSource)
        realtimeCaptureInFlight = captureSource
        realtimeNextCaptureNanos = frameTimeNanos + realtimeFrameIntervalNanos
        val requested = runCatching {
            PixelCopy.request(
                activity.window,
                realtimeCaptureSourceRect,
                captureSource.bitmap,
                pixelCopyFinishedListener,
                mainHandler
            )
        }.isSuccess
        if (!requested) handleRealtimeCaptureResult(PixelCopy.ERROR_SOURCE_INVALID)
    }

    private fun handleRealtimeCaptureResult(result: Int) {
        val captureSource = realtimeCaptureInFlight ?: return
        val workStartedNanos = System.nanoTime()
        try {
            realtimeCaptureInFlight = null
            if (closed || !activityVisible || realtimeCaptureSuspended ||
                effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
            ) {
                return
            }

            if (result == PixelCopy.SUCCESS) {
                val outcome = sanitizeRealtimeCapture(captureSource)
                if (outcome != LiquidCaptureOutcome.FAILED) {
                    applyCaptureThroughputSample(workStartedNanos)
                    // NO_GLASS_VISIBLE 说明本帧压根没画玻璃，截图里也就不含自身反馈，可直接
                    // 采用；把它计入熔断计数会让长列表滚动 33ms 就永久关掉整个实时效果。
                    realtimeCaptureFailureCount = 0
                    realtimeBackdropSource = captureSource
                    bindPreparedBackendsToBackdrop(captureSource)
                    invalidateRegisteredSurfaces()
                    return
                }
            }

            // 失败会拉长下一次完成间隔，不能算进稳态吞吐。
            captureThroughput.reset()
            if (result != PixelCopy.ERROR_SOURCE_NO_DATA) realtimeCaptureFailureCount += 1
            if (LiquidRealtimeCapturePolicy.shouldSuspend(realtimeCaptureFailureCount)) {
                suspendRealtimeCapture(releaseBuffers = true)
            } else {
                realtimeNextCaptureNanos = System.nanoTime() +
                    LiquidRealtimeCapturePolicy.RETRY_DELAY_MS * NANOS_PER_MILLISECOND
            }
        } finally {
            performanceController?.reportActualWorkDuration(
                System.nanoTime() - workStartedNanos
            )
        }
    }

    /**
     * 把已绘制玻璃区域以 0xDC 的稳定底图覆盖，仍保留约 14% 上一帧轮廓作为内部景深。
     * 该递推强度严格小于 1，可抑制无限反馈，同时让实时画面在边缘折射中保持可感知。
     *
     * 返回值区分"没有可见玻璃"与"真的失败"，调用方只对后者累计熔断计数。
     */
    /**
     * 记录一次成功完成，必要时按实测吞吐降一档刷新率。
     *
     * 只降不升：升档需要先请求更高刷新率才能观察可行性，"试探→失败→降回"会在相邻档位之间反复
     * 切换且肉眼可见。会话重建、热状态变化与缓冲重建都会重置统计，届时重新从设备最高档开始。
     */
    private fun applyCaptureThroughputSample(completionNanos: Long) {
        val shouldStepDown = captureThroughput.onCaptureCompleted(
            nowNanos = completionNanos,
            currentTargetFps = realtimeTargetRefreshRate
        )
        if (!shouldStepDown) return
        val root = boundRoot ?: return
        val display = root.display ?: return
        val currentMode = display.mode
        val supported = display.supportedModes.asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .map { it.refreshRate }
            .toList()
        val next = LiquidCaptureThroughputPolicy.stepDownTarget(
            currentTargetFps = realtimeTargetRefreshRate,
            measuredFps = captureThroughput.measuredFramesPerSecond,
            supportedRefreshRates = supported
        )
        captureThroughput.reset()
        if (next >= realtimeTargetRefreshRate - 0.5f) return
        throughputRefreshRateCap = next
        configureRealtimeRefreshRate(root)
    }

    /**
     * 准备与当前截图尺寸 1:1 的抑制底图；尺寸或稳定底图变化时重建。
     *
     * @return 可用时返回 true；分配失败按"本次不做抑制"处理，由调用方回退。
     */
    private fun ensureSuppressionUnderlay(
        stableBackdrop: LiquidBackdropSource,
        width: Int,
        height: Int
    ): Boolean {
        val cached = suppressionUnderlay
        if (cached != null && !cached.isRecycled &&
            cached.width == width && cached.height == height &&
            suppressionUnderlaySource === stableBackdrop && !stableBackdrop.isClosed
        ) {
            return true
        }
        releaseSuppressionUnderlay()
        if (width <= 0 || height <= 0 || stableBackdrop.isClosed) return false
        return runCatching {
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            suppressionScaleBounds.set(0, 0, width, height)
            // 这一次放大与原逐帧填充使用同一滤波与同一源，输出内容一致。
            stableBackdrop.drawRoot(canvas, suppressionScaleBounds, 255)
            bitmap.prepareToDraw()
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            suppressionUnderlay = bitmap
            suppressionUnderlayShader = shader
            suppressionUnderlaySource = stableBackdrop
            suppressionPaint.shader = shader
            true
        }.getOrElse {
            releaseSuppressionUnderlay()
            false
        }
    }

    private fun releaseSuppressionUnderlay() {
        suppressionPaint.shader = null
        suppressionUnderlayShader = null
        suppressionUnderlaySource = null
        suppressionUnderlay = null
    }

    /**
     * 按**发起截图那一刻**的已绘制几何构建抑制遮罩。
     *
     * 位置一律取 footprint / viewport 在最近一次 draw 时记录的屏幕原点，而不是实时
     * `getLocationOnScreen`：`PixelCopy` 读的是最近一次已合成的帧，用当前坐标会在快速滑动时
     * 与截图内容错开几十像素。工作量与放在回调里构建完全相同。
     */
    private fun buildSuppressionMask(
        root: View,
        captureSource: LiquidBackdropSource
    ): Boolean {
        if (captureSource.isClosed || root.width <= 0 || root.height <= 0) return false
        val bitmap = captureSource.bitmap
        val scaleX = bitmap.width.toFloat() / root.width.toFloat()
        val scaleY = bitmap.height.toFloat() / root.height.toFloat()
        val rootOriginX = rootScreenLocation[0]
        val rootOriginY = rootScreenLocation[1]
        realtimeCaptureMask.reset()
        realtimeCaptureMask.fillType = Path.FillType.WINDING
        var hasMask = false
        val paddingPx = parameters.effectPaddingDp * density
        val iterator = surfaceViews.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val surface = entry.key
            val footprint = entry.value
            if (!surface.isAttachedToWindow) {
                iterator.remove()
                continue
            }
            if (!surface.isShown || surface.alpha <= 0f || surface.rootView !== root.rootView) continue
            if (!footprint.hasOrigin) continue
            val rawLeft = footprint.originX - rootOriginX + footprint.left - paddingPx
            val rawTop = footprint.originY - rootOriginY + footprint.top - paddingPx
            val rawRight = footprint.originX - rootOriginX + footprint.right + paddingPx
            val rawBottom = footprint.originY - rootOriginY + footprint.bottom + paddingPx
            if (rawRight <= 0f || rawBottom <= 0f || rawLeft >= root.width ||
                rawTop >= root.height
            ) {
                continue
            }
            val left = (rawLeft * scaleX).coerceIn(0f, bitmap.width.toFloat())
            val top = (rawTop * scaleY).coerceIn(0f, bitmap.height.toFloat())
            val right = (rawRight * scaleX).coerceIn(0f, bitmap.width.toFloat())
            val bottom = (rawBottom * scaleY).coerceIn(0f, bitmap.height.toFloat())
            if (right > left && bottom > top) {
                realtimeCaptureMask.addRoundRect(
                    left,
                    top,
                    right,
                    bottom,
                    (footprint.radiusPx + paddingPx) * scaleX,
                    (footprint.radiusPx + paddingPx) * scaleY,
                    Path.Direction.CW
                )
                hasMask = true
            }
        }
        val stretchIterator = stretchViewports.keys.iterator()
        val stretchBandPx = LiquidRealtimeCapturePolicy
            .stretchFeedbackBandDp(parameters.effectPaddingDp) * density
        while (stretchIterator.hasNext()) {
            val viewport = stretchIterator.next()
            if (!viewport.isAttachedToWindow) {
                stretchIterator.remove()
                continue
            }
            if (!viewport.isShown || viewport.alpha <= 0f || viewport.rootView !== root.rootView) {
                continue
            }
            val origin = stretchViewportOrigins[viewport]?.takeIf { it.hasOrigin } ?: continue
            val left = ((origin.x - rootOriginX) * scaleX).coerceIn(0f, bitmap.width.toFloat())
            val top = ((origin.y - rootOriginY) * scaleY).coerceIn(0f, bitmap.height.toFloat())
            val right = (left + viewport.width * scaleX).coerceAtMost(bitmap.width.toFloat())
            val bottom = (top + viewport.height * scaleY).coerceAtMost(bitmap.height.toFloat())
            val bandX = (stretchBandPx * scaleX).coerceAtMost((right - left) * 0.5f)
            val bandY = (stretchBandPx * scaleY).coerceAtMost((bottom - top) * 0.5f)
            if (right > left && bottom > top && bandX > 0f && bandY > 0f) {
                realtimeCaptureMask.addRect(left, top, right, top + bandY, Path.Direction.CW)
                realtimeCaptureMask.addRect(left, bottom - bandY, right, bottom, Path.Direction.CW)
                realtimeCaptureMask.addRect(left, top + bandY, left + bandX, bottom - bandY, Path.Direction.CW)
                realtimeCaptureMask.addRect(right - bandX, top + bandY, right, bottom - bandY, Path.Direction.CW)
                hasMask = true
            }
        }
        return hasMask
    }

    /**
     * 把已绘制玻璃区域以 0xDC 的稳定底图覆盖，仍保留约 14% 上一帧轮廓作为内部景深。
     * 该递推强度严格小于 1，可抑制无限反馈，同时让实时画面在边缘折射中保持可感知。
     *
     * 遮罩几何在发起截图时就已按当帧绘制位置构建（[buildSuppressionMask]），这里只负责应用。
     * 返回值区分"没有可见玻璃"与"真的失败"，调用方只对后者累计熔断计数。
     */
    private fun sanitizeRealtimeCapture(
        captureSource: LiquidBackdropSource
    ): LiquidCaptureOutcome {
        val stableBackdrop = backdropSource ?: return LiquidCaptureOutcome.FAILED
        if (captureSource.isClosed || stableBackdrop.isClosed) return LiquidCaptureOutcome.FAILED
        if (!realtimeMaskReady) return LiquidCaptureOutcome.NO_GLASS_VISIBLE

        val bitmap = captureSource.bitmap
        realtimeCaptureBounds.set(0, 0, bitmap.width, bitmap.height)
        realtimeCaptureCanvas.setBitmap(bitmap)
        return try {
            if (ensureSuppressionUnderlay(stableBackdrop, bitmap.width, bitmap.height)) {
                // 预缩放底图与截图 1:1，逐帧只剩 alpha 混合，不再做双线性放大。
                suppressionPaint.alpha = LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA
                realtimeCaptureCanvas.drawPath(realtimeCaptureMask, suppressionPaint)
            } else {
                // 预缩放位图分配失败时回退到原路径，抑制强度与几何完全一致。
                stableBackdrop.drawRootMasked(
                    realtimeCaptureCanvas,
                    realtimeCaptureMask,
                    realtimeCaptureBounds,
                    LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA
                )
            }
            LiquidCaptureOutcome.SUPPRESSED
        } finally {
            realtimeCaptureCanvas.setBitmap(null)
        }
    }

    private fun ensureRealtimeCaptureSources(root: View): List<LiquidBackdropSource>? {
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) {
            scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.RETRY_DELAY_MS)
            return null
        }
        val target = LiquidRealtimeCapturePolicy.resolveSize(
            fullWidth = width,
            fullHeight = height,
            pixelBudget = realtimeSamplePixelBudget
        )
        val existing = realtimeCaptureSources
        if (existing.size == LiquidRealtimeCapturePolicy.BUFFER_COUNT &&
            existing.all {
                !it.isClosed && it.fullWidth == width && it.fullHeight == height &&
                    it.bitmap.width == target.width && it.bitmap.height == target.height
            }
        ) {
            return existing
        }

        releaseRealtimeCaptureSources(rebindStableBackdrop = true)
        val created = ArrayList<LiquidBackdropSource>(LiquidRealtimeCapturePolicy.BUFFER_COUNT)
        val result = runCatching {
            repeat(LiquidRealtimeCapturePolicy.BUFFER_COUNT) {
                val bitmap = createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(palette.background)
                created += LiquidBackdropSource.fromRealtimeBitmap(bitmap, width, height)
            }
            created.toList()
        }.getOrElse {
            created.forEach { source ->
                source.close()
                if (!source.bitmap.isRecycled) source.bitmap.recycle()
            }
            realtimeCaptureSuspended = true
            return null
        }
        realtimeCaptureSources = result
        realtimeCaptureNextIndex = 0
        return result
    }

    private fun suspendRealtimeCapture(releaseBuffers: Boolean) {
        realtimeCaptureSuspended = true
        removeRealtimeFrameCallback()
        performanceController?.stop()
        restorePreferredRefreshRate()
        if (releaseBuffers) releaseRealtimeCaptureSources(rebindStableBackdrop = true)
    }

    private fun releaseRealtimeCaptureSources(rebindStableBackdrop: Boolean) {
        val stableBackdrop = backdropSource
        realtimeBackdropSource = null
        driverBoundSources.clear()
        // 缓冲尺寸变化会改变单次回读成本，旧吞吐样本不再代表当前配置。
        captureThroughput.reset()
        releaseSuppressionUnderlay()
        if (rebindStableBackdrop && stableBackdrop != null && !stableBackdrop.isClosed &&
            backendDriver?.backend != LiquidRenderBackend.TRANSLUCENT
        ) {
            bindPreparedBackendsToBackdrop(stableBackdrop)
        }
        realtimeCaptureSources.forEach(LiquidBackdropSource::close)
        realtimeCaptureSources = emptyList()
        realtimeCaptureNextIndex = 0
    }

    /** 只选择 bind 阶段已准备的实例；该方法允许从 draw 调用，但绝不创建图形资源。 */
    private fun selectCurrentPreparedBackend(): Boolean {
        while (!closed) {
            val candidate = fallbackPlan.current ?: return false
            val prepared = preparedDrivers[candidate]
            if (prepared == null) {
                fallbackPlan.advanceAfterFailure(candidate)
                continue
            }
            backendDriver = prepared
            return true
        }
        return false
    }

    /**
     * 只把新 backdrop 绑定到当前后端；其余后备驱动在真正被选中时再补绑。
     *
     * 逐帧全量绑定是纯浪费：`LiquidBlurBackendApi31.bindBackdrop` 每次都会丢弃并重录一个引用
     * 整张实时截图的 RenderNode display list，而 API 33 设备上它永远不会被绘制。
     */
    private fun bindPreparedBackendsToBackdrop(source: LiquidBackdropSource) {
        driverBoundSources.clear()
        if (!ensureCurrentDriverBound(source)) dispatchFatalFailure()
    }

    /** 绑定失败按后端失败处理并降级；成功后记录已绑定的 source，避免重复绑定。 */
    private fun ensureCurrentDriverBound(source: LiquidBackdropSource): Boolean {
        while (!closed) {
            val driver = backendDriver ?: if (selectCurrentPreparedBackend()) {
                backendDriver
            } else null
            if (driver == null) return false
            if (!driver.requiresBackdrop) return true
            if (driverBoundSources[driver.backend] === source) return true
            if (runCatching { driver.bindBackdrop(source) }.isSuccess) {
                driverBoundSources[driver.backend] = source
                return true
            }
            driverBoundSources.remove(driver.backend)
            preparedDrivers.remove(driver.backend)?.close()
            backendDriver = null
            if (fallbackPlan.advanceAfterFailure(driver.backend) == null) return false
        }
        return false
    }

    private fun advanceAfterFailure(failed: LiquidRenderBackend): Boolean {
        driverBoundSources.remove(failed)
        preparedDrivers.remove(failed)?.close()
        if (backendDriver?.backend == failed) backendDriver = null
        fallbackPlan.advanceAfterFailure(failed) ?: return false
        val activated = selectCurrentPreparedBackend()
        val source = realtimeBackdropSource ?: backdropSource
        if (activated && source != null && !source.isClosed && !ensureCurrentDriverBound(source)) {
            return false
        }
        invalidateRegisteredSurfaces()
        return activated
    }

    private fun advanceToTranslucent() {
        while (fallbackPlan.current != null &&
            fallbackPlan.current != LiquidRenderBackend.TRANSLUCENT
        ) {
            val failed = requireNotNull(fallbackPlan.current)
            preparedDrivers.remove(failed)?.close()
            if (backendDriver?.backend == failed) backendDriver = null
            fallbackPlan.advanceAfterFailure(failed)
        }
        if (backendDriver?.backend != LiquidRenderBackend.TRANSLUCENT) {
            backendDriver = null
            selectCurrentPreparedBackend()
        }
    }

    /** 直接 SDK guard 让 Android Lint 能静态证明下面两个 @RequiresApi 构造调用。 */
    @SuppressLint("ReplaceWithAndroidVersion")
    private fun createBackend(backend: LiquidRenderBackend): LiquidBackendDriver = when (backend) {
        LiquidRenderBackend.REFRACTION -> if (Build.VERSION.SDK_INT >= 33) {
            LiquidRefractionBackendApi33(parameters, density)
        } else error("RuntimeShader requires API 33")
        LiquidRenderBackend.BLUR -> if (Build.VERSION.SDK_INT >= 31) {
            LiquidBlurBackendApi31(parameters.blurRadiusDp * density)
        } else error("RenderEffect requires API 31")
        LiquidRenderBackend.TRANSLUCENT -> LiquidTranslucentBackend()
    }

    private fun dispatchFatalFailure() {
        if (closed || fatalPosted) return
        fatalPosted = true
        val root = boundRoot
        val callback = onFatalFailure
        if (root != null) root.post { if (!closed) callback?.invoke() }
        else callback?.invoke()
    }

    /** Drawable.draw 已真实成功返回后才排队确认，避免 OnDrawListener 的绘制前时序。 */
    private fun scheduleHealthConfirmationAfterDraw() {
        val root = boundRoot ?: return
        if (closed || fatalPosted || healthPosted || !successfulDraw || !root.isShown) return
        healthPosted = true
        root.post {
            if (!closed && !fatalPosted) onFirstVisibleDraw?.invoke()
        }
    }

    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        activityVisible = false
        realtimeCaptureSuspended = true
        removeRealtimeFrameCallback()
        performanceController?.close()
        realtimeCaptureInFlight = null
        customBackdropLoadGeneration += 1L
        customBackdropFuture?.cancel(true)
        customBackdropFuture = null
        customBackdropRequest = null
        backgroundWorker.shutdownNow()
        val root = boundRoot
        rootLayoutListener?.let { listener -> root?.removeOnLayoutChangeListener(listener) }
        rootLayoutListener = null
        rootScrollListener?.let { listener ->
            root?.viewTreeObserver?.takeIf { it.isAlive }
                ?.removeOnScrollChangedListener(listener)
        }
        rootScrollListener = null
        surfaceViews.clear()
        stretchViewports.clear()
        onFirstVisibleDraw = null
        onFatalFailure = null
        releaseSuppressionUnderlay()
        preparedDrivers.values.forEach(LiquidBackendDriver::close)
        preparedDrivers.clear()
        backendDriver = null
        releaseRealtimeCaptureSources(rebindStableBackdrop = false)
        realtimeCaptureCanvas.setBitmap(null)
        backdropSource?.close()
        backdropSource = null
        retiredBackdropSources.forEach(LiquidBackdropSource::close)
        retiredBackdropSources.clear()
        restorePreferredRefreshRate()
        boundRoot = null
        rootDrawable = null
    }

    @Suppress("DEPRECATION")
    private fun restorePreferredRefreshRate() {
        val applied = appliedPreferredRefreshRate ?: return
        val original = originalPreferredRefreshRate ?: return
        val attributes = activity.window.attributes
        val appliedModeId = appliedPreferredDisplayModeId
        val originalModeId = originalPreferredDisplayModeId
        var changed = false
        if (abs(attributes.preferredRefreshRate - applied) < 0.01f) {
            attributes.preferredRefreshRate = original
            changed = true
        }
        if (appliedModeId != null && originalModeId != null &&
            attributes.preferredDisplayModeId == appliedModeId
        ) {
            attributes.preferredDisplayModeId = originalModeId
            changed = true
        }
        if (changed) {
            activity.window.attributes = attributes
        }
        appliedPreferredRefreshRate = null
        originalPreferredRefreshRate = null
        appliedPreferredDisplayModeId = null
        originalPreferredDisplayModeId = null
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L

/**
 * 超出回弹亮边的层数与线宽。
 *
 * 最外层线宽决定整条亮边的视觉厚度：原为 `1.1 + 2.1×layer`（1.1/3.2/5.3dp，带宽约 5.3dp），
 * 用户反馈过厚，收到 `0.8 + 1.0×layer`（0.8/1.8/2.8dp，带宽约 2.8dp，约为原来的 53%）。
 * **层数保持 3**：由内到外的 alpha 梯度才是遮住前景与静态底图接缝的机制，减层会让接缝重新暴露。
 */
private const val STRETCH_EDGE_LAYERS = 3
private const val STRETCH_EDGE_BASE_WIDTH_DP = 0.8f
private const val STRETCH_EDGE_WIDTH_STEP_DP = 1.0f

private class LiquidRootDrawable(
    private val renderer: LiquidActivityRenderer,
    private val fallbackColor: Int
) : Drawable() {
    private val location = IntArray(2)
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        if (view != null) view.getLocationOnScreen(location)
        else {
            location[0] = 0
            location[1] = 0
        }
        renderer.drawRoot(
            canvas, bounds, drawableAlpha, location[0], location[1], fallbackColor
        )
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

private class LiquidSurfaceDrawable(
    private val renderer: LiquidActivityRenderer,
    private val fallbackColor: Int,
    private val radiusPx: Float,
    private val role: SurfaceRole
) : Drawable() {
    private val location = IntArray(2)
    private val motionBoundsF = RectF()
    private val motionBounds = Rect()
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        if (view != null) {
            view.getLocationOnScreen(location)
        }
        else {
            location[0] = 0
            location[1] = 0
        }
        var drawBounds = bounds
        var drawRadiusPx = radiusPx
        var drawFallbackColor = fallbackColor
        var drawX = location[0]
        var drawY = location[1]
        val motionProvider = view as? LiquidMotionSurfaceFrameProvider
        if (motionProvider != null) {
            motionProvider.copyLiquidMotionBounds(motionBoundsF)
            if (motionBoundsF.width() > 0f && motionBoundsF.height() > 0f) {
                motionBounds.set(
                    floor(motionBoundsF.left).toInt(),
                    floor(motionBoundsF.top).toInt(),
                    ceil(motionBoundsF.right).toInt(),
                    ceil(motionBoundsF.bottom).toInt()
                )
                drawBounds = motionBounds
                drawRadiusPx = motionProvider.liquidMotionCornerRadiusPx()
                drawFallbackColor = motionProvider.liquidMotionFallbackColor()
                drawX += motionBounds.left
                drawY += motionBounds.top
            }
        }
        if (view != null) {
            renderer.registerSurfaceView(view, drawBounds, drawRadiusPx, location[0], location[1])
        }
        renderer.drawSurface(
            canvas,
            drawBounds,
            drawRadiusPx,
            drawableAlpha,
            drawX,
            drawY,
            drawFallbackColor,
            role
        )
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private fun AppViewsActivity.isHardwareAccelerationRequested(): Boolean {
    val windowFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    val appFlag = applicationInfo.flags and ApplicationInfo.FLAG_HARDWARE_ACCELERATED
    return windowFlag != 0 || appFlag != 0
}
