package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/**
 * 功能开关类二级菜单的水平留白唯一来源（净化进阶、增强进阶、兼容）。
 *
 * 基准是既有净化布局：菜单壳 8dp + 分组内容 12dp，控件距菜单边框合计 20dp。
 * 平铺分组同样需要内容留白，不能因为没有折叠卡片就省略。兼容菜单壳为 10dp，
 * 应补足剩余距离，而不是直接照抄 12dp。传入壳的实际像素值后相减，避免不同密度的取整偏差。
 * 留白只由内容容器承担；可点击入口行不要再额外叠加水平 padding。不要改全局 MaterialSwitch。
 * 标题过长时应精简文案或按设计允许换行，不能通过缩小留白来消除省略号。
 */
internal object SettingsMenuSpacing {
    const val ADVANCED_SHELL_DP = 8
    const val EXPERIMENTAL_SHELL_DP = 10
    private const val REFERENCE_CONTENT_DP = 12f

    fun referenceContentPaddingPx(density: Float): Int =
        (REFERENCE_CONTENT_DP * density).toInt()

    fun matchingContentPaddingPx(
        referenceShellPx: Int,
        ownShellPx: Int,
        density: Float
    ): Int = (referenceShellPx + referenceContentPaddingPx(density) - ownShellPx)
        .coerceAtLeast(0)
}
