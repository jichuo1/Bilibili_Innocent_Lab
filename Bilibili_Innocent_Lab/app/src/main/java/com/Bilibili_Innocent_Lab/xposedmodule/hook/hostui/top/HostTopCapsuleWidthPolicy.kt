package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockWindow
import kotlin.math.max

/**
 * 顶栏胶囊的左右边距。手机宽度保持原边距，只额外避开刘海与侧边导航栏；
 * 超宽窗口（与底栏同一门槛）限宽居中，标签放不下时仍靠滑动与弧形渐隐。
 */
internal object HostTopCapsuleWidthPolicy {
    /** 比 5 个标签的大底栏（488dp）宽一些：顶栏同时承载分类与功能按钮。 */
    const val MAX_WIDTH_DP = 560f

    fun marginDp(parentWidthDp: Float, baseMarginDp: Float, sideDp: Float): Float {
        val safe = max(baseMarginDp, if (sideDp > 0f) sideDp + HostDockWindow.SIDE_GAP_DP else 0f)
        if (!parentWidthDp.isFinite() || parentWidthDp < HostDockWindow.WIDE_WINDOW_DP) return safe
        return max(safe, (parentWidthDp - MAX_WIDTH_DP) / 2f)
    }
}
