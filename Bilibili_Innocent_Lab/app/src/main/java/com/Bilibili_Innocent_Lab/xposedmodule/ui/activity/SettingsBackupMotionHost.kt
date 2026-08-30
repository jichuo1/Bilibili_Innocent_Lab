@file:Suppress("ReplaceWithViewOutlineProviderExtension")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.highcapable.betterandroid.ui.extension.view.textColor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal data class SettingsBackupMotionGeometry(
    val collapsedBounds: SettingsBackupMotionRect,
    val expandedBounds: SettingsBackupMotionRect,
    val collapsedTitleBounds: SettingsBackupMotionRect,
    val expandedTitleBounds: SettingsBackupMotionRect,
    val collapsedTitleTextSizePx: Float,
    val expandedTitleTextSizePx: Float,
    val collapsedCornerRadiusPx: Float,
    val contentTravelPx: Float,
    val titleMotionEnabled: Boolean
)

internal enum class SettingsBackupTransitionTitleMode {
    SOURCE_TITLE,
    CROSSFADE_FROM_PAGE_TITLE,
    HIDDEN
}

/**
 * 设置备份页整个 Activity 生命周期内唯一的动画宿主。
 *
 * 页面切换只替换 [pageClip] 的 child；形变 surface、圆角裁剪、标题副本和输入拦截层均保持，
 * 让预测式返回可以连续 seek，而不依赖不可 seek 的 framework/shared-element transition。
 */
@SuppressLint("ViewConstructor")
internal class SettingsBackupMotionHost(
    context: Context,
    private val collapsedSurfaceColor: Int,
    private val expandedSurfaceColor: Int,
    titleColor: Int,
    sourceTitle: CharSequence
) : FrameLayout(context) {

    private val backdropClip = MotionClipFrameLayout(context)
    private val backdropRoot = View(context)
    private val surface = MorphSurfaceView(context)
    private val pageClip = MotionClipFrameLayout(context)
    private val transitionTitle = TextView(context).apply {
        text = sourceTitle
        textSize = 17f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        textColor = titleColor
        setTypeface(typeface, Typeface.BOLD)
        isSingleLine = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.INVISIBLE
        pivotX = 0f
        pivotY = 0f
    }
    private val inputBlocker = View(context).apply {
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private var currentPage: View? = null
    private var currentToolbarTitle: TextView? = null
    private val motionFrame = SettingsBackupMotionFrameBuffer()

    var expansion: Float = 1f
        private set

    var onWindowSizeChangedDuringMotion: (() -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
        backdropClip.addView(
            backdropRoot,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(backdropClip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pageClip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(transitionTitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(inputBlocker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        if (oldWidth > 0 && oldHeight > 0 && expansion < 0.999f) {
            onWindowSizeChangedDuringMotion?.invoke()
            return
        }
        surface.setFrame(
            left = 0f,
            top = 0f,
            right = width.toFloat(),
            bottom = height.toFloat(),
            cornerRadiusPx = 0f,
            color = expandedSurfaceColor
        )
        if (expansion >= 0.999f) backdropClip.clearMotionOutline()
    }

    /** Liquid renderer 绑定到该层；其父容器始终跟随形变 surface 裁剪。 */
    fun liquidBackdropRoot(): View = backdropRoot

    fun replacePage(page: View, toolbarTitle: TextView) {
        currentToolbarTitle?.alpha = 1f
        pageClip.removeAllViews()
        pageClip.addView(
            page,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        currentPage = page
        currentToolbarTitle = toolbarTitle
        showExpandedImmediately()
    }

    fun prepareFirstFrameForEntry() {
        backdropClip.visibility = View.INVISIBLE
        surface.visibility = View.INVISIBLE
        currentPage?.alpha = 0f
        currentToolbarTitle?.alpha = 0f
        transitionTitle.visibility = View.INVISIBLE
        blockInteraction(true)
    }

    fun beginMotion() {
        backdropClip.visibility = View.VISIBLE
        transitionTitle.visibility = View.VISIBLE
        blockInteraction(true)
    }

    fun applyExpansion(
        geometry: SettingsBackupMotionGeometry,
        value: Float,
        titleMode: SettingsBackupTransitionTitleMode,
        contentTiming: SettingsBackupContentTiming
    ) {
        val clamped = value.coerceIn(0f, 1f)
        expansion = clamped
        SettingsBackupMotionSpec.fillFrame(
            out = motionFrame,
            expansion = clamped,
            collapsedBounds = geometry.collapsedBounds,
            expandedBounds = geometry.expandedBounds,
            collapsedTitleBounds = geometry.collapsedTitleBounds,
            expandedTitleBounds = geometry.expandedTitleBounds,
            collapsedTitleTextSizePx = geometry.collapsedTitleTextSizePx,
            expandedTitleTextSizePx = geometry.expandedTitleTextSizePx,
            collapsedCornerRadiusPx = geometry.collapsedCornerRadiusPx,
            contentTravelPx = geometry.contentTravelPx,
            contentTiming = contentTiming
        )

        if (surface.visibility != View.VISIBLE) surface.visibility = View.VISIBLE
        if (backdropClip.visibility != View.VISIBLE) backdropClip.visibility = View.VISIBLE
        backdropClip.alpha = motionFrame.surfaceAlpha
        surface.setFrame(
            left = motionFrame.left,
            top = motionFrame.top,
            right = motionFrame.right,
            bottom = motionFrame.bottom,
            cornerRadiusPx = motionFrame.cornerRadiusPx,
            color = ColorUtils.blendARGB(
                collapsedSurfaceColor,
                expandedSurfaceColor,
                clamped
            )
        )
        surface.alpha = motionFrame.surfaceAlpha
        backdropClip.setMotionOutline(
            motionFrame.left,
            motionFrame.top,
            motionFrame.right,
            motionFrame.bottom,
            motionFrame.cornerRadiusPx
        )
        pageClip.setMotionOutline(
            motionFrame.left,
            motionFrame.top,
            motionFrame.right,
            motionFrame.bottom,
            motionFrame.cornerRadiusPx
        )

        currentPage?.apply {
            alpha = motionFrame.contentAlpha
            translationY = motionFrame.contentTranslationYPx
        }

        val sourceTakeoverAlpha = SettingsBackupMotionSpec.smoothStep(0.02f, 0.14f, clamped)
        val titleAlpha = when (titleMode) {
            SettingsBackupTransitionTitleMode.SOURCE_TITLE -> sourceTakeoverAlpha
            SettingsBackupTransitionTitleMode.CROSSFADE_FROM_PAGE_TITLE -> {
                val exitProgress = 1f - clamped
                sourceTakeoverAlpha * SettingsBackupMotionSpec.smoothStep(0f, 0.35f, exitProgress)
            }
            SettingsBackupTransitionTitleMode.HIDDEN -> 0f
        }
        val toolbarAlpha = when (titleMode) {
            SettingsBackupTransitionTitleMode.SOURCE_TITLE -> 0f
            SettingsBackupTransitionTitleMode.CROSSFADE_FROM_PAGE_TITLE -> 1f - titleAlpha
            SettingsBackupTransitionTitleMode.HIDDEN -> 1f
        }
        currentToolbarTitle?.alpha = toolbarAlpha

        transitionTitle.apply {
            val targetVisibility = if (titleMode == SettingsBackupTransitionTitleMode.HIDDEN) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
            if (visibility != targetVisibility) visibility = targetVisibility
            x = motionFrame.titleX
            y = motionFrame.titleY
            val baseTextSize = geometry.expandedTitleTextSizePx.coerceAtLeast(1f)
            if (abs(textSize - baseTextSize) > 0.5f) {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseTextSize)
            }
            val titleScale = motionFrame.titleTextSizePx / baseTextSize
            scaleX = titleScale
            scaleY = titleScale
            alpha = titleAlpha
        }
    }

    /** 来源坐标不再可靠时的无方向退化动画，不使用过期矩形。 */
    fun applyFallbackExpansion(
        value: Float,
        contentTravelPx: Float,
        contentTiming: SettingsBackupContentTiming
    ) {
        val clamped = value.coerceIn(0f, 1f)
        val contentFraction = if (contentTiming == SettingsBackupContentTiming.PREDICTIVE) {
            SettingsBackupMotionSpec.contentFraction(clamped, contentTiming)
        } else {
            clamped
        }
        expansion = clamped
        if (surface.visibility != View.VISIBLE) surface.visibility = View.VISIBLE
        if (backdropClip.visibility != View.VISIBLE) backdropClip.visibility = View.VISIBLE
        backdropClip.alpha = clamped
        backdropClip.setMotionOutline(0f, 0f, width.toFloat(), height.toFloat(), 0f)
        surface.setFrame(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            0f,
            expandedSurfaceColor
        )
        surface.alpha = clamped
        pageClip.clearMotionOutline()
        currentPage?.apply {
            alpha = contentFraction
            translationY = (1f - contentFraction) * contentTravelPx
            pivotX = width / 2f
            pivotY = height / 2f
            scaleX = 0.985f + 0.015f * clamped
            scaleY = 0.985f + 0.015f * clamped
        }
        currentToolbarTitle?.alpha = 1f
        transitionTitle.visibility = View.INVISIBLE
    }

    fun showExpandedImmediately() {
        expansion = 1f
        backdropClip.visibility = View.VISIBLE
        backdropClip.alpha = 1f
        backdropClip.clearMotionOutline()
        surface.visibility = View.VISIBLE
        if (width > 0 && height > 0) {
            surface.setFrame(
                left = 0f,
                top = 0f,
                right = width.toFloat(),
                bottom = height.toFloat(),
                cornerRadiusPx = 0f,
                color = expandedSurfaceColor
            )
        }
        surface.alpha = 1f
        pageClip.clearMotionOutline()
        currentPage?.apply {
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
        currentToolbarTitle?.alpha = 1f
        transitionTitle.apply {
            alpha = 0f
            scaleX = 1f
            scaleY = 1f
            visibility = View.INVISIBLE
        }
        blockInteraction(false)
    }

    fun blockInteraction(blocked: Boolean) {
        inputBlocker.visibility = if (blocked) View.VISIBLE else View.GONE
        pageClip.importantForAccessibility = if (blocked) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private class MorphSurfaceView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private var cornerRadiusPx = 0f

        fun setFrame(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            cornerRadiusPx: Float,
            color: Int
        ) {
            val normalizedRadius = cornerRadiusPx.coerceAtLeast(0f)
            if (bounds.left == left && bounds.top == top &&
                bounds.right == right && bounds.bottom == bottom &&
                this.cornerRadiusPx == normalizedRadius && paint.color == color
            ) {
                return
            }
            this.bounds.set(left, top, right, bottom)
            this.cornerRadiusPx = normalizedRadius
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, paint)
        }
    }

    private class MotionClipFrameLayout(context: Context) : FrameLayout(context) {
        private val motionBounds = RectF()
        private var motionRadius = 0f

        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (motionBounds.isEmpty) {
                        outline.setRect(0, 0, view.width, view.height)
                    } else {
                        outline.setRoundRect(
                            floor(motionBounds.left).toInt(),
                            floor(motionBounds.top).toInt(),
                            ceil(motionBounds.right).toInt(),
                            ceil(motionBounds.bottom).toInt(),
                            motionRadius
                        )
                    }
                }
            }
        }

        fun setMotionOutline(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radiusPx: Float
        ) {
            val normalizedRadius = radiusPx.coerceAtLeast(0f)
            if (clipToOutline && motionBounds.left == left && motionBounds.top == top &&
                motionBounds.right == right && motionBounds.bottom == bottom &&
                motionRadius == normalizedRadius
            ) {
                return
            }
            motionBounds.set(left, top, right, bottom)
            motionRadius = normalizedRadius
            clipToOutline = true
            invalidateOutline()
        }

        fun clearMotionOutline() {
            clipToOutline = false
            motionBounds.setEmpty()
            motionRadius = 0f
        }
    }
}
