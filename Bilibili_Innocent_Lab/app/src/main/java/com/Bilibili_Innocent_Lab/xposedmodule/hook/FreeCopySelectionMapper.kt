package com.Bilibili_Innocent_Lab.xposedmodule.hook

/**
 * 将气泡 TextView 的显示选区转换为可复制的纯文本。
 *
 * ReplacementSpan 在屏幕上是不可拆分的绘制单元，但其底层字符可能是 U+FFFC、宿主占位符
 * 或 `[表情名]`。调用方只需提供已确认的语义文本；本工具会在选区碰到 Span 时自动扩展到
 * 整个 Span，并用语义文本替换底层占位字符。纯 Kotlin 实现，不进入评论绑定/滚动热路径。
 */
internal object FreeCopySelectionMapper {

    data class Replacement(
        val displayStart: Int,
        val displayEnd: Int,
        val copyText: String
    )

    /** 当前可见 Span 与完整 raw 中同一自定义表情 token 的精确对应关系。 */
    data class AlignedReplacement(
        val displayStart: Int,
        val displayEnd: Int,
        val rawStart: Int,
        val rawEnd: Int,
        val copyText: String
    )

    private data class SemanticUnit(
        val key: String,
        val sourceStart: Int,
        val sourceEnd: Int,
        val replacement: Boolean,
        val bracketToken: String? = null
    )

    /**
     * 按“普通文字 + Emoji 槽位”的结构对齐 raw 与屏幕显示文本。只有屏幕 ReplacementSpan
     * 对应到 raw 中真实的 `[表情名]` 时才建立映射；原生 Unicode Emoji、用户输入的普通
     * `[文字]` 和宿主末尾 U+200B 都不会被误配。该方法只在用户长按时调用。
     */
    fun alignCustomEmojiTokens(
        rawText: String,
        displayText: CharSequence,
        displayReplacementRanges: List<IntRange>,
        expectedTokens: List<String> = emptyList()
    ): List<AlignedReplacement>? {
        val rawUnits = semanticUnits(rawText, emptyList())
        val displayUnits = semanticUnits(displayText, displayReplacementRanges)
        if (displayUnits.isEmpty()) return emptyList()

        fun matchesAt(offset: Int): Boolean {
            if (offset < 0 || offset + displayUnits.size > rawUnits.size) return false
            return displayUnits.indices.all { rawUnits[offset + it].key == displayUnits[it].key }
        }

        // 评论折叠只截掉尾部，正常情况必然从 raw 开头对齐；非零偏移只在唯一命中时接受，
        // 防止重复短语把某个 Span 映射到另一处同名表情。
        val offset = if (matchesAt(0)) {
            0
        } else {
            val candidates = (0..(rawUnits.size - displayUnits.size).coerceAtLeast(-1))
                .filter(::matchesAt)
            if (candidates.size != 1) return null
            candidates.single()
        }

        val out = ArrayList<AlignedReplacement>(displayReplacementRanges.size)
        var expectedIndex = 0
        displayUnits.forEachIndexed { index, displayUnit ->
            val rawUnit = rawUnits[offset + index]
            if (!displayUnit.replacement) return@forEachIndexed
            // 单个未知/非表情 ReplacementSpan 不得让同一评论已经确认的表情映射全部作废。
            // URL 模型适配会负责混合 Span 的主路径；这里保留结构对齐作为版本降级路径。
            val token = rawUnit.bracketToken ?: return@forEachIndexed
            if (expectedTokens.isNotEmpty()) {
                val matchedExpectedIndex = (expectedIndex until expectedTokens.size)
                    .firstOrNull { expectedTokens[it] == token }
                    ?: return@forEachIndexed
                expectedIndex = matchedExpectedIndex + 1
            }
            out += AlignedReplacement(
                displayStart = displayUnit.sourceStart,
                displayEnd = displayUnit.sourceEnd,
                rawStart = rawUnit.sourceStart,
                rawEnd = rawUnit.sourceEnd,
                copyText = token
            )
        }
        return out
    }

    private fun semanticUnits(
        text: CharSequence,
        replacementRanges: List<IntRange>
    ): List<SemanticUnit> {
        val ranges = replacementRanges
            .filter { it.first >= 0 && it.last >= it.first && it.last < text.length }
            .sortedBy { it.first }
        val out = ArrayList<SemanticUnit>(text.length)
        var rangeIndex = 0
        var i = 0
        while (i < text.length) {
            while (rangeIndex < ranges.size && ranges[rangeIndex].last < i) rangeIndex++
            val range = ranges.getOrNull(rangeIndex)
            if (range != null && i == range.first) {
                out += SemanticUnit(
                    key = CommentTextIdentity.EMOJI_SLOT.toString(),
                    sourceStart = range.first,
                    sourceEnd = range.last + 1,
                    replacement = true
                )
                i = range.last + 1
                rangeIndex++
                continue
            }

            val emojiEnd = CommentTextIdentity.emojiTokenEnd(text, i)
            if (emojiEnd > i) {
                val token = text.subSequence(i, emojiEnd).toString()
                out += SemanticUnit(
                    key = CommentTextIdentity.EMOJI_SLOT.toString(),
                    sourceStart = i,
                    sourceEnd = emojiEnd,
                    replacement = false,
                    bracketToken = token.takeIf { it.startsWith('[') && it.endsWith(']') }
                )
                i = emojiEnd
                continue
            }

            when {
                text[i] == '\uFFFC' -> out += SemanticUnit(
                    CommentTextIdentity.EMOJI_SLOT.toString(), i, i + 1, false
                )
                // 未被 ReplacementSpan 覆盖的 U+200B 是宿主排版哨兵，不是可复制内容。
                text[i] == '\u200B' || text[i].isWhitespace() || text[i] == '…' -> Unit
                i + 2 < text.length && text[i] == '.' && text[i + 1] == '.' && text[i + 2] == '.' -> {
                    i += 3
                    continue
                }
                else -> out += SemanticUnit(text[i].toString(), i, i + 1, false)
            }
            i++
        }
        return out
    }

    fun mapSelection(
        displayText: CharSequence,
        selectionStart: Int,
        selectionEnd: Int,
        replacements: List<Replacement>
    ): String? {
        if (selectionStart < 0 || selectionEnd < 0 || selectionStart == selectionEnd) return null

        var start = minOf(selectionStart, selectionEnd).coerceIn(0, displayText.length)
        var end = maxOf(selectionStart, selectionEnd).coerceIn(0, displayText.length)
        if (start >= end) return null

        val valid = replacements
            .asSequence()
            .filter {
                it.displayStart >= 0 && it.displayEnd > it.displayStart &&
                    it.displayEnd <= displayText.length && it.copyText.isNotEmpty()
            }
            .sortedBy { it.displayStart }
            .toList()

        // ReplacementSpan 是原子绘制单元：选区只要碰到它，就复制完整的语义 token。
        valid.forEach { replacement ->
            if (start < replacement.displayEnd && end > replacement.displayStart) {
                start = minOf(start, replacement.displayStart)
                end = maxOf(end, replacement.displayEnd)
            }
        }

        val out = StringBuilder(end - start)
        var cursor = start
        valid.forEach { replacement ->
            if (replacement.displayEnd <= start || replacement.displayStart >= end) return@forEach
            if (replacement.displayStart < cursor) return@forEach
            if (cursor < replacement.displayStart) {
                out.append(displayText, cursor, replacement.displayStart)
            }
            out.append(replacement.copyText)
            cursor = replacement.displayEnd
        }
        if (cursor < end) out.append(displayText, cursor, end)
        return out.toString()
    }
}
