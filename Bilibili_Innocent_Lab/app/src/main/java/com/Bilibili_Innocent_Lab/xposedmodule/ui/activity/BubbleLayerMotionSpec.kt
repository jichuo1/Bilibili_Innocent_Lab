package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/**
 * 气泡表面、逐行内容与来源图标的分层交接（纯数学，不依赖 Android）。
 *
 * 所有层共用同一个展开进度；退场及打断只需反向推进，不另开延迟任务或第二条动画时钟。
 * 内容在表面展开后由上至下滑入，同一业务行保持一个动画单位，不拆散标题与说明。
 */
internal object BubbleLayerMotionSpec {
    /** 正文最迟在展开进度 90% 到位，稳定端必须精确恢复原 alpha 与位移。 */
    fun contentFraction(progress: Float, index: Int, count: Int): Float {
        val p = boundedProgress(progress)
        if (p >= 0.90f) return 1f
        val lastIndex = count.coerceAtLeast(1) - 1
        val rank = if (lastIndex == 0) 0f else index.coerceIn(0, lastIndex).toFloat() / lastIndex
        val start = 0.32f + 0.28f * rank
        // 浮点相加可能令最后一行的结束值略大于 .9；统一固定稳定端。
        val end = (start + 0.30f).coerceAtMost(0.90f)
        return smooth(start, end, p)
    }

    fun surfaceOpacity(progress: Float): Float = smooth(0.035f, 0.15f, progress)

    /** 真实来源图标仅在贴合原位置的末端交接，不能与已移动的图标层同时完整显示。 */
    fun sourceIconWeight(progress: Float): Float = 1f - smooth(0f, 0.035f, progress)

    /** 图标接过真实来源后短暂保留，再与气泡表面融合。 */
    fun iconOpacity(progress: Float): Float =
        (1f - sourceIconWeight(progress)) * (1f - smooth(0.12f, 0.30f, progress))

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
