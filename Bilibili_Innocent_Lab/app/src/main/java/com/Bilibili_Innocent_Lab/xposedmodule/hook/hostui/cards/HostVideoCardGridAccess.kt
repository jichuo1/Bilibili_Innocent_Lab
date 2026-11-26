package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method

/** 安装期定位成员，测量热路径只使用本地 Method 引用，不再查询反射缓存。 */
internal class HostVideoCardGridAccess private constructor(
    val measureChild: Method,
    val measureLinearChild: Method,
    private val calculateDecorations: Method,
    private val gridClass: Class<*>,
    private val linearClass: Class<*>,
    private val paramsClass: Class<*>,
    private val spanCount: Method,
    private val orientation: Method,
    private val spanSize: Method,
    private val spanIndex: Method
) {
    fun decorations(manager: Any, view: android.view.View, out: android.graphics.Rect) {
        calculateDecorations.invoke(manager, view, out)
    }

    /** -1 保留宿主布局，0 左列 / 1 右列 / 2 通栏 / 3 中间列。 */
    fun spacingSpan(manager: Any, params: Any): Int {
        if (!linearClass.isInstance(manager) || orientation.invoke(manager) != 1) return -1
        if (!gridClass.isInstance(manager)) return 2
        val count = spanCount.invoke(manager) as? Int ?: return -1
        if (!paramsClass.isInstance(params)) return -1
        val size = spanSize.invoke(params) as? Int ?: return -1
        if (size == count) return 2
        if (count !in 2..4 || size != 1) return -1
        val index = (spanIndex.invoke(params) as? Int)?.takeIf { it in 0 until count } ?: return -1
        return when (index) {
            0 -> 0
            count - 1 -> 1
            else -> 3
        }
    }

    fun doubleColumnSpan(manager: Any, params: Any): Int {
        if (!paramsClass.isInstance(params) || spanCount.invoke(manager) != 2 || orientation.invoke(manager) != 1 || spanSize.invoke(params) != 1) return -1
        return (spanIndex.invoke(params) as? Int)?.takeIf { it in 0..1 } ?: -1
    }

    companion object {
        fun resolve(loader: ClassLoader): HostVideoCardGridAccess? {
            val grid = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.GridLayoutManager") ?: return null
            val linear = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.LinearLayoutManager") ?: return null
            val layout = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$LayoutManager") ?: return null
            val params = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.GridLayoutManager\$LayoutParams") ?: return null
            val signature = arrayOf(android.view.View::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            val measure = KavaMemberLookup.methodOrNull(grid, "measureChild", *signature)
                ?: KavaMemberLookup.declaredMethods(grid, makeAccessible = true) {
                    it.returnType == Void.TYPE && it.parameterTypes.contentEquals(signature)
                }.singleOrNull() ?: return null
            val linearMeasure = KavaMemberLookup.methodOrNull(layout, "measureChildWithMargins",
                android.view.View::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!) ?: return null
            val decorations = KavaMemberLookup.methodOrNull(layout, "calculateItemDecorationsForChild",
                android.view.View::class.java, android.graphics.Rect::class.java) ?: return null
            return HostVideoCardGridAccess(measure, linearMeasure, decorations, grid, linear, params,
                KavaMemberLookup.inheritedMethodOrNull(grid, "getSpanCount") ?: return null,
                KavaMemberLookup.inheritedMethodOrNull(linear, "getOrientation") ?: return null,
                KavaMemberLookup.inheritedMethodOrNull(params, "getSpanSize") ?: return null,
                KavaMemberLookup.inheritedMethodOrNull(params, "getSpanIndex") ?: return null)
        }
    }
}
