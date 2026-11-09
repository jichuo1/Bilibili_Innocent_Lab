package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.view.RoundedCorner
import android.view.View
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import kotlin.math.max

/** API 31 的 RoundedCorner 只留在这一层：共用类的方法签名和字节码里都不能出现这个类型。 */
@RequiresApi(31)
internal object HostDockCornerApi31 {
    /** 把左右下圆角在窗口纵坐标 [y] 处的水平侵入量（像素）并入 [sides]（左、右）。 */
    @DoNotInline
    fun bottomIntrusion(view: View, y: Float, sides: IntArray) {
        val insets = view.rootWindowInsets ?: return
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.let { sides[0] = max(sides[0], intrusion(it, y)) }
        insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.let { sides[1] = max(sides[1], intrusion(it, y)) }
    }

    private fun intrusion(corner: RoundedCorner, y: Float): Int =
        HostDockCorner.intrusion(corner.radius.toFloat(), corner.center.y.toFloat(), y).toInt()
}
