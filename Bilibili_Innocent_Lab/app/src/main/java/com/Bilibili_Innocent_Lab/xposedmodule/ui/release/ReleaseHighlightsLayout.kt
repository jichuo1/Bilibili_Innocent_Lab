package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

internal object ReleaseHighlightsLayout {
    data class Budget(val bodyHeight: Int, val scrollWholeContent: Boolean)
    fun budget(available: Int, padding: Int, fixed: Int, bodyMargins: Int,
        desired: Int, minimumReadableBody: Int): Budget {
        val remaining = (available - padding - fixed - bodyMargins).coerceAtLeast(0)
        return if (remaining < minimumReadableBody)
            Budget((available - padding).coerceAtLeast(1),true)
        else Budget(minOf(desired,remaining),false)
    }
}
