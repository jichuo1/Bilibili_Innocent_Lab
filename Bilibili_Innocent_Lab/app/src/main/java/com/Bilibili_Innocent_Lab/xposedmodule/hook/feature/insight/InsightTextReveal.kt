package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import java.text.BreakIterator
import java.util.Locale

/** 只对尚未显现的尾部维护字素透明度，限制大块响应的动画积压与 span 数量。 */
internal class InsightTextReveal {
    data class Glyph(val start: Int, val end: Int, val born: Long) {
        fun alpha(now: Long): Float {
            val p = ((now - born) / 180f).coerceIn(0f, 1f)
            return p * p * (3 - 2 * p)
        }
    }
    private var text = ""
    private val pending = mutableListOf<Glyph>()
    fun update(value: String, now: Long): List<Glyph> {
        var common = 0
        while (common < minOf(text.length, value.length) && text[common] == value[common]) common++
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
        if (!iterator.isBoundary(common)) common = iterator.preceding(common)
        pending.removeAll { it.end > common || it.alpha(now) >= 1f }
        val ranges = ArrayDeque<Pair<Int, Int>>()
        var start = common
        var end = iterator.following(start)
        while (end != BreakIterator.DONE) {
            ranges.addLast(start to end)
            if (ranges.size > 96) ranges.removeFirst()
            start = end; end = iterator.next()
        }
        var born = maxOf(now - 6, pending.lastOrNull()?.born ?: now - 6)
        for ((from, to) in ranges) {
            born = minOf(born + 6, now + 180)
            pending += Glyph(from, to, born)
        }
        while (pending.size > 96) pending.removeAt(0)
        text = value
        return pending.toList()
    }
    fun active(now: Long): Boolean = pending.any { it.alpha(now) < 1f }
    fun clear() { text = ""; pending.clear() }
}
