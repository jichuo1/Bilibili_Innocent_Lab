package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

/** 顶栏标签在功能区分割边缘渐隐的几何；View 与 Compose 两条路径共用。 */
internal object HostTopTabFadePolicy {
    const val FADE_DP = 24f
    /** 渐隐终点与菜单/搜索按钮之间留出的空隙。 */
    const val ACTION_GAP_DP = 4f
    /** 少量标签由居中逻辑接管内边距，渐隐只处理需要滚动的多标签条。 */
    const val MIN_ITEMS = 3

    /** 标签条右缘越过 [limit] 的部分并入右内边距，内容在分割边缘处被裁切并渐隐。 */
    fun endPadding(basePadding: Int, viewRight: Int, limit: Int): Int =
        basePadding.coerceAtLeast(0) + (viewRight - limit).coerceAtLeast(0)

    /** 与 HorizontalScrollView 的实际滚动上限一致：视口要扣掉两侧内边距。 */
    fun maxScroll(contentWidth: Int, viewWidth: Int, paddingLeft: Int, paddingRight: Int): Int =
        (contentWidth - (viewWidth - paddingLeft - paddingRight)).coerceAtLeast(0)

    /** 已向右滚过的距离决定左侧渐隐长度比例；未滚动时首个标签完整显示。 */
    fun leadingStrength(scroll: Int, fadePx: Float): Float =
        if (fadePx <= 0f || scroll <= 0) 0f else (scroll / fadePx).coerceIn(0f, 1f)

    /** 还能继续向右滚的距离决定右侧渐隐长度比例；滚到底时末个标签完整显示。 */
    fun trailingStrength(scroll: Int, maxScroll: Int, fadePx: Float): Float =
        if (fadePx <= 0f || maxScroll <= scroll) 0f else ((maxScroll - scroll) / fadePx).coerceIn(0f, 1f)
}
