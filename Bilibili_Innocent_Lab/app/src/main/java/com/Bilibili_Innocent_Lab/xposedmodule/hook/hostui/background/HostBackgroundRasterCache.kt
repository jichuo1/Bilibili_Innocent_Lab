package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.util.concurrent.Executors

/** 按实际像素尺寸合成，缓存有界；尺寸动画期间继续用原始绘制，不拉伸旧缓存。 */
internal object HostBackgroundRasterCache {
    data class Key(val config: HostBackgroundConfig, val night: Boolean, val width: Int, val height: Int, val image: Bitmap?) {
        // 编辑器释放源图后仍可能淘汰旧条目；计费不能再访问 recycled Bitmap。
        val imageBytes = image?.allocationByteCount ?: 0
    }
    private val lock = Any()
    private val cache = object : LruCache<Key, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount + key.imageBytes
    }
    private val pending = HashMap<Key, MutableList<(Bitmap?) -> Unit>>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "host-background-raster").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun get(key: Key): Bitmap? = synchronized(lock) { cache.get(key) }

    fun request(key: Key, ready: (Bitmap?) -> Unit) {
        synchronized(lock) {
            cache.get(key)?.let { ready(it); return }
            pending[key]?.let { it += ready; return }
            pending[key] = mutableListOf(ready)
        }
        worker.execute(HostThreadGuard.runnable("host.background.raster.worker") {
            val bitmap = runCatching { render(key) }.getOrNull()
            val callbacks = synchronized(lock) {
                if (bitmap != null) cache.put(key, bitmap)
                pending.remove(key).orEmpty()
            }
            main.post(HostThreadGuard.runnable("host.background.raster.ready") { callbacks.forEach { it(bitmap) } })
        })
    }

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
