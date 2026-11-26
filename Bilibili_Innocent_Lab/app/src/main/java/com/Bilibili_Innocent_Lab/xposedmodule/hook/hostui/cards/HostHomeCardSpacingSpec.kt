package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

/** -1 保留原布局；自定义值表示卡片之间的总距离，而不是在宿主间距上叠加。 */
internal data class HostHomeCardSpacingSpec(val horizontalDp: Int = DEFAULT, val verticalDp: Int = DEFAULT) {
    val enabled: Boolean get() = horizontalDp >= 0 || verticalDp >= 0

    fun normalized() = HostHomeCardSpacingSpec(normalize(horizontalDp), normalize(verticalDp))

    fun horizontalMargin(span: Int, left: Boolean, padding: Int, decoration: Int, density: Float): Int? {
        if (horizontalDp < 0 || span !in 0..3) return null
        val gap = (horizontalDp * density).toInt()
        val inset = when {
            span == 2 || span == 0 && left || span == 1 && !left -> gap - padding
            left -> gap - gap / 2
            else -> gap / 2
        }
        return inset - decoration
    }

    fun verticalMargin(span: Int, top: Boolean, decoration: Int, density: Float): Int? {
        if (verticalDp < 0 || span !in 0..3) return null
        val gap = (verticalDp * density).toInt()
        return (if (top) gap / 2 else gap - gap / 2) - decoration
    }

    companion object {
        const val DEFAULT = -1
        const val MAX_DP = 40
        fun normalize(value: Int): Int = if (value in 0..MAX_DP) value else DEFAULT
    }
}
