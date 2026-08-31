package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState

/** 激活状态卡的语义色与边缘光晕规格；纯色只参与边缘，不覆盖 Liquid 玻璃主体。 */
internal object ActivationCardVisualSpec {
    const val CORNER_RADIUS_DP = 15f
    const val OUTER_GLOW_WIDTH_DP = 7f
    const val INNER_GLOW_WIDTH_DP = 4f
    const val BORDER_WIDTH_DP = 1.4f
    const val OUTER_GLOW_ALPHA = 0.10f
    const val INNER_GLOW_ALPHA = 0.22f
    const val BORDER_ALPHA = 0.78f

    fun tone(displayState: ActivationDisplayState): DiagnosticStatusTone = when (displayState) {
        ActivationDisplayState.ACTIVE_LSPOSED,
        ActivationDisplayState.ACTIVE_NPATCH -> DiagnosticStatusTone.OK
        ActivationDisplayState.CHECKING -> DiagnosticStatusTone.INFO
        ActivationDisplayState.UNAVAILABLE -> DiagnosticStatusTone.ACTION_REQUIRED
    }
}

/**
 * 在 View 前景内绘制三层由淡到实的状态色描边，形成有界、无需离屏模糊的柔和边缘光晕。
 * 所有描边都向内收，既不会被 View 边界裁掉，也不会遮住卡片中央文字。
 */
internal class ActivationCardAccentDrawable(
    accentColor: Int,
    density: Float
) : Drawable() {
    private val cornerRadius = ActivationCardVisualSpec.CORNER_RADIUS_DP * density
    private val layers = listOf(
        strokeLayer(
            accentColor,
            ActivationCardVisualSpec.OUTER_GLOW_ALPHA,
            ActivationCardVisualSpec.OUTER_GLOW_WIDTH_DP * density
        ),
        strokeLayer(
            accentColor,
            ActivationCardVisualSpec.INNER_GLOW_ALPHA,
            ActivationCardVisualSpec.INNER_GLOW_WIDTH_DP * density
        ),
        strokeLayer(
            accentColor,
            ActivationCardVisualSpec.BORDER_ALPHA,
            ActivationCardVisualSpec.BORDER_WIDTH_DP * density
        )
    )
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        layers.forEach { layer ->
            val paint = layer.paint
            val inset = paint.strokeWidth * 0.5f
            if (bounds.width() <= paint.strokeWidth || bounds.height() <= paint.strokeWidth) {
                return@forEach
            }
            paint.alpha = (layer.baseAlpha * drawableAlpha / 255f)
                .toInt()
                .coerceIn(0, 255)
            canvas.drawRoundRect(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset,
                (cornerRadius - inset).coerceAtLeast(0f),
                (cornerRadius - inset).coerceAtLeast(0f),
                paint
            )
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        layers.forEach { it.paint.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun strokeLayer(color: Int, alpha: Float, widthPx: Float): StrokeLayer {
        val baseAlpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return StrokeLayer(
            paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = widthPx.coerceAtLeast(1f)
                this.color = ColorUtils.setAlphaComponent(color, 0xFF)
                this.alpha = baseAlpha
            },
            baseAlpha = baseAlpha
        )
    }

    private data class StrokeLayer(val paint: Paint, val baseAlpha: Int)
}
