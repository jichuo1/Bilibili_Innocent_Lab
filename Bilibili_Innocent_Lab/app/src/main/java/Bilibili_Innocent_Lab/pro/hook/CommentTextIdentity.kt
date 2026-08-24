package Bilibili_Innocent_Lab.pro.hook

/** 纯文本身份归一化；不依赖 Android，便于覆盖 emoji/折叠文本回归测试。 */
internal object CommentTextIdentity {

    /** 身份比对专用 emoji 槽位，不会作为最终展示文本返回。 */
    const val EMOJI_SLOT = '\uE000'

    fun matchKey(text: CharSequence, replacementRanges: List<IntRange> = emptyList()): String {
        val ranges = replacementRanges.sortedBy { it.first }
        val out = StringBuilder(text.length)
        var rangeIndex = 0
        var i = 0
        while (i < text.length) {
            while (rangeIndex < ranges.size && ranges[rangeIndex].last < i) rangeIndex++
            val spanRange = ranges.getOrNull(rangeIndex)
            if (spanRange != null && i == spanRange.first) {
                out.append(EMOJI_SLOT)
                i = minOf(text.length, spanRange.last + 1)
                rangeIndex++
                continue
            }
            val emojiEnd = emojiTokenEnd(text, i)
            if (emojiEnd > i) {
                out.append(EMOJI_SLOT)
                i = emojiEnd
                continue
            }
            when {
                text[i] == '\uFFFC' -> out.append(EMOJI_SLOT)
                text[i] == '…' -> Unit
                i + 2 < text.length && text[i] == '.' && text[i + 1] == '.' && text[i + 2] == '.' -> {
                    i += 3
                    continue
                }
                !text[i].isWhitespace() -> out.append(text[i])
            }
            i++
        }
        return out.toString()
    }

    /** 返回从 start 开始的 emoji/[自定义表情] token 的 exclusive end，非 token 返回 start。 */
    fun emojiTokenEnd(text: CharSequence, start: Int): Int {
        if (start >= text.length) return start
        if (text[start] == '[') {
            val close = (start + 1 until minOf(text.length, start + 26))
                .firstOrNull { text[it] == ']' }
            if (close != null && close > start + 1 &&
                (start + 1 until close).none { text[it] == '\r' || text[it] == '\n' }
            ) return close + 1
        }
        val first = Character.codePointAt(text, start)
        if (first in 0x30..0x39 || first == 0x23 || first == 0x2A) {
            var keycapEnd = start + Character.charCount(first)
            if (keycapEnd < text.length && Character.codePointAt(text, keycapEnd) == 0xFE0F) {
                keycapEnd += Character.charCount(0xFE0F)
            }
            return if (keycapEnd < text.length && Character.codePointAt(text, keycapEnd) == 0x20E3) {
                keycapEnd + Character.charCount(0x20E3)
            } else {
                start
            }
        }
        if (!isEmojiCodePoint(first)) return start
        var end = start + Character.charCount(first)
        // 国旗由两个区域指示符组成；将整面旗帜视作一个 emoji。
        if (first in 0x1F1E6..0x1F1FF && end < text.length) {
            val second = Character.codePointAt(text, end)
            if (second in 0x1F1E6..0x1F1FF) end += Character.charCount(second)
        }
        while (end < text.length) {
            val cp = Character.codePointAt(text, end)
            when {
                cp == 0xFE0E || cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF ->
                    end += Character.charCount(cp)
                cp == 0x200D -> {
                    val nextStart = end + Character.charCount(cp)
                    if (nextStart >= text.length) break
                    val next = Character.codePointAt(text, nextStart)
                    if (!isEmojiCodePoint(next)) break
                    end = nextStart + Character.charCount(next)
                }
                cp in 0xE0020..0xE007F -> end += Character.charCount(cp)
                else -> break
            }
        }
        return end
    }

    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 ||
            cp == 0x2122 || cp == 0x2139 || cp == 0x3030 || cp == 0x303D ||
            cp == 0x3297 || cp == 0x3299
}
