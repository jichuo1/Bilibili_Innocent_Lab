package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One coalescing worker, one current image, and bounded resource-ready retries. */
internal class BrandSplashImageCache(
    private val context: Context,
    private val model: BrandSplashModelAccess,
    private val storage: BrandSplashStorageAccess,
    private val state: BrandSplashSelectionState<Any>,
    private val environment: HookEnvironment,
    decoderPoint: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint? = null
) {
    class Photo(val bitmap: Bitmap, val image: BrandSplashModelAccess.Image, val signature: String) {
        val shown = AtomicBoolean(false)
        val exitRequested = AtomicBoolean(false)
    }
    private data class Scope(val owner: WeakReference<Any>, val invalidate: Method)
    private val decoder = BrandSplashBitmapDecoder(environment, decoderPoint).also { it.install() }
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "BIL-BrandImage").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val photo = AtomicReference<Photo?>()
    private val desiredSignature = AtomicReference("")
    private val scopes = ArrayList<Scope>()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    private var requestToken = 0L

    init {
        synchronized(instancesLock) { instances[this] = Unit }
    }

    fun current(): Photo? = photo.get()?.takeIf { it.signature == desiredSignature.get() }
    fun isSelectionCurrent(signature: String): Boolean = signature.isNotEmpty() && signature == desiredSignature.get()

    /** 内存压力下释放模块持有的开屏解码缓存与在途重试；不主动 recycle 位图以留给渲染器安全退场。 */
    fun clear() {
        synchronized(lock) {
            requestToken++
            pending?.cancel(false)
            pending = null
            photo.set(null)
        }
        synchronized(scopes) {
            scopes.removeAll { it.owner.get() == null }
        }
    }

    fun observeScope(owner: Any, invalidate: Method) {
        synchronized(scopes) {
            scopes.removeAll { it.owner.get() == null }
            if (scopes.any { it.owner.get() === owner }) return
            if (scopes.size >= 16) scopes.removeAt(0)
            scopes.add(Scope(WeakReference(owner), invalidate))
        }
    }

    fun changed() {
        synchronized(lock) {
            val snapshot = state.snapshot()
            val images = snapshot.selected.mapNotNull(model::image)
            val signature = if (snapshot.customMode) signature(images) else ""
            if (desiredSignature.getAndSet(signature) == signature) {
                // A successful identical write invalidates the previous revision's load or rotation.
                val cached = photo.get()
                val reload = brandSplashImageReloadAfterWrite(signature, cached?.signature,
                    cached?.exitRequested?.get() == true, cached?.image?.key)
                if (reload != null) {
                    request(snapshot, images, signature, reload.afterKey)
                }
                return
            }
            if (signature.isEmpty()) {
                requestToken++
                pending?.cancel(false)
                pending = null
                photo.set(null)
                invalidateScopes()
            } else request(snapshot, images, signature, null)
        }
    }

    fun shown(value: Photo) {
        if (!value.shown.compareAndSet(false, true)) return
        executor.execute {
            if (desiredSignature.get() == value.signature) {
                runCatching { storage.rememberRotation(context, value.image.key) }
            }
        }
    }

    fun exited(value: Photo) {
        if (!value.shown.get() || photo.get() !== value) return
        val snapshot = state.snapshot()
        val images = snapshot.selected.mapNotNull(model::image)
        if (!snapshot.customMode || images.size < 2 || signature(images) != value.signature) return
        if (!value.exitRequested.compareAndSet(false, true)) return
        request(snapshot, images, value.signature, value.image.key)
    }

    private fun request(snapshot: BrandSplashSelectionState.Snapshot<Any>, images: List<BrandSplashModelAccess.Image>,
        signature: String, after: String?) {
        synchronized(lock) {
            requestToken++
            val token = requestToken
            pending?.cancel(false)
            pending = executor.schedule({
                if (!isCurrent(token, snapshot.revision, signature)) return@schedule
                val cursor = after ?: runCatching { storage.rotationKey(context) }.getOrDefault("")
                val last = images.indexOfFirst { it.key == cursor }
                val selected = images[(last + 1).mod(images.size)]
                load(token, snapshot.revision, selected, signature, 0)
            }, 0, TimeUnit.MILLISECONDS)
        }
    }

    private fun load(token: Long, revision: Long, selected: BrandSplashModelAccess.Image, signature: String, attempt: Int) {
        if (!isCurrent(token, revision, signature)) return
        val bitmap = runCatching { decoder.decode(selected) }.getOrElse {
            environment.logError("brand_custom_bitmap", "[BIL] 自选开屏资源无法解码，保留原画面")
            null
        }
        if (!isCurrent(token, revision, signature)) return
        if (bitmap != null) {
            // A host Painter may retain the preceding Bitmap while a page exits. Let GC own its lifetime.
            photo.set(Photo(bitmap, selected, signature))
            invalidateScopes()
        } else if (attempt < RETRY_DELAYS.size) {
            synchronized(lock) {
                if (isCurrent(token, revision, signature)) {
                    pending = executor.schedule({ load(token, revision, selected, signature, attempt + 1) },
                        RETRY_DELAYS[attempt], TimeUnit.MILLISECONDS)
                }
            }
        } else {
            photo.get()?.exitRequested?.set(false)
            environment.logInfo("brand_custom_resource_pending", "[BIL] 自选开屏素材尚未下载完成，本次保留原画面")
        }
    }

    private fun isCurrent(token: Long, revision: Long, signature: String): Boolean = synchronized(lock) {
        token == requestToken && state.isCurrent(revision) && desiredSignature.get() == signature
    }

    private fun invalidateScopes() {
        val ready = synchronized(scopes) {
            scopes.removeAll { it.owner.get() == null }
            scopes.toList()
        }
        main.post {
            ready.forEach { scope -> scope.owner.get()?.let { value ->
                runCatching { scope.invalidate.invoke(value) }.onFailure {
                    environment.logInfo("brand_custom_recompose", "[BIL] 开屏页面已离开，等待下一次显示")
                }
            } }
        }
    }

    private fun signature(images: List<BrandSplashModelAccess.Image>): String = images.joinToString(";") {
        "${it.key}/${it.hash}/${it.mode}/${it.showLogo}"
    }

    companion object {
        private val RETRY_DELAYS = longArrayOf(250L, 1_000L, 2_500L, 5_000L)
        private val instancesLock = Any()
        private val instances = WeakHashMap<BrandSplashImageCache, Unit>()

        fun clearAll() {
            val snapshot = synchronized(instancesLock) { instances.keys.toList() }
            snapshot.forEach { it.clear() }
        }
    }
}
