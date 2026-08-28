package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

/** 一次回复脉络分析的稳定身份；只保存宿主提供的基础数值，不持有宿主对象。 */
internal data class ReplyTopologyThreadKey(
    val oid: Long,
    val type: Long,
    val rootRpid: Long
) {
    val isValid: Boolean
        get() = oid > 0L && type >= 0L && rootRpid > 0L
}

/**
 * 回复节点的紧凑输入快照。调用方应在宿主 Hook/请求回调内立即完成转换，不能把
 * ReplyInfo、CommentItem、Spanned 或 View 带入后台构图线程。
 */
internal data class ReplyTopologyNodeSnapshot(
    val rpid: Long,
    val rootRpid: Long,
    val parentRpid: Long,
    val dialogId: Long,
    val ctime: Long,
    val authorMid: Long,
    val authorName: String,
    val repliedAuthorName: String?,
    val messagePreview: String,
    val flags: Int = 0
) {
    companion object {
        const val DEFAULT_MESSAGE_PREVIEW_CODE_POINTS = 120
        private const val MAX_AUTHOR_NAME_CODE_POINTS = 48

        /** 将宿主 CharSequence 一次性收敛为有界 String，防止 Span/宿主对象逃逸。 */
        fun fromRaw(
            rpid: Long,
            rootRpid: Long,
            parentRpid: Long,
            dialogId: Long = 0L,
            ctime: Long = 0L,
            authorMid: Long = 0L,
            authorName: CharSequence? = null,
            repliedAuthorName: CharSequence? = null,
            message: CharSequence? = null,
            flags: Int = 0,
            previewCodePoints: Int = DEFAULT_MESSAGE_PREVIEW_CODE_POINTS
        ): ReplyTopologyNodeSnapshot = ReplyTopologyNodeSnapshot(
            rpid = rpid,
            rootRpid = rootRpid,
            parentRpid = parentRpid,
            dialogId = dialogId,
            ctime = ctime,
            authorMid = authorMid,
            authorName = ReplyTopologyText.summarize(authorName, MAX_AUTHOR_NAME_CODE_POINTS),
            repliedAuthorName = repliedAuthorName
                ?.let { ReplyTopologyText.summarize(it, MAX_AUTHOR_NAME_CODE_POINTS) }
                ?.takeIf(String::isNotEmpty),
            messagePreview = ReplyTopologyText.summarize(message, previewCodePoints),
            flags = flags
        )
    }
}

/** 位标记避免为每个节点分配 EnumSet；渲染层按位决定占位和异常提示。 */
internal object ReplyTopologyNodeFlags {
    const val ROOT = 1
    const val PLACEHOLDER = 1 shl 1
    const val MISSING_ROOT = 1 shl 2
    const val MISSING_PARENT = 1 shl 3
    const val SELF_PARENT = 1 shl 4
    const val CYCLE = 1 shl 5
    const val DUPLICATE = 1 shl 6
    const val DUPLICATE_CONFLICT = 1 shl 7
    const val FILTERED = 1 shl 8
    const val UNAVAILABLE = 1 shl 9

    fun has(flags: Int, flag: Int): Boolean = flags and flag != 0
}

internal data class ReplyTopologyGraphDiagnostics(
    val droppedInvalidNodes: Int = 0,
    val droppedWrongRootNodes: Int = 0,
    val duplicateNodes: Int = 0,
    val conflictingDuplicateNodes: Int = 0,
    val missingParentNodes: Int = 0,
    val selfParentNodes: Int = 0,
    val cycleNodes: Int = 0,
    val brokenCycleEdges: Int = 0
)

/**
 * 供 UI 直接渲染的不可变语义快照。各数组共享同一 index；父节点由 parentIndexes 表示，
 * 不再分配 Edge/children 对象。顺序为 root-first 的迭代式先序，兄弟节点按 ctime+rpid 稳定排序。
 */
internal class ReplyTopologyGraph internal constructor(
    val key: ReplyTopologyThreadKey,
    internal val rpids: LongArray,
    internal val parentIndexes: IntArray,
    internal val depths: IntArray,
    internal val childCounts: IntArray,
    internal val ctimes: LongArray,
    internal val authorMids: LongArray,
    internal val dialogIds: LongArray,
    internal val flags: IntArray,
    internal val authorNames: Array<String>,
    internal val repliedAuthorNames: Array<String?>,
    internal val messagePreviews: Array<String>,
    val diagnostics: ReplyTopologyGraphDiagnostics
) {
    val size: Int
        get() = rpids.size

    init {
        val expectedSize = rpids.size
        require(
            parentIndexes.size == expectedSize &&
                depths.size == expectedSize &&
                childCounts.size == expectedSize &&
                ctimes.size == expectedSize &&
                authorMids.size == expectedSize &&
                dialogIds.size == expectedSize &&
                flags.size == expectedSize &&
                authorNames.size == expectedSize &&
                repliedAuthorNames.size == expectedSize &&
                messagePreviews.size == expectedSize
        ) { "Reply topology arrays must have the same size" }
    }
}

/** 文本只做一次有界归一化；不会切断 UTF-16 代理对、组合符或常见 ZWJ Emoji。 */
internal object ReplyTopologyText {
    private const val ZERO_WIDTH_SPACE = 0x200B
    private const val ZERO_WIDTH_NO_BREAK_SPACE = 0xFEFF
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val MAX_CLUSTER_EXTENSION_CODE_POINTS = 32

    fun summarize(text: CharSequence?, maxCodePoints: Int): String {
        if (text.isNullOrEmpty() || maxCodePoints <= 0) return ""
        val normalized = normalizeWhitespace(text)
        if (normalized.isEmpty()) return ""

        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount <= maxCodePoints) return normalized

        var end = normalized.offsetByCodePoints(0, maxCodePoints)
        end = keepBracketTokenWhole(normalized, end)
        end = extendUnicodeCluster(normalized, end)
        return if (end >= normalized.length) normalized else normalized.substring(0, end) + '…'
    }

    private fun normalizeWhitespace(text: CharSequence): String {
        val out = StringBuilder(text.length.coerceAtMost(256))
        var pendingSpace = false
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            when {
                codePoint == ZERO_WIDTH_SPACE || codePoint == ZERO_WIDTH_NO_BREAK_SPACE -> Unit
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> {
                    if (out.isNotEmpty()) pendingSpace = true
                }
                else -> {
                    if (pendingSpace) out.append(' ')
                    out.appendCodePoint(codePoint)
                    pendingSpace = false
                }
            }
        }
        return out.toString()
    }

    /** 避免把 `[自定义表情]` 切成半个 token；超长异常 token 则在 `[` 前截断。 */
    private fun keepBracketTokenWhole(text: String, proposedEnd: Int): Int {
        val open = text.lastIndexOf('[', startIndex = proposedEnd - 1)
        if (open < 0 || text.indexOf(']', startIndex = open) < proposedEnd) return proposedEnd
        val close = text.indexOf(']', startIndex = proposedEnd)
        if (close < 0 || close - open > 256) return if (open > 0) open else proposedEnd
        val containsLineBreak = (open + 1 until close).any { text[it] == '\r' || text[it] == '\n' }
        return if (containsLineBreak) proposedEnd else close + 1
    }

    private fun extendUnicodeCluster(text: String, proposedEnd: Int): Int {
        var end = proposedEnd
        var extensions = 0

        // 国旗由两个区域指示符组成，不能在两者之间截断。
        if (end < text.length && isRegionalIndicator(Character.codePointAt(text, end))) {
            var previous = end
            var regionalCount = 0
            while (previous > 0) {
                val codePoint = Character.codePointBefore(text, previous)
                if (!isRegionalIndicator(codePoint)) break
                regionalCount++
                previous -= Character.charCount(codePoint)
            }
            if (regionalCount % 2 == 1) {
                end += Character.charCount(Character.codePointAt(text, end))
                extensions++
            }
        }

        while (end < text.length && extensions < MAX_CLUSTER_EXTENSION_CODE_POINTS) {
            val next = Character.codePointAt(text, end)
            when {
                isClusterExtension(next) -> {
                    end += Character.charCount(next)
                    extensions++
                }
                next == ZERO_WIDTH_JOINER -> {
                    val joinedStart = end + Character.charCount(next)
                    if (joinedStart >= text.length) break
                    val joined = Character.codePointAt(text, joinedStart)
                    end = joinedStart + Character.charCount(joined)
                    extensions += 2
                }
                end > 0 && Character.codePointBefore(text, end) == ZERO_WIDTH_JOINER -> {
                    end += Character.charCount(next)
                    extensions++
                }
                else -> break
            }
        }
        return end
    }

    private fun isClusterExtension(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint in 0xE0020..0xE007F ||
            codePoint == 0x20E3
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF
}
