package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/**
 * 读取宿主列表的原始内容坐标，视觉层的 translationY 不参与计算。
 * RecyclerView 来自宿主 ClassLoader，不能依靠模块的 RecyclerView 类型转换。
 */
internal class HostTopListPosition(val view: View) {
    private val layoutManager = runCatching {
        KavaMemberLookup.inheritedMethodOrNull(view.javaClass, "getLayoutManager")?.invoke(view)
    }.getOrNull()
    private val position = layoutManager?.let {
        KavaMemberLookup.inheritedMethodOrNull(it.javaClass, "getPosition", View::class.java)
    }
    private val decoratedTop = layoutManager?.let {
        KavaMemberLookup.inheritedMethodOrNull(it.javaClass, "getDecoratedTop", View::class.java)
    }
    private val decoratedHeight = layoutManager?.let {
        KavaMemberLookup.inheritedMethodOrNull(it.javaClass, "getDecoratedMeasuredHeight", View::class.java)
    }

    /** 预绘制每帧读取且只在当帧消费：复用同一实例，不逐帧分配。 */
    class Edge {
        var distance = 0f
        var firstRowExtent = Float.POSITIVE_INFINITY
    }
    private val reusableEdge = Edge()

    private fun edgeOf(distance: Float, firstRowExtent: Float = Float.POSITIVE_INFINITY) = reusableEdge.also {
        it.distance = distance
        it.firstRowExtent = firstRowExtent
    }

    fun edge(): Edge? = runCatching {
        if (view is ScrollView) return@runCatching edgeOf(view.scrollY.coerceAtLeast(0).toFloat())
        val children = view as? ViewGroup ?: return@runCatching null
        if (children.childCount == 0) return@runCatching null
        if (view is AbsListView) {
            if (view.firstVisiblePosition != 0) return@runCatching edgeOf(Float.POSITIVE_INFINITY)
            val first = view.getChildAt(0)
            val distance = if (view.canScrollVertically(-1)) (view.paddingTop - first.top).coerceAtLeast(0) else 0
            return@runCatching edgeOf(distance.toFloat(),
                (first.height + view.paddingTop).toFloat())
        }
        if (layoutManager == null || position == null || decoratedTop == null) return@runCatching null
        var pendingLayout = false
        for (i in 0 until children.childCount) {
            val child = children.getChildAt(i)
            val index = position.invoke(layoutManager, child) as? Int ?: return@runCatching null
            if (index < 0) pendingLayout = true
            if (index != 0) continue
            val top = decoratedTop.invoke(layoutManager, child) as? Int ?: return@runCatching null
            val height = decoratedHeight?.invoke(layoutManager, child) as? Int ?: child.height
            val distance = if (view.canScrollVertically(-1)) (view.paddingTop - top).coerceAtLeast(0) else 0
            return@runCatching edgeOf(distance.toFloat(),
                (height + view.paddingTop).coerceAtLeast(1).toFloat())
        }
        // 刷新/预测布局尚未分配 adapter 位置时沿用上一帧，不把 NO_POSITION 当成远离顶部。
        if (pendingLayout) null else edgeOf(Float.POSITIVE_INFINITY)
    }.getOrNull()
}
