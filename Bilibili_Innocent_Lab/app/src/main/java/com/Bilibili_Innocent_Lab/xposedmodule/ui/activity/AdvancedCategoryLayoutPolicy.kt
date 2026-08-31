package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** 进阶设置原始子控件中，一个分类需要搬入折叠内容区的半开索引范围。 */
internal data class AdvancedCategoryChildRange(
    val startInclusive: Int,
    val endExclusive: Int
)

/**
 * 根据四个分类标题的位置切分原始控件树。
 *
 * 相邻分类标题前的最后一个 View 是旧版分隔线；重组为独立卡片后不再需要，因此范围会主动
 * 排除该 View。输入不完整或顺序异常时返回 null，让界面保留原始布局而不是冒险搬错控件。
 */
internal object AdvancedCategoryLayoutPolicy {
    fun resolve(
        markerIndices: List<Int>,
        childCount: Int
    ): List<AdvancedCategoryChildRange>? {
        if (markerIndices.isEmpty() || childCount <= 0) return null
        if (markerIndices.any { it !in 0 until childCount }) return null
        if (markerIndices.zipWithNext().any { (current, next) -> current >= next }) return null

        return markerIndices.mapIndexed { index, markerIndex ->
            val startInclusive = markerIndex + 1
            val endExclusive = markerIndices.getOrNull(index + 1)?.minus(1) ?: childCount
            if (startInclusive > endExclusive) return null
            AdvancedCategoryChildRange(startInclusive, endExclusive)
        }
    }
}
