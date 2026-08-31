package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlin.math.ceil
import kotlin.math.floor

/** 动态形变表面向 Liquid Drawable 暴露当前帧，不把 renderer 泄漏给业务 View。 */
internal interface LiquidMotionSurfaceFrameProvider {
    fun copyLiquidMotionBounds(outBounds: RectF)
    fun liquidMotionCornerRadiusPx(): Float
    fun liquidMotionFallbackColor(): Int
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
    private val outlineGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = parameters.highlightGlowWidthDp * density
    }
    private val rootFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.background
    }
    private val surfaceViews = WeakHashMap<View, Unit>()
    private val retiredBackdropSources = LinkedHashSet<LiquidBackdropSource>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val realtimeCaptureCanvas = Canvas()
    private val realtimeCaptureMask = Path()
    private val realtimeCaptureBounds = Rect()
    private val realtimeCaptureSourceRect = Rect()
    private val realtimeRootLocation = IntArray(2)
    private val realtimeSurfaceLocation = IntArray(2)

    private var backendDriver: LiquidBackendDriver? = null
    private var backdropSource: LiquidBackdropSource? = null
    private var realtimeBackdropSource: LiquidBackdropSource? = null
    private var realtimeCaptureSources: List<LiquidBackdropSource> = emptyList()
    private var realtimeCaptureNextIndex = 0
    private var realtimeCaptureInFlight: LiquidBackdropSource? = null
    private var realtimeCaptureRequestStartedAt = 0L
    private var realtimeCaptureFailureCount = 0
    private var realtimeCaptureSuspended = false
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
    private val realtimeCaptureRunnable = Runnable { requestRealtimeCapture() }
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
        val scrollListener = ViewTreeObserver.OnScrollChangedListener {
            invalidateRegisteredSurfaces()
        }
        rootScrollListener = scrollListener
        root.viewTreeObserver.addOnScrollChangedListener(scrollListener)

        rebuildBackdrop(root)
        val drawable = LiquidRootDrawable(this, palette.background)
        rootDrawable = drawable
        root.background = drawable

        root.invalidate()
        if (activityVisible) scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
        return true
    }

    @MainThread
    fun onActivityStarted() {
        if (closed) return
        activityVisible = true
        scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
    }

    @MainThread
    fun onActivityStopped() {
        activityVisible = false
        boundRoot?.removeCallbacks(realtimeCaptureRunnable)
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
                isStretchAllowed = isStretchAllowed
            )
        }.getOrNull()
    }

    @MainThread
    fun finishStretchViewport(view: View?) {
        (view as? LiquidStretchViewport)?.finishStretch()
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
                    viewY - rootScreenLocation[1]
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
        if (parameters.highlightGlowAlpha > 0f && outlineGlowPaint.strokeWidth > 0f &&
            bounds.width() > outlineGlowPaint.strokeWidth &&
            bounds.height() > outlineGlowPaint.strokeWidth
        ) {
            outlineGlowPaint.color = ColorUtils.setAlphaComponent(
                Color.WHITE,
                (parameters.highlightGlowAlpha * 255f * alpha / 255f)
                    .toInt()
                    .coerceIn(0, 255)
            )
            val glowInset = outlineGlowPaint.strokeWidth * 0.5f
            canvas.drawRoundRect(
                bounds.left + glowInset,
                bounds.top + glowInset,
                bounds.right - glowInset,
                bounds.bottom - glowInset,
                (radiusPx - glowInset).coerceAtLeast(0f),
                (radiusPx - glowInset).coerceAtLeast(0f),
                outlineGlowPaint
            )
        }
        // 标准档保持既有 0x66 高光；实时档使用更亮、更宽且带柔和内圈的双层边缘。
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

    /** Surface Drawable 在首次 draw 时登记 callback View；弱键避免 renderer 反向延长 View 生命周期。 */
    internal fun registerSurfaceView(view: View) {
        if (!closed) surfaceViews[view] = Unit
    }

    private fun invalidateRegisteredSurfaces() {
        val iterator = surfaceViews.keys.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next()
            if (!view.isAttachedToWindow) iterator.remove()
            else if (view.isShown) view.invalidate()
        }
    }

    /** 以目标 30 FPS 排队；任何时刻只允许一个 PixelCopy 请求在飞行。 */
    private fun scheduleRealtimeCapture(delayMs: Long) {
        val root = boundRoot ?: return
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            realtimeCaptureInFlight != null
        ) {
            return
        }
        root.removeCallbacks(realtimeCaptureRunnable)
        root.postOnAnimationDelayed(realtimeCaptureRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun requestRealtimeCapture() {
        val root = boundRoot ?: return
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            realtimeCaptureInFlight != null
        ) {
            return
        }
        if (!root.isAttachedToWindow || !root.isShown ||
            root.windowVisibility != View.VISIBLE || surfaceViews.isEmpty()
        ) {
            scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.RETRY_DELAY_MS)
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
        realtimeCaptureInFlight = captureSource
        realtimeCaptureRequestStartedAt = SystemClock.uptimeMillis()
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
        realtimeCaptureInFlight = null
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }

        if (result == PixelCopy.SUCCESS && sanitizeRealtimeCapture(captureSource)) {
            realtimeCaptureFailureCount = 0
            realtimeBackdropSource = captureSource
            bindPreparedBackendsToBackdrop(captureSource)
            invalidateRegisteredSurfaces()
            val elapsed = SystemClock.uptimeMillis() - realtimeCaptureRequestStartedAt
            scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.nextFrameDelayMs(elapsed))
            return
        }

        if (result != PixelCopy.ERROR_SOURCE_NO_DATA) realtimeCaptureFailureCount += 1
        if (LiquidRealtimeCapturePolicy.shouldSuspend(realtimeCaptureFailureCount)) {
            suspendRealtimeCapture(releaseBuffers = true)
        } else {
            scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.RETRY_DELAY_MS)
        }
    }

    /**
     * 把已绘制玻璃区域以 0xDC 的稳定底图覆盖，仍保留约 14% 上一帧轮廓作为内部景深。
     * 该递推强度严格小于 1，可抑制无限反馈，同时让实时画面在边缘折射中保持可感知。
     */
    private fun sanitizeRealtimeCapture(captureSource: LiquidBackdropSource): Boolean {
        val root = boundRoot ?: return false
        val stableBackdrop = backdropSource ?: return false
        if (captureSource.isClosed || stableBackdrop.isClosed || root.width <= 0 || root.height <= 0) {
            return false
        }

        val bitmap = captureSource.bitmap
        val scaleX = bitmap.width.toFloat() / root.width.toFloat()
        val scaleY = bitmap.height.toFloat() / root.height.toFloat()
        root.getLocationOnScreen(realtimeRootLocation)
        realtimeCaptureMask.reset()
        var hasMask = false
        val iterator = surfaceViews.keys.iterator()
        while (iterator.hasNext()) {
            val surface = iterator.next()
            if (!surface.isAttachedToWindow) {
                iterator.remove()
                continue
            }
            if (!surface.isShown || surface.alpha <= 0f || surface.rootView !== root.rootView) continue
            surface.getLocationOnScreen(realtimeSurfaceLocation)
            val left = ((realtimeSurfaceLocation[0] - realtimeRootLocation[0]) * scaleX)
                .coerceIn(0f, bitmap.width.toFloat())
            val top = ((realtimeSurfaceLocation[1] - realtimeRootLocation[1]) * scaleY)
                .coerceIn(0f, bitmap.height.toFloat())
            val right = (left + surface.width * scaleX).coerceAtMost(bitmap.width.toFloat())
            val bottom = (top + surface.height * scaleY).coerceAtMost(bitmap.height.toFloat())
            if (right > left && bottom > top) {
                realtimeCaptureMask.addRect(left, top, right, bottom, Path.Direction.CW)
                hasMask = true
            }
        }
        if (!hasMask) return false

        realtimeCaptureBounds.set(0, 0, bitmap.width, bitmap.height)
        realtimeCaptureCanvas.setBitmap(bitmap)
        val saveCount = realtimeCaptureCanvas.save()
        return try {
            realtimeCaptureCanvas.clipPath(realtimeCaptureMask)
            stableBackdrop.drawRoot(
                realtimeCaptureCanvas,
                realtimeCaptureBounds,
                LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA
            )
            true
        } finally {
            realtimeCaptureCanvas.restoreToCount(saveCount)
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
        val target = LiquidRealtimeCapturePolicy.resolveSize(width, height)
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
        boundRoot?.removeCallbacks(realtimeCaptureRunnable)
        if (releaseBuffers) releaseRealtimeCaptureSources(rebindStableBackdrop = true)
    }

    private fun releaseRealtimeCaptureSources(rebindStableBackdrop: Boolean) {
        val stableBackdrop = backdropSource
        realtimeBackdropSource = null
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

    /** 预先绑定所有后备驱动，运行时 draw 失败只做 Map 切换。 */
    private fun bindPreparedBackendsToBackdrop(source: LiquidBackdropSource) {
        val failed = ArrayList<LiquidRenderBackend>()
        preparedDrivers.forEach { (backend, driver) ->
            if (driver.requiresBackdrop && runCatching { driver.bindBackdrop(source) }.isFailure) {
                failed += backend
            }
        }
        failed.forEach { backend -> preparedDrivers.remove(backend)?.close() }
        if (backendDriver?.backend in failed) backendDriver = null
        if (!selectCurrentPreparedBackend()) dispatchFatalFailure()
    }

    private fun advanceAfterFailure(failed: LiquidRenderBackend): Boolean {
        preparedDrivers.remove(failed)?.close()
        if (backendDriver?.backend == failed) backendDriver = null
        fallbackPlan.advanceAfterFailure(failed) ?: return false
        val activated = selectCurrentPreparedBackend()
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
        boundRoot?.removeCallbacks(realtimeCaptureRunnable)
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
        onFirstVisibleDraw = null
        onFatalFailure = null
        preparedDrivers.values.forEach(LiquidBackendDriver::close)
        preparedDrivers.clear()
        backendDriver = null
        releaseRealtimeCaptureSources(rebindStableBackdrop = false)
        realtimeCaptureCanvas.setBitmap(null)
        backdropSource?.close()
        backdropSource = null
        retiredBackdropSources.forEach(LiquidBackdropSource::close)
        retiredBackdropSources.clear()
        boundRoot = null
        rootDrawable = null
    }
}

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
            renderer.registerSurfaceView(view)
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
