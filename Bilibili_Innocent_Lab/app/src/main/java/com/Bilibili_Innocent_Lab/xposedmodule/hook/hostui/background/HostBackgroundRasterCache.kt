package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import io.github.jichuo1.meguviz.drawable.android.BitmapWeigher
import io.github.jichuo1.meguviz.drawable.cache.FlightCache
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 按实际像素尺寸合成，缓存有界；尺寸动画期间继续用原始绘制，不拉伸旧缓存。 */
internal object HostBackgroundRasterCache {
    data class Key(val config: HostBackgroundConfig, val night: Boolean, val width: Int, val height: Int, val image: Bitmap?) {
        // 编辑器释放源图后仍可能淘汰旧条目；计费不能再访问 recycled Bitmap。
        val imageBytes = image?.allocationByteCount ?: 0
    }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "host-background-raster").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    internal class Queue(worker: Executor, main: FlightCache.OwnerPoster, render: (Key) -> Bitmap) {
        private class Raster(val bitmap: Bitmap, val bytes: Long)
        private val cache = FlightCache.builder<Key, Raster>(worker, main)
            .maxEntries(Int.MAX_VALUE).maxWeight(24L * 1024 * 1024).maxFlights(16)
            .weigher { it.bytes }.build()
        private val produce = FlightCache.Producer<Key, Raster> { key, _ ->
            val bitmap = render(key)
            Raster(bitmap, BitmapWeigher.INSTANCE.weigh(bitmap) + key.imageBytes)
        }

        fun get(key: Key): Bitmap? = cache.peek(key)?.bitmap

        /** False means that no callback will arrive and the current owner may retry later. */
        fun request(key: Key, current: () -> Boolean, ready: (Bitmap?) -> Unit): Boolean {
            val ticket = cache.request(key, produce, object : FlightCache.Subscriber<Raster> {
                override fun isCurrent() = current()
                override fun onReady(value: Raster) = ready(value.bitmap)
                override fun onFailed(failure: Throwable) = ready(null)
            })
            return when (ticket.status()) {
                FlightCache.Status.READY -> { if (current()) ready(ticket.value().bitmap); true }
                FlightCache.Status.STARTED, FlightCache.Status.JOINED -> true
                else -> false
            }
        }
    }

    private val queue = Queue(
        Executor { task -> worker.execute(HostThreadGuard.runnable("host.background.raster.worker") { task.run() }) },
        FlightCache.OwnerPoster { task -> main.post(HostThreadGuard.runnable("host.background.raster.ready") { task.run() }) },
        ::render
    )

    fun get(key: Key) = queue.get(key)
    fun request(key: Key, current: () -> Boolean = { true }, ready: (Bitmap?) -> Unit) = queue.request(key, current, ready)

    internal fun render(key: Key): Bitmap {
        require(key.width > 0 && key.height > 0 && key.width.toLong() * key.height <= 4_000_000)
        val bitmap = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
        try {
            HostBackgroundDrawable(key.config, key.night, key.image, cacheEnabled = false).also {
                it.setBounds(0, 0, key.width, key.height)
                it.draw(Canvas(bitmap))
            }
            bitmap.setHasAlpha(false)
            bitmap.prepareToDraw()
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
}
