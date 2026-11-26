package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

/** Compose `$changed` 参数掩码操作：每参数槽占 3 bits（shift = 1 + index * 3），索引超界时不移位以免回绕污染低位槽。 */
internal object ComposeChangedMask {
    private const val SLOT_MASK = 0b1110
    private const val UNCERTAIN_VALUE = 0b0100

    /** 清空已替换参数在 `$changed` 中的比较位（bits 1..3），保留强制重绘 bit 0 及其他参数槽。 */
    fun clearSlots(flags: Int, vararg parameterIndices: Int): Int {
        var mask = 0
        for (index in parameterIndices) {
            if (index in 0..9) mask = mask or (SLOT_MASK shl (index * 3))
        }
        return if (mask == 0) flags else flags and mask.inv()
    }

    /** 清空已替换参数槽并置为 `Uncertain (0b0100)`，促使后续 Compose 条件分支重新评估。 */
    fun markUncertain(flags: Int, vararg parameterIndices: Int): Int {
        var mask = 0
        var uncertain = 0
        for (index in parameterIndices) {
            if (index in 0..9) {
                val shift = index * 3
                mask = mask or (SLOT_MASK shl shift)
                uncertain = uncertain or (UNCERTAIN_VALUE shl shift)
            }
        }
        return if (mask == 0) flags else (flags and mask.inv()) or uncertain
    }
}
