@file:Suppress("SameParameterValue")

package Bilibili_Innocent_Lab.pro.ui.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import com.highcapable.betterandroid.ui.extension.component.base.isUiInNightMode
import com.highcapable.betterandroid.ui.extension.component.base.toPx
import com.highcapable.hikage.annotation.HikageView
import top.defaults.drawabletoolbox.DrawableBuilder

@HikageView
class MaterialSwitch(context: Context, attrs: AttributeSet?) : SwitchCompat(context, attrs) {

    private fun trackColors(selected: Int, pressed: Int, normal: Int): ColorStateList {
        val colors = intArrayOf(selected, pressed, normal)
        val states = arrayOfNulls<IntArray>(3)
        states[0] = intArrayOf(android.R.attr.state_checked)
        states[1] = intArrayOf(android.R.attr.state_pressed)
        states[2] = intArrayOf()
        return ColorStateList(states, colors)
    }

    private val thumbColor
        get() = if (resources.configuration.isUiInNightMode) 0xFF7C7C7C else 0xFFCCCCCC

    init {
        trackDrawable = DrawableBuilder()
            .rectangle()
            .rounded()
            .solidColor(0xFF656565.toInt())
            .height(20.toPx(context))
            .cornerRadius(15.toPx(context))
            .build()
        thumbDrawable = DrawableBuilder()
            .rectangle()
            .rounded()
            .solidColor(Color.WHITE)
            .size(20.toPx(context), 20.toPx(context))
            .cornerRadius(20.toPx(context))
            .strokeWidth(8.toPx(context))
            .strokeColor(Color.TRANSPARENT)
            .build()
        trackTintList = trackColors(
            0xFF656565.toInt(),
            thumbColor.toInt(),
            thumbColor.toInt()
        )
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
}