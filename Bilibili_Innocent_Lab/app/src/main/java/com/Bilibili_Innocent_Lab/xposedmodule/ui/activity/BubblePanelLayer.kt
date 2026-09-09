package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.withSave
import com.highcapable.betterandroid.ui.extension.view.child

/** 三个顶栏气泡专用：玻璃表面、原生内容、图标轮廓独立，不缩放文字或改变布局。 */
@SuppressLint("ViewConstructor")
internal class BubblePanelLayer(
    context: Context,
    private val content: LinearLayout,
    source: ImageView?,
    bodyBackground: Drawable,
    tailBackground: Drawable,
    private val color: Int,
    private val cornerRadius: Float,
    private val tailHeight: Float,
    private val tailHalfWidth: Float
) : FrameLayout(context) {
    private class Row(val view: View) {
        val alpha = view.alpha
        val x = view.translationX
        val y = view.translationY
        fun restore() {
            view.alpha = alpha
            view.translationX = x
            view.translationY = y
        }
    }

    private val rows = Array(content.childCount) { Row(content.child(it)) }
    private val icon = source?.let(::BubbleIconProxy)
    private val body = BubbleSkinSurfaceView(context, bodyBackground, color)
    private val tail = BubbleSkinSurfaceView(context, tailBackground, color)
    private val sourceBounds = RectF()
    private val capturedSourceBounds = RectF()
    private val frameBounds = RectF()
    private val bodyBounds = RectF()
    private val tailBounds = RectF()
    private val tailPath = Path()
    private var placement: BubblePlacement? = null
    private var progress = 0f
    private var entryShape = true
    private var prepared = false
    private val rowTravel = 14f * resources.displayMetrics.density
    private val overlap = resources.displayMetrics.density

    private val backdrop = object : FrameLayout(context) {
        private val boundedLayer = RectF()
        private val maskBlend = Paint().apply { this.color = Color.WHITE }
        private val maskComposite = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        override fun dispatchDraw(canvas: Canvas) {
            val mix = BubbleLayerMotionSpec.contourMix(progress)
            if (mix <= 0f || icon?.hasSnapshot != true || frameBounds.isEmpty ||
                BubbleLayerMotionSpec.surfaceOpacity(progress) <= 0f) {
                super.dispatchDraw(canvas)
                return
            }
            boundedLayer.set(frameBounds)
            if (!boundedLayer.intersect(0f, 0f, width.toFloat(), height.toFloat())) return
            // 仅收拢到图标附近的小矩形才融合透明轮廓；稳定面板完全不建立中间层。
            val surfaceSave = canvas.saveLayer(boundedLayer, null)
            try {
                super.dispatchDraw(canvas)
                val maskSave = canvas.saveLayer(boundedLayer, maskComposite)
                try {
                    maskBlend.alpha = ((1f - mix) * 255f).toInt()
                    canvas.drawRect(boundedLayer, maskBlend)
                    icon.drawMask(canvas)
                } finally { canvas.restoreToCount(maskSave) }
            } finally { canvas.restoreToCount(surfaceSave) }
        }
    }

    private val viewport = object : FrameLayout(context) {
        val clip = Path()
        var blocked = true
            set(value) {
                field = value
                isClickable = value
                importantForAccessibility = if (value) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    else IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }

        override fun dispatchDraw(canvas: Canvas) {
            if (clip.isEmpty) return
            canvas.withSave {
                clipPath(clip)
                super.dispatchDraw(this)
            }
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = blocked
    }

    private val iconView = object : View(context) {
        override fun onDraw(canvas: Canvas) { icon?.drawIcon(canvas) }
        override fun hasOverlappingRendering(): Boolean = false
    }

    init {
        clipChildren = false
        clipToPadding = false
        backdrop.clipChildren = false
        backdrop.addView(body, fullSize())
        backdrop.addView(tail, fullSize())
        viewport.clipChildren = false
        viewport.addView(content)
        viewport.blocked = true
        iconView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(backdrop, fullSize())
        addView(viewport, fullSize())
        addView(iconView, fullSize())
    }

    private fun fullSize() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    fun setContentLayoutParams(params: LayoutParams) { content.layoutParams = params }
    fun setPlacement(value: BubblePlacement) { placement = value }
    fun setAnchor(value: SettingsBackupMotionRect) {
        sourceBounds.set(value.left, value.top, value.right, value.bottom)
    }

    fun prepare() {
        if (prepared) return
        prepared = true
        icon?.prepare(this)
        if (icon?.hasSnapshot == true) {
            icon.copySourceBounds(capturedSourceBounds)
            if (!capturedSourceBounds.isEmpty) sourceBounds.set(capturedSourceBounds)
        }
        content.scaleX = 1f
        content.scaleY = 1f
        content.alpha = 1f
        viewport.blocked = true
    }

    fun applyFrame(value: Float, entering: Boolean) {
        if (!prepared) return
        progress = value.coerceIn(0f, 1f)
        entryShape = entering
        viewport.blocked = true
        val p = placement ?: return
        val sx = BubbleMotionSpec.scaleX(progress, entryShape)
        val sy = BubbleMotionSpec.scaleY(progress, entryShape)
        frameBounds.set(
            lerp(sourceBounds.left, content.left.toFloat(), sx),
            lerp(sourceBounds.top, content.top.toFloat(), sy),
            lerp(sourceBounds.right, content.right.toFloat(), sx),
            lerp(sourceBounds.bottom, content.bottom.toFloat(), sy)
        )
        val shape = smooth(.12f, .65f, progress)
        val height = tailHeight * shape
        val radius = lerp(minOf(sourceBounds.width(), sourceBounds.height()) / 2f, cornerRadius, sy)
            .coerceAtMost(minOf(frameBounds.width(), frameBounds.height()) / 2f).coerceAtLeast(0f)
        bodyBounds.set(frameBounds)
        if (p.tailEdge == BubbleTailEdge.TOP) bodyBounds.top += height else bodyBounds.bottom -= height
        val opacity = BubbleLayerMotionSpec.surfaceOpacity(progress)
        body.updateFrame(bodyBounds, radius, opacity)
        tailPath.rewind()
        if (height > .5f && frameBounds.width() > 0f) {
            val widthRatio = frameBounds.width() / content.width.coerceAtLeast(1)
            val tipX = frameBounds.left + p.tailCenterX * widthRatio
            val baseX = frameBounds.left + p.tailBaseCenterX * widthRatio
            val half = tailHalfWidth * widthRatio * shape
            val edgeY = if (p.tailEdge == BubbleTailEdge.TOP) bodyBounds.top else bodyBounds.bottom
            val tipY = if (p.tailEdge == BubbleTailEdge.TOP) frameBounds.top else frameBounds.bottom
            val baseY = edgeY + if (p.tailEdge == BubbleTailEdge.TOP) overlap else -overlap
            tailPath.moveTo(baseX - half, baseY)
            tailPath.quadTo(baseX - half * .4f, edgeY, tipX, tipY)
            tailPath.quadTo(baseX + half * .4f, edgeY, baseX + half, baseY)
            tailPath.close()
            tailPath.computeBounds(tailBounds, true)
            tail.updateFrame(tailBounds, 0f, opacity, tailPath)
        } else tail.clearFrame()

        viewport.clip.rewind()
        viewport.clip.addRoundRect(bodyBounds, radius, radius, Path.Direction.CW)
        for (index in rows.indices) {
            val row = rows[index]
            val fraction = BubbleLayerMotionSpec.contentFraction(progress, index, rows.size)
            row.view.alpha = row.alpha * fraction
            row.view.translationX = row.x
            row.view.translationY = row.y - rowTravel * (1f - fraction)
        }
        icon?.updateFrame(frameBounds, progress)
        backdrop.invalidate()
        viewport.invalidate()
        iconView.invalidate()
    }

    fun settleExpanded() {
        prepare()
        applyFrame(1f, false)
        rows.forEach(Row::restore)
        viewport.blocked = false
        icon?.settleExpanded()
        iconView.invalidate()
    }

    fun dispose() {
        rows.forEach(Row::restore)
        content.alpha = 1f
        content.scaleX = 1f
        content.scaleY = 1f
        viewport.blocked = false
        icon?.dispose()
        body.clearFrame()
        tail.clearFrame()
        prepared = false
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction
    private fun smooth(start: Float, end: Float, value: Float): Float {
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
