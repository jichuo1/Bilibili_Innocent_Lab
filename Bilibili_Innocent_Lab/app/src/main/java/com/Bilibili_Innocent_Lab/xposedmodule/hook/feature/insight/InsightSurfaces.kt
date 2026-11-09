package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import com.lumen.coacervation.engine.host.LumenFluentControl
import com.lumen.coacervation.engine.host.LumenFluentControlOptions
import com.lumen.coacervation.engine.host.LumenFluentElevation
import com.lumen.coacervation.engine.host.LumenFluentElevationOptions

/** 使用项目已有宿主取色边界和 Lumen Fluent 控件；无独立固定配色。 */
internal class InsightSurfaces(private val root: View, private val panel: Boolean = true) : AutoCloseable {
    private val theme = HostChromeTheme(root.context)
    private var colors: HostChromeColors = theme.read()
    private val controls = mutableListOf<Pair<LumenFluentControl, Boolean>>()
    private val icons = mutableListOf<ImageView>()
    private val labels = mutableListOf<Pair<TextView, Boolean>>()
    private val observer = ViewTreeObserver.OnPreDrawListener {
        val next = theme.read()
        if (next != colors) { colors = next; refresh() }
        true
    }
    private var tree: ViewTreeObserver? = null
    init {
        if (panel) controls += LumenFluentControl(root, colors.palette(), options(false, true)) to false
        tree = root.viewTreeObserver.takeIf { it.isAlive }?.also { it.addOnPreDrawListener(observer) }
    }
    fun control(view: View, quiet: Boolean = false) {
        controls += LumenFluentControl(view, colors.palette(), options(!quiet, false)) to !quiet
        if (view is TextView) label(view, accent = !quiet)
    }
    fun icon(view: ImageView) {
        icons += view; view.imageTintList = ColorStateList.valueOf(colors.palette().textPrimary)
    }
    fun label(view: TextView, accent: Boolean = false) {
        labels += view to accent
        view.setTextColor(if (accent) colors.palette().primary else colors.palette().textPrimary)
        view.setLinkTextColor(colors.palette().primary)
    }
    private fun options(button: Boolean, panel: Boolean) = LumenFluentControlOptions(
        // 简介滚动头部与结果正文使用实色，避免字幕/视频透过文本；形状和状态层由 Lumen 绘制。
        acrylic = LumenAcrylicOptions(enabled = false, fallbackColor =
            if (button) colors.palette().surfaceVariant else colors.palette().surface),
        elevation = LumenFluentElevationOptions(level = if (panel) LumenFluentElevation.CARD else LumenFluentElevation.CONTROL,
            shadowEnabled = false, strokeEnabled = panel || button, pressedLowersElevation = !panel),
        cornerRadiusDp = if (panel) 16f else if (button) 12f else 22f, stateLayerEnabled = !panel, focusVisualEnabled = !panel)
    private fun refresh() {
        controls.forEachIndexed { index, (control, button) ->
            control.updatePalette(colors.palette()); control.update(options(button, panel && index == 0))
        }
        icons.forEach { it.imageTintList = ColorStateList.valueOf(colors.palette().textPrimary) }
        labels.forEach { (view, accent) ->
            view.setTextColor(if (accent) colors.palette().primary else colors.palette().textPrimary)
            view.setLinkTextColor(colors.palette().primary)
        }
    }
    override fun close() {
        tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(observer); tree = null
        controls.forEach { it.first.close() }; controls.clear(); labels.clear(); icons.clear()
    }
}
