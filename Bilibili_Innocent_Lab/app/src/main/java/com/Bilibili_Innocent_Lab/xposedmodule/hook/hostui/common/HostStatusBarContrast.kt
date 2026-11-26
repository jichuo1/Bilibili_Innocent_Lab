package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.view.View
import androidx.core.view.ViewCompat

/** 只恢复自己写入的状态栏前景；宿主在页面切换时晚到的设置会成为新的原始值。 */
internal class HostStatusBarContrast {
    private var originalLightStatus: Boolean? = null
    private var appliedLightStatus: Boolean? = null

    fun apply(view: View, darkContent: Boolean) {
        val controller = ViewCompat.getWindowInsetsController(view) ?: return
        val current = controller.isAppearanceLightStatusBars
        val applied = appliedLightStatus
        if (applied == null) {
            if (originalLightStatus == null) originalLightStatus = current
        } else if (current != applied) {
            originalLightStatus = current
        }
        val light = !darkContent
        if (current != light) controller.isAppearanceLightStatusBars = light
        appliedLightStatus = light
    }

    fun restore(view: View) {
        val original = originalLightStatus ?: return
        val controller = ViewCompat.getWindowInsetsController(view)
        if (controller != null && controller.isAppearanceLightStatusBars == appliedLightStatus)
            controller.isAppearanceLightStatusBars = original
        originalLightStatus = null
        appliedLightStatus = null
    }
}
