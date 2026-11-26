package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.roundToInt

/** 仅收起已识别的外层关注标题行，保留原发布 View 和状态栏安全区域。 */
internal class FollowFeedHeader private constructor(
    private val page: View,
    private val outer: ViewGroup,
    private val appBar: View,
    private val plate: View,
    private val publish: View,
    private val originalParent: ViewGroup,
    private val selection: FollowFeedSelection,
    palette: LumenPalette
) : AutoCloseable {
    private val originalIndex = originalParent.indexOfChild(publish)
    private val originalParams = publish.layoutParams
    private val originalAppVisibility = appBar.visibility
    private val originalPlateVisibility = plate.visibility
    private var nativePadding = page.paddingTop
    private var appliedPadding = nativePadding
    private val edits = FollowFeedViewEdits()
    private val location = IntArray(2)
    private var closed = false

    init {
        try {
            originalParent.removeView(publish)
            val density = page.resources.displayMetrics.density
            val actionSize = (FollowFeedStyle.OPTION_HEIGHT_DP * density).roundToInt()
            selection.actionContainer.addView(publish, FrameLayout.LayoutParams(actionSize, actionSize,
                Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = (FollowFeedStyle.OUTER_DP * density).roundToInt()
            })
            selection.reserveAction(true)
            appBar.visibility = View.GONE
            plate.visibility = View.GONE
            updatePalette(palette)
            sync()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun updatePalette(palette: LumenPalette) {
        edits.restore()
        edits.background(outer, ColorDrawable(palette.background))
    }

    private fun safePadding(): Int {
        val top = ViewCompat.getRootWindowInsets(page)
            ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            ?.top
            ?: run {
                val id = page.resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id == 0) 0 else page.resources.getDimensionPixelSize(id)
            }
        page.getLocationInWindow(location)
        return FollowFeedStyle.contentTopPadding(top, location[1])
    }

    /** 原 ye(height) 仍由宿主执行，只映射它本来写入的标题栏占位。 */
    fun mapNativePadding(requested: Int): Int {
        nativePadding = requested
        appliedPadding = safePadding()
        return appliedPadding
    }

    fun sync() {
        if (closed) return
        val padding = safePadding()
        if (page.paddingTop != appliedPadding && page.paddingTop != nativePadding) return
        if (page.paddingTop != padding) {
            page.setPadding(page.paddingLeft, padding, page.paddingRight, page.paddingBottom)
        }
        appliedPadding = padding
    }

    override fun close() {
        if (closed) return
        closed = true
        if (publish.parent === selection.actionContainer) {
            selection.actionContainer.removeView(publish)
            originalParent.addView(publish, originalIndex.coerceAtMost(originalParent.childCount), originalParams)
        } else if (publish.parent == null) {
            originalParent.addView(publish, originalIndex.coerceAtMost(originalParent.childCount), originalParams)
        }
        selection.reserveAction(false)
        if (appBar.visibility == View.GONE) appBar.visibility = originalAppVisibility
        if (plate.visibility == View.GONE) plate.visibility = originalPlateVisibility
        if (page.paddingTop == appliedPadding)
            page.setPadding(page.paddingLeft, nativePadding, page.paddingRight, page.paddingBottom)
        edits.restore()
    }

    companion object {
        fun create(page: View, selection: FollowFeedSelection, palette: LumenPalette): FollowFeedHeader? {
            fun id(name: String) = page.resources.getIdentifier(name, "id", page.context.packageName)
            val appId = id("fo_app_bar")
            val plateId = id("top_tab_container")
            val publishId = id("fo_publish_menu")
            val titleId = id("title")
            val tabsId = id("toolbar_tabs")
            if (listOf(appId, plateId, publishId, titleId, tabsId).any { it == 0 }) return null
            var ancestor = page.parent as? ViewGroup
            repeat(6) {
                val outer = ancestor ?: return null
                val app = outer.findViewById<View>(appId)
                val plate = outer.findViewById<View>(plateId)
                if (app?.parent === outer && plate?.parent === outer) {
                    val publish = app.findViewById<View>(publishId) ?: return null
                    val title = app.findViewById<View>(titleId) as? TextView ?: return null
                    val tabs = app.findViewById<View>(tabsId) ?: return null
                    // 有其他一级标签或未知标题的宿主继续使用原导航，不强行隐藏。
                    val publishParent = publish.parent as? ViewGroup ?: return null
                    if (!isSupportedHeaderTitle(title.text?.toString()) || tabs.visibility == View.VISIBLE ||
                        publish.visibility != View.VISIBLE) return null
                    return runCatching {
                        FollowFeedHeader(page, outer, app, plate, publish, publishParent, selection, palette)
                    }.getOrNull()
                }
                ancestor = outer.parent as? ViewGroup
            }
            return null
        }
    }
}

private val SUPPORTED_FOLLOW_HEADER_TITLES = setOf("关注", "關注", "追蹤", "following", "follow")

internal fun isSupportedHeaderTitle(title: String?): Boolean =
    title?.trim()?.lowercase(java.util.Locale.ROOT) in SUPPORTED_FOLLOW_HEADER_TITLES
