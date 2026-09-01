package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

import android.content.Context
import android.view.View
import android.widget.ScrollView

/** 内容不足时自适应高度，内容较长时只把正文限制在弹窗可用区域。 */
internal class ReleaseNotesScrollView(context: Context) : ScrollView(context) {
    var maximumHeight: Int = Int.MAX_VALUE
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentSize = View.MeasureSpec.getSize(heightMeasureSpec)
        val parentMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val available = if (parentMode == View.MeasureSpec.UNSPECIFIED) {
            maximumHeight
        } else {
            parentSize.coerceAtMost(maximumHeight)
        }
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST)
        )
    }
}
