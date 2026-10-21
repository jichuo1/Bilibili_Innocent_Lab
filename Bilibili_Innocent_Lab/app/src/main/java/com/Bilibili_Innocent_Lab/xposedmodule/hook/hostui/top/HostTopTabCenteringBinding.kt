package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGlowView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** View-owned listeners retain bindings; this registry never retains a detached hierarchy. */
internal object NativeTopTabsCentering {
    private val bindings = WeakHashMap<ViewGroup, WeakReference<HostTopTabCenteringBinding>>()

    fun attach(dock: ViewGroup, tabs: ViewGroup?, onApplied: (() -> Unit)? = null): HostTopTabCenteringBinding? {
        tabs ?: return null
        val previous = synchronized(bindings) { bindings[dock]?.get() }
        if (previous?.matches(tabs) == true) { previous.updateCallback(onApplied); previous.sync(); return previous }
        previous?.close()
        return HostTopTabCenteringBinding(dock, tabs, onApplied).also {
            synchronized(bindings) { bindings[dock] = WeakReference(it) }
        }
    }
    fun release(dock: ViewGroup) {
        synchronized(bindings) { bindings.remove(dock)?.get() }?.close()
    }
}

/** Centers a short native tab strip through measurement, leaving its sibling actions untouched. */
internal class HostTopTabCenteringBinding internal constructor(
    private val dock: ViewGroup,
    private val tabs: ViewGroup,
    private var onApplied: (() -> Unit)? = null
) : AutoCloseable {
    private data class Target(val scroll: HorizontalScrollView, val strip: LinearLayout)
    private data class Generation(val target: Target, val dockWidth: Int, val dockHeight: Int,
                                  val viewport: List<Int>, val safeLeft: Int, val safeRight: Int,
                                  val items: List<View>, val itemWidths: List<Int>, val fontScale: Float)
    private class Ownership(val target: Target, val left: Int, val right: Int, val width: Int, val gravity: Int, val fillViewport: Boolean,
                            var applied: HostTopTabCenteringPolicy.Plan, var generation: Generation) {
        var mutated = false
        var confirmed = false
        val centeredGravity = gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK.inv() or Gravity.CENTER_HORIZONTAL
        fun intact(): Boolean = target.scroll.paddingLeft == applied.leftPadding &&
            target.scroll.paddingRight == applied.rightPadding && target.strip.layoutParams?.width == applied.stripWidth &&
            target.strip.gravity == centeredGravity && target.scroll.isFillViewport
        fun restore() {
            val scroll = target.scroll
            val restoreLeft = if (scroll.paddingLeft == applied.leftPadding) left else scroll.paddingLeft
            val restoreRight = if (scroll.paddingRight == applied.rightPadding) right else scroll.paddingRight
            if (scroll.paddingLeft != restoreLeft || scroll.paddingRight != restoreRight)
                scroll.setPadding(restoreLeft, scroll.paddingTop, restoreRight, scroll.paddingBottom)
            target.strip.layoutParams?.let { params ->
                if (params.width == applied.stripWidth && params.width != width) {
                    params.width = width
                    target.strip.layoutParams = params
                }
            }
            if (target.strip.gravity == centeredGravity && gravity != centeredGravity) target.strip.gravity = gravity
            if (scroll.isFillViewport && !fillViewport) scroll.isFillViewport = false
        }
    }
    private var owned: Ownership? = null
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private var rejected: Generation? = null
    private val titleIds = HashMap<Int, Boolean>()
    private val bounds = Rect()
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { sync() }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { observe(); sync() }
        override fun onViewDetachedFromWindow(view: View) { unobserve(); restore() }
    }

    init {
        dock.addOnAttachStateChangeListener(attachment)
        if (dock.isAttachedToWindow) { observe(); sync() }
    }

    private fun observe() {
        val current = dock.viewTreeObserver
        if (observer === current && current.isAlive) return
        unobserve()
        if (current.isAlive) { current.addOnGlobalLayoutListener(layout); observer = current }
    }
    private fun unobserve() {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
        observer = null
    }
    private fun restore() { owned?.restore(); owned = null }
    internal fun matches(candidate: ViewGroup): Boolean = !closed && tabs === candidate
    internal fun updateCallback(callback: (() -> Unit)?) {
        if (callback == null) return // A decoration-only attachment must not erase lifecycle diagnostics.
        val notifyExisting = onApplied == null && owned?.confirmed == true
        onApplied = callback
        if (notifyExisting) runCatching(callback)
    }

    /** Runs after a real layout; assignments are idempotent and never happen from a draw callback. */
    internal fun sync() {
        if (closed || !dock.isAttachedToWindow || dock.width <= 0) return
        val target = target() ?: run { restore(); return }
        var state = owned
        if (state != null && (state.target != target || !state.intact())) {
            // Preserve a host replacement instead of treating our previous values as a new baseline.
            restore()
            return
        }
        val items = (0 until target.strip.childCount).map { target.strip.getChildAt(it) }
            .filter { it.visibility != View.GONE && !HostTopBarFxController.isUnderlineIndicator(it) }
        if (items.size !in 1..2) { rejected = null; restore(); return }
        if (items.any { it.visibility != View.VISIBLE || it.width <= 0 || !hasTabTitle(it, 0) }) { restore(); return }
        // offsetDescendantRectToMyCoords subtracts the descendant's own scroll offsets too.
        // Start in content coordinates so this remains the physical viewport after a prior swipe.
        bounds.set(target.scroll.scrollX, target.scroll.scrollY,
            target.scroll.scrollX + target.scroll.width, target.scroll.scrollY + target.scroll.height)
        runCatching { dock.offsetDescendantRectToMyCoords(target.scroll, bounds) }.getOrElse { restore(); return }
        val actions = runCatching { actionInsets(target.scroll) }.getOrNull() ?: run { restore(); return }
        val generation = Generation(target, dock.width, dock.height,
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom), actions.first, actions.second,
            items, items.map { it.layoutParams?.width ?: 0 }, dock.resources.configuration.fontScale)
        if (generation == rejected) { restore(); return }
        if (rejected != null) rejected = null
        if (state != null && state.generation == generation) {
            // Custom HorizontalScrollView parents may override the final strip.layout() without
            // changing any LayoutParams. Confirm pixels in the real view tree after native layout.
            if (target.scroll.isLayoutRequested || target.strip.isLayoutRequested) return
            if (!centered(items, maxOf(actions.first, bounds.left + state.left),
                    minOf(dock.width - actions.second, bounds.right - state.right))) {
                rejected = generation
                restore()
                return
            }
            confirm(state)
        }
        val margins = target.strip.layoutParams as? ViewGroup.MarginLayoutParams
        val occupied = items.maxOf { it.right + ((it.layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin ?: 0) } -
            items.minOf { it.left - ((it.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0) }
        val plan = HostTopTabCenteringPolicy.plan(items.size, dock.width, actions.first, actions.second,
            bounds.left, bounds.right, state?.left ?: target.scroll.paddingLeft, state?.right ?: target.scroll.paddingRight,
            target.strip.paddingLeft, target.strip.paddingRight, margins?.leftMargin ?: 0, margins?.rightMargin ?: 0,
            occupied) ?: run { restore(); return }
        if (target.strip.minimumWidth > plan.stripWidth || (target.strip.background?.minimumWidth ?: 0) > plan.stripWidth) {
            restore(); return
        }
        if (state == null) {
            val params = target.strip.layoutParams ?: return
            state = Ownership(target, target.scroll.paddingLeft, target.scroll.paddingRight, params.width,
                target.strip.gravity, target.scroll.isFillViewport, plan, generation)
            owned = state
        }
        state.applied = plan
        state.generation = generation
        var changed = false
        // HorizontalScrollView first measures children with UNSPECIFIED width. fillViewport is
        // required for its second, exact viewport measurement; a LayoutParams width alone is ignored.
        if (!target.scroll.isFillViewport) { target.scroll.isFillViewport = true; changed = true }
        if (target.scroll.paddingLeft != plan.leftPadding || target.scroll.paddingRight != plan.rightPadding) {
            target.scroll.setPadding(plan.leftPadding, target.scroll.paddingTop, plan.rightPadding, target.scroll.paddingBottom)
            changed = true
        }
        val params = target.strip.layoutParams
        if (params.width != plan.stripWidth) { params.width = plan.stripWidth; target.strip.layoutParams = params; changed = true }
        if (target.strip.gravity != state.centeredGravity) { target.strip.gravity = state.centeredGravity; changed = true }
        if (changed) {
            state.mutated = true
            state.confirmed = false
        } else if (!target.scroll.isLayoutRequested && !target.strip.isLayoutRequested) {
            if (centered(items, maxOf(actions.first, bounds.left + state.left),
                    minOf(dock.width - actions.second, bounds.right - state.right))) confirm(state)
            else { rejected = generation; restore() }
        }
    }

    private fun centered(items: List<View>, safeLeft: Int, safeRight: Int): Boolean = runCatching {
        val rectangle = Rect()
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        for (item in items) {
            rectangle.set(item.scrollX, item.scrollY, item.scrollX + item.width, item.scrollY + item.height)
            dock.offsetDescendantRectToMyCoords(item, rectangle)
            left = minOf(left, rectangle.left)
            right = maxOf(right, rectangle.right)
        }
        left >= safeLeft && right <= safeRight && kotlin.math.abs(left.toLong() + right - dock.width) <= 2L
    }.getOrDefault(false)

    private fun confirm(state: Ownership) {
        if (!state.mutated || state.confirmed) return
        state.confirmed = true
        onApplied?.let { runCatching(it) }
    }

    private fun target(): Target? {
        var ancestor: View? = tabs
        while (ancestor != null && ancestor !== dock) {
            if (ancestor is HorizontalScrollView) return certify(ancestor)
            ancestor = ancestor.parent as? View
        }
        val candidates = ArrayList<HorizontalScrollView>(2)
        var visited = 0
        var truncated = false
        fun visit(view: View, depth: Int) {
            if (++visited > 64 || depth > 6) { truncated = true; return }
            if (candidates.size > 1) return
            if (view is HorizontalScrollView) { candidates += view; return }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
        }
        visit(tabs, 0)
        return if (truncated) null else candidates.singleOrNull()?.let(::certify)
    }
    private fun certify(scroll: HorizontalScrollView): Target? {
        if (scroll.childCount != 1 || scroll.width <= 0) return null
        val strip = scroll.getChildAt(0) as? LinearLayout ?: return null
        // Custom strips can override measuring/placement; only the standard Android row is writable.
        if (strip.javaClass != LinearLayout::class.java || strip.orientation != LinearLayout.HORIZONTAL) return null
        return Target(scroll, strip)
    }
    /** Also protects overlay actions whose viewport was not reduced by the host's parent layout. */
    private fun actionInsets(scroll: HorizontalScrollView): Pair<Int, Int>? {
        var left = dock.paddingLeft
        var right = dock.width - dock.paddingRight
        var visited = 0
        val rectangle = Rect()
        fun visit(view: View): Boolean {
            if (view.visibility != View.VISIBLE || view is HostDockLayer || view is HostGlowView) return true
            if (++visited > 64) return false
            if (view.isClickable || view.isFocusable || view.hasOnClickListeners()) {
                rectangle.set(0, 0, view.width, view.height)
                dock.offsetDescendantRectToMyCoords(view, rectangle)
                if (rectangle.width() <= 0 || rectangle.bottom <= bounds.top || rectangle.top >= bounds.bottom) return true
                when {
                    rectangle.right * 2 <= dock.width -> left = maxOf(left, rectangle.right)
                    rectangle.left * 2 >= dock.width -> right = minOf(right, rectangle.left)
                    else -> return false
                }
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) if (!visit(view.getChildAt(index))) return false
            }
            return true
        }
        var branch: View = scroll
        while (branch !== dock) {
            val parent = branch.parent as? ViewGroup ?: return null
            for (index in 0 until parent.childCount) {
                val sibling = parent.getChildAt(index)
                if (sibling !== branch && !visit(sibling)) return null
            }
            branch = parent
        }
        return left to dock.width - right
    }
    private fun hasTabTitle(view: View, depth: Int): Boolean {
        if (view is TextView) return view.id != View.NO_ID && view.id != 0 && titleIds.getOrPut(view.id) {
            runCatching { view.resources.getResourceEntryName(view.id) == "tab_title" }.getOrDefault(false)
        }
        return depth < 3 && view is ViewGroup && view.childCount <= 16 &&
            (0 until view.childCount).any { hasTabTitle(view.getChildAt(it), depth + 1) }
    }
    override fun close() {
        if (closed) return
        closed = true
        unobserve(); dock.removeOnAttachStateChangeListener(attachment); restore()
    }
}
