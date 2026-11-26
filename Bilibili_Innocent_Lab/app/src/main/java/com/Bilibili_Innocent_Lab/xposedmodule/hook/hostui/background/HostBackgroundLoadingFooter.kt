package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.util.SparseIntArray
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostViewResourceNames

/** 仅识别已应用壁纸列表的宿主加载尾项，保留进度动画、提示和点击行为。 */
internal class HostBackgroundLoadingFooter {
    private val kinds = SparseIntArray()

    fun apply(list: ViewGroup) {
        for (index in 0 until list.childCount) {
            val root = list.getChildAt(index) as? ViewGroup ?: continue
            // 实机尾项是三个直接子项；不递归搜索卡片或播放器里的进度条。
            if (root.childCount != 3) continue
            var mask = 0
            for (childIndex in 0 until root.childCount) {
                val child = root.getChildAt(childIndex)
                val kind = kind(child)
                if (kind == LOADING && child is ProgressBar ||
                    (kind == LEFT || kind == RIGHT) && child is TextView) mask = mask or kind
            }
            if (mask == (LOADING or LEFT or RIGHT) && root.background != null) root.background = null
        }
    }

    private fun kind(view: View): Int {
        if (view.id == View.NO_ID) return 0
        val cached = kinds.get(view.id, -1)
        if (cached >= 0) return cached
        val kind = when (HostViewResourceNames.name(view)) {
            "loading" -> LOADING
            "tv_left" -> LEFT
            "tv_right" -> RIGHT
            else -> 0
        }
        kinds.put(view.id, kind)
        return kind
    }

    private companion object {
        const val LOADING = 1
        const val LEFT = 2
        const val RIGHT = 4
    }
}
