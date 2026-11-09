package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.View

/**
 * 让前景装饰固定在 View 的视口上，而不是跟着内容滚动。
 *
 * View 绘制前把画布平移了 (-scrollX, -scrollY)；背景由 View.drawBackground 自行补偿，前景没有。
 * 单行 EditText 输入长文本后光标滚到末尾（scrollX > 0），未补偿的描边会整体左移、与背景错位。
 * 与 Lumen 引擎 1.5.3 起 `controlOutline` 内置的同名包装同一语义；宿主这里用的是自己的描边拷贝，需单独接上。
 * 只有真正作为 View 前景（可经 RippleDrawable 等容器间接持有）时才补偿；挂在背景上时保持原样，避免重复平移。
 */
internal class ViewportForegroundDrawable(drawable: Drawable) : DrawableWrapper(drawable) {
    override fun draw(canvas: Canvas) {
        var outer: Drawable = this
        var owner = callback
        var depth = 0
        while (owner is Drawable && depth++ < 16) {
            outer = owner
            owner = owner.callback
        }
        val view = owner as? View
        val x = if (view?.foreground === outer) view.scrollX else 0
        val y = if (view?.foreground === outer) view.scrollY else 0
        if (x == 0 && y == 0) { super.draw(canvas); return }
        val saved = canvas.save()
        try {
            canvas.translate(x.toFloat(), y.toFloat())
            super.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }
}
