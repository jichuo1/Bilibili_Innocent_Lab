package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 自定义隐藏规则统一解析：逗号、中文逗号、分号或换行分隔，忽略空项和大小写。 */
internal object RuleSetCodec {
    private val separators = Regex("[,，;；\\r\\n]+")

    fun parse(raw: String): Set<String> = raw.split(separators)
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .toCollection(linkedSetOf())

    fun matches(tokens: Set<String>, vararg values: String?): Boolean {
        if (tokens.isEmpty()) return false
        for (raw in values) {
            if (raw == null) continue
            // 保持 Unicode 整串小写映射语义；ignoreCase 子串比较不与它等价。
            val value = raw.lowercase()
            for (token in tokens) {
                if (value.contains(token)) return true
            }
        }
        return false
    }
}
