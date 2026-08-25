package com.Bilibili_Innocent_Lab.xposedmodule.hook

/** 纯文本身份归一化；不依赖 Android，便于覆盖 emoji/折叠文本回归测试。 */
internal object CommentTextIdentity {

    /** 身份比对专用 emoji 槽位，不会作为最终展示文本返回。 */
    const val EMOJI_SLOT = '\uE000'

    /**
     * B 站活动/联动表情名称可能显著长于普通 `[dog]`。原先 26 字符的窗口会让长名称
     * 在身份校验和剪贴板映射两处同时失效。评论正文总长另有 3000 上限，这里放宽到
     * 256 仍是严格有界扫描，并继续禁止跨行 token。
     */
    const val MAX_CUSTOM_EMOJI_TOKEN_LENGTH = 256

    /**
     * 宿主折叠控件追加的控制尾部很短；限制扫描窗口既避免把正文中间的省略号当成折叠
     * 标记，也让长按路径的判断保持严格有界。
     */
    private const val MAX_FOLD_CONTROL_TAIL_LENGTH = 96

    /**
     * 返回宿主追加的折叠控制尾部（如 `... 展开`）起点，未识别时返回 null。
     *
     * decoratedRanges 来自原始 Spanned 中的非 ReplacementSpan 装饰。B 站的可展开控件会
     * 分别给尾部省略标记和紧随其后的操作文案设置点击/着色 Span；用户正文中的普通
     * `...` 没有这组相邻结构，因此不能只按固定文案或纯字符串删除。
     */
    fun foldControlStart(text: CharSequence, decoratedRanges: List<IntRange>): Int? {
        if (text.length < 2 || decoratedRanges.size < 2) return null
        val minStart = (text.length - MAX_FOLD_CONTROL_TAIL_LENGTH).coerceAtLeast(1)
        val ranges = decoratedRanges
            .filter { it.first >= 0 && it.last >= it.first && it.last < text.length }
            .sortedBy { it.first }
        if (ranges.size < 2) return null

        for (marker in ranges.asReversed()) {
            if (marker.first < minStart) continue
            val markerText = text.subSequence(marker.first, marker.last + 1).toString().trim()
            val isEllipsisMarker = markerText == "…" ||
                (markerText.length >= 3 && markerText.all { it == '.' })
            if (!isEllipsisMarker) continue

            val markerEnd = marker.last + 1
            val hasAdjacentActionDecoration = ranges.any { candidate ->
                candidate !== marker &&
                    candidate.first in markerEnd..minOf(text.length, markerEnd + 1) &&
                    candidate.last >= candidate.first
            }
            if (hasAdjacentActionDecoration) return marker.first
        }
        return null
    }

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
                // B 站 9.8.0 富文本会在每段 ImageSpan 的 U+200B 之外，再在整段末尾
                // 追加一个不带 Span 的 U+200B。前者已由 replacementRanges 转为槽位，
                // 后者只是排版哨兵，不能参与评论身份或 Emoji 数量判断。
                text[i] == '\u200B' -> Unit
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
            val close = (start + 1 until minOf(text.length, start + MAX_CUSTOM_EMOJI_TOKEN_LENGTH + 1))
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
