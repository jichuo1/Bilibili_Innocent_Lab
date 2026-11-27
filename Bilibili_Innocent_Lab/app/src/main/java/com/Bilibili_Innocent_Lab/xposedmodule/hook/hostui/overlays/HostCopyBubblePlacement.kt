package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays

/** 保留原定位规则：文字左侧对齐、底部 20% 留白、失效锚点回退，并避开系统栏与刘海安全区。 */
internal object HostCopyBubblePlacement {
    fun left(
        anchorX: Int,
        bubbleMaxWidth: Int,
        screenWidth: Int,
        lead: Int,
        margin: Int,
        safeLeft: Int = 0,
        safeRight: Int = 0
    ): Float {
        val minLeft = safeLeft.coerceAtLeast(0) + margin
        val maxRight = (screenWidth - safeRight.coerceAtLeast(0) - margin).coerceAtLeast(minLeft)
        var x = (anchorX - lead).toFloat()
        if (x + bubbleMaxWidth > maxRight) x = (maxRight - bubbleMaxWidth).toFloat()
        return x.coerceAtLeast(minLeft.toFloat())
    }

    fun top(
        anchorY: Int,
        anchorHeight: Int,
        shown: Boolean,
        bubbleHeight: Int,
        screenHeight: Int,
        gap: Int,
        margin: Int,
        safeTop: Int = 0,
        safeBottom: Int = 0
    ): Float {
        val minTop = safeTop.coerceAtLeast(0) + margin
        val bottomClearance = maxOf(
            screenHeight - (screenHeight * .8f).toInt(),
            safeBottom.coerceAtLeast(0) + margin
        )
        val maxBottom = (screenHeight - bottomClearance).coerceAtLeast(minTop)
        val y = if (shown && anchorY in 0 until screenHeight) anchorY else (screenHeight * .4f).toInt()
        var top = (y + anchorHeight + gap).toFloat()
        if (top + bubbleHeight > maxBottom) top = (y - bubbleHeight - gap).toFloat()
        if (top + bubbleHeight > maxBottom) top = (maxBottom - bubbleHeight).toFloat()
        return top.coerceAtLeast(minTop.toFloat())
    }
}
