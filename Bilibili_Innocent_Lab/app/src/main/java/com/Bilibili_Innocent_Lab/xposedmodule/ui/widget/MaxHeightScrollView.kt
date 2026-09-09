package com.Bilibili_Innocent_Lab.xposedmodule.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.widget.ScrollView

/**
 * 按内容收敛、但不超过 [maxHeightPx] 的 [ScrollView]。
 *
 * `ScrollView` 只有"给定高度"和"无限撑开"两种行为，没有 maxHeight。设置搜索的结果区原先写死
 * 固定高度（min(360dp, 44% 屏高)），空结果时也占满——面板居中时不明显，改成贴着图标的气泡后
 * 下半部一大片留白就很显眼。
 *
 * 这里把测量模式改成 `AT_MOST(maxHeightPx)`：内容少就按内容高，内容多才停在上限并开始滚动。
 *
 * `isFillViewport` 必须保持 false，否则子 View 会被强行拉满视口，收敛就失效了。
 */
@SuppressLint("ViewConstructor")
internal class MaxHeightScrollView(
    context: Context,
    private val maxHeightPx: Int
) : ScrollView(context) {

    init {
        isFillViewport = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeightPx <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        )
    }
}
