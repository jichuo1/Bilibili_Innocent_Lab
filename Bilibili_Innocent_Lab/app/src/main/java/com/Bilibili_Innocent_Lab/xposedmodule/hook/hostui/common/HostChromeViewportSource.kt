package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.SamplingMatrixMath
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import kotlin.math.ceil
import kotlin.math.floor

/** 顶/底栏各自只录制实际取样范围；探针仍读取原生前景，保持原来的对比度补偿语义。 */
internal class HostChromeViewportSource(
    private val source: HostChromeContentSource,
    private val compositeView: Boolean = false,
    private val regions: (consumer: (View, Float, Float, Float) -> Unit) -> Unit
) : LumenVersionedContentSource, AutoCloseable {
    private val content = source.coordinateView as ViewGroup
    // Prism's public entry accepts a View source. Keep its proxy outside the captured subtree.
    private val proxyOwner = if (compositeView) content.rootView as ViewGroup else content
    private val mapping = ViewSamplingMatrix()
    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val versionMatrices = ArrayList<FloatArray>()
    private val union = RectF()
    private val region = RectF()
    private val contentBounds = RectF()
    private var unsafe = false
    private var drawing = false
    override val coordinateView = object : View(content.context) {
        // OverlayViewGroup has no ordinary parent chain; its default isShown() is false.
        // The proxy has exactly the same lifecycle and visible region as its content owner.
        override fun isShown(): Boolean = content.isShown

        override fun onDraw(canvas: Canvas) {
            // Ordinary proxies serve the software legibility probe only. Prism's public View
            // source also records the composed scene on GPU, outside the captured subtree.
            if (!drawing && (compositeView || !canvas.isHardwareAccelerated))
                draw(canvas, foregroundOnly = !compositeView)
        }
    }.apply {
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        proxyOwner.overlay.add(this)
    }
    override val observeCoordinateViewInvalidations: Boolean get() = false
    override val sourceEpoch: Long get() = source.sourceEpoch
    override val excludesSurfaces: Boolean get() = source.excludesSurfaces
    override val availability: LumenSourceAvailability get() =
        if (unsafe) LumenSourceAvailability.PROHIBITED else source.availability
    override val contentVersion: Long
        get() = mapping.withAncestorMemo {
            var version = source.contentVersion
            version = version * 31 + coordinateView.left
            version = version * 31 + coordinateView.top
            version = version * 31 + coordinateView.width
            version = version * 31 + coordinateView.height
            fun mix(value: Long) { version = version * 31 + value }
            fun visit(view: View, transform: FloatArray, depth: Int) {
                // GPU ViewGroup invalidation can leave only the descendant dirty. A retained
                // RenderNode sees that change, but the software bitmap needs a new recording.
                // Ignore the invisible proxy and branches clipped outside the sampling halo.
                if (depth >= 128 || view.visibility != View.VISIBLE || view.alpha <= 0f) return
                if (!SamplingMatrixMath.isFinite(transform)) return
                if (transform[0] == 1f && transform[1] == 0f && transform[3] == 0f && transform[4] == 1f &&
                    transform[6] == 0f && transform[7] == 0f && transform[8] == 1f) {
                    region.set(transform[2], transform[5], transform[2] + view.width, transform[5] + view.height)
                } else {
                    matrix.setValues(transform)
                    region.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                    matrix.mapRect(region)
                }
                val intersects = region.intersects(0f, 0f, coordinateView.width.toFloat(), coordinateView.height.toFloat())
                if (!intersects && (view !is ViewGroup || view.clipChildren)) return
                mix(System.identityHashCode(view).toLong())
                mix(view.width.toLong()); mix(view.height.toLong())
                mix(view.scrollX.toLong()); mix(view.scrollY.toLong()); mix(view.alpha.toBits().toLong())
                mix(if (view.isDirty) view.drawingTime else -1L)
                // This also versions owner/child property movement inside a fixed Window crop.
                for (value in transform) mix(value.toBits().toLong())
                if (view is ViewGroup && depth + 1 < 128) {
                    // The target is fixed throughout this read. Compose each child from its
                    // already resolved parent instead of finding/inverting Window matrices
                    // for every descendant. Reuse one scratch matrix per depth, never per View.
                    if (versionMatrices.size <= depth) versionMatrices.add(FloatArray(9))
                    val childMatrix = versionMatrices[depth]
                    for (index in 0 until view.childCount) {
                        val child = view.getChildAt(index)
                        if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
                        child.matrix.getValues(childMatrix)
                        SamplingMatrixMath.translateAfter(childMatrix, (child.left.toLong() - view.scrollX).toFloat(),
                            (child.top.toLong() - view.scrollY).toFloat())
                        SamplingMatrixMath.multiply(transform, childMatrix, childMatrix)
                        visit(child, childMatrix, depth + 1)
                    }
                }
            }
            // Background-only sources already declare all their inputs. Surface invalidations
            // must not wake them, and there is no foreground subtree to inspect.
            if (!source.excludesSurfaces && mapping.sourceToTarget(content, coordinateView, matrix)) {
                matrix.getValues(matrixValues)
                visit(content, matrixValues, 0)
            }
            version and Long.MAX_VALUE
        }

    fun sync(): Boolean {
        union.setEmpty()
        unsafe = false
        // OverlayViewGroup is attached to the Window but has no ordinary parent chain to
        // its content owner. Crop in its actual coordinate space, the same space the engine
        // sees for the proxy. Owner-local bounds lose ancestor offsets/transforms here.
        val coordinates = coordinateView.parent as? View
        regions { view, insetX, insetY, padding ->
            if (!source.excludesSurfaces) {
                var node: View? = view
                while (node != null) {
                    if (node === content) { unsafe = true; break }
                    node = node.parent as? View
                }
            }
            if (coordinates == null || !view.isShown || view.width <= 0 || view.height <= 0 ||
                !mapping.sourceToTarget(view, coordinates, matrix)) return@regions
            val x = insetX.coerceIn(0f, (view.width - 1f) / 2f)
            val y = insetY.coerceIn(0f, (view.height - 1f) / 2f)
            region.set(x - padding, y - padding, view.width - x + padding, view.height - y + padding)
            matrix.mapRect(region)
            union.union(region)
        }
        if (coordinates == null || !mapping.sourceToTarget(content, coordinates, matrix)) return false
        contentBounds.set(0f, 0f, content.width.toFloat(), content.height.toFloat())
        matrix.mapRect(contentBounds)
        if (union.isEmpty || !union.intersect(contentBounds)) union.set(contentBounds)
        val left = floor(union.left).toInt(); val top = floor(union.top).toInt()
        val right = ceil(union.right).toInt(); val bottom = ceil(union.bottom).toInt()
        val changed = coordinateView.left != left || coordinateView.top != top ||
            coordinateView.right != right || coordinateView.bottom != bottom
        if (changed) coordinateView.layout(left, top, right, bottom)
        return changed
    }

    override fun drawContent(canvas: Canvas) = draw(canvas, foregroundOnly = false)

    private fun draw(canvas: Canvas, foregroundOnly: Boolean) {
        if (drawing || unsafe || !mapping.sourceToTarget(content, coordinateView, matrix)) return
        drawing = true
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, coordinateView.width, coordinateView.height)
            canvas.concat(matrix)
            canvas.clipRect(0, 0, content.width, content.height)
            if (foregroundOnly) {
                canvas.translate(-content.scrollX.toFloat(), -content.scrollY.toFloat())
                content.draw(canvas)
            } else source.drawContent(canvas)
        } finally { canvas.restoreToCount(saved); drawing = false }
    }

    override fun close() { proxyOwner.overlay.remove(coordinateView) }
}
