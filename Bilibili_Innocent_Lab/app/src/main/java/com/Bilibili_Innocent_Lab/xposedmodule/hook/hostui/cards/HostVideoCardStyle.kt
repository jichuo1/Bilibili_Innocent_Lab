package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 处理宿主 RecyclerView 视频条目及其不感兴趣反馈占位卡；不遍历 Activity 或播放器。 */
internal class HostVideoCardStyle(
    private val grid: HostVideoCardGridAccess?,
    private val host: HostVideoCardHostAccess,
    private val radiusDp: Int,
    private val onApplied: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val decorateCards: Boolean = true,
    private val background: com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController? = null,
    private val homeSpacing: HostHomeCardSpacingSpec = HostHomeCardSpacingSpec()
) {
    private class Cover(view: View) {
        val view = WeakReference(view)
        var width = -1
        var height = -1
        var nativeRadius = -1f
    }

    private class State(
        val childCount: Int,
        val covers: Array<Cover>,
        val info: WeakReference<View>?,
        val spacing: HostVideoCardSpacing,
        val feedback: Boolean,
        val livePanel: Boolean,
        val nativePanels: Array<WeakReference<View>>
    ) : ViewOutlineProvider() {
        var surface: Drawable? = null
        var surfaceRadius = -1f
        val geometry = HostVideoCardGeometry()
        var decorated = false
        var color = 0
        var integratedCover = false

        fun updateGeometry(width: Int, height: Int, radius: Float): Boolean {
            return geometry.update(width, height, radius)
        }

        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, geometry.radius)
        }
    }

    // 值只弱引用子 View，避免 WeakHashMap 的值经 child.parent 反向持有 key。
    private val states = WeakHashMap<View, State>()
    private val homeRoots = WeakHashMap<View, Boolean>()

    fun markHome(root: View) { homeRoots[root] = true }

    private fun isInHome(root: View): Boolean {
        var current: View? = root
        while (current != null) {
            if (homeRoots.containsKey(current)) return true
            current = current.parent as? View
        }
        return false
    }
    private val excluded = WeakHashMap<View, Int>()
    private val listWatchers = WeakHashMap<ViewGroup, ListWatcher>()
    private val resourceKinds = SparseIntArray()
    private val recyclerTypes = HashMap<Class<*>, Boolean>()
    private val rowDecorations = android.graphics.Rect()
    private val coverNames = setOf("cover_layout", "cover", "video_cover", "iv_cover", "cover_image", "image_cover", "cover_container", "thumbnail", "pic", "ivCover")
    private val titleNames = setOf("title", "title_layout", "video_title", "tv_title", "tvTitle")
    private val coverOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height,
                HostVideoCardStyleSpec.coverRadius(view.width, view.height, radiusDp, view.resources.displayMetrics.density))
        }
    }
    private val layoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        // 滚动只改变位置，跳过所有查找、反射、遍历和轮廓重建。
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) safelyApply(view)
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = safelyApply(view)
        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    /** 每帧每个列表只读一次宿主主题；平时仅比较缓存值，不扫描子树或重新测量。 */
    private inner class ListWatcher(parent: ViewGroup) : ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
        private val parent = WeakReference(parent)
        private var observer: WeakReference<ViewTreeObserver>? = null

        fun start() {
            val current = parent.get()?.viewTreeObserver ?: return
            if (observer?.get() === current) return
            stop()
            current.addOnPreDrawListener(this)
            observer = WeakReference(current)
        }

        private fun stop() {
            observer?.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
        }

        override fun onViewAttachedToWindow(view: View) = start()
        override fun onViewDetachedFromWindow(view: View) = stop()

        override fun onPreDraw(): Boolean {
            val list = parent.get() ?: return true
            val night = host.isNight(list.context)
            background?.apply(list, night)
            if (!decorateCards) return true
            val color = HostVideoCardSurface.color(night)
            // 宿主换肤可能同时替换列表背景和卡片背景，当前帧绘制前一并恢复。
            for (i in 0 until list.childCount) {
                val root = list.getChildAt(i) as? ViewGroup ?: continue
                val state = states[root] ?: continue
                if (!state.decorated) continue
                var changed = state.color != color || root.background !== state.surface ||
                    root.outlineProvider !== state || !root.clipToOutline
                if (!changed) changed = state.nativePanels.any { it.get()?.background != null }
                if (!changed) for (cached in state.covers) {
                    val cover = cached.view.get() ?: continue
                    val outline = if (state.integratedCover) ViewOutlineProvider.BOUNDS else coverOutline
                    if (cover.outlineProvider !== outline || cover.clipToOutline == state.integratedCover ||
                        cover.width != cached.width || cover.height != cached.height) {
                        changed = true
                        break
                    }
                }
                if (changed) safelyApply(root, night)
            }
            return true
        }
    }

    fun replacesSeparators(list: ViewGroup): Boolean {
        if (!decorateCards || isInPlayer(list)) return false
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            val state = states[child] ?: continue
            if (state.decorated && child.background === state.surface) return true
        }
        return false
    }

    fun bind(root: View, feedback: Boolean = false) {
        if (root !is ViewGroup) return
        if (isInPlayer(root)) return
        var state = states[root]
        if (state == null || state.feedback != feedback || state.childCount != root.childCount || !coversBelongTo(root, state)) {
            if (!feedback && state == null && excluded[root] == root.childCount) return
            val discovered = discover(root, state?.spacing, feedback)
            if (discovered == null) {
                excluded[root] = root.childCount
                return
            }
            if (state == null && (decorateCards || background != null)) {
                root.addOnLayoutChangeListener(layoutListener)
                root.addOnAttachStateChangeListener(attachListener)
            }
            state = discovered
            states[root] = state
            excluded.remove(root)
        }
        // 字号和封面信息留白在绑定/首次测量前设置，布局回调不再修改 LayoutParams。
        if (decorateCards) {
            configureInfo(root, state)
            // 历史等横向条目的封面原本贴着透明条目边缘；卡片化后补齐内侧留白。
            val cover = state.covers.singleOrNull()?.view?.get()
            val params = cover?.layoutParams as? ViewGroup.MarginLayoutParams
            if (root.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                cover?.parent === root && params != null && params.width > 0 && params.leftMargin == 0) {
                val inset = (10f * root.resources.displayMetrics.density).toInt()
                if (root.paddingLeft < inset) root.setPadding(inset, root.paddingTop, root.paddingRight, root.paddingBottom)
            }
        }
        safelyApply(root)
    }

    private fun coversBelongTo(root: ViewGroup, state: State): Boolean {
        for (cached in state.covers) {
            var parent = cached.view.get()?.parent ?: return false
            var found = false
            for (depth in 0..10) {
                if (parent === root) { found = true; break }
                parent = parent.parent ?: break
            }
            if (!found) return false
        }
        return true
    }

    /** GridLayoutManager 已分配 span、尚未测量子项时调用；不发送 requestLayout。 */
    fun prepareMeasurement(manager: Any, root: View) {
        if (!decorateCards && !homeSpacing.enabled) return
        val state = states[root] ?: return
        if (isInPlayer(root)) return
        if (state.livePanel && !isInLiveHome(root)) return
        val params = root.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val span = grid?.spacingSpan(manager, params) ?: -1
        val spacing = state.spacing
        val custom = homeSpacing.enabled && isInHome(root)
        // 直接更新已被宿主持有的参数，原测量流程一次得到正确尺寸。
        val parent = root.parent as? View
        rowDecorations.setEmpty()
        if (span == 2 || custom) grid?.decorations(manager, root, rowDecorations)
        val density = root.resources.displayMetrics.density
        val left = spacing.left(if (decorateCards) span else -1, (parent?.paddingLeft ?: 0) + rowDecorations.left)
        val right = spacing.right(if (decorateCards) span else -1, (parent?.paddingRight ?: 0) + rowDecorations.right)
        val top = spacing.top(if (decorateCards) span else -1)
        val bottom = spacing.bottom(if (decorateCards) span else -1)
        params.setMargins(
            if (custom) homeSpacing.horizontalMargin(span, true, parent?.paddingLeft ?: 0, rowDecorations.left, density) ?: left else left,
            if (custom) homeSpacing.verticalMargin(span, true, rowDecorations.top, density) ?: top else top,
            if (custom) homeSpacing.horizontalMargin(span, false, parent?.paddingRight ?: 0, rowDecorations.right, density) ?: right else right,
            if (custom) homeSpacing.verticalMargin(span, false, rowDecorations.bottom, density) ?: bottom else bottom)
        if (custom && span in 0..2) onApplied()
    }

    private fun discover(root: ViewGroup, previousSpacing: HostVideoCardSpacing?, feedback: Boolean): State? {
        val covers = ArrayList<Cover>(1)
        var hasTitle = false
        var playerContent = false
        var info: WeakReference<View>? = null
        var liveInfo: View? = null
        var livePanel = root.javaClass.name == "tv.danmaku.bili.widget.RoundRectFrameLayout"
        fun visit(view: View, depth: Int) {
            if (depth > 10 || depth > 0 && isRecycler(view.javaClass)) return
            when (resourceName(view)) {
                "bottomContaner", "title_area" -> liveInfo = view
                "ivCover", "inline_area" -> livePanel = true
                "home_head_layout" -> { livePanel = true; liveInfo = view }
            }
            when (resourceKind(view)) {
                PLAYER -> { playerContent = true; return }
                COVER -> if (view is ImageView || view is ViewGroup) {
                    covers += Cover(view)
                    return // 容器统一裁切图片、渐变和角标。
                }
                TITLE -> if (view is TextView || view is ViewGroup) hasTitle = true
                INFO -> if (depth == 1) info = WeakReference(view)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        // 占位卡保留整卡裁切、柔影与双列留白，不修改反馈内容的内部布局。
        if (!feedback) visit(root, 0)
        if (playerContent || !feedback && (!hasTitle || covers.isEmpty() && !livePanel)) return null
        val params = root.layoutParams as? ViewGroup.MarginLayoutParams
        val spacing = previousSpacing ?: HostVideoCardSpacing(params?.leftMargin ?: 0,
            params?.topMargin ?: 0, params?.rightMargin ?: 0, params?.bottomMargin ?: 0,
            root.resources.displayMetrics.density)
        return State(root.childCount, covers.toTypedArray(), info, spacing, feedback, livePanel,
            (if (livePanel) listOfNotNull(liveInfo) else emptyList()).map { WeakReference(it) }.toTypedArray())
    }

    private fun configureInfo(root: ViewGroup, state: State) {
        val child = state.info?.get() ?: return
        val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = root.resources.displayMetrics.density
        val inset = (12f * density).toInt()
        val bottom = (9f * density).toInt()
        if (params.leftMargin != inset || params.rightMargin != inset || params.bottomMargin < bottom) {
            params.leftMargin = inset
            params.rightMargin = inset
            params.bottomMargin = maxOf(params.bottomMargin, bottom)
            child.layoutParams = params
        }
        if (child is ViewGroup) for (i in 0 until child.childCount) {
            val text = child.getChildAt(i) as? TextView ?: continue
            val target = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, text.resources.displayMetrics)
            if (text.textSize != target) text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            // 图标独立保持原来的 9sp 尺寸，调整字号时不再一起改变图标。
            val iconTarget = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, text.resources.displayMetrics)
            val icons = text.compoundDrawables
            var iconsChanged = false
            for (icon in icons) {
                if (icon == null) continue
                val width = icon.bounds.width()
                val height = icon.bounds.height()
                val size = maxOf(width, height)
                if (size <= iconTarget || size <= 0) continue
                val scale = iconTarget / size
                icon.setBounds(0, 0, (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1))
                iconsChanged = true
            }
            if (iconsChanged) text.setCompoundDrawables(icons[0], icons[1], icons[2], icons[3])
        }
    }

    private fun safelyApply(view: View, night: Boolean? = null) {
        if (!decorateCards && background == null) return
        val root = view as? ViewGroup ?: return
        val state = states[root] ?: return
        runCatching { apply(root, state, night) }.onFailure(onError)
    }

    private fun apply(root: ViewGroup, state: State, nightOverride: Boolean?) {
        val parent = root.parent as? ViewGroup ?: return
        if (!isRecycler(parent.javaClass)) return
        if (isInPlayer(parent)) return
        if (state.livePanel && !isInLiveHome(root)) return
        val night = nightOverride ?: host.isNight(root.context)
        background?.apply(parent, night)
        watch(parent)
        if (!decorateCards) return
        var count = 0
        var single: View? = null
        for (cached in state.covers) {
            val cover = cached.view.get() ?: continue
            // 不改变 Story 竖屏播放器或全屏媒体容器。
            if (cover.width <= 0 || cover.height <= 0 || !state.livePanel && cover.height >= parent.height * 0.7f) continue
            count++
            single = cover
        }
        if (count == 0 && !state.feedback && !state.livePanel) return
        // 贴满卡片顶部的封面由整个卡片统一裁切，底边保持直线，避免圆角切出异色缺口。
        val integrated = count == 1 && single != null && integratedCover(root, single)
        state.integratedCover = integrated
        for (cached in state.covers) {
            val cover = cached.view.get() ?: continue
            if (cover.width <= 0 || cover.height <= 0 || !state.livePanel && cover.height >= parent.height * 0.7f) continue
            val resized = cached.width != cover.width || cached.height != cover.height
            cached.width = cover.width
            cached.height = cover.height
            val outline = if (integrated) ViewOutlineProvider.BOUNDS else coverOutline
            val replaced = cover.outlineProvider !== outline
            val nativeRadius = if (integrated) 0f else HostVideoCardStyleSpec.coverRadius(
                cover.width, cover.height, radiusDp, cover.resources.displayMetrics.density)
            if (cached.nativeRadius != nativeRadius || replaced) {
                host.setCoverRadius(cover, nativeRadius)
                cached.nativeRadius = nativeRadius
            }
            if (cover.outlineProvider !== outline) cover.outlineProvider = outline
            if (cover.clipToOutline == integrated) cover.clipToOutline = !integrated
            if (resized && !replaced) cover.invalidateOutline()
        }
        val density = root.resources.displayMetrics.density
        val radius = if (integrated)
            HostVideoCardStyleSpec.coverRadius(single.width, single.height, radiusDp, density)
            else HostVideoCardStyleSpec.cardRadius(root.width, root.height, radiusDp, density)
        val resized = state.updateGeometry(root.width, root.height, radius)
        val color = HostVideoCardSurface.color(night)
        if (state.surface == null || state.surfaceRadius != radius || state.color != color) {
            val surface = HostVideoCardSurface.create(root, radius, night) ?: return
            state.color = color
            state.surfaceRadius = radius
            state.surface = surface
        }
        if (!state.decorated) {
            // 静态卡片由平台轮廓提供柔影；不再维护独立位图纹理。
            HostVideoCardShadow.apply(root)
            state.decorated = true
            onApplied()
        }
        if (root.background !== state.surface) root.background = state.surface
        // 信息区保留宿主约束和点击目标，背景由整卡统一承载，换肤后也不会重新出现方形底板。
        for (panel in state.nativePanels) panel.get()?.let { if (it.background != null) it.background = null }
        val replaced = root.outlineProvider !== state
        if (replaced) root.outlineProvider = state
        // 引擎背景、裁切与平台柔影共用半径。
        if (!root.clipToOutline) root.clipToOutline = true
        if (parent.clipChildren) parent.clipChildren = false
        if (resized && !replaced) root.invalidateOutline()
    }

    private fun watch(parent: ViewGroup) {
        if (!listWatchers.containsKey(parent)) {
            val watcher = ListWatcher(parent)
            listWatchers[parent] = watcher
            parent.addOnAttachStateChangeListener(watcher)
            if (parent.isAttachedToWindow) watcher.start()
        }
    }
    private fun resourceKind(view: View): Int {
        val id = view.id
        if (id == View.NO_ID) return OTHER
        val cached = resourceKinds.get(id, -1)
        if (cached >= 0) return cached
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrDefault("")
        val kind = when {
            // 结束页卡片在 bind 时可能尚未挂到播放器；其进度占位 ViewStub 可提前识别。
            name == "endpage_progess_root" || name == "endpage_progress_root" ||
                name == "video_container" || name == "video_area" || name == "control_container" ||
                name == "recycler_top_car" || name == "shortcut_video_view" -> PLAYER
            name in coverNames -> COVER
            name in titleNames -> TITLE
            name == "cover_bottom_info_container" -> INFO
            else -> OTHER
        }
        resourceKinds.put(id, kind)
        return kind
    }

    private fun resourceName(view: View): String = if (view.id == View.NO_ID) "" else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private fun isInLiveHome(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (resourceName(node) == "liveTabContainer") return true
            node = node.parent as? View
        }
        return false
    }

    private fun integratedCover(root: ViewGroup, cover: View): Boolean {
        var node = cover
        var left = cover.left
        var top = cover.top
        while (node.parent !== root) {
            node = node.parent as? View ?: return false
            left += node.left
            top += node.top
        }
        val tolerance = (2 * root.resources.displayMetrics.density).toInt()
        return left in 0..tolerance && top in 0..tolerance &&
            root.width - cover.width in 0..tolerance
    }

    /** 播放器结束页采用固定深色背景/浅色标题，不能套用页面卡片的主题表面。 */
    private fun isInPlayer(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (resourceKind(current) == PLAYER ||
                current.javaClass.name == "tv.danmaku.biliplayerimpl.controlcontainer.ControlContainer") return true
            current = current.parent as? View
        }
        return false
    }

    private fun isRecycler(type: Class<*>): Boolean = recyclerTypes.getOrPut(type) {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name == "androidx.recyclerview.widget.RecyclerView") return@getOrPut true
            current = current.superclass
        }
        false
    }

    private companion object {
        const val OTHER = 0
        const val COVER = 1
        const val TITLE = 2
        const val INFO = 3
        const val PLAYER = 4
    }
}
