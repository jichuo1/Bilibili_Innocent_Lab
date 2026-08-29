package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import kotlin.math.roundToInt

/**
 * 脉络悬浮窗折叠/展开的纯动效规格：时长、紧凑高度、内容错相位 alpha 窗口与插值。
 *
 * 设计映射自设置备份页的容器动效纪律：高度是唯一几何通道（内容不做非等比缩放），
 * 内容 alpha 相对高度动画错相位——收起时内容先行淡出再收缩（被裁剪的始终是透明
 * 内容），展开时先撑开再渐显；动画进行中的重复触发由调用方消费，不在本规格内。
 * 无 Android 依赖，供 JVM 测试锁定端点与单调性。
 */
internal object ReplyTopologyCompactMotionSpec {

    /** 折叠态保留的标题行与状态行高度（dp）；状态行保留以持续展示进度与提示。
     *  数值必须与 PanelView 的 createHeader/createStatusRow 布局参数保持一致。 */
    const val HEADER_HEIGHT_DP = 48
    const val STATUS_HEIGHT_DP = 38

    /** 折叠态总高度（dp）= 标题行 + 状态行。 */
    const val COMPACT_HEIGHT_DP = HEADER_HEIGHT_DP + STATUS_HEIGHT_DP

    /** 收起时长略短于展开：收起是"让出视野"，用户对它的等待容忍度更低。 */
    const val COLLAPSE_DURATION_MS = 240L
    const val EXPAND_DURATION_MS = 280L

    /** 收起时内容（透明度行 + 列表）alpha 归零的进度上限；其后被裁剪的均为透明内容。 */
    const val CONTENT_FADE_OUT_END = 0.35f

    /** 展开时内容开始渐显的进度下限；容器先到位，正文后显。 */
    const val CONTENT_FADE_IN_START = 0.55f

    /**
     * 校验展开/紧凑高度关系，非法输入直接抛出，避免动画以负值或倒置区间运行。
     * @return 校验通过的 (expandedPx, compactPx)
     */
    fun requireHeights(expandedPx: Int, compactPx: Int): Pair<Int, Int> {
        require(expandedPx > 0) { "expanded height must be positive: $expandedPx" }
        require(compactPx > 0) { "compact height must be positive: $compactPx" }
        require(expandedPx > compactPx) {
            "expanded height $expandedPx must exceed compact height $compactPx"
        }
        return expandedPx to compactPx
    }

    /** 进度 [0,1] 区间内的线性高度插值；越界进度被钳制，结果不小于 1px。 */
    fun heightAt(progress: Float, fromPx: Int, toPx: Int): Int {
        val clamped = progress.coerceIn(0f, 1f)
        val value = fromPx + (toPx - fromPx) * clamped
        return value.roundToInt().coerceAtLeast(1)
    }

    /**
     * 内容区 alpha：收起时在 [0, CONTENT_FADE_OUT_END] 线性淡出至 0 后保持；
     * 展开时在 [CONTENT_FADE_IN_START, 1] 线性渐显。中间进度保持相位边界值，
     * 保证与高度动画组合时内容从不以半透明状态被裁剪进标题行。
     */
    fun contentAlphaAt(progress: Float, collapsing: Boolean): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return if (collapsing) {
            if (clamped >= CONTENT_FADE_OUT_END) 0f
            else 1f - clamped / CONTENT_FADE_OUT_END
        } else {
            if (clamped <= CONTENT_FADE_IN_START) 0f
            else (clamped - CONTENT_FADE_IN_START) / (1f - CONTENT_FADE_IN_START)
        }
    }

    /** 展开态高度在折叠期间保持不变；调用方无需自行缓存即可安全重入展开。 */
    fun requireExpandedHeight(expandedPx: Int, compactPx: Int): Int =
        requireHeights(expandedPx, compactPx).first
}
