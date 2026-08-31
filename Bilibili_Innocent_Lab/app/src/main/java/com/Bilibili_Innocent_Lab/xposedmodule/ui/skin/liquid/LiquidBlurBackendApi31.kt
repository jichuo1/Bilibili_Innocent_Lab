package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend

/** API 31+ 独立 backdrop 模糊；RenderEffect 只作用于共享背景 RenderNode。 */
@RequiresApi(31)
internal class LiquidBlurBackendApi31(
    private val blurRadiusPx: Float
) : LiquidBackendDriver {
    override val backend = LiquidRenderBackend.BLUR
    override val requiresBackdrop = true

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipBounds = RectF()
    private val clipPath = Path()
    private var renderNode: RenderNode? = null
    private var blurEffect: RenderEffect? = null
    private var backdropScaleX = 1f
    private var backdropScaleY = 1f
    private var sampledBlurRadius = Float.NaN

    override fun bindBackdrop(source: LiquidBackdropSource) {
        check(!source.isClosed) { "Cannot bind a closed Liquid backdrop" }
        val node = renderNode ?: RenderNode("BilibiliInnocentLab-LiquidBackdrop")
        node.discardDisplayList()
        check(node.setPosition(0, 0, source.bitmap.width, source.bitmap.height)) {
            "Unable to position Liquid blur RenderNode"
        }
        val recordingCanvas = node.beginRecording(source.bitmap.width, source.bitmap.height)
        try {
            recordingCanvas.drawBitmap(source.bitmap, 0f, 0f, bitmapPaint)
        } finally {
            node.endRecording()
        }
        backdropScaleX = source.fullWidth.toFloat() / source.bitmap.width.toFloat()
        backdropScaleY = source.fullHeight.toFloat() / source.bitmap.height.toFloat()
        val nextBlurRadius = (
            blurRadiusPx / maxOf(backdropScaleX, backdropScaleY)
            ).coerceAtLeast(0.1f)
        if (blurEffect == null || sampledBlurRadius != nextBlurRadius) {
            val effect = RenderEffect.createBlurEffect(
                nextBlurRadius,
                nextBlurRadius,
                Shader.TileMode.CLAMP
            )
            node.setRenderEffect(effect)
            blurEffect = effect
            sampledBlurRadius = nextBlurRadius
        }
        renderNode = node
    }

    override fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int,
        opticalIntensity: Float
    ) {
        val node = checkNotNull(renderNode) { "Liquid blur backdrop is not bound" }
        clipBounds.set(bounds)
        clipPath.reset()
        clipPath.addRoundRect(clipBounds, radiusPx, radiusPx, Path.Direction.CW)
        val saveCount = canvas.save()
        try {
            canvas.clipPath(clipPath)
            canvas.translate(
                bounds.left.toFloat() - viewX.toFloat(),
                bounds.top.toFloat() - viewY.toFloat()
            )
            canvas.scale(backdropScaleX, backdropScaleY)
            canvas.drawRenderNode(node)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun close() {
        renderNode?.setRenderEffect(null)
        renderNode?.discardDisplayList()
        renderNode = null
        blurEffect = null
        sampledBlurRadius = Float.NaN
    }
}
