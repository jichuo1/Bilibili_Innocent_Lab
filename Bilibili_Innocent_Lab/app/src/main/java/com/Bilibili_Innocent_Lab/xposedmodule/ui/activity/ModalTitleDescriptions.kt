package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Layout
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.withSave
import kotlin.math.roundToInt

/** 描述留在原位置，在形变背景上方渐隐/渐显；合成行只绘制第二行以后。 */
internal class ModalTitleDescriptions(
    private val source: TextView,
    private val anchor: View,
    private val originalColors: ColorStateList
) {
    private class Block(val view: TextView, val firstLine: Int, val color: Int, val alpha: Float) {
        var layout: Layout? = null
        var x = 0f
        var y = 0f
        val visible = Rect()
    }

    private val owner = Any()
    private val blocks = ArrayList<Block>()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private val sourceBounds = Rect()
    private var prepared = false
    private var nativeReleased = false
    private var weight = 1f

    fun prepare(root: ViewGroup) {
        if (!prepared) {
            prepared = true
            if ((source.layout?.lineCount ?: 0) > 1) {
                blocks.add(Block(source, 1, originalColors.defaultColor, source.alpha))
            }
            source.getLocationOnScreen(location)
            val descriptionTop = location[1] + source.height
            var remaining = 64
            fun visit(view: View) {
                if (--remaining < 0 || view.visibility != View.VISIBLE) return
                if (view is TextView && view !== source && !view.isClickable && !view.isFocusable &&
                    view.text.isNotEmpty() && view.text !is Spanned) {
                    view.getLocationOnScreen(location)
                    if (location[1] >= descriptionTop) {
                        val alpha = alphaOwners.original(view, view.alpha)
                        alphaOwners.acquire(view, owner, alpha)
                        blocks.add(Block(view, 0, view.textColors.defaultColor, alpha))
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    if (remaining <= 0) break
                    visit(view.getChildAt(i))
                }
            }
            if (anchor !== source) visit(anchor)
        }
        root.getLocationOnScreen(rootLocation)
        // getGlobalVisibleRect 使用来源窗口的根坐标，不能直接当成屏幕坐标。
        // 标题入口已验证完整可见，可用它的两种坐标求出窗口偏移。
        source.getLocationOnScreen(location)
        source.getGlobalVisibleRect(sourceBounds)
        val windowX = location[0] - sourceBounds.left
        val windowY = location[1] - sourceBounds.top
        for (block in blocks) {
            val view = block.view
            val layout = view.layout
            block.layout = layout
            if (layout == null || !view.getGlobalVisibleRect(block.visible)) {
                block.layout = null
                continue
            }
            view.getLocationOnScreen(location)
            block.x = (location[0] - rootLocation[0] + view.totalPaddingLeft - view.scrollX).toFloat()
            block.y = (location[1] - rootLocation[1] + view.baseline - layout.getLineBaseline(0) - view.scrollY).toFloat()
            block.visible.offset(windowX - rootLocation[0], windowY - rootLocation[1])
        }
    }

    fun apply(expansion: Float) {
        weight = ModalTitleMotionSpec.descriptionWeight(expansion)
    }

    fun hideNative() {
        for (block in blocks) if (block.view !== source) block.view.alpha = 0f
    }

    fun draw(canvas: Canvas) {
        if (!prepared || weight <= 0f) return
        for (block in blocks) {
            val layout = block.layout ?: continue
            if (layout.lineCount <= block.firstLine) continue
            val paint = layout.paint
            val borrowedColor = paint.color
            val opacity = (Color.alpha(block.color) * block.alpha * weight).roundToInt().coerceIn(0, 255)
            paint.color = (block.color and 0x00FFFFFF) or (opacity shl 24)
            try {
                canvas.withSave {
                    clipRect(block.visible)
                    translate(block.x, block.y)
                    clipRect(0f, layout.getLineTop(block.firstLine).toFloat(), layout.width.toFloat(), layout.height.toFloat())
                    layout.draw(this)
                }
            } finally {
                paint.color = borrowedColor
            }
        }
    }

    fun restoreNative() {
        if (!prepared || nativeReleased) return
        nativeReleased = true
        for (block in blocks) {
            if (block.view !== source) alphaOwners.release(block.view, owner)?.let { block.view.alpha = it }
        }
    }

    fun dispose() {
        if (!prepared) return
        restoreNative()
        prepared = false
        blocks.clear()
    }

    companion object {
        private val alphaOwners = ModalTitleColorOwners<TextView, Float>()
    }
}
