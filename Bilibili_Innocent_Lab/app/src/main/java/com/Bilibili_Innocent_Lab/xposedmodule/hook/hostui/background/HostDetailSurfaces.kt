package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.lumen.coacervation.engine.host.LumenContentSource
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 视频前景面板的语义表面。只取样壁纸，不录制播放器、文字或玻璃自身。 */
internal class HostDetailSurfaces(panel: ViewGroup, private val owned: MutableMap<View, Drawable>) {
    private enum class Role { HEADER, BODY, DIVIDER, INPUT }
    private class Target(val role: Role, var binding: LumenSurfaceBinding? = null, var drawable: Drawable? = null)
    private val panel = WeakReference(panel)
    private val targets = WeakHashMap<View, Target>()
    private var session: LumenSurfaceSession? = null
    private var dark: Boolean? = null
    private var wallpaper: HostBackgroundDrawable? = null
    private var offset = Long.MIN_VALUE
    private var rasterReady = false
    private var width = 0
    private var height = 0
    private var source: LumenContentSource? = null

    /** 布局变化时只发现容器，跳过列表条目；不为每条评论建立独立采样。 */
    fun discover() {
        val panel = panel.get() ?: return
        val found = HashMap<View, Role>()
        fun visit(view: View, depth: Int) {
            if (depth > 24 || view is ImageView || HostBackgroundPages.protectedAncestor(view)) return
            val name = name(view)
            val role = when {
                view.parent === panel && name == "tabs_back_board" -> Role.HEADER
                view.parent === panel && name == "pager" -> Role.BODY
                view.parent === panel && name == "shadow_dig" -> Role.DIVIDER
                name == "fake_input_layout" -> Role.INPUT
                else -> null
            }
            if (role != null) found[view] = role
            if (view !is ViewGroup || generateSequence(view.javaClass as Class<*>?) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" }) return
            for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(panel, 0)
        for (view in targets.keys.toList()) if (view !in found) {
            targets.remove(view)?.binding?.close()
            owned.remove(view)
        }
        for ((view, role) in found) targets.getOrPut(view) { Target(role) }
    }

    fun apply(night: Boolean) {
        val panel = panel.get() ?: return
        if (targets.isEmpty()) return
        val themeChanged = dark != night
        val active = session ?: LumenSurfaceSession(panel.context, LumenPalette.neutral(night),
            LumenSurfaceSessionOptions(maxSurfaces = 8)).also {
            session = it
            source = object : LumenContentSource {
                override val coordinateView: View = panel
                override val excludesSurfaces: Boolean = true
                override fun drawContent(canvas: Canvas) {
                    // 只绘制原有壁纸，绝不调用 panel.draw()，避免祖先采样包含注入表面。
                    (panel.background as? HostBackgroundDrawable)?.draw(canvas)
                }
            }
        }
        if (themeChanged) { active.updatePalette(LumenPalette.neutral(night)); dark = night }
        for ((view, target) in targets) {
            if (target.binding?.isBound != true || view.background !== target.drawable) {
                target.binding?.close()
                target.binding = active.bindSource(view, options(target.role, night),
                    source.takeIf { target.role == Role.HEADER })
                target.drawable = view.background
                target.drawable?.let { owned[view] = it }
            } else if (themeChanged) target.binding?.update(options(target.role, night))
        }
        val next = panel.background as? HostBackgroundDrawable
        val nextOffset = next?.scrollOffset ?: 0L
        val ready = next?.isRasterReady == true
        if (wallpaper !== next || offset != nextOffset || rasterReady != ready || themeChanged ||
            width != panel.width || height != panel.height) {
            wallpaper = next; offset = nextOffset; rasterReady = ready
            width = panel.width; height = panel.height
            active.notifyContentChanged()
        }
    }

    private fun options(role: Role, night: Boolean): LumenSurfaceOptions {
        val tint = when (role) {
            Role.HEADER -> if (night) .68f else .72f
            Role.BODY -> if (night) .56f else .62f
            Role.INPUT -> if (night) .84f else .88f
            Role.DIVIDER -> 1f
        }
        return LumenSurfaceOptions(
            material = if (role == Role.HEADER) LumenSurfaceMaterial.FROSTED else LumenSurfaceMaterial.STATIC,
            role = if (role == Role.BODY) SurfaceRole.CARD else SurfaceRole.TOP_BAR,
            radiusDp = 0f,
            color = if (role == Role.DIVIDER) (if (night) 0x38FFFFFF else 0x24000000)
                else if (night) 0xFF181A20.toInt() else 0xFFF8F9FC.toInt(),
            tintOpacity = tint, fallbackTintOpacity = if (role == Role.HEADER) .88f else tint,
            edgeEnabled = false,
            // 磨砂帧完整覆盖底图后再加色罩，避免清晰星点从玻璃下漏出。
            backdropOpacity = 1f,
            sampling = LumenSurfaceSampling(enabled = role == Role.HEADER, refractionEnabled = false,
                blurRadiusDp = 16f, softwareBlurRadiusDp = 10f, maxSoftwarePixels = 48_000)
        )
    }

    fun detach() {
        session?.close()
        session = null
        source = null
        wallpaper = null
        dark = null
        for ((view, target) in targets) {
            owned.remove(view)
            target.binding = null
            target.drawable = null
        }
    }

    private fun name(view: View) = if (view.id == View.NO_ID) "" else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
}
