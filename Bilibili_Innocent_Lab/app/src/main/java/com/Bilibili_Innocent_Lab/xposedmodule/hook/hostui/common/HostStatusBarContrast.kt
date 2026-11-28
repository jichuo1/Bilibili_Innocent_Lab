package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat

private fun readLightStatusBars(view: View): Boolean? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = view.windowInsetsController
            ?: resolveHostWindow(view.context)?.decorView?.windowInsetsController
            ?: return null
        return (controller.systemBarsAppearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0
    }
    val window = resolveHostWindow(view.context) ?: return null
    return WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars
}

private fun writeLightStatusBars(view: View, light: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = view.windowInsetsController
            ?: resolveHostWindow(view.context)?.decorView?.windowInsetsController
            ?: return
        controller.setSystemBarsAppearance(
            if (light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
        return
    }
    val window = resolveHostWindow(view.context) ?: return
    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = light
}

private fun resolveHostWindow(context: Context): Window? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return (current as? Activity)?.window
}

/** 只恢复自己写入的状态栏前景；宿主在页面切换时晚到的设置会成为新的原始值。 */
internal class HostStatusBarContrast internal constructor(
    private val readLight: (View) -> Boolean?,
    private val writeLight: (View, Boolean) -> Unit
) {
    constructor() : this(::readLightStatusBars, ::writeLightStatusBars)

    private var originalLightStatus: Boolean? = null
    private var appliedLightStatus: Boolean? = null

    fun apply(view: View, darkContent: Boolean) {
        val current = readLight(view) ?: return
        val applied = appliedLightStatus
        if (applied == null) {
            if (originalLightStatus == null) originalLightStatus = current
        } else if (current != applied) {
            originalLightStatus = current
        }
        val light = !darkContent
        if (current != light) writeLight(view, light)
        appliedLightStatus = light
    }

    fun restore(view: View) {
        val original = originalLightStatus ?: return
        val current = readLight(view)
        if (current != null && current == appliedLightStatus) writeLight(view, original)
        originalLightStatus = null
        appliedLightStatus = null
    }
}
