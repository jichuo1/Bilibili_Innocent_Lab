package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import kotlin.math.max
import kotlin.math.min

/** Pixel geometry shared by native strips and Compose's native child placement. */
internal object HostTopTabCenteringPolicy {
    data class Plan(val leftPadding: Int, val rightPadding: Int, val stripWidth: Int)

    fun plan(count: Int, dockWidth: Int, dockLeft: Int, dockRight: Int, viewportLeft: Int, viewportRight: Int,
             paddingLeft: Int, paddingRight: Int, stripLeft: Int, stripRight: Int, marginLeft: Int, marginRight: Int,
             occupiedWidth: Int): Plan? {
        if (count !in 1..2 || dockWidth <= 0 || viewportLeft < 0 || viewportRight > dockWidth ||
            viewportRight <= viewportLeft || listOf(dockLeft, dockRight, paddingLeft, paddingRight, stripLeft,
                stripRight, marginLeft, marginRight).any { it < 0 } || occupiedWidth <= 0) return null
        val safeLeft = max(dockLeft, viewportLeft + paddingLeft) + marginLeft
        val safeRight = min(dockWidth - dockRight, viewportRight - paddingRight) - marginRight
        // LinearLayout centers inside its own padding; compensate asymmetric native strip padding.
        val centerTwice = dockWidth - stripLeft + stripRight
        var width = min(centerTwice - 2 * safeLeft, 2 * safeRight - centerTwice)
        if (((centerTwice - width) and 1) != 0) width--
        if (width <= 0 || occupiedWidth + stripLeft + stripRight > width) return null
        val left = (centerTwice - width) / 2
        val start = left - viewportLeft - marginLeft
        val end = viewportRight - left - width - marginRight
        return if (start >= paddingLeft && end >= paddingRight) Plan(start, end, width) else null
    }

    /** Returns a viewport-local child x, or null when the native scrolling layout must remain. */
    fun centeredOffset(count: Int, dockWidth: Int, viewportLeft: Int, viewportRight: Int, occupiedWidth: Int): Int? {
        val plan = plan(count, dockWidth, 0, 0, viewportLeft, viewportRight, 0, 0, 0, 0, 0, 0, occupiedWidth)
            ?: return null
        return plan.leftPadding + (plan.stripWidth - occupiedWidth) / 2
    }
}
