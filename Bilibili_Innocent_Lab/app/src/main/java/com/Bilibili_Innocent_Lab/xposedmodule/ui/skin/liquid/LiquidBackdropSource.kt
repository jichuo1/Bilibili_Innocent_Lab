package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import kotlin.math.roundToInt

/**
 * 每个 Activity 唯一的静态 Monet underlay/backdrop。
 *
 * 它不捕获 Window 或 View 树，因而不会递归采样已经绘制的 Liquid 表面。背景以稳定底色和
 * 两处低强度、超大径向色洗组成，避免旧三段对角渐变的明显分区；绘制路径只更新复用的
 * AGSL 坐标 uniform，并始终映射同一张静态位图。
 */
internal class LiquidBackdropSource private constructor(
    val bitmap: Bitmap,
    val customAssetId: String?,
    fullWidth: Int,
    fullHeight: Int
) : AutoCloseable {
    var fullWidth: Int = fullWidth
        private set
    var fullHeight: Int = fullHeight
        private set

    val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
                return LiquidBackdropSource(bitmap, null, fullWidth, fullHeight)
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
            return LiquidBackdropSource(bitmap, assetId, fullWidth, fullHeight)
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
