package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

/** Only reject definitely off-window surfaces; uncertain geometry fails open. */
internal object LiquidRefreshVisibilityPolicy {
    fun intersectsWindow(left: Float, top: Float, right: Float, bottom: Float,
        windowLeft: Float, windowTop: Float, windowRight: Float, windowBottom: Float,
        padding: Float): Boolean {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            !windowLeft.isFinite() || !windowTop.isFinite() || !windowRight.isFinite() ||
            !windowBottom.isFinite() || !padding.isFinite() || padding < 0f ||
            right <= left || bottom <= top || windowRight <= windowLeft || windowBottom <= windowTop) return true
        return right + padding >= windowLeft && bottom + padding >= windowTop &&
            left - padding <= windowRight && top - padding <= windowBottom
    }
}

/** Off-window surfaces stay registered, and re-entry refreshes even at the original recording origin. */
internal class LiquidSurfaceRefreshState {
    private var skipped = false

    fun shouldRefresh(visible: Boolean, originChanged: Boolean, contentChanged: Boolean): Boolean {
        if (!visible) {
            skipped = true
            return false
        }
        val refresh = skipped || originChanged || contentChanged
        skipped = false
        return refresh
    }
}
