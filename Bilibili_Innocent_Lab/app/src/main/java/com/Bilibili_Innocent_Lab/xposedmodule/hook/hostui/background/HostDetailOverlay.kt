package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.host.LumenContentSource
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceTarget
import java.lang.ref.WeakReference

/** 浮层底板先完整遮住底页，再绘制引擎磨砂；采样边缘的透明像素也不会露出底页文字。 */
internal class HostDetailOverlay(view: View, session: LumenSurfaceSession, options: LumenSurfaceOptions,
    source: LumenContentSource?) : Drawable(), AutoCloseable {
    private val view = WeakReference(view)
    private val original = view.background
    private val matrices = ViewSamplingMatrix()
    private val paint = Paint().apply { color = requireNotNull(options.color) }
    private var opacity = 255
    private val target = object : LumenSurfaceTarget {
        override val coordinateView: View get() = requireNotNull(this@HostDetailOverlay.view.get())
        override val width: Int get() = this@HostDetailOverlay.view.get()?.width ?: 0
        override val height: Int get() = this@HostDetailOverlay.view.get()?.height ?: 0
        override val isAttachedToWindow: Boolean get() = this@HostDetailOverlay.view.get()?.isAttachedToWindow == true
        override val isShown: Boolean get() = this@HostDetailOverlay.view.get()?.isShown == true
        override val alpha: Float get() = this@HostDetailOverlay.view.get()?.alpha ?: 0f
        override val layoutDirection: Int get() = this@HostDetailOverlay.view.get()?.layoutDirection ?: View.LAYOUT_DIRECTION_LTR
        override fun localToScreen(out: FloatArray): Boolean = HostThreadGuard.call("host.detail.overlay.matrix", false) {
            this@HostDetailOverlay.view.get()?.let { matrices.localToScreen(it, out) } == true
        }
        override fun invalidate() = HostThreadGuard.run("host.detail.overlay.invalidate") {
            invalidateSelf()
            this@HostDetailOverlay.view.get()?.invalidate()
        }
    }
    val binding = session.bindTarget(target, options, source)

    init { replaceBackground(view, this) }

    fun update(options: LumenSurfaceOptions) {
        paint.color = requireNotNull(options.color)
        binding.update(options)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) = HostThreadGuard.run("host.detail.overlay.draw") {
        val save = if (opacity < 255) canvas.saveLayerAlpha(bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(), opacity) else canvas.save()
        try {
            canvas.drawRect(bounds, paint)
            binding.draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun close() {
        binding.close()
        view.get()?.takeIf { it.background === this }?.let { replaceBackground(it, original) }
    }

    private fun replaceBackground(view: View, drawable: Drawable?) {
        val left = view.paddingLeft; val top = view.paddingTop
        val right = view.paddingRight; val bottom = view.paddingBottom
        view.background = drawable
        view.setPadding(left, top, right, bottom)
    }
}
