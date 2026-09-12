package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 把"刚点的是哪一项"从面板那一跳带到占位卡那一跳。
 *
 * ## 为什么需要一个槽
 *
 * 被点项的 `extend`（分区项上等于这张卡的分区 id）只活在 `FeedbackItem` 上；
 * 而这张卡的分区只能从占位卡回指的原卡读到。两者不在同一个调用里：
 *
 * ```
 * FeedbackItem.getExtend()            ← 有 extend，没有卡
 *   ↓ 同一线程、同一次同步调用、相隔几微秒
 * DislikeItemData.setDislikeRequestRecord() / setSelectedDislikeReason()   ← 有卡，没有 extend
 * ```
 *
 * 中间那段是被 R8 重打包的宿主代码（`st0.o#b`），类名方法名全混淆，挂不上。
 * 所以用一个**按线程**的槽把前一跳的结果传给后一跳。
 *
 * ## 为什么不会串
 *
 * - **按线程隔离**：两跳必定同线程（同一次同步调用链），不会被别的线程的点击覆盖。
 * - **读一次就清**：[consume] 取走即失效，同一次点击不会被算两遍。
 * - **有时效**：超过 [FRESH_WINDOW_MS] 视为过期。旧版 `CardClickProcessor` 那条链
 *   会调 `getExtend()` 却不产生占位卡，留下的残值必须自己老死，不能被下一张卡认领。
 *
 * 三条保险任一生效，宁可漏记也不误记——错记会把用户没点过的分区写进黑名单。
 */
internal object SectionPickClickSlot {

    /** 两跳实际间隔是微秒级；给到 2 秒纯粹是容错，超过必然是残值。 */
    const val FRESH_WINDOW_MS = 2_000L

    /** 被点项的一次快照；只有数字与字符串，不含任何面板文字。 */
    data class Pick(val itemId: Long?, val extend: String?, val atMillis: Long)

    private val slot = ThreadLocal<Pick?>()

    fun remember(itemId: Long?, extend: String?, nowMillis: Long) {
        slot.set(Pick(itemId, extend, nowMillis))
    }

    /** 取走并清空；过期的一律当没有。 */
    fun consume(nowMillis: Long): Pick? {
        val pick = slot.get() ?: return null
        slot.set(null)
        return pick.takeIf { nowMillis - it.atMillis in 0..FRESH_WINDOW_MS }
    }

    fun resetForTest() = slot.set(null)
}
