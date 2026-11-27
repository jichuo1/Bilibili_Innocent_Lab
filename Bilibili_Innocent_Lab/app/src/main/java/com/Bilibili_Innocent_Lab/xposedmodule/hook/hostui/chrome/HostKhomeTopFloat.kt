package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.BoundedClassCache
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * khome 9.16 lays its feed container out below `HomeAppBarLayout` (status-bar margin, collapsible
 * search row, capsule row) through a ScrollingViewBehavior. Floating the capsule takes over that
 * layout role: the container runs up to the CoordinatorLayout top, and every ViewPager2 page keeps
 * its resting position through an equal top padding on its own list, or on the page when it has
 * none. The host keeps the AppBar, its collapse/snap offsets, nested scrolling and touch handling.
 *
 * Runs in pre-draw only. Every write goes through an ownership ledger and is undone on [restore],
 * or, for the container margin, whenever the current bottom tab has no top bar.
 */
internal class HostKhomeTopFloat {
    internal class Frame(val root: ViewGroup, val appBar: ViewGroup, val content: ViewGroup?, val header: View?)

    private class RefreshAccess(val start: Method, val end: Method, val set: Method, val refreshing: Method)

    private val margins = HostChromeValueLedger<View, Int>(
        { (it.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0 },
        { view, value -> (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = value; view.layoutParams = it } })
    private val paddings = HostChromeValueLedger<View, Int>({ it.paddingTop },
        { view, value -> view.setPadding(view.paddingLeft, value, view.paddingRight, view.paddingBottom) })
    private val clips = HostChromeValueLedger<ViewGroup, Boolean>({ it.clipToPadding }, { view, value -> view.clipToPadding = value })
    private val refreshes = HostChromeValueLedger<View, Pair<Int, Int>>(
        { view -> refreshAccess(view)?.let { (it.start.invoke(view) as Int) to (it.end.invoke(view) as Int) } ?: (0 to 0) },
        { view, value -> refreshAccess(view)?.set?.invoke(view, false, value.first, value.second) },
        { HostKhomeFloatPolicy.restoredRefreshOffsets(it.first, it.second) })
    private val scrim = Scrim()
    private var scrimRoot: WeakReference<ViewGroup>? = null
    private var shift = 0
    private var dirty = true
    private var signature = 0L
    private val churnTracker = ChurnTracker()
    private var stopped = false

    var floating = false
        private set

    /** A host layout pass may have attached pages or replaced their lists. */
    fun markDirty() { dirty = true }

    /** Returns true when host layout was changed and the frame about to be drawn is stale. */
    fun sync(frame: Frame?, enabled: Boolean, scrimColor: Int?): Boolean {
        val content = frame?.content
        // A bottom tab without a top bar detaches the behavior and hides the AppBar; give the space back.
        if (frame == null || content == null || !enabled || stopped ||
            frame.appBar.visibility != View.VISIBLE || frame.appBar.height <= 0) return deactivate()
        // Without pages to pad, an extended container would put content under the capsule.
        val pager = findPager(content) ?: return deactivate()
        val strip = pager.getChildAt(0) as? ViewGroup ?: return deactivate()
        val appBarLp = frame.appBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return deactivate()
        val next = appBarLp.topMargin + frame.appBar.height
        if (next != shift) { shift = next; dirty = true }
        var changed = margins.restoreAll(except = content)
        changed = margins.apply(content) {
            HostKhomeFloatPolicy.contentMargin(it, appBarLp.topMargin, frame.appBar.height)
        } || changed
        floating = true
        val current = pages(strip)
        if (dirty || current != signature) {
            dirty = false
            signature = current
            changed = scan(strip) || changed
        }
        updateScrim(frame.root, appBarLp.topMargin, scrimColor)
        if (churnTracker.record(targetOffset = next, pageSignature = current, wroteLayout = changed, nowMs = SystemClock.uptimeMillis())) {
            stopped = true
            ModernHookLog.info("[BIL] 新版首页顶栏悬浮已停用：宿主持续改写布局")
            return restore()
        }
        return changed
    }

    /** Undo every module write that is still current; host rewrites since then are kept. */
    fun restore(): Boolean {
        var changed = margins.restoreAll()
        changed = paddings.restoreAll() || changed
        changed = clips.restoreAll() || changed
        for (view in refreshes.keys()) if (!refreshing(view)) changed = refreshes.restore(view) || changed
        removeScrim()
        floating = false
        shift = 0; dirty = true; signature = 0L
        churnTracker.reset()
        return changed
    }

    private fun deactivate(): Boolean {
        // Paddings stay on the hidden home pages; only the shared container is given back.
        val changed = margins.restoreAll()
        removeScrim()
        floating = false
        churnTracker.clearBaseline()
        return changed
    }

    private fun scan(strip: ViewGroup): Boolean {
        var changed = false
        for (index in 0 until strip.childCount) {
            val page = strip.getChildAt(index)
            // Unlaid pages change the signature once they are measured.
            if (page.width <= 0 || page.height <= 0) continue
            val list = primaryList(page)
            if (list == null) {
                // Compose or nested pagers: the page itself starts below the capsule, as natively.
                changed = paddings.apply(page) { it + shift } || changed
                continue
            }
            changed = paddings.restore(page) || changed
            val atTop = !list.canScrollVertically(-1)
            if (paddings.apply(list) { it + shift }) {
                changed = true
                // The first row keeps its old coordinates after setPadding; re-anchor only at the top.
                if (atTop) Lookup.inheritedMethodOrNull(list.javaClass, "scrollToPosition", Int::class.javaPrimitiveType!!)
                    ?.invoke(list, 0)
            }
            (list as? ViewGroup)?.let { clips.apply(it) { false } }
            refreshFor(list, page)?.let { refresh ->
                // Changing the offsets resets the spinner; never interrupt a running refresh.
                if (refreshing(refresh)) dirty = true
                else changed = refreshes.apply(refresh) { HostKhomeFloatPolicy.refreshOffsets(it.first, it.second, shift) } || changed
            }
        }
        return changed
    }

    private fun pages(strip: ViewGroup): Long {
        var value = strip.childCount.toLong()
        for (index in 0 until strip.childCount) {
            val page = strip.getChildAt(index)
            value = value * 31 + System.identityHashCode(page)
            value = value * 31 + page.width
            value = value * 31 + page.height
        }
        return value
    }

    private fun findPager(content: ViewGroup): ViewGroup? {
        var seen = 0
        fun visit(view: View, depth: Int): ViewGroup? {
            if (depth > 6 || ++seen > 96 || view.visibility != View.VISIBLE || view !is ViewGroup) return null
            if (hasType(view, "ViewPager2")) return view
            for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)?.let { return it }
            return null
        }
        return visit(content, 0)
    }

    private fun primaryList(page: View): View? {
        var seen = 0
        fun visit(view: View, depth: Int): View? {
            if (depth > 12 || ++seen > 400 || view.visibility != View.VISIBLE) return null
            if (hasType(view, "RecyclerView")) {
                // Horizontal lists are carousels or nested pagers; their children are not the feed.
                return view.takeIf { vertical(it) && HostKhomeFloatPolicy.primaryList(it.width, it.height, page.width, page.height) }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)?.let { return it }
            return null
        }
        return visit(page, 0)
    }

    private fun vertical(list: View): Boolean = runCatching {
        val manager = Lookup.inheritedMethodOrNull(list.javaClass, "getLayoutManager")?.invoke(list) ?: return false
        Lookup.inheritedMethodOrNull(manager.javaClass, "canScrollVertically")?.invoke(manager) == true
    }.getOrDefault(false)

    private fun refreshFor(list: View, page: View): View? {
        var node = list.parent as? View
        while (node != null) {
            if (hasType(node, "SwipeRefreshLayout")) return node.takeIf { refreshAccess(it) != null }
            if (node === page) return null
            node = node.parent as? View
        }
        return null
    }

    private fun refreshAccess(view: View): RefreshAccess? {
        val type = view.javaClass
        val int = Int::class.javaPrimitiveType!!
        return RefreshAccess(
            Lookup.inheritedMethodOrNull(type, "getProgressViewStartOffset") ?: return null,
            Lookup.inheritedMethodOrNull(type, "getProgressViewEndOffset") ?: return null,
            Lookup.inheritedMethodOrNull(type, "setProgressViewOffset", Boolean::class.javaPrimitiveType!!, int, int) ?: return null,
            Lookup.inheritedMethodOrNull(type, "isRefreshing") ?: return null)
    }

    private fun refreshing(view: View): Boolean =
        runCatching { refreshAccess(view)?.refreshing?.invoke(view) == true }.getOrDefault(true)

    private fun updateScrim(root: ViewGroup, statusBar: Int, color: Int?) {
        if (color == null || statusBar <= 0 || root.width <= 0) { removeScrim(); return }
        if (scrimRoot?.get() !== root) {
            removeScrim()
            root.overlay.add(scrim)
            scrimRoot = WeakReference(root)
        }
        scrim.update(color, root.width, statusBar)
    }

    private fun removeScrim() {
        scrimRoot?.get()?.overlay?.remove(scrim)
        scrimRoot = null
    }

    /** Keeps the status-bar icons legible over the feed; the AppBar below it is never covered. */
    private class Scrim : Drawable() {
        private val paint = Paint()
        private var color = 0
        private var height = 0

        fun update(color: Int, width: Int, height: Int) {
            if (color != this.color || height != this.height) {
                this.color = color; this.height = height
                val solid = (color and 0xFFFFFF) or ((HostKhomeFloatPolicy.SCRIM_ALPHA * 255).roundToInt() shl 24)
                paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(solid, solid, color and 0xFFFFFF),
                    floatArrayOf(0f, HostKhomeFloatPolicy.SCRIM_HOLD, 1f), Shader.TileMode.CLAMP)
                invalidateSelf()
            }
            if (bounds.width() != width || bounds.height() != height) setBounds(0, 0, width, height)
        }

        override fun draw(canvas: Canvas) { if (!bounds.isEmpty) canvas.drawRect(bounds, paint) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    internal class ChurnTracker(
        private val limit: Int = HostKhomeFloatPolicy.CHURN_LIMIT,
        private val windowMs: Long = HostKhomeFloatPolicy.CHURN_WINDOW_MS
    ) {
        private var hasBaseline = false
        private var lastTargetOffset = 0
        private var lastPageSignature = 0L
        private var windowStartMs = 0L
        var count = 0
            private set

        /**
         * 仅在目标偏移量和页签签名未变化，却依然因宿主回滚布局产生写入时计数；
         * 首次注入及正常布局变化不计为抖动。
         */
        fun record(
            targetOffset: Int,
            pageSignature: Long,
            wroteLayout: Boolean,
            nowMs: Long
        ): Boolean {
            if (!hasBaseline || targetOffset != lastTargetOffset || pageSignature != lastPageSignature) {
                hasBaseline = true
                lastTargetOffset = targetOffset
                lastPageSignature = pageSignature
                return false
            }
            if (!wroteLayout) return false
            if (windowStartMs == 0L || nowMs - windowStartMs > windowMs) {
                windowStartMs = nowMs
                count = 0
            }
            return ++count > limit
        }

        fun clearBaseline() {
            hasBaseline = false
        }

        fun reset() {
            hasBaseline = false
            lastTargetOffset = 0
            lastPageSignature = 0L
            windowStartMs = 0L
            count = 0
        }
    }

    companion object {
        private val types = BoundedClassCache<ConcurrentHashMap<String, Boolean>>(
            maxEntries = 128,
            tag = "host.khome.types"
        )

        private fun hasType(view: View, name: String): Boolean =
            types.getOrPut(view.javaClass) { ConcurrentHashMap() }.getOrPut(name) {
                generateSequence(view.javaClass as Class<*>) { it.superclass }.any { it.simpleName == name }
            }

        private fun behavior(view: View): Any? = runCatching {
            val lp = view.layoutParams ?: return null
            Lookup.inheritedMethodOrNull(lp.javaClass, "getBehavior")?.invoke(lp)
        }.getOrNull()

        /**
         * [owner] is the AndroidComposeView of the second row (the `LocalView`), nested in its
         * ComposeView. The AppBar must be the row's direct container inside a CoordinatorLayout.
         */
        fun locate(owner: View, headerId: Int): Frame? {
            var parent = owner.parent as? ViewGroup
            var depth = 0
            while (parent != null && depth++ < 4 && !hasType(parent, "AppBarLayout")) parent = parent.parent as? ViewGroup
            val appBar = parent?.takeIf { hasType(it, "AppBarLayout") } ?: return null
            val root = appBar.parent as? ViewGroup ?: return null
            if (!hasType(root, "CoordinatorLayout")) return null
            val appBarIndex = root.indexOfChild(appBar)
            var header: View? = null
            var content: ViewGroup? = null
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child === appBar) continue
                if (index < appBarIndex && headerId != 0 && child.id == headerId && child.javaClass.name.endsWith("ComposeView")) header = child
                if (child is ViewGroup && behavior(child)?.let { hasType(it.javaClass, "ScrollingViewBehavior") } == true) content = child
            }
            return Frame(root, appBar, content, header)
        }

        private fun hasType(type: Class<*>, name: String): Boolean =
            generateSequence(type as Class<*>?) { it.superclass }.any { it.simpleName == name }
    }
}
