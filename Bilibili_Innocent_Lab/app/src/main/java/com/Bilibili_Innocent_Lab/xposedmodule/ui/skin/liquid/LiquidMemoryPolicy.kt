package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

/**
 * onTrimMemory 的数值不是简单的“越大越紧急”：20 表示 UI hidden，而 15 才表示前台运行
 * 临界内存。显式分类可避免用户普通切后台后永久丢失 Liquid 图形资源。
 */
internal object LiquidMemoryPolicy {
    private const val RUNNING_CRITICAL = 15
    private const val COMPLETE = 80

    fun shouldReleaseGraphics(level: Int): Boolean =
        level == RUNNING_CRITICAL || level >= COMPLETE
}
