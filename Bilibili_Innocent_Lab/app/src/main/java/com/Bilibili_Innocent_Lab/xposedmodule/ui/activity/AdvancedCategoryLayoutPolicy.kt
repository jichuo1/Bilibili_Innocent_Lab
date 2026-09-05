package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** 进阶设置原始子控件中，一个分类需要搬入折叠内容区的半开索引范围。 */
internal data class AdvancedCategoryChildRange(
    val startInclusive: Int,
    val endExclusive: Int
)

/**
 * 根据各分类标题的位置切分原始控件树，不依赖分类数量或用途。
 *
 * 标题是唯一分界；下一个标题前的最后一个 View 仍属于本组，不能再按旧布局假定它是分隔线。
 * 输入不完整或顺序异常时返回 null，让界面保留原始布局而不是冒险搬错或丢弃控件。
 */
internal object AdvancedCategoryLayoutPolicy {
    fun resolve(
        markerIndices: List<Int>,
        childCount: Int
    ): List<AdvancedCategoryChildRange>? {
        if (markerIndices.firstOrNull() != 0 || childCount <= 0) return null
        if (markerIndices.any { it !in 0 until childCount }) return null
        if (markerIndices.zipWithNext().any { (current, next) -> current >= next }) return null

        return markerIndices.mapIndexed { index, markerIndex ->
            val startInclusive = markerIndex + 1
            val endExclusive = markerIndices.getOrNull(index + 1) ?: childCount
            if (startInclusive > endExclusive) return null
            AdvancedCategoryChildRange(startInclusive, endExclusive)
        }
    }
}
