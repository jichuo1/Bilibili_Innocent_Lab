package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 首页推荐小卡成组规则的镜像。
 *
 * 宿主在刷新与加载更多时对每页响应调用 `ActionsKt.avoidEmptyWindow`（8.84–9.17.0 一致）：按顺序把双列小卡
 * 凑满一组（8.x 固定 2 张，9.1 起为窗口档位列数），遇到大卡或列表结束时，未凑满的那组**整组丢弃**。
 * 服务端下发的页本来成组完整；模块从中间删掉一张小卡，就会让同组另一张也被宿主扔掉。
 *
 * [regroup] 让未凑满的小卡越过其后的大卡、与下一段小卡凑组：小卡之间、大卡之间各自的相对顺序都不变，
 * 只是大卡可能比原位置早一行出现。末尾仍凑不满的那组照旧交给宿主丢弃，不跨页搬运卡片。
 *
 * 纯逻辑，不碰反射；[isSmall] 返回 null 表示读不出卡片样式，此时一律保持原样。
 */
internal object SmallCardGrouping {

    /** 宿主会丢弃的卡片数；任一卡片样式未知时返回 null。 */
    fun discarded(items: List<*>, groupSize: Int, isSmall: (Any?) -> Boolean?): Int? {
        if (groupSize < 1) return null
        var pending = 0
        var dropped = 0
        for (item in items) {
            val small = isSmall(item) ?: return null
            if (small) {
                pending += 1
                if (pending == groupSize) pending = 0
            } else {
                dropped += pending
                pending = 0
            }
        }
        return dropped + pending
    }

    /**
     * 返回重新凑组后的新列表；任一卡片样式未知、组大小无效或无需调整时返回 [items] 本身。
     * 结果与输入包含完全相同的元素，只改变未凑满小卡相对后续大卡的位置。
     */
    fun regroup(items: List<*>, groupSize: Int, isSmall: (Any?) -> Boolean?): List<*> {
        if (groupSize < 2 || items.size < 2) return items
        val out = ArrayList<Any?>(items.size)
        val pending = ArrayList<Any?>(groupSize)
        var moved = false
        for (item in items) {
            val small = isSmall(item) ?: return items
            if (small) {
                pending += item
                if (pending.size == groupSize) {
                    out.addAll(pending)
                    pending.clear()
                }
            } else {
                // 未凑满的小卡留在待定组里，越过这张大卡继续凑组。
                if (pending.isNotEmpty()) moved = true
                out += item
            }
        }
        out.addAll(pending)
        return if (moved) out else items
    }
}
