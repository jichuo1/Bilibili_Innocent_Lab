package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** 浅色使用背景色薄纱，深色使用 40% 黑；所有设置弹窗共用同一背板。 */
internal object SettingsModalScrim {
    fun color(surface: Int, background: Int): Int =
        if (ColorUtils.calculateLuminance(surface) < 0.5) 0x66000000
        else ColorUtils.setAlphaComponent(ColorUtils.blendARGB(background, Color.BLACK, 0.08f), 0x80)
}
