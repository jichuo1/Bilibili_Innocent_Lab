package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import io.github.jichuo1.meguviz.android.extensions.setMargins
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
import kotlin.math.roundToInt

/** 为文字列表和功能分组保留阅读层；不逐行模糊，不接管列表、点击或媒体。 */
internal class HostReadingSurfaces(root: ViewGroup, private val owned: MutableMap<View, Drawable>) : AutoCloseable {
    private enum class Role { READING, GROUP, MINE }
    private class Target(val role: Role) {
        var binding: LumenSurfaceBinding? = null
        var drawable: Drawable? = null
        var spacing: Spacing? = null
        var mine: HostMineReadingCard? = null
    }
    private class Spacing(view: View, params: ViewGroup.MarginLayoutParams, val topInset: Int, val bottomInset: Int) {
        val view = WeakReference(view)
        val params = WeakReference(params)
        val top = params.topMargin
        val bottom = params.bottomMargin
        fun restore() {
            val view = view.get() ?: return
            val current = params.get()?.takeIf { it === view.layoutParams } ?: return
            view.setMargins(current.leftMargin,
                if (current.topMargin == top + topInset) top else current.topMargin,
                current.rightMargin,
                if (current.bottomMargin == bottom + bottomInset) bottom else current.bottomMargin)
        }
    }
    private val root = WeakReference(root)
    private val targets = WeakHashMap<View, Target>()
    private var session: LumenSurfaceSession? = null
    private var dark: Boolean? = null

    fun discover() {
        val root = root.get() ?: return
        val found = HashMap<View, Role>()
        val protectedRoot = HostBackgroundPages.protectedAncestor(root)
        val externalOwner = HostBackgroundPages.owner(root)?.let { it !== root } == true
        fun visit(view: View, depth: Int) {
            if (depth > 32 || protectedRoot || HostBackgroundPages.protected(view)) return
            if (view !== root && (externalOwner || HostBackgroundPages.pageRoot(view))) return
            if (view !is ViewGroup) return
            val name = name(view)
            val parentName = (view.parent as? View)?.let(::name)
            when {
                name == "mine_recycle" -> {
                    found[view] = Role.MINE
                    return
                }
                name == "search_discover_list" -> {
                    for (i in 0 until view.childCount) {
                        val section = view.getChildAt(i) as? ViewGroup ?: continue
                        if (hasChild(section, "title")) found[section] = Role.GROUP
                    }
                    return
                }
                name == "rv_messages" -> {
                    found[view] = Role.READING
                    for (i in 0 until view.childCount) {
                        val entry = view.getChildAt(i) as? ViewGroup ?: continue
                        if (hasChild(entry, "im_top_hint")) found[entry] = Role.GROUP
                    }
                    return
                }
                parentName == "list_container" && (view.parent as View).id == android.R.id.list_container -> {
                    found[view] = Role.READING
                    return
                }
            }
            // 只进入页面容器及 ViewPager2；其他列表内已有视频、动态、评论等专用材质。
            if (!view.javaClass.name.startsWith("androidx.viewpager2.") &&
                generateSequence(view.javaClass as Class<*>?) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" }) return
            for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        for (view in targets.keys.toList()) if (view !in found) release(view)
        for ((view, role) in found) targets.getOrPut(view) { Target(role) }
    }

    fun apply(night: Boolean) {
        val root = root.get() ?: return
        if (targets.isEmpty()) return
        val palette = LumenPalette.neutral(night)
        val changed = dark != night
        val active = session ?: LumenSurfaceSession(root.context, palette,
            LumenSurfaceSessionOptions(maxSurfaces = 12, pauseWhenWindowUnfocused = false)).also { session = it }
        if (changed) { active.updatePalette(palette); dark = night }
        for ((view, target) in targets) {
            if (!view.isShown) continue
            if (target.role == Role.MINE && view is ViewGroup) {
                val card = target.mine ?: HostMineReadingCard(view).also { target.mine = it }
                card.sync(night)
            }
            if (target.role == Role.GROUP || target.role == Role.MINE) {
                // 下拉刷新容器自行定位列表，间距放在其外层，避免列表 margin 被忽略。
                val spacingView = if (target.role == Role.MINE)
                    (view.parent as? View)?.takeIf { name(it) == "refresh_layout" } ?: view
                else view
                val params = spacingView.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null && target.spacing?.params?.get() !== params) {
                    target.spacing?.restore()
                    val density = view.resources.displayMetrics.density
                    val topInset = if (target.role == Role.MINE)
                        ((12 * density).roundToInt() - params.topMargin).coerceAtLeast(0)
                    else (4 * density).roundToInt()
                    val bottomInset = if (target.role == Role.MINE) 0 else topInset
                    target.spacing = Spacing(spacingView, params, topInset, bottomInset)
                    // 个人中心只在整张列表卡片上方留白，内部各组不增加间距；保留宿主横向布局。
                    spacingView.setMargins(params.leftMargin, params.topMargin + topInset,
                        params.rightMargin, params.bottomMargin + bottomInset)
                }
            }
            if (target.binding?.isBound != true || view.background !== target.drawable) {
                target.binding?.close()
                val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                target.binding = active.bindSource(view, options(target.role, night), null)
                target.drawable = view.background
                target.drawable?.let { owned[view] = it }
                view.setPadding(padding[0], padding[1], padding[2], padding[3])
            } else if (changed) target.binding?.update(options(target.role, night))
        }
    }

    private fun options(role: Role, night: Boolean) = LumenSurfaceOptions(
        material = LumenSurfaceMaterial.STATIC, role = SurfaceRole.CARD,
        radiusDp = if (role == Role.READING) 0f else 16f,
        color = if (night) (if (role != Role.READING) 0xFF242731.toInt() else 0xFF181A20.toInt())
            else 0xFFF8F9FC.toInt(),
        tintOpacity = if (night) .90f else .94f,
        fallbackTintOpacity = if (night) .90f else .94f,
        edgeEnabled = false,
        sampling = LumenSurfaceSampling(enabled = false)
    )

    private fun release(view: View) {
        val target = targets.remove(view) ?: return
        if (owned[view] === target.drawable) owned.remove(view)
        target.binding?.close()
        target.mine?.close()
        target.spacing?.restore()
    }

    override fun close() {
        for (view in targets.keys.toList()) release(view)
        session?.close(); session = null; dark = null
    }

    private fun hasChild(view: ViewGroup, childName: String) =
        (0 until view.childCount).any { name(view.getChildAt(it)) == childName }

    private fun name(view: View) = HostViewResourceNames.name(view)
}
