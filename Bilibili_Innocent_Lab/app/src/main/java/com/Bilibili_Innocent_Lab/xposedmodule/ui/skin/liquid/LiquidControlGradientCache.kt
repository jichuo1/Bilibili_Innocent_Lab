package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

/** Vertical gradients do not depend on horizontal bounds. Mutable key avoids animation-time allocation. */
internal class LiquidControlGradientCache {
    private var initialized = false
    private var top = 0f
    private var bottom = 0f
    private var startColor = 0
    private var endColor = 0

    fun update(top: Float, bottom: Float, startColor: Int, endColor: Int): Boolean {
        if (initialized && this.top == top && this.bottom == bottom &&
            this.startColor == startColor && this.endColor == endColor) return false
        initialized = true
        this.top = top
        this.bottom = bottom
        this.startColor = startColor
        this.endColor = endColor
        return true
    }
}
