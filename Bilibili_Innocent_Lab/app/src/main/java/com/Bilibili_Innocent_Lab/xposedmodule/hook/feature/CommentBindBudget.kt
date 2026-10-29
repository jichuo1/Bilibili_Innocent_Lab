package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 评论绑定「按时间预算分批」的纯逻辑（假时钟、假队列可测）。**没有接入生产路径。**
 *
 * 为什么暂不接入：性能报告 P2-04 要求先测量每次 drain、每个 item 的耗时与节点数，再决定是否 deadline
 * 分批；[CommentBindProbe] 已就位，但还没有来自真实宿主（需要 root / LSPosed 注入的 Bilibili 进程）的数据。
 * 没有数据就不知道耗时主要来自多项累加（deadline 有用）还是单项过大（deadline 无用，需要可续跑游标），
 * 因此这里只固化「如果要做，边界应当是什么」，让将来的接入只剩接线。
 *
 * 与现有 `drainCommentBinds` 的对应关系（保持不变的部分）：
 * - 队列按入队先后排列，同一 View 只保留最新任务，超过 [capacity] 从最旧一端淘汰；
 * - 每次从队尾取最新的 [batchSize] 项（LIFO），批内按入队先后处理；
 * - generation / attach 检查仍由处理函数负责。
 *
 * 新增的部分：
 * - [CommentBindBudget.run] 在 item 之间检查时间预算，**每批至少处理一项**（保证前进）；
 * - 提前结束时，未处理项由 [CommentBindQueue.giveBack] 按 generation 序放回原位：不排到比批次之后
 *   入队的新任务更靠前的位置（LIFO 语义），其 View 已有更新任务时丢弃旧项（不覆盖较新代际），
 *   并重新服从容量上限；
 * - 批内最旧的等待超过 [CommentBindBudget.Policy.starvationLimitNs] 时放弃本批的预算，
 *   防止低优先级任务在持续有新任务时永久饥饿。
 *
 * 边界：单个 item 本身超预算时，这里无能为力（item 内部不可中断）；那需要可续跑游标，且恢复时必须重验
 * attach、generation 与交互状态，采集未完成时不能把未知项标成有效绑定——不在本组件范围内。
 *
 * 线程：两个类都不加锁。调用方（HookEntry）应在既有的 `pendingBindLock` 内调用 [CommentBindQueue]，
 * 在锁外调用 [CommentBindBudget.run]；View 树的读取与修改始终留在主线程。
 */
internal class CommentBindQueue<T : Any>(
    private val capacity: Int = 96,
    private val batchSize: Int = 6
) {
    class Entry<T : Any>(
        /** 同一 View 的身份；比较用 `===`。 */
        val key: Any,
        /** 全局单调递增的入队代次，兼作入队顺序。 */
        val generation: Long,
        /** 入队时刻（0 = 未打点）。 */
        val enqueuedAtNanos: Long,
        val payload: T
    )

    data class Enqueued(val replaced: Int, val overflowDropped: Int)
    data class GivenBack(val restored: Int, val supersededDropped: Int, val overflowDropped: Int)

    private val entries = ArrayList<Entry<T>>()

    val size: Int get() = entries.size

    /** 队列内容，最旧在前（诊断 / 测试用）。 */
    fun snapshot(): List<Entry<T>> = ArrayList(entries)

    /** 同 View 旧任务被替换，随后追加，超过容量从最旧一端淘汰。 */
    fun enqueue(entry: Entry<T>): Enqueued {
        var replaced = 0
        for (i in entries.lastIndex downTo 0) {
            if (entries[i].key === entry.key) {
                entries.removeAt(i)
                replaced++
            }
        }
        entries.add(entry)
        var overflow = 0
        while (entries.size > capacity) {
            entries.removeAt(0)
            overflow++
        }
        return Enqueued(replaced, overflow)
    }

    /** 取出最新的至多 [batchSize] 项；返回值按入队先后排列（与现有 drain 的处理顺序一致）。 */
    fun takeBatch(): List<Entry<T>> {
        val take = minOf(entries.size, batchSize)
        val start = entries.size - take
        val batch = ArrayList<Entry<T>>(take)
        for (i in start until entries.size) batch.add(entries[i])
        entries.subList(start, entries.size).clear()
        return batch
    }

    /**
     * 归还提前结束时没处理的项（[unprocessed] 按入队先后排列）。
     * - 该 View 已有更新的任务（generation 更大）→ 丢弃归还项；
     * - 否则按 generation 序插回，批次之后入队的新任务仍排在它们后面；
     * - 最后重新服从容量上限（最旧的先被淘汰）。
     */
    fun giveBack(unprocessed: List<Entry<T>>): GivenBack {
        var restored = 0
        var superseded = 0
        for (entry in unprocessed) {
            if (entries.any { it.key === entry.key && it.generation > entry.generation }) {
                superseded++
                continue
            }
            // 同 View 已有不更新的任务（理论上不会出现）：保留 generation 更大者，不制造重复。
            entries.removeAll { it.key === entry.key }
            var at = entries.size
            while (at > 0 && entries[at - 1].generation > entry.generation) at--
            entries.add(at, entry)
            restored++
        }
        var overflow = 0
        while (entries.size > capacity) {
            entries.removeAt(0)
            overflow++
        }
        return GivenBack(restored, superseded, overflow)
    }
}

internal object CommentBindBudget {
    /**
     * @param budgetNs 单次 drain 的时间预算；第二项起每项开始前检查，已用满就停。
     * @param starvationLimitNs 批内有任务已等待这么久，则本批不受预算限制（最终完成延迟的上界保护）。
     */
    class Policy(val budgetNs: Long, val starvationLimitNs: Long)

    class Outcome<T : Any>(
        val processed: Int,
        /** 提前结束时未处理的项，按入队先后排列；交给 [CommentBindQueue.giveBack]。 */
        val unprocessed: List<CommentBindQueue.Entry<T>>,
        val starvationOverride: Boolean
    ) {
        val stoppedEarly: Boolean get() = unprocessed.isNotEmpty()
    }

    fun <T : Any> run(
        batch: List<CommentBindQueue.Entry<T>>,
        startNs: Long,
        policy: Policy,
        clock: CommentBindProbe.Clock,
        process: (CommentBindQueue.Entry<T>) -> Unit
    ): Outcome<T> {
        val override = batch.any { it.enqueuedAtNanos != 0L && startNs - it.enqueuedAtNanos >= policy.starvationLimitNs }
        for (i in batch.indices) {
            // 每批至少处理一项：预算再小也保证前进。
            if (i > 0 && !override && clock.nanoTime() - startNs >= policy.budgetNs) {
                return Outcome(i, batch.subList(i, batch.size).toList(), override)
            }
            process(batch[i])
        }
        return Outcome(batch.size, emptyList(), override)
    }
}
