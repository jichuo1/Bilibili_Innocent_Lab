@file:Suppress("ReplaceWithViewOutlineProviderExtension")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.graphics.Outline
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 图标锚点形变的承载层：一张全屏、可变 outline 的表面，弹窗卡片是它唯一的 child。
 *
 * 形状完全由 outline 裁剪表达——**不新增自绘 surface**，因此不会多出一个
 * `LiquidMotionSurfaceFrameProvider` 采样面（实时液态玻璃的逐帧回读预算是硬约束）。
 * 阴影同样由 outline 生成，形状变化时阴影自然跟随，卡片自身的 elevation 在形变期间让位。
 *
 * 与 `SettingsBackupMotionHost` 的关系：两者共用"可变 outline 裁剪"这一个原语，但那个 host
 * 还要承担 backdrop、标题副本、跨窗口坐标和页面替换；图标锚点一条都不需要，所以单独实现，
 * 不去继承或改造它。
 */
internal class IconAnchoredMotionLayer(context: Context) : FrameLayout(context) {

    private val motionBounds = RectF()
    private var motionRadius = 0f
    private var shaped = false

    /**
     * 形变期间吞掉全部触摸：卡片正在移动，落点与用户看到的位置对不上。
     *
     * 用 `isClickable` 让 `View.onTouchEvent` 自己消费，而不是重写 `onTouchEvent`——后者会触发
     * lint 的 `ClickableViewAccessibility`，而这个层本来就没有点击语义，不该为了绕检查去补一个
     * 空的 `performClick`。拦截交给 [onInterceptTouchEvent]，消费交给 clickable。
     */
    var blockInteraction = false
        set(value) {
            field = value
            isClickable = value
            isFocusable = value
            importantForAccessibility = if (value) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        }

    init {
        clipChildren = true
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (!shaped || motionBounds.isEmpty) {
                    outline.setRect(0, 0, view.width, view.height)
                    outline.alpha = 0f
                    return
                }
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

    fun applyFrame(left: Float, top: Float, right: Float, bottom: Float, radiusPx: Float) {
        val normalizedRadius = radiusPx.coerceAtLeast(0f)
        if (shaped && clipToOutline &&
            motionBounds.left == left && motionBounds.top == top &&
            motionBounds.right == right && motionBounds.bottom == bottom &&
            motionRadius == normalizedRadius
        ) {
            return
        }
        motionBounds.set(left, top, right, bottom)
        motionRadius = normalizedRadius
        shaped = true
        clipToOutline = true
        invalidateOutline()
    }

    /**
     * 回到"没有形变"的终态：关闭裁剪并把表面让给卡片自己的背景。
     *
     * 必须显式清掉 outline，否则最后一帧的圆角会永久留在层上，卡片内容一旦超出该矩形
     * （例如展开的搜索结果列表）就会被裁掉。
     */
    fun clearShape() {
        if (!shaped && !clipToOutline) return
        shaped = false
        clipToOutline = false
        motionBounds.setEmpty()
        motionRadius = 0f
        invalidateOutline()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = blockInteraction
}
