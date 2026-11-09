package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays

import android.view.View
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import kotlin.math.roundToInt
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole

/** 回复脉络面板的静态表面会话；不采样宿主内容，透明度只影响背景。 */
internal class HostOverlaySurfaces(private val root: View, color: Int, stroke: Int, dark: Boolean, radiusDp: Float = 16f) {
    private val session = LumenSurfaceSession(root.context, LumenPalette.neutral(dark).copy(
        surface = color, primary = stroke))
    private val panelOptions = LumenSurfacePresets.staticPanel().copy(
        color = color, radiusDp = radiusDp, tintOpacity = 1f, fallbackTintOpacity = 1f,
        edgeWidthDp = 1f, edgeTopColor = stroke, edgeBottomColor = stroke, edgeEnabled = radiusDp > 0f)
    private val panel = session.bind(root, panelOptions)
    private var alphaHidden = root.alpha <= 0f
    private val alphaObserver = ViewTreeObserver.OnPreDrawListener {
        HostThreadGuard.call("host.overlay.surfaces.predraw", true) {
            val hidden = root.alpha <= 0f
            // 引擎跳过 alpha=0 的背景。属性动画只改 RenderNode，恢复可见时需重录这份空白缓存。
            if (alphaHidden && !hidden) root.invalidate()
            alphaHidden = hidden
            true
        }
    }

    init { root.viewTreeObserver.addOnPreDrawListener(alphaObserver) }

    fun opacity(value: Float) {
        // 旧面板只改变填充 alpha，描边与文字保持原来的不透明度。
        val color = checkNotNull(panelOptions.color)
        panel.update(panelOptions.copy(color = (color and 0xFFFFFF) or
            (((color ushr 24) * value).roundToInt().coerceIn(0, 255) shl 24)))
    }

    fun chip(view: View, color: Int, radiusDp: Float = 12f): LumenSurfaceBinding = session.bind(view,
        LumenSurfaceOptions(material = panelOptions.material, role = SurfaceRole.SELECTED_ITEM,
            color = color, radiusDp = radiusDp, tintOpacity = 1f, fallbackTintOpacity = 1f,
            edgeEnabled = false, sampling = panelOptions.sampling))

    fun close() {
        root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(alphaObserver)
        session.close()
    }
}
