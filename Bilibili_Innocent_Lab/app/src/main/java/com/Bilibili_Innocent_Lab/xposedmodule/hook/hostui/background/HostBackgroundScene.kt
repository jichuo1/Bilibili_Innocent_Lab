package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 视口只保留可见区及前后一块；滚动帧只平移位图，后台任务合并到最新视口。
 *
 * 绘制分两种用途（[draw] 的 `synchronous`）：
 * - 精确离屏生成（`synchronous = true`，只有 `cacheEnabled = false` 的离屏 Drawable 使用，运行在工作线程上）：
 *   缓存未命中就地同步生成，返回的内容完整。
 * - 交互绘制（`synchronous = false`，包括软件 Canvas 的 Picture 采样录制，可能在主线程）：只取已就绪的 tile，
 *   缺失的 tile 画确定的降级底色并提交共享的异步生成任务；每个 tile 就绪都会推进 [contentVersion] 并刷新，
 *   采样方据此重新取样，而不是把降级底色当成最终结果。
 *
 * 多个 Scene 对同一个 [Key] 同时冷 miss 时只生成一次（[TileFlightTable]）；某个 Scene 的视口移走后，
 * 它放弃的、尚未开始且无人订阅的任务被移出待办，已开始的任务仍会进共享缓存但不再发布给它。
 */
internal class HostBackgroundScene(private val config: HostBackgroundConfig, private val invalidate: () -> Unit) {
    internal data class Key(val config: HostBackgroundConfig, val night: Boolean, val width: Int, val band: Long)
    private val lock = Any()
    private var wanted = emptyList<Key>()
    private val ready = HashMap<Key, Bitmap>()
    private var first = 0L
    private var last = 0L
    private var offset = 0
    private var logicalWidth = 0
    private var rasterWidth = 0
    private var theme = false
    @Volatile private var failedAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    /** 共享任务表里的订阅句柄；只持有 Scene 的弱引用，在途任务不会延长 Scene 的寿命。 */
    private val subscription = Subscription(WeakReference(this))
    private val version = AtomicLong()
    internal val isReady get() = synchronized(lock) { wanted.isNotEmpty() && wanted.filter { it.band in first..last }.all { ready.containsKey(it) } }
    internal val retainedTileCount get() = synchronized(lock) { ready.size }
    /** 每发布一个有效 tile 加一：采样方用它（而不是单个「全部就绪」布尔）判断内容是否变化。 */
    internal val contentVersion get() = version.get()

    internal class Subscription(val scene: WeakReference<HostBackgroundScene>)

    fun draw(canvas: Canvas, bounds: Rect, scroll: Long, night: Boolean, alpha: Int, filter: ColorFilter?, synchronous: Boolean) {
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val oldFirst = first
        val oldLast = last
        first = Math.floorDiv(scroll, width.toLong())
        offset = Math.floorMod(scroll, width.toLong()).toInt()
        last = first + (offset.toLong() + height - 1) / width
        val nextWidth = width.coerceAtMost(1080)
        val nextTheme = night && !config.preset.isCelestial
        var retarget = false
        val keys = synchronized(lock) {
            if (wanted.isEmpty() || oldFirst != first || oldLast != last || rasterWidth != nextWidth || theme != nextTheme) {
                val keys = (first..last).map { Key(config, nextTheme, nextWidth, it) } +
                    listOf(Key(config, nextTheme, nextWidth, last + 1), Key(config, nextTheme, nextWidth, first - 1))
                wanted = keys
                ready.keys.retainAll(keys.toSet())
                for (key in keys) if (!ready.containsKey(key)) cached(key)?.let { ready[key] = it }
                retarget = true
            }
            wanted
        }
        logicalWidth = width
        rasterWidth = nextWidth
        theme = nextTheme
        if (synchronous) {
            // 精确生成：不排进共享小线程池，也不等它——就地生成，保证返回时内容完整。
            for (key in keys.take((last - first + 1).toInt())) {
                if (synchronized(lock) { ready.containsKey(key) }) continue
                val bitmap = cached(key) ?: render(key).also { cache(key, it) }
                synchronized(lock) { ready[key] = bitmap; version.incrementAndGet() }
            }
        } else requestMissing(keys, retarget)
        paint.shader = null
        paint.colorFilter = filter
        paint.color = if (config.preset.isCelestial) 0xff03040c.toInt() else if (night) 0xff10121b.toInt() else 0xffe8f3f1.toInt()
        paint.alpha = alpha
        val saved = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawRect(bounds, paint)
        for (key in keys) {
            if (key.band !in first..last) continue
            val bitmap = synchronized(lock) { ready[key] } ?: continue
            val top = bounds.top + ((key.band - first) * logicalWidth - offset).toFloat()
            source.set(0, 1, bitmap.width, bitmap.height - 1)
            destination.set(bounds.left.toFloat(), top, bounds.right.toFloat(), top + logicalWidth)
            canvas.drawBitmap(bitmap, source, destination, paint)
        }
        canvas.restoreToCount(saved)
    }

    /**
     * 交互绘制：把仍缺失的 tile 订阅到共享任务表。视口变化时先放弃过时订阅。
     * 缓存检查与加入/注册在 cacheLock 内原子完成；Scene 自己的锁从不在持有 cacheLock 时获取。
     */
    private fun requestMissing(keys: List<Key>, retarget: Boolean) {
        val missing = synchronized(lock) { keys.filter { !ready.containsKey(it) } }
        if (retarget) synchronized(cacheLock) { flights.retain(subscription, keys.toSet()) }
        if (missing.isEmpty()) return
        if (SystemClock.uptimeMillis() - failedAt < FAILURE_BACKOFF_MS) return
        val hits = ArrayList<Pair<Key, Bitmap>>(0)
        var created = 0
        var rejected = false
        synchronized(cacheLock) {
            for (key in missing) {
                val hit = rasters.get(key)
                if (hit != null) { hits += key to hit; continue }
                when (flights.request(key, subscription)) {
                    TileFlightTable.Outcome.CREATED -> created++
                    TileFlightTable.Outcome.JOINED -> Unit
                    TileFlightTable.Outcome.REJECTED -> rejected = true
                }
            }
        }
        if (hits.isNotEmpty()) synchronized(lock) {
            for ((key, bitmap) in hits) if (key in wanted && ready.put(key, bitmap) == null) version.incrementAndGet()
        }
        repeat(created) { worker.execute { drain() } }
        // 任务表满（正常使用不会发生）：稍后再绘制一次重新订阅，而不是丢掉这些 tile。
        if (rejected) main.postDelayed({ invalidate() }, REJECT_RETRY_MS)
    }

    /** 主线程：一个 tile 生成完成。只发布给仍匹配当前视口的订阅者。 */
    private fun onTile(key: Key, bitmap: Bitmap?) {
        if (bitmap == null) {
            failedAt = SystemClock.uptimeMillis()
            return
        }
        val published = synchronized(lock) {
            if (key in wanted) { ready[key] = bitmap; version.incrementAndGet(); true } else false
        }
        if (published) invalidate()
    }

    companion object {
        private const val FAILURE_BACKOFF_MS = 1_000L
        private const val REJECT_RETRY_MS = 100L
        private val cacheLock = Any()
        private val rasters = object : LruCache<Key, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
        }
        private val flights = TileFlightTable<Key, Subscription>()
        private val worker = Executors.newFixedThreadPool(2) { Thread(it, "host-background-world").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
        private fun cached(key: Key): Bitmap? = synchronized(cacheLock) { rasters.get(key) }
        private fun cache(key: Key, bitmap: Bitmap) = synchronized(cacheLock) { rasters.put(key, bitmap) }

        /** 诊断 / 验收：共享任务表的累计计数与当前待办数。 */
        internal data class FlightStats(val created: Long, val joined: Long, val cancelled: Long, val completed: Long,
                                        val rejected: Long, val pending: Int, val renders: Long)
        private val renders = AtomicLong()
        internal fun flightStats(): FlightStats = synchronized(cacheLock) {
            FlightStats(flights.created, flights.joined, flights.cancelled, flights.completed, flights.rejected,
                flights.pendingCount(), renders.get())
        }
        /** 测试：渲染前调用，参数为 tile key 与正在渲染的线程。 */
        @Volatile internal var renderObserver: ((Key, Thread) -> Unit)? = null
        /** 测试：清空共享位图缓存，制造冷 miss。 */
        internal fun clearSharedCache() = synchronized(cacheLock) { rasters.evictAll() }

        /** 工作线程：取一个最旧的未开始任务生成，完成后只通知仍订阅它的 Scene。 */
        private fun drain() {
            val key = synchronized(cacheLock) { flights.take { it.scene.get() != null } } ?: return
            val bitmap = runCatching {
                cached(key) ?: run {
                    renderObserver?.invoke(key, Thread.currentThread())
                    renders.incrementAndGet()
                    render(key).also { cache(key, it) }
                }
            }.getOrNull()
            val subscribers = synchronized(cacheLock) { flights.finish(key) }
            if (subscribers.isEmpty()) return // 已无人订阅：结果留在共享缓存，不发布给过期的 Scene
            main.post { for (subscription in subscribers) subscription.scene.get()?.onTile(key, bitmap) }
        }

        internal fun render(key: Key): Bitmap {
            val width = key.width
            val bitmap = Bitmap.createBitmap(width, width + 2, Bitmap.Config.ARGB_8888)
            val texture = HostBackgroundCosmicClouds.texture(key.config.preset, key.band, key.night)
            try {
                val canvas = Canvas(bitmap)
                canvas.translate(0f, 1f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                paint.shader = HostBackgroundCosmicClouds.shader(texture, width.toFloat())
                canvas.drawRect(0f, -1f, width.toFloat(), width + 1f, paint)
                if (key.config.preset.isCelestial) for (band in key.band - 1..key.band + 1) {
                    val saved = canvas.save()
                    canvas.translate(0f, ((band - key.band) * width).toFloat())
                    HostBackgroundCelestialArtwork.create(key.config.preset, width.toFloat(), band)?.draw(canvas, 255, null)
                    canvas.restoreToCount(saved)
                }
                paint.shader = null
                paint.color = if (key.config.preset.isCelestial) Color.BLACK else if (key.night) 0xff10121b.toInt() else Color.WHITE
                paint.alpha = key.config.veil * 255 / 100
                canvas.drawRect(0f, -1f, width.toFloat(), width + 1f, paint)
                bitmap.setHasAlpha(false)
                bitmap.prepareToDraw()
                return bitmap
            } catch (failure: Throwable) { bitmap.recycle(); throw failure }
            finally { texture.recycle() }
        }
    }
}
