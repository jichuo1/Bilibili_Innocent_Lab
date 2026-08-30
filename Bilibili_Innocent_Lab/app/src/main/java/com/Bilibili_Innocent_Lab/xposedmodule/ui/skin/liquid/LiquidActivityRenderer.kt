package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import java.util.WeakHashMap

/**
 * MainActivity 首批使用的 Activity 级 Liquid renderer。
 *
 * 每个实例只持有一个静态 backdrop source；Surface Drawable 共享该 source 与当前后端。Bitmap、
 * RuntimeShader、RenderEffect 都在绑定/降级路径创建，draw 只更新位置和 uniform。
 */
internal class LiquidActivityRenderer(
    private val activity: AppViewsActivity,
    private val palette: MonetColors
) : AutoCloseable {
    private val density = activity.resources.displayMetrics.density
    private val visualTuning = LiquidVisualTuningPolicy.resolve(
        dark = ColorUtils.calculateLuminance(palette.surface) < 0.5
    )
    private val parameters: LiquidParameters = LiquidTokenResolver.resolve(visualTuning)
    private val backendCandidates = LiquidCapabilityPolicy.candidateOrder(
        sdkInt = AndroidVersion.code,
        hardwareAccelerated = activity.isHardwareAccelerationRequested()
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
    private val surfaceViews = WeakHashMap<View, Unit>()
    private val retiredBackdropSources = LinkedHashSet<LiquidBackdropSource>()

    private var backendDriver: LiquidBackendDriver? = null
    private var backdropSource: LiquidBackdropSource? = null
    private var boundRoot: View? = null
    private var rootDrawable: LiquidRootDrawable? = null
    private var rootLayoutListener: View.OnLayoutChangeListener? = null
    private var rootScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var backdropRebuildPosted = false
    private var onFirstVisibleDraw: (() -> Unit)? = null
    private var onFatalFailure: (() -> Unit)? = null
    private var successfulDraw = false
    private var healthPosted = false
    private var fatalPosted = false
    private var closed = false
    private val rootScreenLocation = IntArray(2)

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
        return true
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
        forceTranslucentAndReleaseBackdrop()
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
                checkNotNull(backdropSource) { "GPU Liquid backend has no backdrop source" }
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
        // 用户已确认该高光观感良好：宽度、颜色和 0x66 alpha 保持不变。
        outlinePaint.color = ColorUtils.setAlphaComponent(
            Color.WHITE,
            (0x66 * alpha / 255f).toInt().coerceIn(0, 255)
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
        if (existing != null && existing.fullWidth == width && existing.fullHeight == height) return

        val targetSize = LiquidBackdropSizingPolicy.resolve(width, height)
        if (existing != null &&
            existing.bitmap.width == targetSize.width &&
            existing.bitmap.height == targetSize.height
        ) {
            existing.updateFullSize(width, height)
            bindPreparedBackendsToBackdrop(existing)
            root.invalidate()
            invalidateRegisteredSurfaces()
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

    private fun forceTranslucentAndReleaseBackdrop() {
        advanceToTranslucent()
        val source = backdropSource
        backdropSource = null
        val root = boundRoot
        if (source != null) {
            if (root != null && root.isAttachedToWindow &&
                root.isShown && root.windowVisibility == View.VISIBLE
            ) {
                retireBackdropAfterFrame(root, source)
            } else source.close()
        }
        if (root == null || !root.isAttachedToWindow || !root.isShown ||
            root.windowVisibility != View.VISIBLE
        ) {
            retiredBackdropSources.forEach(LiquidBackdropSource::close)
            retiredBackdropSources.clear()
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
        renderer.drawSurface(
            canvas,
            bounds,
            radiusPx,
            drawableAlpha,
            location[0],
            location[1],
            fallbackColor,
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
