package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidMotionSurfaceFrameProvider
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 气泡独立的语义表面：Drawable 的 callback 直接归属于真实 View，保留 Liquid 坐标与刷新登记。
 *
 * 帧边界与可选小角路径都位于本 View 的局部坐标中；不缩放正文、不创建额外皮肤会话。
 * 每个实例必须使用调用方单独创建的背景 Drawable，不能与另一个表面共享 callback。
 */
@SuppressLint("ViewConstructor")
internal class BubbleSkinSurfaceView(
    context: Context,
    surfaceBackground: Drawable,
    private val fallbackColor: Int
) : View(context), LiquidMotionSurfaceFrameProvider {

    private val frameBounds = RectF()
    private val frameClip = Path()
    private val fadeBounds = RectF()
    private val canvasClipBounds = Rect()
    private var frameRadiusPx = 0f
    private var frameOpacity = 0
    private var hasFrame = false
    private var hasClip = false
    private val surface = surfaceBackground

    init {
        // 显式归属本 View，而非 Drawable 包装器；onDraw 负责有界绘制，保留 View.draw 契约。
        surface.callback = this
        surface.alpha = 255
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    fun updateFrame(bounds: RectF, radiusPx: Float, opacity: Float, clip: Path? = null) {
        if (!bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite() || bounds.isEmpty ||
            !radiusPx.isFinite() || !opacity.isFinite()
        ) {
            clearFrame()
            return
        }
        val radius = radiusPx.coerceIn(0f, minOf(bounds.width(), bounds.height()) * 0.5f)
        val alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        // 非空 Path 可被调用方原地更新，不能只比较引用后跳过复制。
        if (hasFrame && !hasClip && clip == null && frameBounds == bounds &&
            frameRadiusPx == radius && frameOpacity == alpha
        ) return

        frameBounds.set(bounds)
        frameRadiusPx = radius
        frameOpacity = alpha
        hasFrame = true
        hasClip = clip != null
        if (clip != null) frameClip.set(clip) else frameClip.rewind()
        surface.let { surface ->
            // Liquid 的 Drawable alpha 只影响染色/描边，不影响折射底图；统一在有界层里淡化。
            if (surface.alpha != 255) surface.alpha = 255
            if (surface is GradientDrawable && surface.cornerRadius != radius) {
                surface.cornerRadius = radius
            }
        }
        invalidate()
    }

    fun clearFrame() {
        if (!hasFrame) return
        hasFrame = false
        hasClip = false
        frameBounds.setEmpty()
        frameClip.rewind()
        frameRadiusPx = 0f
        frameOpacity = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasFrame || frameOpacity == 0 || (hasClip && frameClip.isEmpty)) return
        // View.draw 保留框架行为，但不自动铺满背景；Drawable callback 显式维护为 this。
        // Liquid 用同一帧 provider 读取精确边界，Material 使用以下整数包围盒。
        val left = floor(frameBounds.left).toInt()
        val top = floor(frameBounds.top).toInt()
        val right = ceil(frameBounds.right).toInt()
        val bottom = ceil(frameBounds.bottom).toInt()
        val current = surface.bounds
        if (current.left != left || current.top != top || current.right != right || current.bottom != bottom) {
            surface.setBounds(left, top, right, bottom)
        }
        if (!hasClip && frameOpacity == 255) {
            surface.draw(canvas)
            return
        }
        val saved = if (frameOpacity < 255) {
            // 只在小气泡与图标交接时建立局部图层；稳定展开态无中间层。
            // 不使用整窗 View.alpha，且同时受表面、本 View 和当前 Canvas 裁剪限制。
            if (!canvas.getClipBounds(canvasClipBounds)) return
            fadeBounds.set(frameBounds)
            if (!fadeBounds.intersect(0f, 0f, width.toFloat(), height.toFloat()) ||
                !fadeBounds.intersect(
                    canvasClipBounds.left.toFloat(), canvasClipBounds.top.toFloat(),
                    canvasClipBounds.right.toFloat(), canvasClipBounds.bottom.toFloat()
                )
            ) return
            canvas.saveLayerAlpha(
                fadeBounds.left, fadeBounds.top, fadeBounds.right, fadeBounds.bottom, frameOpacity
            )
        } else canvas.save()
        try {
            if (frameOpacity < 255) canvas.clipRect(fadeBounds)
            if (hasClip) canvas.clipPath(frameClip)
            surface.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === surface || super.verifyDrawable(who)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        surface.callback = this
    }

    override fun onDetachedFromWindow() {
        unscheduleDrawable(surface)
        surface.callback = null
        super.onDetachedFromWindow()
    }

    override fun copyLiquidMotionBounds(outBounds: RectF) {
        outBounds.set(frameBounds)
    }

    override fun liquidMotionCornerRadiusPx(): Float = frameRadiusPx

    override fun liquidMotionFallbackColor(): Int = fallbackColor
}
