package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.AmbientBackdropScene
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion

/**
 * Activity 的稳定 underlay，或高负载模式下由 PixelCopy 三缓冲持有的实时采样 source。
 *
 * 普通 create/custom 路径不捕获 Window 或 View 树；实时 source 只接管预先分配的可变 Bitmap，
 * 捕获调度与反馈抑制仍由 Activity renderer 负责。
 */
internal class LiquidBackdropSource private constructor(
    val bitmap: Bitmap,
    val customAssetId: String?,
    val isRealtime: Boolean,
    fullWidth: Int,
    fullHeight: Int,
    private val opticalBitmap: Bitmap = bitmap,
    private val crispRefraction: Boolean = false
) : AutoCloseable {
    var fullWidth: Int = fullWidth
        private set
    var fullHeight: Int = fullHeight
        private set

    // 实时档在截图缺席时仍使用清晰底图；标清档保留预先模糊过的光学副本。
    // 自动背景已共用一张位图，实时截图也不经过 CPU 模糊，两者不需要额外副本。
    private val refractionBitmap = if (crispRefraction && opticalBitmap !== bitmap) bitmap else opticalBitmap
    val bitmapShader = BitmapShader(refractionBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    val refractionWidth: Int get() = refractionBitmap.width
    val refractionHeight: Int get() = refractionBitmap.height

    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 外部窗口的光学直采专用 Shader 与 Matrix；仍使用模糊副本。
     *
     * 不能复用 [bitmapShader]：那一份已经作为 RuntimeShader 的 `content` 输入被后端持有，
     * 逐帧改写它的 local matrix 会污染折射采样。
     */
    private val opticalRegionShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(refractionBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // 与旧路径的 FILTER_BITMAP_FLAG 对齐：稳定底图是 0.25 倍采样，最近邻会在
            // 抑制区域露出明显色块。setFilterMode 是 API 33 才有的显式声明，31-32 仍依赖
            // opticalRegionPaint 的 FILTER_BITMAP_FLAG。
            if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        }
    }
    private val opticalRegionMatrix = Matrix()
    private val opticalRootToLocal = Matrix()
    private val opticalLocalToRoot = Matrix()
    private val stretchCoordinates = com.lumen.coacervation.engine.liquid.LiquidStretchSampling()
    private val opticalRegionPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = opticalRegionShader }
    }
    private var closed = false
    private var published = false

    val preparedByteCount: Long get() = bitmap.allocationByteCount.toLong() +
        if (opticalBitmap !== bitmap) opticalBitmap.allocationByteCount.toLong() else 0L

    /** Independent shader state over already published pixels; no shared View or session. */
    fun forkPublished(width: Int, height: Int): LiquidBackdropSource {
        check(published && !isRealtime && !bitmap.isRecycled && !opticalBitmap.isRecycled)
        return LiquidBackdropSource(bitmap, customAssetId, false, width, height,
            opticalBitmap, crispRefraction).also { it.published = true }
    }

    val isClosed: Boolean
        get() = closed

    /** 采样尺寸未变化时只更新窗口映射，不重新分配 Bitmap。 */
    fun updateFullSize(width: Int, height: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        require(width > 0 && height > 0) { "Backdrop dimensions must be positive" }
        fullWidth = width
        fullHeight = height
    }

    fun drawRoot(canvas: Canvas, bounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        rootPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, bounds, rootPaint)
    }

    /** 截图抑制区填入显示底图，避免停下后玻璃采到预模糊副本；画笔仅供截图线程使用。 */
    fun drawSuppressionBackdrop(canvas: Canvas, bounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        suppressionBackdropPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, bounds, suppressionBackdropPaint)
    }

    private val suppressionBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 在表面本地坐标中按根坐标取一块光学采样区，供外部窗口（Dialog）里的玻璃表面使用：
     * 那些表面折射不到自己窗口的内容，实时截屏里对应位置只有未压暗的锐利底页，
     * 改采已过滤副本才不会把底页文字透进面板。它与截图抑制、边缘溶解各持独立 Shader，
     * 不触碰折射后端持有的 [bitmapShader]。
     *
     * @param rootOffsetX/rootOffsetY 表面在 backdrop 全幅坐标中的位置（根视图像素）。
     */
    fun drawOpticalRegion(
        canvas: Canvas,
        localBounds: Rect,
        radiusPx: Float,
        rootOffsetX: Float,
        rootOffsetY: Float,
        alpha: Int,
        localToBackdrop: Matrix? = null,
        stretchSampling: FloatArray? = null
    ) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (localBounds.isEmpty || refractionWidth <= 0 || refractionHeight <= 0 ||
            fullWidth <= 0 || fullHeight <= 0
        ) return
        val scaleX = fullWidth.toFloat() / refractionWidth.toFloat()
        val scaleY = fullHeight.toFloat() / refractionHeight.toFloat()
        opticalRegionMatrix.setScale(scaleX, scaleY)
        stretchCoordinates.opticalTransform(localBounds, localToBackdrop, rootOffsetX, rootOffsetY,
            stretchSampling, opticalLocalToRoot)
        if (opticalLocalToRoot.invert(opticalRootToLocal)) {
            opticalRegionMatrix.postConcat(opticalRootToLocal)
        } else {
            opticalRegionMatrix.postTranslate(-rootOffsetX, -rootOffsetY)
        }
        opticalRegionShader.setLocalMatrix(opticalRegionMatrix)
        opticalRegionPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawRoundRect(
            localBounds.left.toFloat(), localBounds.top.toFloat(),
            localBounds.right.toFloat(), localBounds.bottom.toFloat(),
            radiusPx, radiusPx, opticalRegionPaint
        )
    }

    /**
     * 按根坐标把**可见根背景**（[bitmap]，与 [drawRoot] 同一张）画进 [bounds]，
     * 逐像素乘以 [alphaMask] 的 alpha 再乘 [alpha]。供滚动边缘溶解把内容"溶回"窗口底图：
     * 画的必须与根背景逐像素一致，否则溶解区会露出一块色差。
     *
     * 独立的 Shader/Matrix：[bitmapShader] 被折射后端持有，[opticalRegionShader] 属于光学直采。
     * 同一 Shader 在多个宿主间逐次改 local matrix 是安全的——HWUI 在录制那一刻快照原生实例。
     */
    fun drawPresentationRegion(
        canvas: Canvas,
        bounds: RectF,
        rootOffsetX: Float,
        rootOffsetY: Float,
        alphaMask: Shader?,
        alpha: Int
    ) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0 || fullWidth <= 0 || fullHeight <= 0) return
        presentationMatrix.setScale(
            fullWidth.toFloat() / bitmap.width.toFloat(),
            fullHeight.toFloat() / bitmap.height.toFloat()
        )
        presentationMatrix.postTranslate(-rootOffsetX, -rootOffsetY)
        presentationShader.setLocalMatrix(presentationMatrix)
        presentationPaint.shader = if (alphaMask == null) presentationShader else {
            // ComposeShader 在子 Shader 的 local matrix 变化后会自行重建原生实例（API 26+），
            // 同一遮罩只需要组合一次。DST_IN：底图 × 遮罩 alpha。
            if (composedMask !== alphaMask) {
                composedShader = ComposeShader(presentationShader, alphaMask, PorterDuff.Mode.DST_IN)
                composedMask = alphaMask
            }
            composedShader
        }
        presentationPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawRect(bounds, presentationPaint)
    }

    private val presentationShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (AndroidVersion.isAtLeast(AndroidVersion.T)) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }
    private val presentationMatrix = Matrix()
    private val presentationPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
    private var composedMask: Shader? = null
    private var composedShader: ComposeShader? = null

    /** Commit before binding this source to a renderer or exposing it to a window draw. */
    fun markPublished() {
        check(!closed) { "Liquid backdrop source is closed" }
        if (published) return
        published = true
        bitmap.prepareToDraw()
        if (opticalBitmap !== bitmap) opticalBitmap.prepareToDraw()
    }

    /** Worker results that never reached a display list can release both owned images immediately. */
    fun discardUnpublished() {
        if (closed) return
        check(!published && !isRealtime) { "Cannot recycle a published or realtime backdrop source" }
        closed = true
        if (opticalBitmap !== bitmap && !opticalBitmap.isRecycled) opticalBitmap.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    /**
     * 抑制缓存分配失败时直接以显示底图填充路径，与缓存路径同源同滤波。
     *
     * 旧实现是 `clipPath` + 全图 `drawBitmap`：即使裁剪把光栅化限制在玻璃区域，Skia 仍要为
     * 整张目标位图建立一次抗锯齿裁剪掩码并做 save/restore。改成一次带 Shader 的路径填充后
     * 不再分配裁剪掩码，也不在反馈抑制的逐帧路径执行 CPU 模糊。
     */
    fun drawSuppressionBackdropMasked(canvas: Canvas, path: Path, dstBounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (dstBounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        suppressionMatrix.setScale(
            dstBounds.width().toFloat() / bitmap.width.toFloat(),
            dstBounds.height().toFloat() / bitmap.height.toFloat()
        )
        suppressionMatrix.postTranslate(dstBounds.left.toFloat(), dstBounds.top.toFloat())
        suppressionShader.setLocalMatrix(suppressionMatrix)
        suppressionPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(path, suppressionPaint)
    }

    private val suppressionShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (AndroidVersion.isAtLeast(AndroidVersion.T)) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }
    private val suppressionMatrix = Matrix()
    private val suppressionPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = suppressionShader }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 已提交到硬件 display list 的 Bitmap 不能用 recycle() 充当 GPU fence。断开 renderer、
        // Shader 与 source 引用后交给运行时回收，旧 display list 的 native 引用可安全完成重放。
    }

    companion object {
        /**
         * 未设自定义图时的稳定 underlay：与标准磨砂皮肤共用 [AmbientBackdropScene] 配方，
         * 两种材质下用户看到的是同一个 Monet 氛围背景。
         *
         * 自动背景只有低频渐变；去掉颗粒后，显示与光学采样共用同一张位图，
         * 不再读取像素或复制底图。自定义图片仍保留独立的模糊采样副本。
         */
        fun create(
            palette: MonetColors,
            fullWidth: Int,
            fullHeight: Int
        ): LiquidBackdropSource {
            val size = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
            val bitmap = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val dark = ColorUtils.calculateLuminance(palette.background) < .5
                AmbientBackdropScene.paint(canvas, palette, size.width, size.height, dark)

                // 顶部与底部精确回到 background，避免状态栏/导航栏出现颜色接缝。
                val seamPaint = Paint(
                    Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
                )
                val transparentBackground = ColorUtils.setAlphaComponent(palette.background, 0)
                seamPaint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    size.height.toFloat(),
                    intArrayOf(
                        palette.background,
                        transparentBackground,
                        transparentBackground,
                        palette.background
                    ),
                    floatArrayOf(0f, 0.10f, 0.90f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(
                    0f,
                    0f,
                    size.width.toFloat(),
                    size.height.toFloat(),
                    seamPaint
                )

                bitmap.prepareToDraw()
                return LiquidBackdropSource(
                    bitmap = bitmap,
                    customAssetId = null,
                    isRealtime = false,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight
                )
            } catch (throwable: Throwable) {
                bitmap.recycle()
                throw throwable
            }
        }

        /**
         * Called on the existing background loader after bounded image decoding. One optical copy
         * is shared by every surface; the caller keeps ownership of [bitmap] if construction fails.
         */
        @WorkerThread
        fun fromCustomBitmap(
            bitmap: Bitmap,
            assetId: String,
            fullWidth: Int,
            fullHeight: Int,
            density: Float,
            crispRefraction: Boolean
        ): LiquidBackdropSource {
            check(Looper.myLooper() !== Looper.getMainLooper()) {
                "Custom optical backdrop must be prepared off the main thread"
            }
            require(!bitmap.isRecycled) { "Custom backdrop bitmap is recycled" }
            require(assetId.isNotBlank()) { "Custom backdrop asset id is blank" }
            val expected = LiquidBackdropSizingPolicy.resolvePresentation(fullWidth, fullHeight)
            require(bitmap.width == expected.width && bitmap.height == expected.height) {
                "Custom backdrop bitmap does not match the bounded presentation size"
            }
            // 显示图保留原生像素；仅缩小光学副本，避免全分辨率 CPU 模糊。
            val sample = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
            val optical = createBitmap(sample.width, sample.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(optical).drawBitmap(bitmap, null, Rect(0, 0, sample.width, sample.height),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
                val pixels = IntArray(sample.width * sample.height)
                optical.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                val softened = LiquidOpticalSamplingPolicy.soften(pixels, sample.width, sample.height,
                    fullWidth, density)
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Backdrop replaced")
                optical.setPixels(softened, 0, sample.width, 0, 0, sample.width, sample.height)
                return LiquidBackdropSource(
                    bitmap = bitmap,
                    customAssetId = assetId,
                    isRealtime = false,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight,
                    opticalBitmap = optical,
                    crispRefraction = crispRefraction
                )
            } catch (failure: Throwable) {
                optical.recycle()
                throw failure
            }
        }

        /** 由 PixelCopy 三缓冲拥有的可变窗口截图；source 只建立长期复用的采样 Shader。 */
        fun fromRealtimeBitmap(
            bitmap: Bitmap,
            fullWidth: Int,
            fullHeight: Int
        ): LiquidBackdropSource {
            require(!bitmap.isRecycled && bitmap.isMutable) {
                "Realtime backdrop bitmap must be mutable and available"
            }
            require(fullWidth > 0 && fullHeight > 0) {
                "Realtime backdrop dimensions must be positive"
            }
            return LiquidBackdropSource(
                bitmap = bitmap,
                customAssetId = null,
                isRealtime = true,
                fullWidth = fullWidth,
                fullHeight = fullHeight
            )
        }
    }
}
