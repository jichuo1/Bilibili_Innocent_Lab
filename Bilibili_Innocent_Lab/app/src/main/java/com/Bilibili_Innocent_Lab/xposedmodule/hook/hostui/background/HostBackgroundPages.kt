package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsetsController
import android.view.Window
import android.view.SurfaceView
import android.view.TextureView
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 布局变化时发现页面容器和当前可见行；滚动帧复用弱引用，媒体子树保持原样。 */
internal class HostBackgroundPages(
    private val background: HostBackgroundController,
    private val host: HostVideoCardHostAccess,
    private val onError: (Throwable) -> Unit
) {
    private val pages = WeakHashMap<ViewGroup, Page>()
    private val detailBackgrounds = WeakHashMap<View, Drawable>()
    private val types = HashMap<Class<*>, Boolean>()
    private val materialFills = HashMap<Class<*>, Pair<java.lang.reflect.Method, java.lang.reflect.Method>?>()
    private val visiblePageBounds = Rect()
    val requiresNight: Boolean get() = background.requiresNight

    fun attach(root: ViewGroup, kind: HostBackgroundPagePolicy.Kind, window: Window? = null) {
        val owner = owner(root)
        if (owner === root || owner != null && kinds[owner] != HostBackgroundPagePolicy.Kind.NATIVE && kind != HostBackgroundPagePolicy.Kind.COMMENT) return
        // 内容 Layer 与外层评论 Fragment 属于同一面板，不能在标题栏下面重启壁纸。
        if (kind == HostBackgroundPagePolicy.Kind.COMMENT && owner != null && kinds[owner] == kind) return
        // 子 Fragment 可能比父页面先完成创建；整页接入后只保留一个壁纸原点。
        for (child in pages.keys.toList()) {
            var parent = child.parent as? View
            while (parent != null && parent !== root) parent = parent.parent as? View
            if (parent === root) {
                // 通用窗口不能吞掉首页/Compose/动态的专用处理；独立评论弹层保留页面边界。
                if (kind == HostBackgroundPagePolicy.Kind.NATIVE || kind != HostBackgroundPagePolicy.Kind.COMMENT && kinds[child] == HostBackgroundPagePolicy.Kind.COMMENT) continue
                pages.remove(child)?.dispose()
                kinds.remove(child)
                background.release(child)
            }
        }
        kinds[root] = kind
        val page = Page(root, kind, window)
        pages[root] = page
        for (existing in pages.values) existing.invalidate()
        root.addOnAttachStateChangeListener(page)
        if (root.isAttachedToWindow) page.start()
    }

    fun attachHomeList(list: ViewGroup) {
        if (kind(list)?.let { it != HostBackgroundPagePolicy.Kind.NATIVE } == true || name(list) !in listNames || !recycler(list)) return
        var node = list.parent as? View
        var home = false
        while (node != null) {
            val id = name(node)
            if (id in protectedNames || node.javaClass.name.contains("Player") || node.javaClass.name.contains("ControlContainer")) return
            if (id == "primary_multi_page_layout") home = true
            node = node.parent as? View
        }
        if (!home) return
        val manager = KavaMemberLookup.inheritedMethodOrNull(list.javaClass, "getLayoutManager")?.invoke(list) ?: return
        val vertical = KavaMemberLookup.inheritedMethodOrNull(manager.javaClass, "canScrollVertically")?.invoke(manager) == true
        if (vertical) attach(list, HostBackgroundPagePolicy.Kind.FEED)
    }

    fun scroll(list: ViewGroup, dy: Int): Boolean {
        var node: View? = list
        while (node != null) {
            val page = pages[node]
            if (page != null) return page.scroll(list, dy)
            node = node.parent as? View
        }
        return false
    }

    private fun updateDetailWallpaper(panel: ViewGroup) {
        var weightedOffset = 0.0
        var visibleWidth = 0L
        for ((root, page) in pages) {
            if (sharedWallpaperOwner(root) !== panel || !root.isShown || !root.getGlobalVisibleRect(visiblePageBounds)) continue
            val offset = page.wallpaperOffset() ?: continue
            val width = visiblePageBounds.width()
            weightedOffset += offset.toDouble() * width
            visibleWidth += width
        }
        // 左右滑动时两个标签都可见，按可见宽度连续过渡，避免最后一个回调抢走顶栏原点。
        if (visibleWidth > 0) background.pageOffset(panel, kotlin.math.round(weightedOffset / visibleWidth).toLong())
    }

    private fun recycler(view: View): Boolean = types.getOrPut(view.javaClass) {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name.startsWith("androidx.viewpager2.")) return@getOrPut false
            if (type.name == "androidx.recyclerview.widget.RecyclerView") return@getOrPut true
            type = type.superclass
        }
        false
    }

    private fun name(view: View) = if (view.id == View.NO_ID) "" else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private fun vertical(list: ViewGroup): Boolean? {
        val manager = KavaMemberLookup.inheritedMethodOrNull(list.javaClass, "getLayoutManager")?.invoke(list) ?: return null
        return KavaMemberLookup.inheritedMethodOrNull(manager.javaClass, "canScrollVertically")?.invoke(manager) as? Boolean
    }

    private fun media(view: View, depth: Int = 0): Boolean {
        if (protected(view)) return true
        if (depth >= 6 || view !is ViewGroup) return false
        for (i in 0 until view.childCount) if (media(view.getChildAt(i), depth + 1)) return true
        return false
    }

    private inner class Page(root: ViewGroup, private val kind: HostBackgroundPagePolicy.Kind, window: Window?) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
        private val root = WeakReference(root)
        private var observer: WeakReference<ViewTreeObserver>? = null
        private var dirty = true
        private var containers = emptyList<WeakReference<View>>()
        private var lists = emptyList<WeakReference<ViewGroup>>()
        private var texts = emptyList<WeakReference<TextView>>()
        private val positions = WeakHashMap<ViewGroup, Position>()
        private var activeList: WeakReference<ViewGroup>? = null
        private val window = WeakReference(window)
        private val wallpaperSystemBars = background.requiresNight || window?.context?.javaClass?.name == HostBackgroundPagePolicy.SIDE_CENTER_ACTIVITY
        private var sideCenter: WeakReference<View>? = null
        private var windowBackground = WeakReference<Drawable>(null)
        private val detailSurfaces = if (kind == HostBackgroundPagePolicy.Kind.NATIVE && videoPanel(root))
            HostDetailSurfaces(root, detailBackgrounds) else null
        private var mediaWindow = window?.context?.let { context ->
            HostBackgroundPagePolicy.mediaDetailActivity(generateSequence(context.javaClass as Class<*>?) { it.superclass }
                .map { it.name }.toList())
        } == true
        private val wholePage = kind != HostBackgroundPagePolicy.Kind.FEED && kind != HostBackgroundPagePolicy.Kind.LIVE
        private val nativeContainers = kind == HostBackgroundPagePolicy.Kind.NATIVE || kind == HostBackgroundPagePolicy.Kind.COMMENT

        fun invalidate() { dirty = true }

        private inner class Position(list: ViewGroup) {
            val top = HostBackgroundListPosition(list.javaClass)
            var offset = 0L
        }

        private fun visible(list: ViewGroup) = list.isShown && list.getGlobalVisibleRect(android.graphics.Rect())

        private fun position(list: ViewGroup) = positions.getOrPut(list) { Position(list) }.also {
            if (it.offset != 0L && it.top.isAtTop(list)) it.offset = 0L
        }

        fun wallpaperOffset(): Long? {
            val page = root.get() ?: return null
            if (dirty) discover(page)
            val list = activeList?.get()?.takeIf(::visible) ?: lists.firstNotNullOfOrNull { it.get()?.takeIf(::visible) }
            return list?.let { position(it).offset }
        }

        fun start() {
            val tree = root.get()?.viewTreeObserver ?: return
            if (observer?.get() === tree) return
            stop()
            tree.addOnGlobalLayoutListener(this)
            tree.addOnPreDrawListener(this)
            observer = WeakReference(tree)
            dirty = true
        }

        private fun stop() {
            observer?.get()?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalLayoutListener(this)
                it.removeOnPreDrawListener(this)
            }
            observer = null
            detailSurfaces?.detach()
        }

        fun dispose() {
            stop()
            root.get()?.removeOnAttachStateChangeListener(this)
        }

        override fun onViewAttachedToWindow(view: View) = start()
        override fun onViewDetachedFromWindow(view: View) = stop()
        override fun onGlobalLayout() { dirty = true }

        private fun discover(root: ViewGroup) {
            val nextContainers = ArrayList<WeakReference<View>>()
            val nextLists = ArrayList<WeakReference<ViewGroup>>()
            val nextTexts = ArrayList<WeakReference<TextView>>()
            fun visit(node: View, depth: Int) {
                if (depth > (if (nativeContainers) 32 else 12) || node is ImageView) return
                if (node !== root && kinds.containsKey(node)) return
                if (node is TextView) {
                    if (nativeContainers) nextTexts += WeakReference(node)
                    return
                }
                val id = name(node)
                if (id == "sidecenter_layout") sideCenter = WeakReference(node)
                if (protected(node)) return
                if (kind == HostBackgroundPagePolicy.Kind.NATIVE && node !== root && node is ViewGroup &&
                    videoPanel(node) && node.width >= root.width * .9f) {
                    // 详情页的媒体层可以占满窗口；内容前景必须拥有自己的底板，不能透到播放器黑底。
                    attach(node, HostBackgroundPagePolicy.Kind.NATIVE)
                    mediaWindow = true
                    return
                }
                if (node is ViewGroup && recycler(node)) {
                    // 只认页面主列表，横向头像、Banner、分区标签不各自重画壁纸。
                    val vertical = vertical(node)
                    val main = vertical != false && (id in listNames || vertical == true && node.width >= root.width * .9f)
                    if (main && (kind != HostBackgroundPagePolicy.Kind.DYNAMIC ||
                            node.parent is android.widget.FrameLayout || id == "recycler")) {
                        nextLists += WeakReference(node)
                        if (wholePage) nextContainers += WeakReference(node)
                        if (!wholePage || kind == HostBackgroundPagePolicy.Kind.PAGE) {
                            for (i in 0 until node.childCount) {
                                val row = node.getChildAt(i)
                                if (row is ViewGroup && row.width >= node.width * .9f && !media(row))
                                    nextContainers += WeakReference(row)
                            }
                        }
                        var parent = node.parent as? View
                        while (parent != null && parent !== root) {
                            nextContainers += WeakReference(parent)
                            parent = parent.parent as? View
                        }
                    }
                    // 评论、详情、历史等原生列表的背景可能位于行内多层容器，且详情列表会嵌套评论列表。
                    // 只检查已经挂载的可见行，不遍历适配器、不触及图片和播放器。
                    if (nativeContainers) for (i in 0 until node.childCount) visit(node.getChildAt(i), depth + 1)
                    return
                }
                if (node !== root && (wholePage || id in sectionNames)) nextContainers += WeakReference(node)
                if (node is ViewGroup) for (i in 0 until node.childCount) visit(node.getChildAt(i), depth + 1)
            }
            visit(root, 0)
            containers = nextContainers.distinctBy { it.get() }
            lists = nextLists
            texts = nextTexts
            detailSurfaces?.discover()
            dirty = false
        }

        override fun onPreDraw(): Boolean {
            runCatching {
                val page = root.get()?.takeIf { it.isShown } ?: return@runCatching
                if (dirty) discover(page)
                // 播放窗口只给前景面板铺壁纸，状态栏、播放器及其工具栏保留宿主材质。
                if (mediaWindow) return@runCatching
                val night = host.isNight(page.context) || background.requiresNight
                if (wallpaperSystemBars && kind == HostBackgroundPagePolicy.Kind.NATIVE && page === page.rootView) {
                    // 侧边中心默认使用白色状态栏文字；透明化后按壁纸主题同步对比度，覆盖晚到的 Compose 副作用。
                    val darkBars = night || (sideCenter?.get()?.background as? ColorDrawable)?.color == android.graphics.Color.BLACK
                    val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    val flags = if (darkBars) page.systemUiVisibility and lightFlags.inv() else page.systemUiVisibility or lightFlags
                    if (flags != page.systemUiVisibility) page.systemUiVisibility = flags
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        val appearance = if (darkBars) 0 else mask
                        page.windowInsetsController?.let { if (it.systemBarsAppearance and mask != appearance) it.setSystemBarsAppearance(appearance, mask) }
                    }
                }
                if (wholePage) background.apply(page, night)
                else for (ref in lists) ref.get()?.let { background.apply(it, night) }
                val wallpaperOwner = sharedWallpaperOwner(page)
                if (wallpaperOwner != null) {
                    // 首页和视频详情的顶栏、正文透出同一张壁纸，子页面不再从原点重画。
                    clearNeutral(page, page, false)
                    for (ref in lists) ref.get()?.let {
                        // 列表绑定可早于 Fragment 接入，释放此前由卡片监听器创建的独立壁纸。
                        background.release(it)
                        clearNeutral(it, page, false)
                    }
                }
                window.get()?.let { window ->
                    val drawable = page.background
                    if (drawable != null && windowBackground.get() !== drawable) {
                        // 通过 Window 同步 Decor 的底色/回退背景，避免旧页面仍用主题白色填充系统栏空隙。
                        window.setBackgroundDrawable(drawable)
                        windowBackground = WeakReference(drawable)
                    }
                }
                for (ref in containers) ref.get()?.takeIf { owner(it) === page }?.let { clearNeutral(it, page, nativeContainers) }
                if (night) for (ref in texts) ref.get()?.takeIf { owner(it) === page }?.let { readableText(it, page) }
                for (ref in lists) ref.get()?.takeIf { owner(it) === page }?.let(background::prepareList)
                if ((wholePage || wallpaperOwner != null) && kind != HostBackgroundPagePolicy.Kind.COMPOSE) {
                    if (wallpaperOwner != null && kind != HostBackgroundPagePolicy.Kind.FEED) updateDetailWallpaper(wallpaperOwner)
                    else wallpaperOffset()?.let { background.pageOffset(wallpaperOwner ?: page, it) }
                }
                detailSurfaces?.apply(night)
            }.onFailure(onError)
            return true
        }

        fun scroll(list: ViewGroup, dy: Int): Boolean {
            val page = root.get() ?: return false
            val wallpaperOwner = sharedWallpaperOwner(page)
            if ((!wholePage && wallpaperOwner == null) || kind == HostBackgroundPagePolicy.Kind.COMPOSE) return false
            if (lists.none { it.get() === list } || !visible(list)) return false
            val position = positions.getOrPut(list) { Position(list) }
            position.offset += dy
            activeList = WeakReference(list)
            if (wallpaperOwner != null && kind != HostBackgroundPagePolicy.Kind.FEED) updateDetailWallpaper(wallpaperOwner)
            else background.pageOffset(wallpaperOwner ?: page, position.offset)
            return true
        }
    }

    private fun clearNeutral(view: View, page: ViewGroup, nativeContainers: Boolean) {
        if (detailBackgrounds[view]?.let { it === view.background } == true) return
        // 侧边中心会随标签切换，把同一容器从 Bg2 改成黑色。媒体页白色顶栏依赖这块黑底。
        if ((view.background as? ColorDrawable)?.color == android.graphics.Color.BLACK && name(view) == "sidecenter_layout") return
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        when (val drawable = view.background) {
            is ColorDrawable -> if (HostBackgroundPagePolicy.neutral(drawable.color)) view.background = null
            is LayerDrawable -> {
                if (!nativeContainers || name(view) !in chromeNames || view.width < page.width * .9f) return
                // 使用原状态的密度；重新传 Resources 会缩放程序创建的像素级分隔线。
                val copy = drawable.constantState?.newDrawable()?.mutate() as? LayerDrawable ?: return
                var changed = false
                val line = if (copy.numberOfLayers == 2) copy.getLayerInsetTop(1) else 0
                val borderedInput = line in 1..(view.resources.displayMetrics.density * 4f).toInt() &&
                    (0 until 2).all { i ->
                        copy.getLayerHeight(i) <= 0 && copy.getLayerInsetLeft(i) == 0 && copy.getLayerInsetRight(i) == 0 &&
                            copy.getLayerInsetBottom(i) == 0 && (copy.getDrawable(i) as? GradientDrawable)?.let(::plainGradient) == true
                    } && copy.getLayerInsetTop(0) == 0
                if (borderedInput) {
                    // 宿主用两块全宽矩形模拟顶边线：保留露出的细线，把底层限制在线高而非整栏。
                    copy.setLayerHeight(0, line)
                    (copy.getDrawable(1) as GradientDrawable).setColor(android.graphics.Color.TRANSPARENT)
                    changed = true
                }
                for (i in 0 until copy.numberOfLayers) {
                    if (borderedInput) break
                    // 输入栏底色与顶边分隔线共享 LayerDrawable，只移除覆盖整块栏位的纯色层。
                    val height = copy.getLayerHeight(i)
                    val width = copy.getLayerWidth(i)
                    if (height > 0 && height < view.height * .9f || width > 0 && width < view.width * .9f) continue
                    val layer = copy.getDrawable(i)
                    if (layer is ColorDrawable && HostBackgroundPagePolicy.neutral(layer.color)) {
                        layer.color = android.graphics.Color.TRANSPARENT
                        changed = true
                    } else if (layer is GradientDrawable && plainGradient(layer)) {
                        layer.setColor(android.graphics.Color.TRANSPARENT)
                        changed = true
                    }
                }
                if (changed) view.background = copy
            }
            is GradientDrawable -> {
                // 宿主的纯色矩形入口保留轮廓/描边；有渐变、圆角或业务色的材质不改。
                val gradient = drawable.colors
                val colors = drawable.color?.takeUnless { it.isStateful }
                val pageChrome = nativeContainers && view.width >= page.width * .9f && (!view.isClickable || name(view) in chromeNames)
                val neutralGradient = pageChrome && gradient != null && gradient.isNotEmpty() && gradient.all {
                    it ushr 24 == 0 || HostBackgroundPagePolicy.neutral(it or (0xff shl 24))
                }
                val pagePanel = pageChrome &&
                    (view.height >= page.height * .45f || name(view) in chromeNames)
                if (drawable.shape != GradientDrawable.RECTANGLE || !pagePanel && (drawable.cornerRadius != 0f ||
                    drawable.cornerRadii?.any { it != 0f } == true) || !neutralGradient && (gradient != null || colors == null || !HostBackgroundPagePolicy.neutral(colors.defaultColor))) return
                val copy = drawable.constantState?.newDrawable()?.mutate() as? GradientDrawable ?: return
                copy.setColor(android.graphics.Color.TRANSPARENT)
                view.background = copy
            }
            else -> {
                if (!nativeContainers || view.isClickable || view.width < page.width * .9f || drawable == null) return
                val access = materialFills.getOrPut(drawable.javaClass) {
                    if (generateSequence(drawable.javaClass as Class<*>?) { it.superclass }.none { it.name == "com.google.android.material.shape.MaterialShapeDrawable" }) return@getOrPut null
                    val read = KavaMemberLookup.inheritedMethodOrNull(drawable.javaClass, "getFillColor") ?: return@getOrPut null
                    val write = KavaMemberLookup.inheritedMethodOrNull(drawable.javaClass, "setFillColor", ColorStateList::class.java) ?: return@getOrPut null
                    read to write
                } ?: return
                val fill = access.first.invoke(drawable) as? ColorStateList ?: return
                if (fill.isStateful || !HostBackgroundPagePolicy.neutral(fill.defaultColor)) return
                // AppBarLayout 的 MaterialShapeDrawable 并非 ColorDrawable；保留形状、描边和阴影。
                val copy = drawable.constantState?.newDrawable()?.mutate() ?: return
                copy.setTintList(null)
                access.second.invoke(copy, ColorStateList.valueOf(android.graphics.Color.TRANSPARENT))
                view.background = copy
            }
        }
        if (view.paddingLeft != left || view.paddingTop != top || view.paddingRight != right || view.paddingBottom != bottom)
            view.setPadding(left, top, right, bottom)
    }

    private fun readableText(view: TextView, page: ViewGroup) {
        val color = view.currentTextColor
        if (view.textColors.isStateful || !HostBackgroundPagePolicy.neutral(color) || (color and 255) > 95) return
        // 纯色底板移除后，少数固定黑色数字需要深色主题文字；有自身按钮/浅色面板的文字沿用业务样式。
        var node: View? = view
        while (node != null && node !== page) {
            val drawable = node.background
            val solid = when (drawable) {
                is ColorDrawable -> drawable.color
                is GradientDrawable -> drawable.color?.defaultColor
                else -> null
            }
            if (solid != null && solid ushr 24 != 0 && (!HostBackgroundPagePolicy.neutral(solid) || (solid and 255) > 160)) return
            node = node.parent as? View
        }
        view.setTextColor(0xffe3e5e7.toInt())
    }

    private fun plainGradient(drawable: GradientDrawable): Boolean = drawable.shape == GradientDrawable.RECTANGLE &&
        drawable.cornerRadius == 0f && drawable.cornerRadii?.any { it != 0f } != true && drawable.colors == null &&
        drawable.color?.let { !it.isStateful && HostBackgroundPagePolicy.neutral(it.defaultColor) } == true

    companion object {
        private val kinds = WeakHashMap<ViewGroup, HostBackgroundPagePolicy.Kind>()
        fun owner(view: View): ViewGroup? {
            var node: View? = view
            while (node != null) {
                if (kinds.containsKey(node)) return node as? ViewGroup
                node = node.parent as? View
            }
            return null
        }
        fun kind(view: View): HostBackgroundPagePolicy.Kind? {
            return owner(view)?.let { kinds[it] }
        }
        fun nativeFeedOwner(page: ViewGroup): ViewGroup? {
            if (kinds[page] != HostBackgroundPagePolicy.Kind.FEED) return null
            var node = page.parent as? View
            while (node != null) {
                if (node is ViewGroup && kinds[node] == HostBackgroundPagePolicy.Kind.NATIVE &&
                    page.width > 0 && page.width == node.width) return node
                node = node.parent as? View
            }
            return null
        }
        fun sharedWallpaperOwner(page: ViewGroup): ViewGroup? {
            nativeFeedOwner(page)?.let { return it }
            if (kinds[page] != HostBackgroundPagePolicy.Kind.COMMENT && kinds[page] != HostBackgroundPagePolicy.Kind.PAGE) return null
            var node = page.parent as? View
            while (node != null) {
                if (node is ViewGroup && kinds.containsKey(node)) {
                    // 视频详情的前景面板包含简介/评论标签；壁纸不能提升到播放器所在的窗口。
                    return node.takeIf {
                        kinds[it] == HostBackgroundPagePolicy.Kind.NATIVE && name(it) == "content_container" &&
                            (it.parent as? View)?.let(::name) == "round_container" && page.width > 0 && page.width == it.width
                    }
                }
                node = node.parent as? View
            }
            return null
        }
        fun contains(view: View) = kind(view) != null
        private fun protected(view: View): Boolean = view is ImageView || view is SurfaceView || view is TextureView || view is WebView ||
            name(view) in protectedNames || view.javaClass.name.contains("Player") || view.javaClass.name.contains("ControlContainer") ||
            view.javaClass.name.startsWith("com.bilibili.cron.") || view.javaClass.name.startsWith("tv.danmaku.render.")

        fun protectedAncestor(view: View): Boolean {
            var node: View? = view
            while (node != null) {
                if (protected(node)) return true
                node = node.parent as? View
            }
            return false
        }

        private fun name(view: View) = if (view.id == View.NO_ID) "" else
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        private fun videoPanel(view: View): Boolean = name(view) == "content_container" &&
            (view.parent as? View)?.let(::name) == "round_container"
        private val listNames = setOf("recyclerView", "recycler_view", "dy_list", "recycler", "list")
        private val sectionNames = setOf("sticky_container", "sub_sticky_container", "tab_recyclerView", "top_bar", "dy_app_bar", "dy_sticky_group")
        private val chromeNames = setOf("main_top_layout", "nav_top_bar", "top_bar", "mine_top_view", "fake_input_layout", "cmt3_page_title_container")
        private val protectedNames = setOf("video_container", "video_area", "control_container", "player_wrapper", "video_cover_wrapper",
            "cover", "cover_layout", "video_cover", "iv_cover", "cover_image", "image_cover", "cover_container", "thumbnail", "pic", "banner", "dy_tab_layout",
            "avatar", "avatar_container", "avatar_layer", "garb_bg", "high_light_view", "login_mask", "login_mask_container", "video_area_float_layer_container",
            "shortcut_video_view", "recycler_top_car", "cover_ground_layout")
    }
}
