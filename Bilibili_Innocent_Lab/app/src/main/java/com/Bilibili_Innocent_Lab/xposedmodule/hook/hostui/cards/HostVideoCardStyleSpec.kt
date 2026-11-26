package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

internal object HostVideoCardStyleSpec {
    const val DEFAULT_RADIUS = -1
    const val MAX_RADIUS_DP = 40

    fun normalizeRadius(value: Int): Int = if (value in 0..MAX_RADIUS_DP) value else DEFAULT_RADIUS

    fun coverRadius(width: Int, height: Int, radiusDp: Int = DEFAULT_RADIUS, density: Float = 1f): Float {
        val shortSide = minOf(width, height).coerceAtLeast(0)
        return if (normalizeRadius(radiusDp) == DEFAULT_RADIUS) minOf(12f * density, shortSide / 2f)
        else minOf(radiusDp * density, shortSide / 2f)
    }

    fun cardRadius(width: Int, height: Int, radiusDp: Int, density: Float): Float =
        minOf((if (normalizeRadius(radiusDp) == DEFAULT_RADIUS) 16 else radiusDp) * density,
            minOf(width, height).coerceAtLeast(0) / 2f)
}

/** 保存宿主原始留白，复用/换列时不叠加；其他布局恢复原值。 */
internal class HostVideoCardSpacing(
    private val originalLeft: Int,
    private val originalTop: Int,
    private val originalRight: Int,
    private val originalBottom: Int,
    density: Float
) {
    private val outer = (14 * density).toInt()
    private val inner = (7 * density).toInt()
    private val extraTop = (5 * density).toInt()
    private val extraBottom = (12 * density).toInt()
    private val rowOuter = (12 * density).toInt()
    private val rowGap = (5 * density).toInt()

    fun left(span: Int, existingInset: Int = 0): Int = if (span == 2) maxOf(originalLeft, rowOuter - existingInset) else originalLeft + when (span) { 0 -> outer; 1, 3 -> inner; else -> 0 }
    fun right(span: Int, existingInset: Int = 0): Int = if (span == 2) maxOf(originalRight, rowOuter - existingInset) else originalRight + when (span) { 0, 3 -> inner; 1 -> outer; else -> 0 }
    fun top(span: Int): Int = if (span == 2) maxOf(originalTop, rowGap) else originalTop + if (span == 0 || span == 1 || span == 3) extraTop else 0
    fun bottom(span: Int): Int = if (span == 2) maxOf(originalBottom, rowGap) else originalBottom + if (span == 0 || span == 1 || span == 3) extraBottom else 0
}

/** 位置变化不属于形状变化；同尺寸复用无需重建背景和阴影轮廓。 */
internal class HostVideoCardGeometry {
    private var width = -1
    private var height = -1
    var radius = -1f
        private set

    fun update(width: Int, height: Int, radius: Float): Boolean {
        if (this.width == width && this.height == height && this.radius == radius) return false
        this.width = width
        this.height = height
        this.radius = radius
        return true
    }
}
