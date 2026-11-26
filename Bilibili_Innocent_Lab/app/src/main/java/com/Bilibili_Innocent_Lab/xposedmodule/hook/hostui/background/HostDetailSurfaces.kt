package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.host.LumenVersionedContentSource
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 视频前景和独立评论面板的语义表面。只取样壁纸，不录制播放器、文字或玻璃自身。 */
internal class HostDetailSurfaces(panel: ViewGroup, private val owned: MutableMap<View, Drawable>) {
    private enum class Role { HEADER, BODY, DIVIDER, INPUT, OVERLAY }
    private class Target(val role: Role, var binding: LumenSurfaceBinding? = null, var drawable: Drawable? = null,
        var overlay: HostDetailOverlay? = null) {
        var texts = emptyList<WeakReference<TextView>>()
        val contrast = HostWallpaperText()
        fun close() { contrast.restore(); overlay?.close() ?: binding?.close(); overlay = null; binding = null }
    }
    private val panel = WeakReference(panel)
    private val targets = WeakHashMap<View, Target>()
    private var session: LumenSurfaceSession? = null
    private var dark: Boolean? = null
    private var wallpaper: HostBackgroundDrawable? = null
    private var offset = Long.MIN_VALUE
    private var rasterReady = false
    private var width = 0
    private var height = 0
    private var source: LumenVersionedContentSource? = null

    /** 布局变化时只发现容器，跳过列表条目；不为每条评论建立独立采样。 */
    fun discover() {
        val panel = panel.get() ?: return
        val found = HashMap<View, Role>()
        fun visit(view: View, depth: Int) {
            if (depth > 24 || view is ImageView) return
            if (view !== panel && HostBackgroundPages.owner(view)?.let { it !== panel } == true) return
            if (HostDetailFloatLayers.tabSlot(view) && view is ViewGroup) {
                if ((view.parent as? View)?.let(HostBackgroundPages::protectedAncestor) == true) return
                // 服务会把分P、笔记、弹幕回复、选集等挂到同一槽位，不依赖每种业务面板的资源 id。
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (HostDetailFloatLayers.surface(child)) found[child] = Role.OVERLAY
                }
                return
            }
            if (HostBackgroundPages.protectedAncestor(view)) return
            val name = name(view)
            val role = when {
                view.parent === panel && name == "tabs_back_board" -> Role.HEADER
                name == "cmt3_page_title_container" -> Role.HEADER
                view.parent === panel && name == "pager" -> Role.BODY
                view.parent === panel && name == "shadow_dig" -> Role.DIVIDER
                name == "fake_input_layout" -> Role.INPUT
                name == "playlist_container" && view is ViewGroup &&
                    (0 until view.childCount).any { name(view.getChildAt(it)) == "layout_playlist" } -> Role.OVERLAY
                else -> null
            }
            if (role != null) found[view] = role
            if (view !is ViewGroup || generateSequence(view.javaClass as Class<*>?) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" }) return
            for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(panel, 0)
        for (view in targets.keys.toList()) if (view !in found) {
            targets.remove(view)?.close()
            owned.remove(view)
        }
        for ((view, role) in found) {
            val target = targets.getOrPut(view) { Target(role) }
            if (role == Role.OVERLAY && HostDetailFloatLayers.contains(view)) {
                target.contrast.restore()
                val texts = ArrayList<WeakReference<TextView>>()
                fun collect(node: View, depth: Int) {
                    if (depth > 24 || HostBackgroundPages.protected(node)) return
                    if (node is TextView) texts += WeakReference(node)
                    else if (node is ViewGroup) for (i in 0 until node.childCount) collect(node.getChildAt(i), depth + 1)
                }
                collect(view, 0)
                target.texts = texts
            }
        }
    }

    fun apply(night: Boolean) {
        val panel = panel.get() ?: return
        if (targets.isEmpty()) return
        val themeChanged = dark != night
        val active = session ?: LumenSurfaceSession(panel.context, LumenPalette.neutral(night),
            LumenSurfaceSessionOptions(maxSurfaces = 8, pauseWhenWindowUnfocused = false)).also {
            session = it
            source = object : LumenVersionedContentSource {
                override val coordinateView: View get() = requireNotNull(this@HostDetailSurfaces.panel.get())
                override val excludesSurfaces: Boolean = true
                override val observeCoordinateViewInvalidations: Boolean = false
                override val sourceEpoch: Long get() = 0L
                override val availability: LumenSourceAvailability get() = LumenSourceAvailability.READY
                override val contentVersion: Long
                    get() {
                        val host = this@HostDetailSurfaces.panel.get() ?: return 0L
                        val next = host.background as? HostBackgroundDrawable
                        var v = if (dark == true) 1L else 0L
                        v = v * 31L + host.width
                        v = v * 31L + host.height
                        v = v * 31L + (next?.samplingState ?: 0L)
                        v = v * 31L + (next?.scrollOffset ?: 0L)
                        v = v * 31L + if (next?.isRasterReady == true) 1L else 0L
                        return v and Long.MAX_VALUE
                    }
                override fun drawContent(canvas: Canvas) {
                    // 只绘制原有壁纸，绝不调用 panel.draw()，避免祖先采样包含注入表面。
                    // 模糊会采样到面板边界外；先补齐不透明输入，避免底边把下层文字重新透出来。
                    canvas.drawColor(if (dark == true) 0xFF181A20.toInt() else 0xFFF8F9FC.toInt())
                    (this@HostDetailSurfaces.panel.get()?.background as? HostBackgroundDrawable)?.draw(canvas)
                }
            }
        }
        if (themeChanged) { active.updatePalette(LumenPalette.neutral(night)); dark = night }
        for ((view, target) in targets) {
            if (target.binding?.isBound != true || view.background !== target.drawable) {
                target.close()
                if (target.role == Role.OVERLAY) {
                    target.overlay = HostDetailOverlay(view, active, options(target.role, night), source)
                    target.binding = target.overlay?.binding
                } else target.binding = active.bindSource(view, options(target.role, night),
                    source.takeIf { frosted(target.role) })
                target.binding?.let(HostMemoryPressureBridge::registerBinding)
                target.drawable = view.background
                target.drawable?.let { owned[view] = it }
            } else if (themeChanged) {
                target.overlay?.update(options(target.role, night)) ?: target.binding?.update(options(target.role, night))
            }
            // 槽位不再进入正文的文字遍历，磨砂浮层自行处理深色对比度，并在关闭时恢复。
            if (night && view is ViewGroup) for (ref in target.texts) ref.get()?.let { target.contrast.apply(it, view) }
            else target.contrast.restore()
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
            Role.OVERLAY -> if (night) .74f else .78f
        }
        return LumenSurfaceOptions(
            material = if (frosted(role)) LumenSurfaceMaterial.FROSTED else LumenSurfaceMaterial.STATIC,
            role = if (role == Role.BODY) SurfaceRole.CARD else SurfaceRole.TOP_BAR,
            radiusDp = 0f,
            color = if (role == Role.DIVIDER) (if (night) 0x38FFFFFF else 0x24000000)
                else if (night) 0xFF181A20.toInt() else 0xFFF8F9FC.toInt(),
            tintOpacity = tint,
            fallbackTintOpacity = if (role == Role.OVERLAY) 1f else if (role == Role.HEADER) .88f else tint,
            edgeEnabled = false,
            // 磨砂帧完整覆盖底图后再加色罩，避免清晰星点从玻璃下漏出。
            backdropOpacity = 1f,
            sampling = LumenSurfaceSampling(enabled = frosted(role), refractionEnabled = false,
                blurRadiusDp = 16f, softwareBlurRadiusDp = 10f, maxSoftwarePixels = 48_000)
        )
    }

    private fun frosted(role: Role) = role == Role.HEADER || role == Role.OVERLAY

    fun detach() {
        for (target in targets.values) target.close()
        session?.close()
        session = null
        source = null
        wallpaper = null
        dark = null
        for ((view, target) in targets) {
            owned.remove(view)
            target.binding = null
            target.drawable = null
            target.texts = emptyList()
        }
        targets.clear()
    }

    private fun name(view: View) = HostViewResourceNames.name(view)
}
