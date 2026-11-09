package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

/** 宿主 LayerDrawable 里单层的种类；只描述「是否是需要被中和的中性实色层」。 */
internal enum class HostLayerKind {
    OTHER,
    /** ColorDrawable，颜色不透明且接近灰度。 */
    NEUTRAL_COLOR,
    /** 无圆角、无渐变、无状态色的矩形 GradientDrawable，颜色不透明且接近灰度。 */
    NEUTRAL_PLAIN_GRADIENT
}

/** [HostLayerNeutralPlan] 判定所需的全部读取；实现只读，不得复制或修改被读取的 Drawable。 */
internal interface HostLayerSnapshot {
    val count: Int
    fun insetLeft(index: Int): Int
    fun insetTop(index: Int): Int
    fun insetRight(index: Int): Int
    fun insetBottom(index: Int): Int
    fun width(index: Int): Int
    fun height(index: Int): Int
    fun kind(index: Int): HostLayerKind
}

/**
 * 宿主顶栏/输入栏 LayerDrawable 的中和计划。
 *
 * 先在**原** Drawable 上只读扫描，只有确实存在要改的层才返回计划；调用方此时才复制并 `mutate()`。
 * 已经透明、或分隔线与透明底层都处于目标状态的 Drawable 返回 null，不产生任何复制。
 * 判定规则与原先在复制品上的判定逐项一致（复制品与原件在复制瞬间结构相同）。
 * 不按 Drawable 对象身份缓存「已处理」：宿主可能在同一对象上改色值或状态。
 */
internal class HostLayerNeutralPlan private constructor(
    /** >0 表示「两块全宽矩形模拟顶边线」：底层限制为该线高，顶层改透明；此时 [cleared] 为空。 */
    val borderLine: Int,
    /** 需要改成透明的层下标（升序）。 */
    val cleared: IntArray
) {
    val isBorderedInput: Boolean get() = borderLine > 0

    companion object {
        private val NONE = IntArray(0)

        /**
         * @param viewWidth 持有该背景的 View 宽度
         * @param viewHeight 持有该背景的 View 高度
         * @param maxBorderLine 顶边线的最大像素高度（原实现为 `density * 4`）
         */
        fun of(layers: HostLayerSnapshot, viewWidth: Int, viewHeight: Int, maxBorderLine: Int): HostLayerNeutralPlan? {
            val count = layers.count
            val line = if (count == 2) layers.insetTop(1) else 0
            val borderedInput = line in 1..maxBorderLine &&
                (0 until 2).all { i ->
                    layers.height(i) <= 0 && layers.insetLeft(i) == 0 && layers.insetRight(i) == 0 &&
                        layers.insetBottom(i) == 0 && layers.kind(i) == HostLayerKind.NEUTRAL_PLAIN_GRADIENT
                } && layers.insetTop(0) == 0
            if (borderedInput) return HostLayerNeutralPlan(line, NONE)
            var cleared: IntArray? = null
            var size = 0
            for (i in 0 until count) {
                // 输入栏底色与顶边分隔线共享 LayerDrawable，只移除覆盖整块栏位的纯色层。
                val height = layers.height(i)
                val width = layers.width(i)
                if (height > 0 && height < viewHeight * .9f || width > 0 && width < viewWidth * .9f) continue
                if (layers.kind(i) == HostLayerKind.OTHER) continue
                val target = cleared ?: IntArray(count).also { cleared = it }
                target[size++] = i
            }
            return cleared?.let { HostLayerNeutralPlan(0, it.copyOf(size)) }
        }
    }
}
