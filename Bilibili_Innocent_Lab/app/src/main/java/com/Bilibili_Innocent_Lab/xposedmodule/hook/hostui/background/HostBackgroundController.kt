package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.view.ViewGroup
import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** 挂在已识别的内容列表或页面根容器，不插入叠层、不修改 Fragment 可见性。 */
internal class HostBackgroundController(private val config: HostBackgroundConfig, private val onError: (Throwable) -> Unit,
    private val onApplied: () -> Unit = {}) {
    private class Surface(val drawable: HostBackgroundDrawable, val position: HostBackgroundListPosition)
    private val surfaces = WeakHashMap<ViewGroup, Surface>()
    private val loadingFooter = HostBackgroundLoadingFooter()
    private var image: Bitmap? = null
    private var requested = false
    private var applied = false
    val requiresNight: Boolean get() = config.preset.isCelestial

    fun scroll(list: ViewGroup, dy: Int) {
        if (dy == 0 || config.preset == HostBackgroundPreset.CUSTOM) return
        val surface = surfaces[list]?.drawable ?: return
        // 列表可能在中途恢复或替换；原点尚未确认时也必须响应向上滚动，不能锁死在零。
        surface.scrollOffset += dy
    }

    fun pageOffset(page: ViewGroup, offset: Long) {
        if (config.preset != HostBackgroundPreset.CUSTOM) surfaces[page]?.drawable?.scrollOffset = offset
    }

    fun prepareList(list: ViewGroup) = loadingFooter.apply(list)

    fun release(view: ViewGroup) {
        val state = surfaces.remove(view) ?: return
        if (view.background === state.drawable) view.background = null
    }

    fun apply(list: ViewGroup, night: Boolean) {
        if (!config.enabled) return
        // 动态的凝光卡片在列表后方的兄弟层，壁纸必须位于整页后面。
        val page = HostBackgroundPages.owner(list)
        val pageKind = page?.let { HostBackgroundPages.kind(it) }
        val wallpaperOwner = page?.let(HostBackgroundPages::sharedWallpaperOwner)
        if (wallpaperOwner != null) {
            release(list)
            apply(wallpaperOwner, night)
            return
        }
        if (page != null && page !== list && pageKind != HostBackgroundPagePolicy.Kind.FEED && pageKind != HostBackgroundPagePolicy.Kind.LIVE) {
            apply(page, night)
            return
        }
        // 收藏夹内的横向预览属于外层列表内容，不能各自重画一块从原点开始的壁纸。
        // 页面根容器已经确定，不能再越过它提升到承载整个详情页的外层 RecyclerView。
        var ancestor = if (page === list) null else list.parent as? View
        var pageList = list
        while (ancestor != null) {
            if (ancestor is ViewGroup && isRecycler(ancestor.javaClass)) pageList = ancestor
            ancestor = ancestor.parent as? View
        }
        if (pageList !== list) { apply(pageList, night); return }
        val state = surfaces.getOrPut(list) {
            Surface(HostBackgroundDrawable(config, night, image), HostBackgroundListPosition(list.javaClass))
        }
        val surface = state.drawable
        // 稳定布局中首条内容确实回到顶部才归零，不能依赖滚动条的临时边界报告。
        if (config.preset != HostBackgroundPreset.CUSTOM && surface.scrollOffset != 0L && state.position.isAtTop(list)) surface.scrollOffset = 0L
        if (surface.night != night) { surface.night = night; surface.invalidateSelf() }
        if (list.background !== surface) list.background = surface
        // 加载更多尾项有独立的换肤背景，绘制前透出同一条连续壁纸。
        loadingFooter.apply(list)
        if (!applied) { applied = true; onApplied() }
        if (config.preset != HostBackgroundPreset.CUSTOM || requested) return
        requested = true
        val app = list.context.applicationContext
        val anchor = WeakReference(list)
        worker.execute {
            val loaded = HostBackgroundImages.load(app, config)
            anchor.get()?.post {
                if (loaded == null) {
                    onError(IllegalStateException("Custom host background unavailable; using aurora"))
                } else {
                    image = loaded
                    // 每个列表持有自己的 Drawable，共享只读位图，避免 Drawable callback 串页。
                    for ((view, old) in surfaces.toMap()) {
                        val next = HostBackgroundDrawable(config, old.drawable.night, loaded)
                        surfaces[view] = Surface(next, old.position)
                        view.background = next
                    }
                }
            }
        }
    }

    private val recyclerTypes = HashMap<Class<*>, Boolean>()
    private fun isRecycler(type: Class<*>): Boolean = recyclerTypes.getOrPut(type) {
        var current: Class<*>? = type
        while (current != null) {
            // ViewPager2 的 RecyclerView 只是分页容器，不能把壁纸提升到标签栏背后。
            if (current.name.startsWith("androidx.viewpager2.")) return@getOrPut false
            if (current.name == "androidx.recyclerview.widget.RecyclerView") return@getOrPut true
            current = current.superclass
        }
        false
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "host-background").apply { isDaemon = true } }
    }
}
