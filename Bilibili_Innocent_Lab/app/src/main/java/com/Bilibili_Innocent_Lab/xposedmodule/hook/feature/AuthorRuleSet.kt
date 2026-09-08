package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 「按发布者过滤」的共用规则集：评论区、动态页和搜索结果三面共用同一份语义。
 *
 * 同一个输入框里既能写 UID 也能写用户名：纯数字且为正的行按 UID 匹配，其余按用户名匹配。
 *
 * **用户名一律全等匹配**（大小写与首尾空白忽略）。这里刻意不用包含匹配——一条 "a" 之类的
 * 规则会把整页内容删光，而"某个 UP 的名字是另一个 UP 名字的前缀"在站内非常常见。要按文字
 * 内容过滤请用各面自己的关键词规则。
 */
internal data class AuthorRuleSet(
    val mids: Set<Long>,
    val names: Set<String>
) {
    fun isEmpty(): Boolean = mids.isEmpty() && names.isEmpty()

    fun isNotEmpty(): Boolean = !isEmpty()

    /** 安装期按独立读取路径裁剪可执行规则；不能因 UID 缺失丢掉仍可用的用户名规则。 */
    fun available(nameAvailable: Boolean, midAvailable: Boolean): AuthorRuleSet =
        if ((nameAvailable || names.isEmpty()) && (midAvailable || mids.isEmpty())) this
        else AuthorRuleSet(if (midAvailable) mids else emptySet(), if (nameAvailable) names else emptySet())

    /** 两项都读不到时返回 false：读取失败一律保守放行，绝不按"疑似"删除。 */
    fun matches(name: String?, mid: Long?): Boolean =
        (mid != null && mid in mids) ||
            (name != null && name.trim().lowercase() in names)

    companion object {
        val EMPTY = AuthorRuleSet(emptySet(), emptySet())

        /** 单面规则条数上限；防止误粘贴超长文本让每条卡片都做一次大集合查表。 */
        const val MAX_RULES = 256

        fun parse(raw: String): AuthorRuleSet {
            val mids = linkedSetOf<Long>()
            val names = linkedSetOf<String>()
            // RuleSetCodec 已完成分隔、去空与小写化，这里只做 UID / 用户名的二分。
            RuleSetCodec.parse(raw).take(MAX_RULES).forEach { token ->
                val mid = token.toLongOrNull()
                if (mid != null && mid > 0L) mids += mid else names += token
            }
            return AuthorRuleSet(mids, names)
        }
    }
}
