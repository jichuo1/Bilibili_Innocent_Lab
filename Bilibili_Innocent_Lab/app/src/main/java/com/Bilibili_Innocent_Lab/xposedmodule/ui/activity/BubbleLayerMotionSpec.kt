package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/**
 * 气泡表面、逐行内容与来源图标的分层交接（纯数学，不依赖 Android）。
 *
 * 所有层共用同一个展开进度；退场及打断只需反向推进，不另开延迟任务或第二条动画时钟。
 * 内容在表面展开后由上至下滑入，同一业务行保持一个动画单位，不拆散标题与说明。
 */
internal object BubbleLayerMotionSpec {
    /** 正文起点：表面已在 0.15 处完全不透明，正文从这里才开始，不与表面淡入抢戏。 */
    const val CONTENT_START = 0.26f

    /** 正文最迟在展开进度 90% 到位，稳定端必须精确恢复原 alpha 与位移。 */
    const val CONTENT_END = 0.90f

    /**
     * 每行自身的淡入时长 = 相邻两行间隔的这个倍数。
     *
     * 2 即相邻行重叠 50%——"链式加入"的标准手感：上一行刚过半，下一行才起步。
     */
    const val CONTENT_WINDOW_STEPS = 2f

    /**
     * 逐行链式加入。
     *
     * 原实现把**固定总跨度 0.28** 摊到行数上，每行却固定淡入 0.30：GitHub 面板 8 行时
     * 间隔只有 `0.28 / 7 = 0.04`，相邻行重叠 **87%**，八行几乎同时浮现，看上去是一整块
     * 在淡入而不是一行接一行。
     *
     * 改成**恒定间隔 + 与间隔成比例的窗口**，重叠恒为 50%，行数越多只是整条链跑得越快，
     * 链感不会退化成整块。间隔由末行结束正好落在 [CONTENT_END] 反解出来：
     * `START + step*(n-1) + step*STEPS = END`。
     */
    fun contentFraction(progress: Float, index: Int, count: Int): Float {
        val p = boundedProgress(progress)
        if (p >= CONTENT_END) return 1f
        val rows = count.coerceAtLeast(1)
        val lastIndex = rows - 1
        // Float 而非 Int 相加：count 取到 Int.MAX_VALUE 时 rows + 1 会溢出成负数。
        val step = (CONTENT_END - CONTENT_START) /
            (rows.toFloat() + CONTENT_WINDOW_STEPS - 1f)
        val start = CONTENT_START + step * index.coerceIn(0, lastIndex)
        val end = start + step * CONTENT_WINDOW_STEPS
        return smooth(start, end, p)
    }

    fun surfaceOpacity(progress: Float): Float = smooth(0.035f, 0.15f, progress)

    /**
     * 图标接过真实来源后随即开始与气泡表面融合。
     *
     * 淡出窗口 `0.05 → 0.26` 是从 `0.12 → 0.30` 提前来的：真实图标按下面的互补关系接回，
     * 淡出起点越靠近交接点（0.035），"槽位里既没有真实图标、幽灵也已经飞走"的那段就越短。
     * 提前后仅剩 `p ∈ [0.035, 0.05]`（245ms 入场里约 **3.7ms**，不足一帧）真实图标为 0，
     * 而那一刻幽灵才飞出行程的 1%，仍严丝合缝压在原位上。
     */
    fun iconOpacity(progress: Float): Float =
        smooth(0f, 0.035f, progress) * (1f - smooth(0.05f, 0.26f, progress))

    /**
     * 真实来源图标的权重，**恒等于 1 减去图标层的不透明度**——来源位置的图案总量守恒。
     *
     * 原来是一条单调降到 0 的曲线（只交出、不接回），靠 [BubblePanelLayer.settleExpanded] 在
     * 动画末尾一次性还原。但气泡挂在图标**下方**、并不遮住图标：图标层在 30% 处就已淡尽，
     * 真实图标却要等展开结束才回来，工具栏上那个位置因此空掉约 70% 的展开时长——现场表现
     * 正是"图标突然消失一下"。
     *
     * 改成互补关系后 `p ≤ 0.12` 的两条曲线与原来逐位相同（交接、轮廓融合的观感不变），
     * 只是幽灵淡出的同一段里真实图标淡回原位，末端精确回到 1。收起方向由同一进度反向推进，
     * 自动对称，不需要第二条曲线。
     */
    fun sourceIconWeight(progress: Float): Float = 1f - iconOpacity(progress)

    fun contourMix(progress: Float): Float = 1f - smooth(0.03f, 0.14f, progress)

    fun iconTravelFraction(progress: Float): Float = smooth(0.035f, 0.28f, progress)

    private fun boundedProgress(progress: Float): Float =
        if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)

    private fun smooth(start: Float, end: Float, progress: Float): Float {
        val p = boundedProgress(progress)
        if (p <= start) return 0f
        if (p >= end) return 1f
        // Double 中间值避免 Float 在接近 1 时出现微小反向舍入；最终仍为有界 Float。
        val t = (p.toDouble() - start) / (end.toDouble() - start)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }
}
