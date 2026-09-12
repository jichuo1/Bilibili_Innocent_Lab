package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 详情页作者 / 标签黑名单的编解码。
 *
 * **为什么不复用 [RuleSetCodec]**：它的 `matches` 是子串 contains，用在作者名和分区标签上
 * 一定误伤——"科技" 会命中 "科技美学"、"影视" 会命中 "影视飓风"。这里只认**整串相等**
 * （按 Unicode 整串小写归一，与 `RuleSetCodec.parse` 的归一方式保持一致）。
 *
 * 作者一档同时承认 UP 名与 mid：读出来的 mid 会被格式化成十进制字符串再比，
 * 所以用户填 `3546963345672636` 或填 UP 名都能命中，不需要两个设置项。
 */
internal object ExactRuleSetCodec {
    private val separators = Regex("[,，;；\\r\\n]+")

    /** 与 [RuleSetCodec.parse] 同样的分隔与归一，只是不做任何子串语义。 */
    fun parse(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(separators)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::lowercase)
            .toCollection(LinkedHashSet())
    }

    /**
     * 任一取值整串命中即算命中。
     *
     * 读不到（null / 空白）一律**放行**：详情页有大量卡片没有作者或标签
     * （番剧、直播、广告位），"读不到"不等于"未知作者"。
     */
    fun matches(entries: Set<String>, vararg values: String?): Boolean {
        if (entries.isEmpty()) return false
        for (raw in values) {
            if (raw.isNullOrBlank()) continue
            if (raw.trim().lowercase() in entries) return true
        }
        return false
    }

    /** 回写用的规范形式：归一、去重、保序、逗号分隔。 */
    fun encode(values: Collection<String>): String =
        values.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::lowercase)
            .distinct()
            .joinToString(",")

    /** 面板点选时往已有名单里追加一项，返回新的存储字符串。 */
    fun add(raw: String?, value: String): String = encode(parse(raw) + value.trim().lowercase())
}
