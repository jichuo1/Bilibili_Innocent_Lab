package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbColors

/** 底栏数量角标的填充色与文字色。 */
internal data class HostDockBadgeStyle(val fill: Int, val text: Int)

/**
 * 不依赖 Android 的角标策略。
 *
 * 浮动底栏会清掉宿主 tab 内所有 View 的背景，数量角标因此只剩一串文字，和玻璃底融在一起。
 * 打开「角标使用强调色」后，角标改为保留宿主自己的圆角底并染成强调色，文字取与之对比最高的黑或白，
 * 小号数字在任何强调色上都读得清。
 */
internal object HostDockBadgePolicy {
    /** 只认「带 badge 的文字」：数量角标是 TextView，红点这类图片角标不在范围内。 */
    fun isCountBadge(resourceName: String): Boolean = resourceName.contains("badge", ignoreCase = true)

    fun style(accent: Int): HostDockBadgeStyle {
        val fill = accent or 0xFF000000.toInt()
        return HostDockBadgeStyle(fill, onAccent(fill))
    }

    /** 黑白里对比度更高的一个；与调色板「亮色强调色配黑字」的取舍一致。 */
    fun onAccent(fill: Int): Int {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        return if (HostGarbColors.contrast(white, fill) >= HostGarbColors.contrast(black, fill)) white else black
    }
}

/**
 * 把强调色写到宿主的数量角标上。
 *
 * 染色走 View 级的背景着色而不是直接改 Drawable：宿主的 Tint 控件在重设背景或换肤时会按自己的
 * 着色重新套用，View 级着色是它们共用的入口，我们的值写在同一处，下一次核对发现被改就改回来。
 * 逐帧路径只做几次 getter 比较，没有变化就什么都不写、不分配。
 */
internal object HostDockBadgeAppearance {
    fun apply(view: TextView, style: HostDockBadgeStyle) {
        if (view.background == null) {
            // 宿主这一版没有给角标背景时补一个胶囊底，保证强调色有载体。
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * view.resources.displayMetrics.density
                setColor(0xFFFFFFFF.toInt())
            }
            if (view.paddingLeft == 0 && view.paddingRight == 0) {
                val horizontal = (4 * view.resources.displayMetrics.density).toInt()
                view.setPadding(horizontal, view.paddingTop, horizontal, view.paddingBottom)
            }
        }
        if (view.backgroundTintList?.defaultColor != style.fill) {
            view.backgroundTintList = ColorStateList.valueOf(style.fill)
        }
        if (view.currentTextColor != style.text) view.setTextColor(style.text)
    }
}
