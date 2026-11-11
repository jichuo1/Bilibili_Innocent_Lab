package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 「屏蔽 AI 评论与回复」的第四类特征：回复对主评论的复述。
 *
 * AI 小号在楼中楼里很少说自己的话：它们把主评论里的词句换个说法再讲一遍（一整排回复都在复述同一个短语）。
 * 真人的回复也会引用主评论，所以这里**只加分、封顶 [MAX_POINTS]**，单独不足以让任何阈值成立，
 * 只在昵称、等级、正文已经让分数"差一点"时推过线。
 *
 * - 只比较"原样出现"的片段：把两段文本切成只含字母数字汉字的片段，取 4 字窗口比对（窗口不跨标点、不含 @ 与表情占位）；
 * - 太通用的 4 字窗口（"这个视频"、"什么意思"……）、单字连写（"哈哈哈哈"）、纯 ASCII 窗口不算；
 * - 主评论与回复各自有效字数不足 [MIN_LETTERS] 时不比：短回复（"同款"、"哈哈"）本来就会和主评论撞词。
 *
 * 纯函数、无 Android 依赖；主评论只在分数落进"差一点"区间时才读取并建索引（见 [FishScorePolicy.score]）。
 */
internal object FishRootEcho {

    /** 本特征最多加多少分：低于最严档阈值 45 与"仅 Lv≤2"的 14 之差，所以它本身永远不够删。 */
    const val MAX_POINTS = 24
    const val MIN_LETTERS = 8

    private const val WINDOW = 4
    private const val RUN_MEDIUM = 5
    private const val RUN_LONG = 7
    private const val RUN_VERBATIM = 10

    /** 回复里 "回复 @某人 :" 前缀：不是回复自己说的话。 */
    private val LEADING_REPLY = Regex("^\\s*回复\\s*@[^\\s@:：，,]{1,24}\\s*[:：]?")

    /** 高频到几乎每对评论都会共有的 4 字窗口：恰好 4 个字、小写（与归一化后的文本同口径）。 */
    internal val GENERIC_TEXTS = listOf(
        "这个视频", "视频里的", "视频真的", "这个东西", "这个up", "up主的", "up主真", "什么意思", "什么时候",
        "怎么回事", "为什么要", "有没有人", "不知道为", "我觉得这", "我也觉得", "真的太好", "真的很好",
        "太好看了", "太好笑了", "谢谢大佬", "感谢分享", "第一次看", "一直在看"
    )
    private val GENERIC_WINDOWS: LongArray = GENERIC_TEXTS.map(::packString).sorted().toLongArray()

    /**
     * 一条回复所在楼的主评论：正文与昵称都延迟读取、只读一次。
     * 读取失败等同于读不到：不比较、不计分。
     */
    class Root(private val readText: () -> String?, private val readName: () -> String? = { null }) {
        @Volatile private var grams: Any? = null
        @Volatile private var name: Any? = null

        /** 主评论的 4 字窗口索引（升序去重）；正文读不到或有效字数不足时为 null。 */
        internal fun windows(): LongArray? {
            val cached = grams
            if (cached != null) return cached as? LongArray
            val built = runCatching { readText() }.getOrNull()
                ?.takeIf(String::isNotBlank)?.let(TextNormalizer::forMatching)
                ?.let(::indexOf)
            grams = built ?: ABSENT
            return built
        }

        /** 归一化后的主评论作者昵称；读不到为 null。 */
        internal fun authorName(): String? {
            val cached = name
            if (cached != null) return cached as? String
            val built = runCatching { readName() }.getOrNull()
                ?.takeIf(String::isNotBlank)?.let(TextNormalizer::forMatching)
            name = built ?: ABSENT
            return built
        }

        companion object {
            private val ABSENT = Any()

            fun of(text: String?, authorName: String? = null) = Root({ text }, { authorName })
        }
    }

    /**
     * [reply]（已归一化）对 [root] 的复述加分，0..[MAX_POINTS]。
     *
     * - 最长的一段原样复述：5～6 字 +6，7～9 字 +12，≥10 字 +16；
     * - 回复一半以上由主评论的片段拼成（且至少 8 字）：再 +8。
     */
    fun points(root: Root, reply: String): Int {
        val index = root.windows() ?: return 0
        val text = clean(reply)
        val letters = letterCount(text)
        if (letters < MIN_LETTERS) return 0
        var covered = 0
        var coveredEnd = 0
        var run = 0
        var longest = 0
        for (start in 0..text.length - WINDOW) {
            val key = windowKey(text, start)
            if (key != 0L && java.util.Arrays.binarySearch(index, key) >= 0) {
                run += 1
                if (run > longest) longest = run
                covered += start + WINDOW - maxOf(start, coveredEnd)
                coveredEnd = start + WINDOW
            } else {
                run = 0
            }
        }
        if (longest == 0) return 0
        val verbatim = longest + WINDOW - 1
        var points = when {
            verbatim >= RUN_VERBATIM -> 16
            verbatim >= RUN_LONG -> 12
            verbatim >= RUN_MEDIUM -> 6
            else -> 0
        }
        if (covered >= MIN_LETTERS && covered * 2 >= letters) points += 8
        return minOf(MAX_POINTS, points)
    }

    private fun indexOf(rootKey: String): LongArray? {
        val text = clean(rootKey)
        if (letterCount(text) < MIN_LETTERS) return null
        val keys = LongArray(maxOf(0, text.length - WINDOW + 1))
        var size = 0
        for (start in keys.indices) {
            val key = windowKey(text, start)
            if (key != 0L) keys[size++] = key
        }
        if (size == 0) return null
        val sorted = keys.copyOf(size).also { it.sort() }
        var unique = 1
        for (i in 1 until sorted.size) if (sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
        return sorted.copyOf(unique)
    }

    /** 去掉"回复 @某人 :"前缀、@ 提及与表情占位：这些不是说话人自己写的内容。 */
    private fun clean(text: String): String {
        var out = LEADING_REPLY.replace(text, "")
        out = FishScorePolicy.MENTION.replace(out, " ")
        return FishScorePolicy.EMOTE_PLACEHOLDER.replace(out, " ")
    }

    private fun letterCount(text: String): Int {
        var count = 0
        for (ch in text) if (Character.isLetterOrDigit(ch)) count++
        return count
    }

    /**
     * 从 [start] 起的 4 字窗口压成一个 Long；窗口里有非字母数字（标点、空白）、不含非 ASCII 字符、
     * 不同字符少于 3 个、或属于通用窗口时返回 0（0 不会是合法窗口：NUL 不是字母数字）。
     */
    private fun windowKey(text: String, start: Int): Long {
        val a = text[start]
        val b = text[start + 1]
        val c = text[start + 2]
        val d = text[start + 3]
        if (!Character.isLetterOrDigit(a) || !Character.isLetterOrDigit(b) ||
            !Character.isLetterOrDigit(c) || !Character.isLetterOrDigit(d)
        ) return 0L
        if (a.code < 0x80 && b.code < 0x80 && c.code < 0x80 && d.code < 0x80) return 0L
        var distinct = 1
        if (b != a) distinct++
        if (c != a && c != b) distinct++
        if (d != a && d != b && d != c) distinct++
        if (distinct < 3) return 0L
        val key = pack(a, b, c, d)
        return if (java.util.Arrays.binarySearch(GENERIC_WINDOWS, key) >= 0) 0L else key
    }

    private fun pack(a: Char, b: Char, c: Char, d: Char): Long =
        (a.code.toLong() shl 48) or (b.code.toLong() shl 32) or (c.code.toLong() shl 16) or d.code.toLong()

    private fun packString(window: String): Long = pack(window[0], window[1], window[2], window[3])
}
