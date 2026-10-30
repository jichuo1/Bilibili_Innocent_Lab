package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostLatestWorkQueue
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.lumen.coacervation.engine.host.LumenMicaBackdrop
import com.lumen.coacervation.engine.host.LumenMicaImage
import com.lumen.coacervation.engine.host.LumenMicaLayer
import com.lumen.coacervation.engine.host.LumenMicaOptions
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** 一个视口共用基础层和卡片层。只读取模块静态壁纸配方，不截取页面或读取系统壁纸。 */
internal class HostCardMicaField(private val owner: View, private val field: View, palette: LumenPalette,
    private val changed: () -> Unit) : AutoCloseable {
    private val backdrop = LumenMicaBackdrop(field, palette, LumenMicaOptions())
    private var layer = backdrop.layerBackground(LumenMicaLayer.CARD, 0f)
    private var source: HostBackgroundDrawable.MicaSource? = null
    private var generation = 0L
    private var closed = false
    private val preparationsQueue = HostLatestWorkQueue { task ->
        worker.execute(HostThreadGuard.runnable("host.card.mica.worker") { task.run() })
    }
    internal var preparations = 0L
        private set
    val drawable = object : Drawable() {
        override fun draw(canvas: Canvas) { field.background?.draw(canvas); layer.draw(canvas) }
        override fun onBoundsChange(bounds: Rect) { field.background?.bounds = bounds; layer.bounds = bounds }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    fun updatePalette(palette: LumenPalette) {
        backdrop.updatePalette(palette)
        layer = backdrop.layerBackground(LumenMicaLayer.CARD, 0f).also { it.bounds = drawable.bounds }
        changed()
    }

    fun refresh() {
        if (closed || owner.width <= 0 || owner.height <= 0) return
        var view: View? = owner
        var next: HostBackgroundDrawable.MicaSource? = null
        var depth = 0
        while (view != null && depth++ < 128) {
            val background = view.background
            if (background is HostBackgroundDrawable) { next = background.micaSource(); break }
            view = view.parent as? View
        }
        if (source == next) return
        source = next
        val request = ++generation
        // 新背景未准备好时不能继续显示上一张壁纸。
        backdrop.setImage(null)
        changed()
        val input = next ?: return
        val scale = 96f / maxOf(owner.width, owner.height)
        val width = (owner.width * scale).toInt().coerceAtLeast(1)
        val height = (owner.height * scale).toInt().coerceAtLeast(1)
        val weak = WeakReference(this)
        preparationsQueue.submit {
            val image = runCatching {
                val bitmap = input.snapshot(width, height)
                try { LumenMicaImage.prepare(bitmap) } finally { bitmap.recycle() }
            }.getOrNull()
            main.post(HostThreadGuard.runnable("host.card.mica.publish") {
                weak.get()?.takeIf { !it.closed && it.generation == request }?.let {
                    it.preparations++
                    it.backdrop.setImage(image)
                    it.changed()
                }
            })
        }
    }

    fun diagnostics() = backdrop.diagnostics()

    override fun close() {
        if (closed) return
        closed = true; generation++
        preparationsQueue.clear()
        source = null
        backdrop.close()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "host-card-mica").apply { isDaemon = true } }
    }
}
