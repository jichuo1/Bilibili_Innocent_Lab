package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * "用户这次不感兴趣该记下哪个标签"的纯判据。
 *
 * ## tid 是标签，不是分区
 *
 * 2026-09-12 实测定死：宿主把 `ArgsData.getTid()` 写进不感兴趣请求时用的键是 `tag_id`，
 * `getRid()` 才是 `rid`（分区）。所以这整条链记的、推荐过滤比的，都是**标签**。
 * 类名与设置键沿用历史的 `tid`/`section` 字样，只是不改 id 的代价，语义以本注释为准。
 *
 * ## 判据
 *
 * 1. **结构自证**：`extend` 解析出的数字**正好等于这张卡的 `args.tid`**。
 *    命中即可确定"这一项说的就是这张卡的标签"，不依赖任何 id 约定，也不看文字。
 * 2. **兜底**：`id ∈ {2,3}`（旧面板语义，某些卡型上仍可能沿用）。
 *
 * 两条都要求 `type == DISLIKE`（FEEDBACK 组是"色情低俗"那一批，语义完全不同）
 * 且这张卡读得到 `tid`（读不到就放弃，宁可不记也不猜）。
 *
 * ## extend 目前恒为 null
 *
 * 同日实测 9.10.0：面板 11 项（第一层 2 项 + 第二层 9 项）的 `extend` 全是 null，
 * 判据 1 在这个宿主版本上**拿不到数据**，实际生效的只有兜底。这一条不删——
 * `extend` 是唯一自证的判据，宿主哪天填上了它就该立刻优先。没命中的点击会留下
 * 带 `id/extend/tid/rid` 的诊断，见 [shouldDiagnose]。
 *
 * [cardRid] 只进诊断日志、**不参与判定**：它是分区 id，和标签不是同一个 id 空间，
 * 拿它去比标签名单会误删。留着是为了日后真要做"按分区过滤"时有实测证据。
 *
 * **不按面板文字匹配**——AGENTS.md 红线，有测试钉住。标签名只用于快照展示。
 */
internal object SectionPickPolicy {
    /** 旧面板里"分区"（直播卡）与"频道"（普通卡）的两个 id，仅作兜底。 */
    private val LEGACY_SECTION_IDS = setOf(2L, 3L)

    /** 只有"不感兴趣"那一组才承载标签；反馈组不是。 */
    const val DISLIKE_TYPE = "DISLIKE"

    /**
     * 判定这次点击要记下哪个标签；判不出来就返回 null。
     *
     * @param typeName `FeedbackType` 枚举名，读不到传 null
     * @param itemId 被点项的 id，读不到传 null
     * @param extend 被点项的 `extend`，读不到传 null（9.10.0 实测恒为 null）
     * @param cardTid 这张卡的 `args.tid`（**标签 id**），读不到传 null
     * @return 要记下的标签 id；判不出来就是 null
     */
    fun resolveSection(
        typeName: String?,
        itemId: Long?,
        extend: String?,
        cardTid: Long?
    ): Long? {
        if (typeName != DISLIKE_TYPE) return null
        val tid = cardTid?.takeIf { it > 0L } ?: return null
        if (matchesExtend(extend, tid)) return tid
        if (itemId != null && itemId in LEGACY_SECTION_IDS) return tid
        return null
    }

    /** `extend` 是否就是这张卡的标签。 */
    fun matchesExtend(extend: String?, cardTid: Long): Boolean = parseExtend(extend) == cardTid

    /**
     * `extend` 解析成 id。
     *
     * 只认**整串就是这个数字**；`extend` 常见形态还有 JSON 串，那种一律不认——
     * 从 JSON 里捞数字等于在猜结构，宁可退到兜底判据。
     */
    fun parseExtend(extend: String?): Long? =
        extend?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it > 0L }

    /**
     * 点了"不感兴趣"组但两条判据都没命中时，是否值得记一条诊断。
     *
     * 判据来自抓包推断，宿主改了 `extend` 的形态我们不会自动知道。这里对**每个没见过的
     * id/extend 组合**留一条日志（带 id/extend/tid/rid），真机点一次就能拿到真值，
     * 不用再猜。`rid` 一并记下来，是为了日后真要做"按分区过滤"时手里有实测数据。
     * 有界：只记 [MAX_DIAGNOSTIC_IDS] 个不同组合，避免刷屏。
     */
    fun shouldDiagnose(typeName: String?, cardTid: Long?, cardRid: Long?): Boolean =
        typeName == DISLIKE_TYPE &&
            ((cardTid != null && cardTid > 0L) || (cardRid != null && cardRid > 0L))

    const val MAX_DIAGNOSTIC_IDS = 8

    /** 快照条目的 key；同一标签点多次只留一条。key 前缀沿用历史值，不改。 */
    fun snapshotKey(section: Long): String = "tid:$section"

    const val SNAPSHOT_KIND = "section"
}
