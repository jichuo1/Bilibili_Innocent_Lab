package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostViewVisibility
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardSurface

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** 页面负责分组排布和复用；状态栏及卡片材质交给凝光。 */
internal class FollowFeedController(
    private val host: FollowFeedHostAccess,
    private val compactHeaderSupported: Boolean,
    private val report: (String) -> Unit,
    private val error: (Throwable) -> Unit,
    private val material: HostCardMaterial = HostCardMaterial.ORIGINAL
) {
    private class RowState(val row: FollowFeedRow?) {
        val edits = FollowFeedViewEdits()
        var originalInsets: Rect? = null
        var appliedInsets: Rect? = null
        var cachedInsets: Rect? = null
        var palette: LumenPalette? = null
        var decorated = false
        var referenceDecorated = false
        var page = WeakReference<Page>(null)
        fun restoreInsets() {
            val cached = cachedInsets
            val original = originalInsets
            if (cached != null && original != null && cached == appliedInsets) cached.set(original)
            cachedInsets = null; originalInsets = null; appliedInsets = null
        }
    }
    private val rows = WeakHashMap<View, RowState>()
    // 页面自身的 attach listener 持有生命周期；全局索引不能反向强持有 Activity。
    private val pages = WeakHashMap<View, WeakReference<Page>>()
    private val personalChrome = WeakHashMap<View, WeakReference<FollowFeedPersonalChrome>>()
    private val rowAttach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { safely { attachRow(view) } }
        override fun onViewDetachedFromWindow(view: View) {
            rows[view]?.let { it.edits.restore(); it.restoreInsets(); it.palette = null; it.page.clear() }
        }
    }

    fun attach(root: View, personalFeed: Boolean = false) = safely {
        if (pages[root]?.get()?.closed == false) return@safely
        val page = Page(root, personalFeed)
        pages[root] = WeakReference(page)
        page.start()
    }

    fun close(root: View) { pages.remove(root)?.get()?.close() }
    fun pause(root: View) { pages[root]?.get()?.setLifecycleActive(false) }
    fun resume(root: View, personalFeed: Boolean = false) = safely {
        attach(root, personalFeed); pages[root]?.get()?.setLifecycleActive(true)
    }
    fun visibility(root: View, visible: Boolean) { pages[root]?.get()?.setUserVisible(visible) }
    fun headerPadding(root: View, requested: Int) = pages[root]?.get()?.headerPadding(requested) ?: requested

    fun bind(root: View, holder: Any, position: Int) = safely {
        // 只保留这个 View 当前的标量身份，宿主每次 bind 后重读；不能缓存旧模型。
        val row = host.row(holder, position)
        if (row == null && !host.handles(holder) && pageFor(root) == null) return@safely
        val previous = rows[root]
        previous?.edits?.restore()
        previous?.restoreInsets()
        rows[root] = RowState(row)
        if (previous == null) root.addOnAttachStateChangeListener(rowAttach)
        if (root.isAttachedToWindow) attachRow(root)
    }

    fun recycle(root: View) {
        rows.remove(root)?.let { it.edits.restore(); it.restoreInsets() }
        root.removeOnAttachStateChangeListener(rowAttach)
    }

    fun replacesModuleBackground(list: ViewGroup, root: View): Boolean {
        val state = rows[root] ?: return false
        val identity = state.row?.identity ?: return false
        return root.parent === list && state.decorated &&
            (state.row.reference?.quoted != true || state.referenceDecorated) &&
            state.page.get()?.hasCardSurface(list, identity) == true
    }

    fun replacesPersonalSeparators(list: ViewGroup): Boolean {
        val page = pageFor(list) ?: return false
        if (!page.personalFeed || !page.isFeedList(list)) return false
        // 原装饰器只画后续动态头部的分隔线；卡片承托完整时才撤掉它。
        return list.childCount > 0 && (0 until list.childCount).all { index ->
            val root = list.getChildAt(index)
            rows[root]?.row != null && replacesModuleBackground(list, root)
        }
    }

    fun childAttached(root: View) = safely {
        val page = pageFor(root) ?: return@safely
        if (rows[root] == null && page.text(root, "dy_name") != null &&
            page.find<View>(root, "avatar_container") != null) {
            rows[root] = RowState(null)
            root.addOnAttachStateChangeListener(rowAttach)
        }
        attachRow(root)
    }

    fun insets(root: View, rect: Rect) = safely {
        val state = rows[root] ?: return@safely
        val row = state.row ?: return@safely
        val list = root.parent as? ViewGroup ?: return@safely
        val page = pageFor(list) ?: return@safely
        if (!page.isFeedList(list)) return@safely
        if (state.appliedInsets == rect) return@safely // RecyclerView 的 mDecorInsets 是缓存对象。
        state.originalInsets = Rect(rect)
        state.cachedInsets = rect
        val outer = page.dp(FollowFeedStyle.OUTER_DP)
        rect.left += outer; rect.right += outer
        // 覆盖原来的末尾分隔带，不能在同一动态模块之间累计间距。
        if (row.last) rect.bottom = maxOf(rect.bottom, page.dp(FollowFeedStyle.GAP_DP))
        state.appliedInsets = Rect(rect)
    }

    private fun pageFor(view: View): Page? {
        var current: View? = view
        repeat(18) {
            val node = current ?: return null
            pages[node]?.get()?.takeUnless { it.closed }?.let { return it }
            current = node.parent as? View
        }
        return null
    }

    private fun attachRow(root: View) {
        val state = rows[root] ?: return
        val page = pageFor(root) ?: return
        state.page = WeakReference(page)
        val list = root.parent as? ViewGroup ?: return
        if (page.isFeedList(list)) page.list(list)
        styleRow(root, state, page)
        if (state.row == null && page.isFeedList(list)) {
            val frequent = page.find<View>(root, "dy_fl_video_uplist") ?: page.find<View>(root, "dy_fl_uplist")
            val horizontal = frequent?.let { page.group(it, "dy_list") }
            if (horizontal != null) for (i in 0 until horizontal.childCount) {
                val child = horizontal.getChildAt(i)
                val childState = rows.getOrPut(child) {
                    child.addOnAttachStateChangeListener(rowAttach)
                    RowState(null)
                }
                childState.page = WeakReference(page)
                styleRow(child, childState, page)
            }
        }
    }

    private fun styleRow(root: View, state: RowState, page: Page) {
        if (state.row != null && !state.decorated) return
        if (state.palette == page.palette) return
        state.edits.restore()
        state.palette = page.palette
        val palette = page.palette
        val list = root.parent as? ViewGroup ?: return
        if (page.isFeedList(list) && state.row != null) {
            // 内层表面不足或分组不可信时保留宿主的引用底色。
            if (state.row.reference?.quoted == true && !state.referenceDecorated) return
            state.edits.background(root, null)
            // 只清理已由表面承托的模块背景，不扫描/改写播放器、媒体和会员装饰。
            for (name in arrayOf("dy_root", "dy_following_card_header", "dy_card_bottom")) {
                page.find<View>(root, name)?.takeIf { it.parent === root }?.let { state.edits.background(it, null) }
            }
            for (name in arrayOf("dy_text_time", "dy_text_ip", "dy_card_repost_text", "dy_card_comment_text", "dy_card_support_text")) {
                page.text(root, name)?.let {
                    // 点赞等业务强调色必须保留。
                    if (!it.isSelected && !it.isActivated && FollowFeedStyle.isNeutralText(it.currentTextColor))
                        state.edits.text(it, palette.textSecondary)
                }
            }
            page.text(root, "dy_video_title")?.let {
                state.edits.text(it, palette.textPrimary, FollowFeedStyle.VIDEO_TITLE_SP, maxLines = 2)
                state.edits.size(it, height = ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            page.text(root, "card_user_name")?.let {
                if (FollowFeedStyle.isNeutralText(it.currentTextColor)) state.edits.text(it, palette.textPrimary)
            }
            page.text(root, "dy_title")?.let { state.edits.text(it, palette.textPrimary) }
            page.text(root, "dy_text")?.let { state.edits.text(it, palette.textPrimary) }
        } else {
            // 横向最常访问项目只在目标页内识别；原横向 RecyclerView 保持不变。
            val nickname = page.text(root, "dy_name")
            val avatar = page.find<View>(root, "avatar_container")
            if (nickname != null && avatar != null && !page.isFeedList(list)) {
                state.edits.text(nickname, palette.textSecondary, singleLine = true)
                state.edits.size(nickname, height = ViewGroup.LayoutParams.WRAP_CONTENT)
                state.edits.size(avatar, page.dp(FollowFeedStyle.AVATAR_DP), page.dp(FollowFeedStyle.AVATAR_DP))
                val font = root.resources.configuration.fontScale.coerceIn(1f, 2f)
                state.edits.size(root, page.dp((FollowFeedStyle.FREQUENT_WIDTH_DP * font).roundToInt()),
                    page.dp(FollowFeedStyle.frequentHeight(font)))
            }
            for (name in arrayOf("dy_fl_video_uplist", "dy_fl_uplist")) page.find<View>(root, name)?.let {
                state.edits.background(root, null); state.edits.background(it, null)
                page.text(it, "dy_title")?.let { title -> state.edits.text(title, palette.textSecondary, FollowFeedStyle.FREQUENT_TITLE_SP) }
                // 全部页的更多入口独立使用 bg_card_selector；原默认白色不会随
                // 父容器透明而消失。只给原 selector 副本配色，不移除点击与按压反馈。
                page.find<View>(it, "dy_more_container")?.let { more ->
                    state.edits.backgroundTint(more, ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                        intArrayOf(palette.surfaceVariant, palette.background)
                    ))
                    page.text(more, "dy_card_more_text")?.let { label -> state.edits.text(label, palette.textSecondary) }
                }
                page.find<View>(it, "dy_list")?.let { horizontal ->
                    val font = root.resources.configuration.fontScale
                    state.edits.size(horizontal, height = page.dp(FollowFeedStyle.frequentListHeight(font)))
                }
            }
        }
    }

    private inline fun safely(block: () -> Unit) { runCatching(block).onFailure(error) }

    private fun syncPersonalChrome(view: View, palette: LumenPalette) {
        val window = view.rootView
        val chrome = personalChrome[window]?.get()?.takeUnless { it.closed }
            ?: FollowFeedPersonalChrome.create(window)?.also { personalChrome[window] = WeakReference(it) }
        chrome?.updatePalette(palette)
    }

    internal inner class Page(root: View, val personalFeed: Boolean = false) : AutoCloseable, View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val root = WeakReference(root)
        private val theme = HostChromeTheme(root.context)
        private var colors = theme.read()
        var palette = FollowFeedStyle.palette(colors)
            private set
        private val session = LumenSurfaceSession(root.context, palette,
            LumenSurfaceSessionOptions(pauseWhenWindowUnfocused = false))
        private val visibleBounds = Rect()
        private val cardOptions = FollowFeedStyle.card()
        private val edits = FollowFeedViewEdits()
        private val lists = HashMap<ViewGroup, FeedList>()
        private val ids = HashMap<String, Int>()
        private var observer: ViewTreeObserver? = null
        private var selection: FollowFeedSelection? = null
        private var header: FollowFeedHeader? = null
        private var statusBar: FollowFeedStatusBar? = null
        private var headerAttempted = false
        private var themeReadAt = 0L
        private var paused = false
        private var lifecycleActive = true
        private var userVisible = true
        private var selectionAttempted = false
        var closed = false
            private set
        private var reportedDraw = false
        private var reportedCards = false
        private var reportedStatus = false

        fun dp(value: Int) = (value * (root.get()?.resources?.displayMetrics?.density ?: 1f)).roundToInt()
        fun <T : View> find(view: View, name: String): T? {
            val id = ids.getOrPut(name) { view.resources.getIdentifier(name, "id", view.context.packageName) }
            return if (id == 0) null else view.findViewById<T>(id)
        }
        fun text(view: View, name: String): TextView? = find<View>(view, name) as? TextView
        fun group(view: View, name: String): ViewGroup? = find<View>(view, name) as? ViewGroup
        fun isFeedList(view: ViewGroup): Boolean {
            val name = if (personalFeed) "recycler" else "dy_list"
            val id = ids.getOrPut(name) { view.resources.getIdentifier(name, "id", view.context.packageName) }
            val supportedParent = if (personalFeed) view.parent === root.get() && view.parent is RelativeLayout
                else view.parent is FrameLayout
            return id != 0 && view.id == id && supportedParent &&
                view.javaClass.name == "androidx.recyclerview.widget.RecyclerView"
        }

        fun list(view: ViewGroup) { lists.getOrPut(view) { FeedList(view) } }
        fun hasCardSurface(list: ViewGroup, identity: Long) = !closed &&
            lists[list]?.hasSurface(identity) == true

        fun start() {
            val view = root.get() ?: return
            view.addOnAttachStateChangeListener(this)
            applyPageColors(view)
            // bind/attach 可能早于 Fragment 的 posted 接入。只在接入时扫描一次，之后靠
            // RecyclerView 的真实 bind/child-attach 事件补齐，不在滚动帧遍历页面树。
            fun discover(node: View) {
                if (node !is ViewGroup) return
                if (isFeedList(node)) {
                    list(node)
                    for (i in 0 until node.childCount) childAttached(node.getChildAt(i))
                    return
                }
                for (i in 0 until node.childCount) discover(node.getChildAt(i))
            }
            discover(view)
            observe(view)
        }

        private fun applyPageColors(view: View) {
            edits.restore()
            val wallpaper = HostBackgroundPages.contains(view)
            edits.background(view, if (wallpaper) null else ColorDrawable(palette.background))
            if (personalFeed) {
                syncPersonalChrome(view, palette)
                find<View>(view, "header_container")?.let { edits.background(it, if (wallpaper) null else ColorDrawable(palette.background)) }
                text(view, "header")?.let { edits.text(it, palette.textPrimary) }
                text(view, "sub_title")?.let { edits.text(it, palette.textSecondary) }
                return
            }
            find<View>(view, "dy_app_bar")?.let { edits.background(it, if (wallpaper) null else ColorDrawable(palette.background)) }
            var ancestor: View? = view
            repeat(12) {
                val node = ancestor ?: return@repeat
                if (node.id == ids.getOrPut("following_home_container") {
                    node.resources.getIdentifier("following_home_container", "id", node.context.packageName)
                }) {
                    edits.background(node, if (wallpaper) null else ColorDrawable(palette.background))
                    text(node, "fo_title")?.let { edits.text(it, palette.textPrimary) }
                }
                ancestor = node.parent as? View
            }
        }

        private fun observe(view: View) {
            val tree = view.viewTreeObserver
            if (observer === tree) return
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            tree.addOnPreDrawListener(this); observer = tree
        }

        override fun onPreDraw(): Boolean {
            safely {
                val view = root.get() ?: return@safely
                // ViewPager 的相邻页会先滑入窗口，之后才收到选中/恢复回调。
                // 与 pause 的释放边界保持一致：仍参与绘制的页面必须在本帧补齐卡片。
                if (!view.isShown || ((!lifecycleActive || !userVisible) &&
                        (view.windowVisibility != View.VISIBLE || !HostViewVisibility.visible(view, visibleBounds)))) {
                    pause(); return@safely
                }
                if (paused) resume()
                if (personalFeed) syncPersonalChrome(view, palette)
                val now = SystemClock.uptimeMillis()
                if (now - themeReadAt >= 250) {
                    themeReadAt = now
                    val next = theme.read()
                    if (next != colors) {
                        colors = next; palette = FollowFeedStyle.palette(next)
                        session.updatePalette(palette)
                        applyPageColors(view)
                        selection?.updatePalette(palette)
                        header?.updatePalette(palette)
                        statusBar?.updatePalette(palette)
                        lists.values.forEach { it.updatePalette() }
                        rows.forEach { (row, state) -> if (state.page.get() === this) styleRow(row, state, this) }
                    }
                }
                if (!selectionAttempted) group(view, "dy_tab_layout")?.takeIf {
                    (it.getChildAt(0) as? ViewGroup)?.childCount?.let { count -> count > 0 } == true
                }?.let {
                    selectionAttempted = true
                    selection = FollowFeedSelection.create(it, session, palette)
                }
                selection?.sync(); selection?.resume()
                if (!personalFeed && compactHeaderSupported && !headerAttempted && selection != null) {
                    headerAttempted = true
                    header = FollowFeedHeader.create(view, requireNotNull(selection), palette)
                }
                header?.sync()
                if (header != null && statusBar == null && view is FrameLayout)
                    statusBar = FollowFeedStatusBar(view, session, palette)
                lists.values.forEach { it.update() }
                statusBar?.sync(lists.entries.firstOrNull { it.key.isShown && it.value.panelCount > 0 }?.key)
                statusBar?.diagnostics()?.takeIf { it.hasVisibleFrame && it.backend != LumenSurfaceBackend.STATIC }?.let {
                    if (!reportedStatus) {
                        reportedStatus = true
                        diagnostics("status-fusion-${it.backend}-${it.failure}")
                    }
                }
                if (!reportedDraw && session.diagnostics().firstVisibleDraws > 0) {
                    reportedDraw = true
                    diagnostics("visible")
                }
                if (!reportedCards && lists.values.any { it.panelCount > 0 } &&
                    (material.usesSharedField || session.diagnostics().staticDraws > 2)) {
                    reportedCards = true
                    diagnostics("cards-visible")
                }
            }
            return true
        }

        fun pause() {
            if (closed || paused) return
            // onPause 可能发生在 UP 主筛选/空间页的入场动画中，此时底页仍会绘制。
            // 保留最后一帧和背景替换资格，直到 View 真正隐藏/销毁。
            // 入场动画尚在绘制底页时释放表面会让整张动态瞬间退回宿主底色。
            val view = root.get()
            if (view != null && view.windowVisibility == View.VISIBLE && HostViewVisibility.visible(view, visibleBounds)) return
            paused = true
            statusBar?.pause()
            selection?.pause(); lists.values.forEach { it.releasePanels() }
            session.pause()
            diagnostics("paused")
        }

        fun headerPadding(requested: Int) = header?.mapNativePadding(requested) ?: requested

        fun setLifecycleActive(active: Boolean) {
            lifecycleActive = active
            if (active && userVisible) resume() else pause()
        }

        fun setUserVisible(visible: Boolean) {
            userVisible = visible
            if (visible && lifecycleActive) resume() else pause()
        }

        fun resume() {
            if (closed) return
            paused = false; session.resume()
            root.get()?.let(::observe)
        }

        private fun diagnostics(stage: String) {
            val d = session.diagnostics()
            report("[BIL] ${if (personalFeed) "UP 动态页" else "关注页"}凝光 $stage: surfaces=${d.attachedSurfaces}, static=${d.staticDraws}, " +
                "gpu=${d.gpuDraws}, software=${d.softwareDraws}, recordings=${d.contentRecordings}, palette=${d.paletteGeneration}")
        }

        override fun onViewAttachedToWindow(v: View) = resume()
        override fun onViewDetachedFromWindow(v: View) { close(); pages.remove(v) }
        override fun close() {
            if (closed) return
            closed = true
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            root.get()?.removeOnAttachStateChangeListener(this)
            header?.close(); header = null
            statusBar?.close(); statusBar = null
            selection?.close(); selection = null
            lists.values.forEach { it.close() }; lists.clear()
            rows.forEach { (_, state) -> if (state.page.get() === this) {
                state.edits.restore(); state.restoreInsets(); state.palette = null; state.page.clear()
            } }
            edits.restore(); session.close(); diagnostics("closed")
        }

        /** 表面只是列表的背景兄弟层；不拦截触摸，也不绘制自定义 Drawable。 */
        private inner class FeedList(private val list: ViewGroup) : AutoCloseable {
            private val parent = list.parent as ViewGroup
            private val layer = object : FrameLayout(list.context) {
                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
            }.apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isClickable = false; isFocusable = false
                clipChildren = false
                clipToPadding = false
            }
            private val listEdits = FollowFeedViewEdits()
            private var viewport: FollowFeedViewport? = null
            private val originalPadding = list.paddingBottom
            private var appliedPadding = originalPadding
            private val originalTopPadding = list.paddingTop
            private var appliedTopPadding = originalTopPadding
            private val panels = LinkedHashMap<Long, Panel>()
            private val references = LinkedHashMap<FollowFeedReferenceKey, Panel>()
            // 每次 update 的行 / 引用键快照，各遍复用（见 FollowFeedUpdateIndex）。
            private val updateIndex = FollowFeedUpdateIndex()
            private var referenceOptions = FollowFeedStyle.reference(palette)
            private val location = IntArray(2)
            private var dock: WeakReference<View>? = null
            private var dockChecked = false
            private var generation = 0
            private var materialScope: HostVideoCardSurface.Scope? = null

            private inner class Panel(reference: Boolean = false) : View.OnAttachStateChangeListener {
                val view = View(list.context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                var binding: LumenSurfaceBinding? = null
                private var materialPaint: android.graphics.drawable.Drawable? = null
                val bound: Boolean get() = binding?.isBound == true ||
                    materialPaint != null && view.background === materialPaint && materialScope?.closed == false
                var generation = -1
                var top = 0
                var bottom = 0
                var first: FollowFeedRow? = null
                var last: FollowFeedRow? = null
                var valid = true
                var lastChildIndex = -1
                init {
                    view.addOnAttachStateChangeListener(this)
                    // 后出现的外层卡片仍在所有内层表面下面。
                    layer.addView(view, if (reference) layer.childCount else 0, FrameLayout.LayoutParams(0, 0))
                }
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    binding?.close(); binding = null
                    materialScope?.release(view); materialPaint = null
                }
                fun close() {
                    binding?.close(); binding = null
                    materialScope?.release(view); materialPaint = null
                    view.removeOnAttachStateChangeListener(this)
                    layer.removeView(view)
                }
                fun include(row: FollowFeedRow, index: Int, child: View) {
                    val childTop = child.top + child.translationY.roundToInt() + list.top
                    val childBottom = child.bottom + child.translationY.roundToInt() + list.top
                    if (generation != this@FeedList.generation) {
                        generation = this@FeedList.generation; top = childTop; bottom = childBottom
                        first = row; last = row; valid = true
                    } else {
                        valid = valid && FollowFeedGrouping.joins(last!!, row, adjacent = index == lastChildIndex + 1)
                        bottom = childBottom; last = row
                    }
                    lastChildIndex = index
                }
                fun place(options: LumenSurfaceOptions, inset: Int): Boolean {
                    val radius = dp(options.radiusDp.roundToInt())
                    // 部分模块滚出视口时延伸缺失的首尾，避免中途产生圆角横边。
                    val panelTop = if (first?.first == true) top else minOf(top - radius, -radius)
                    val panelBottom = if (last?.last == true) bottom else maxOf(bottom + radius, list.height + radius)
                    val left = list.left + dp(FollowFeedStyle.OUTER_DP + inset)
                    val right = list.right - dp(FollowFeedStyle.OUTER_DP + inset)
                    if (view.left != left || view.top != panelTop || view.right != right || view.bottom != panelBottom)
                        view.layout(left, panelTop, right, panelBottom)
                    if (!bound && view.isAttachedToWindow) {
                        if (material.usesSharedField) {
                            val scope = materialScope ?: HostVideoCardSurface.Scope(parent, colors.dark, material = material)
                                .also { materialScope = it }
                            scope.refresh(colors.dark)
                            materialPaint = scope.apply(view, radius.toFloat())
                        } else binding = session.bind(view, options)
                        return true
                    }
                    return false
                }
            }

            init {
                parent.addView(layer, parent.indexOfChild(list), ViewGroup.LayoutParams(-1, -1))
                if (personalFeed) {
                    appliedTopPadding = maxOf(originalTopPadding, dp(FollowFeedStyle.GAP_DP))
                    list.setPadding(list.paddingLeft, appliedTopPadding, list.paddingRight, list.paddingBottom)
                }
                updatePalette()
            }

            fun updatePalette() {
                listEdits.restore()
                listEdits.background(parent, if (HostBackgroundPages.contains(list)) null else ColorDrawable(palette.background))
                listEdits.background(list, null)
                referenceOptions = FollowFeedStyle.reference(palette)
                references.values.forEach { it.binding?.update(referenceOptions) }
                materialScope?.refresh(colors.dark)
            }

            fun update() {
                if (!list.isShown || !list.isAttachedToWindow) { releasePanels(); return }
                // UP 主页的标题与列表共享 RelativeLayout，卡片层只绘制列表视口。
                if (personalFeed) {
                    val bounds = Rect(list.left, list.top, list.right, list.bottom)
                    if (layer.clipBounds != bounds) layer.clipBounds = bounds
                }
                if (header != null) {
                    if (viewport == null) viewport = root.get()?.let { FollowFeedViewport(list, it as ViewGroup) }
                    viewport?.sync(root.get()?.paddingTop ?: 0)
                }
                generation++
                var surfacesChanged = false
                // 行与引用键每次 update 只收集一次：之后各遍复用，清理离屏引用也不再为每个存量引用重扫全部子 View。
                updateIndex.rebuild(list.childCount) { rows[list.getChildAt(it)]?.row }
                // 先回收离屏引用，不能让上一屏的内层表面占住新动态的配额。
                val staleReferences = references.iterator()
                while (staleReferences.hasNext()) {
                    val entry = staleReferences.next()
                    if (!updateIndex.hasReference(entry.key)) {
                        entry.value.close(); staleReferences.remove(); surfacesChanged = true
                    }
                }
                for (i in 0 until list.childCount) {
                    val child = list.getChildAt(i)
                    val row = updateIndex.rowAt(i) ?: continue
                    val panel = panels[row.identity] ?: run {
                        // 配额优先保证外层承托；内层不足时退回宿主引用底色。
                        if (lists.values.sumOf { it.panelCount } >= FollowFeedStyle.MAX_CARD_SURFACES)
                            lists.values.forEach { if (it.releaseReferences()) surfacesChanged = true }
                        if (lists.values.sumOf { it.panelCount } < FollowFeedStyle.MAX_CARD_SURFACES)
                            Panel().also { panels[row.identity] = it; surfacesChanged = true }
                        else null
                    } ?: continue
                    panel.include(row, i, child)
                }
                val iterator = panels.iterator()
                while (iterator.hasNext()) {
                    val panel = iterator.next().value
                    if (panel.generation != generation || !panel.valid) {
                        panel.close(); iterator.remove(); surfacesChanged = true; continue
                    }
                    if (panel.place(cardOptions, 0)) surfacesChanged = true
                }
                for (i in 0 until list.childCount) {
                    val child = list.getChildAt(i)
                    val row = updateIndex.rowAt(i) ?: continue
                    val reference = row.reference ?: continue
                    val key = updateIndex.keyAt(i) ?: continue
                    if (panels[row.identity]?.valid != true) continue
                    val panel = references[key] ?: if (lists.values.sumOf { it.panelCount } < FollowFeedStyle.MAX_CARD_SURFACES) {
                        Panel(reference = true).also { references[key] = it; surfacesChanged = true }
                    } else continue
                    panel.include(FollowFeedRow(reference.identity, reference.first, reference.last, row.position), i, child)
                }
                val referenceIterator = references.iterator()
                while (referenceIterator.hasNext()) {
                    val panel = referenceIterator.next().value
                    if (panel.generation != generation || !panel.valid) {
                        panel.close(); referenceIterator.remove(); surfacesChanged = true; continue
                    }
                    if (panel.place(referenceOptions, FollowFeedStyle.REFERENCE_INSET_DP))
                        surfacesChanged = true
                }
                // 分组验证和预算检查通过后才移除模块底色。未知布局、重复身份或配额
                // 不足时保留原模块，不能出现只有透明内容而缺失承托表面的半改版。
                for (i in 0 until list.childCount) {
                    val child = list.getChildAt(i)
                    val state = rows[child] ?: continue
                    val panel = state.row?.let { panels[it.identity] }
                    val decorate = panel?.let { it.valid && it.generation == generation } == true
                    // 行未变时复用本次 update 已建好的键，否则按当前行重算（装饰遍之前的回调可能改过 state.row）。
                    val rowKey = if (state.row === updateIndex.rowAt(i)) updateIndex.keyAt(i) else state.row?.referenceKey()
                    val referenceDecorate = rowKey?.let { references[it]?.bound } == true
                    if (state.decorated != decorate || state.referenceDecorated != referenceDecorate) {
                        state.edits.restore(); state.palette = null; state.decorated = decorate
                        state.referenceDecorated = referenceDecorate
                    }
                    styleRow(child, state, this@Page)
                }
                // 原 ItemDecoration 的矩形底色也在列表显示缓存中。绑定重建/撤销
                // 后必须刷新一次，不能等用户滚动才清掉暂停时录入的底色。
                if (surfacesChanged) list.invalidate()
                updateBottomPadding()
            }

            private fun updateBottomPadding() {
                if (!dockChecked) {
                    dockChecked = true
                    dock = WeakReference(find<View>(list.rootView, "bottom_navigation"))
                }
                list.getLocationInWindow(location)
                val viewportBottom = location[1] + list.height
                val bar = dock?.get()?.takeIf { it.isShown && it.height > 0 }
                val dockTop = bar?.let { it.getLocationInWindow(location); location[1] }
                @Suppress("DEPRECATION")
                val systemBottom = list.rootWindowInsets?.systemWindowInsetBottom ?: 0
                val padding = FollowFeedStyle.bottomPadding(originalPadding, viewportBottom, dockTop, systemBottom, dp(FollowFeedStyle.GAP_DP))
                if (padding != appliedPadding && list.paddingBottom == appliedPadding) {
                    list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, padding)
                    appliedPadding = padding
                }
            }

            fun releasePanels() {
                val hadReferences = releaseReferences()
                if (panels.isEmpty() && !hadReferences && materialScope == null) return
                panels.values.forEach { it.close() }; panels.clear()
                materialScope?.close(); materialScope = null
                list.invalidate()
            }
            fun releaseReferences(): Boolean {
                if (references.isEmpty()) return false
                references.values.forEach { it.close() }; references.clear()
                return true
            }
            fun hasSurface(identity: Long) = panels[identity]?.let { it.bound && it.view.isAttachedToWindow } == true
            val panelCount: Int get() = panels.size + references.size
            override fun close() {
                releasePanels(); viewport?.close(); viewport = null
                parent.removeView(layer); listEdits.restore()
                list.setPadding(list.paddingLeft,
                    if (list.paddingTop == appliedTopPadding) originalTopPadding else list.paddingTop,
                    list.paddingRight, if (list.paddingBottom == appliedPadding) originalPadding else list.paddingBottom)
            }
        }
    }

}
