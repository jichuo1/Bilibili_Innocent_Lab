package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 动态页净化的纯判定层：只吃已经读好的信号，不接触 protobuf、反射和 Hook 边界。
 *
 * 关键词**按文本片段逐段匹配**，不把整条动态的各段正文拼成一个大字符串再匹配。拼接会产生
 * 跨段落的假命中（前一段结尾 + 后一段开头恰好凑出关键词），也会给每张卡片多一次字符串分配。
 */
internal object DynamicPurifyPolicy {

    /**
     * 带货与「UP 主推荐」附加卡的枚举常量名。
     *
     * 运行期优先读宿主 `AdditionalType` 上的同名 `int` 常量；读不到才用
     * [FALLBACK_PROMOTION_ADDITIONAL_TYPES]。
     */
    val PROMOTION_ADDITIONAL_TYPE_FIELDS = listOf(
        "additional_type_goods_VALUE",
        "additional_type_up_rcmd_VALUE"
    )

    /** 与上面同序的兜底数值，仅在宿主常量读不出来时使用。 */
    val FALLBACK_PROMOTION_ADDITIONAL_TYPES = setOf(2, 6)

    /** 单条动态读出来的判定信号；null 一律表示"这次没读到"，不能当成"没有"。 */
    internal class Signals(
        val authorName: String? = null,
        val authorMid: Long? = null,
        val promotion: Boolean = false,
        val lockedChargeOnly: Boolean = false,
        /** 逐段回调正文；返回 true 表示已命中，调用方可以立刻停。 */
        val textFragments: ((String) -> Boolean) -> Boolean = { false }
    )

    /** 安装期定型的判据集合；热路径只读它。 */
    internal data class Plan(
        val keywords: Set<String>,
        val authorRules: AuthorRuleSet,
        val removePromotion: Boolean,
        val removeLockedChargeOnly: Boolean
    ) {
        val needsText: Boolean
            get() = keywords.isNotEmpty()

        val needsAuthor: Boolean
            get() = authorRules.isNotEmpty()

        val hasAnyItemJudgement: Boolean
            get() = needsText || needsAuthor || removePromotion || removeLockedChargeOnly

        fun describe(): String = buildList {
            if (keywords.isNotEmpty()) add("keyword=${keywords.size}")
            if (authorRules.isNotEmpty()) {
                add("author=${authorRules.mids.size}uid+${authorRules.names.size}name")
            }
            if (removePromotion) add("promotion")
            if (removeLockedChargeOnly) add("charge-only")
        }.joinToString("/")
    }

    /** 读取失败一律保守放行；只有明确命中某条判据才删除。 */
    fun shouldRemove(signals: Signals, plan: Plan): Boolean {
        if (plan.removePromotion && signals.promotion) return true
        if (plan.removeLockedChargeOnly && signals.lockedChargeOnly) return true
        if (plan.needsAuthor && plan.authorRules.matches(signals.authorName, signals.authorMid)) {
            return true
        }
        if (plan.keywords.isEmpty()) return false
        return signals.textFragments { fragment ->
            RuleSetCodec.matches(plan.keywords, fragment)
        }
    }
}
