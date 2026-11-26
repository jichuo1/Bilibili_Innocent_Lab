package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.roundToInt

/** 只用公开渐隐表面；来源为卡片背景层之外的原 RecyclerView，绝不录制整个页面。 */
internal class FollowFeedStatusBar(
    private val page: FrameLayout,
    private val session: LumenSurfaceSession,
    private var palette: LumenPalette
) : AutoCloseable {
    internal val band = View(page.context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        elevation = page.resources.displayMetrics.density * 2
        // Z 顺序只用于盖在内容上；透明渐隐区域不能产生矩形轮廓投影。
        outlineProvider = null
    }
    private var binding: LumenSurfaceBinding? = null
    private var source: View? = null
    private var contentSource: FollowFeedStatusContentSource? = null
    private var inset = -1
    private var height = 0
    private val location = IntArray(2)
    private var closed = false

    init { page.addView(band, FrameLayout.LayoutParams(-1, 0, Gravity.TOP)) }

    fun sync(list: View?) {
        if (closed) return
        val top = page.paddingTop
        if (top != inset) {
            inset = top
            height = top + (FollowFeedStyle.STATUS_FADE_DP * page.resources.displayMetrics.density).roundToInt()
            band.layoutParams = band.layoutParams?.apply {
                height = this@FollowFeedStatusBar.height
            } ?: FrameLayout.LayoutParams(-1, height, Gravity.TOP)
            band.translationY = -top.toFloat()
            pause()
        }
        // 内容尚未滚到融合带下时只绘制渐隐底色，不录制整列表。
        val next = list?.takeIf { it.isShown && it.isAttachedToWindow && !it.isLayoutRequested }?.let {
            it.getLocationInWindow(location)
            if (location[1] < height) it else null
        }
        if (binding != null && source !== next) pause()
        if (binding == null && band.isAttachedToWindow) {
            source = next
            val options = FollowFeedStyle.statusBand(palette, inset, height)
            binding = if (next == null) session.bind(band, options.copy(material = LumenSurfaceMaterial.STATIC,
                sampling = options.sampling.copy(enabled = false))) else {
                // 上游 GPU 模糊使用 2 倍半径的输入边界，这里保留 4 倍半径以覆盖软件路径与取整。
                val padding = maxOf(options.sampling.blurRadiusDp,
                    options.sampling.softwareBlurRadiusDp ?: options.sampling.blurRadiusDp) *
                    page.resources.displayMetrics.density * 4f + 4f
                val content = FollowFeedStatusContentSource(next, band, padding).also { contentSource = it }
                session.bindSource(band, options, content)
            }
        }
        contentSource?.sync()
    }

    fun updatePalette(value: LumenPalette) {
        palette = value
        val options = FollowFeedStyle.statusBand(value, inset.coerceAtLeast(0), height)
        binding?.update(if (source == null) options.copy(material = LumenSurfaceMaterial.STATIC,
            sampling = options.sampling.copy(enabled = false)) else options)
    }

    fun pause() {
        binding?.close(); binding = null
        contentSource?.close(); contentSource = null
        source = null
    }
    fun diagnostics() = binding?.diagnostics()
    override fun close() {
        if (closed) return
        closed = true
        pause()
        page.removeView(band)
    }
}

/** 上延一个状态栏高度并补等量内边距：起点位置与原生刷新行程保持不变。 */
internal class FollowFeedViewport private constructor(
    private val list: ViewGroup,
    private val page: ViewGroup,
    private val params: ViewGroup.MarginLayoutParams
) : AutoCloseable {
    private val originalMargin = params.topMargin
    private val originalPadding = list.paddingTop
    private val edits = FollowFeedViewEdits()
    private var extension = 0

    init {
        var ancestor: ViewGroup? = list
        while (ancestor != null) {
            edits.unclip(ancestor)
            if (ancestor === page) break
            ancestor = ancestor.parent as? ViewGroup
        }
    }

    fun sync(inset: Int) {
        if (inset == extension || list.layoutParams !== params ||
            params.topMargin != originalMargin - extension || list.paddingTop != originalPadding + extension) return
        extension = inset
        params.topMargin = originalMargin - inset
        list.layoutParams = params
        list.setPadding(list.paddingLeft, originalPadding + inset, list.paddingRight, list.paddingBottom)
    }

    override fun close() {
        if (list.layoutParams === params && params.topMargin == originalMargin - extension) {
            params.topMargin = originalMargin
            list.layoutParams = params
        }
        if (list.paddingTop == originalPadding + extension)
            list.setPadding(list.paddingLeft, originalPadding, list.paddingRight, list.paddingBottom)
        edits.restore()
    }

    companion object {
        fun create(list: ViewGroup, page: ViewGroup): FollowFeedViewport? =
            (list.layoutParams as? ViewGroup.MarginLayoutParams)?.let { FollowFeedViewport(list, page, it) }
    }
}
