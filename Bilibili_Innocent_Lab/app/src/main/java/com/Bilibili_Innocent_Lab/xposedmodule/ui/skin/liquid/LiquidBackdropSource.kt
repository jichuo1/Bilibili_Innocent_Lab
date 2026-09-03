package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import kotlin.math.roundToInt

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
    fullHeight: Int
) : AutoCloseable {
    var fullWidth: Int = fullWidth
        private set
    var fullHeight: Int = fullHeight
        private set

    val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 反馈抑制专用的独立 Shader 与 Matrix。
     *
     * 不能复用 [bitmapShader]：那一份已经作为 RuntimeShader 的 `content` 输入被后端持有，
     * 逐帧改写它的 local matrix 会污染折射采样。
     */
    private val maskShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // 与旧路径的 FILTER_BITMAP_FLAG 对齐：稳定底图是 0.25 倍采样，最近邻会在
            // 抑制区域露出明显色块。setFilterMode 是 API 33 才有的显式声明，31-32 仍依赖
            // maskPaint 的 FILTER_BITMAP_FLAG。
            if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        }
    }
    private val maskMatrix = Matrix()
    private val maskPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = maskShader }
    }
    private var closed = false

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

    /**
     * 以本底图填充给定路径，几何映射与 `drawRoot(canvas, dstBounds, alpha)` 完全一致。
     *
     * 旧实现是 `clipPath` + 全图 `drawBitmap`：即使裁剪把光栅化限制在玻璃区域，Skia 仍要为
     * 整张目标位图建立一次抗锯齿裁剪掩码并做 save/restore。改成一次带 Shader 的路径填充后
     * 输出逐像素相同，但不再分配裁剪掩码。
     */
    fun drawRootMasked(canvas: Canvas, path: Path, dstBounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (dstBounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        maskMatrix.setScale(
            dstBounds.width().toFloat() / bitmap.width.toFloat(),
            dstBounds.height().toFloat() / bitmap.height.toFloat()
        )
        maskMatrix.postTranslate(dstBounds.left.toFloat(), dstBounds.top.toFloat())
        maskShader.setLocalMatrix(maskMatrix)
        maskPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(path, maskPaint)
    }

    override fun close() {
        if (closed) return
        closed = true
        // 已提交到硬件 display list 的 Bitmap 不能用 recycle() 充当 GPU fence。断开 renderer、
        // Shader 与 source 引用后交给运行时回收，旧 display list 的 native 引用可安全完成重放。
    }

    companion object {
        fun create(
            palette: MonetColors,
            tuning: LiquidVisualTuning,
            fullWidth: Int,
            fullHeight: Int
        ): LiquidBackdropSource {
            val size = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
            val bitmap = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(palette.background)
                val ambientPaint = Paint(
                    Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
                )
                drawAmbientWash(
                    canvas = canvas,
                    paint = ambientPaint,
                    color = palette.primary,
                    alpha = tuning.primaryWashAlpha,
                    centerX = -0.18f * size.width,
                    centerY = 0.24f * size.height,
                    radius = 1.25f * size.width
                )
                drawAmbientWash(
                    canvas = canvas,
                    paint = ambientPaint,
                    color = palette.secondary,
                    alpha = tuning.secondaryWashAlpha,
                    centerX = 1.12f * size.width,
                    centerY = 0.70f * size.height,
                    radius = 1.38f * size.width
                )

                // 顶部与底部精确回到 background，避免状态栏/导航栏出现颜色接缝。
                val transparentBackground = ColorUtils.setAlphaComponent(palette.background, 0)
                ambientPaint.shader = LinearGradient(
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
                    ambientPaint
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

        /** 后台解码完成的最终尺寸 Bitmap；调用成功后由 source 接管其生命周期。 */
        fun fromCustomBitmap(
            bitmap: Bitmap,
            assetId: String,
            fullWidth: Int,
            fullHeight: Int
        ): LiquidBackdropSource {
            require(!bitmap.isRecycled) { "Custom backdrop bitmap is recycled" }
            require(assetId.isNotBlank()) { "Custom backdrop asset id is blank" }
            val expected = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
            require(bitmap.width == expected.width && bitmap.height == expected.height) {
                "Custom backdrop bitmap does not match the bounded sample size"
            }
            bitmap.prepareToDraw()
            return LiquidBackdropSource(
                bitmap = bitmap,
                customAssetId = assetId,
                isRealtime = false,
                fullWidth = fullWidth,
                fullHeight = fullHeight
            )
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

        private fun drawAmbientWash(
            canvas: Canvas,
            paint: Paint,
            color: Int,
            alpha: Int,
            centerX: Float,
            centerY: Float,
            radius: Float
        ) {
            val centerAlpha = alpha.coerceIn(0, 255)
            val middleAlpha = (centerAlpha * 0.38f).roundToInt()
            paint.shader = RadialGradient(
                centerX,
                centerY,
                radius.coerceAtLeast(1f),
                intArrayOf(
                    ColorUtils.setAlphaComponent(color, centerAlpha),
                    ColorUtils.setAlphaComponent(color, middleAlpha),
                    ColorUtils.setAlphaComponent(color, Color.TRANSPARENT)
                ),
                floatArrayOf(0f, 0.54f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
        }
    }
}
