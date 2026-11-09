package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.util.Locale

/**
 * 评论绑定批处理的有界诊断：先测量，再决定 drainCommentBinds 要不要做 deadline 分批或可续跑游标
 * （性能报告 P2-04「依据实际工作量分批绑定」）。
 *
 * 记录每次 drain 的耗时、批大小、队列长度、被 generation 淘汰 / 已脱离窗口的项数，以及每个真正
 * 做了整树工作的 item 的耗时、访问节点数、可绑定 View 数和入队到完成的等待时间。
 *
 * ### 成本约束
 * - **默认关闭**。关闭时每个入口只做一次 volatile 读后返回，不读时钟、不加锁、不分配。
 * - 打开后内存固定：两个环形缓冲（最近 [DRAIN_CAPACITY] 次 drain、最近 [ITEM_CAPACITY] 个 item）
 *   在首次打开时一次性分配，之后记录路径不分配对象；累计计数器是固定个数的 Long。
 * - 逐节点的 [countNode] 不加锁，只对主线程内的普通字段自增，由调用方先用本地布尔值短路。
 * - 百分位只在读取时对窗口副本排序，不在绑定路径上计算。
 *
 * ### 线程
 * 记录入口都在主线程（drain、View 树）或持有入队锁的线程调用；记录与读取共用一把私有锁，
 * 因此 [snapshot] / [report] 可以在任意线程调用。锁只在打开时才会被取，且从不在持锁时回调外部代码。
 *
 * ### 它不是什么
 * 时间取自 [Clock]（生产为 `System.nanoTime`），是主线程上 drain / item 的挂钟耗时，不是帧耗时：
 * 它不知道这段时间与哪一帧重叠。JVM 里用假时钟得到的数值只验证记录逻辑，不是性能结论。
 *
 * ### 在宿主里打开
 * 生产代码在安装评论 Hook 时读取一次 `Log.isLoggable("BilCommentBindProbe", DEBUG)`：
 * `adb shell setprop log.tag.BilCommentBindProbe D`，然后重启宿主进程。读数以 `BilibiliInnocentLab`
 * 标签输出（同时进框架日志），由 [takeReportIfDue] 限频生成，不逐 item 打日志。
 */
internal class CommentBindProbe(private val clock: Clock = Clock { System.nanoTime() }) {
    fun interface Clock {
        fun nanoTime(): Long
    }

    /** 窗口内的分位统计（最近的至多 N 个样本；[count] 为 0 时其余字段为 0）。 */
    data class Series(val count: Int, val p50: Long, val p95: Long, val p99: Long, val max: Long)

    data class DrainSample(
        val durationNs: Long,
        /** drain 内单个 item 的最长耗时：与 [durationNs] 对比可区分「多项累加」与「单项过大」。 */
        val maxItemNs: Long,
        val queueBefore: Int,
        val taken: Int,
        val bound: Int,
        val invalid: Int,
        val detached: Int,
        val stale: Int,
        val nodes: Int,
        val queueAfter: Int
    )

    data class ItemSample(
        val durationNs: Long,
        /** 入队到本 item 完成的时间；入队时探针未打开则为 -1。 */
        val waitNs: Long,
        val nodes: Int,
        val bindable: Int,
        val valid: Boolean
    )

    data class Snapshot(
        val enabled: Boolean,
        val drains: Long,
        val deferredByScroll: Long,
        val deferredByTouch: Long,
        val deferredByQuiet: Long,
        /** 真正执行了整树工作的 item 数（不含 generation 淘汰与已脱离窗口的）。 */
        val items: Long,
        val boundItems: Long,
        val invalidItems: Long,
        val detachedItems: Long,
        val staleGenerationItems: Long,
        val enqueued: Long,
        val replacedDuplicates: Long,
        val overflowDropped: Long,
        val maxQueueLength: Int,
        val totalNodes: Long,
        val maxNodes: Int,
        val maxDrainNs: Long,
        val maxItemNs: Long,
        val drainNs: Series,
        val itemNs: Series,
        val itemNodes: Series,
        val waitNs: Series,
        val recentDrains: List<DrainSample>,
        val recentItems: List<ItemSample>
    )

    private class DrainRing {
        val durationNs = LongArray(DRAIN_CAPACITY)
        val maxItemNs = LongArray(DRAIN_CAPACITY)
        val queueBefore = IntArray(DRAIN_CAPACITY)
        val taken = IntArray(DRAIN_CAPACITY)
        val bound = IntArray(DRAIN_CAPACITY)
        val invalid = IntArray(DRAIN_CAPACITY)
        val detached = IntArray(DRAIN_CAPACITY)
        val stale = IntArray(DRAIN_CAPACITY)
        val nodes = IntArray(DRAIN_CAPACITY)
        val queueAfter = IntArray(DRAIN_CAPACITY)
        var size = 0
        var next = 0
    }

    private class ItemRing {
        val durationNs = LongArray(ITEM_CAPACITY)
        val waitNs = LongArray(ITEM_CAPACITY)
        val nodes = IntArray(ITEM_CAPACITY)
        val bindable = IntArray(ITEM_CAPACITY)
        val valid = BooleanArray(ITEM_CAPACITY)
        var size = 0
        var next = 0
    }

    private val lock = Any()
    private var drainRing: DrainRing? = null
    private var itemRing: ItemRing? = null

    /** 打开时一次性分配环形缓冲；关闭只停止记录，已记录的数据保留到 [reset]。 */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (value) synchronized(lock) {
                if (drainRing == null) drainRing = DrainRing()
                if (itemRing == null) itemRing = ItemRing()
            }
            field = value
        }

    // ---- 累计计数（lock 保护）----
    private var drains = 0L
    private var deferredByScroll = 0L
    private var deferredByTouch = 0L
    private var deferredByQuiet = 0L
    private var items = 0L
    private var boundItems = 0L
    private var invalidItems = 0L
    private var detachedItems = 0L
    private var staleItems = 0L
    private var enqueued = 0L
    private var replacedDuplicates = 0L
    private var overflowDropped = 0L
    private var maxQueueLength = 0
    private var totalNodes = 0L
    private var maxNodes = 0
    private var maxDrainNs = 0L
    private var maxItemNs = 0L

    // ---- 当前 drain 的累加（lock 保护；beginDrain 复位）----
    private var curBound = 0
    private var curInvalid = 0
    private var curDetached = 0
    private var curStale = 0
    private var curNodes = 0
    private var curMaxItemNs = 0L

    // ---- 当前 item 的节点计数：只由主线程的 View 树遍历读写，不加锁 ----
    private var itemNodes = 0
    private var itemBindable = 0

    // ---- 限频报告（lock 保护）----
    private var drainsSinceReport = 0
    private var lastReportNs = NO_TOKEN

    /** 入队时间戳：打开时读时钟，关闭时返回 0（表示未打点）。 */
    fun stamp(): Long = if (enabled) clock.nanoTime() else 0L

    /** 一次入队：去重替换了 [replaced] 项旧任务，容量上限淘汰了 [overflowed] 项，之后队列长 [queueLength]。 */
    fun onEnqueue(replaced: Int, overflowed: Int, queueLength: Int) {
        if (!enabled) return
        synchronized(lock) {
            enqueued++
            replacedDuplicates += replaced
            overflowDropped += overflowed
            if (queueLength > maxQueueLength) maxQueueLength = queueLength
        }
    }

    /** 一次 drain 因 [reason]（[DEFER_SCROLL] / [DEFER_TOUCH] / [DEFER_QUIET]）被推迟。 */
    fun onDeferred(reason: Int) {
        if (!enabled) return
        synchronized(lock) {
            when (reason) {
                DEFER_SCROLL -> deferredByScroll++
                DEFER_TOUCH -> deferredByTouch++
                DEFER_QUIET -> deferredByQuiet++
            }
        }
    }

    /** drain 通过全部门控、开始取批。返回令牌；探针关闭时返回 [NO_TOKEN]，对应的 [endDrain] 什么都不做。 */
    fun beginDrain(): Long {
        if (!enabled) return NO_TOKEN
        synchronized(lock) {
            curBound = 0
            curInvalid = 0
            curDetached = 0
            curStale = 0
            curNodes = 0
            curMaxItemNs = 0L
        }
        return clock.nanoTime()
    }

    /** 本 item 的 generation 已被更新的入队取代，直接丢弃，没有做树工作。 */
    fun noteStale() {
        if (!enabled) return
        synchronized(lock) {
            staleItems++
            curStale++
        }
    }

    /** 本 item 已脱离窗口，只撤销登记，没有做树工作。 */
    fun noteDetached() {
        if (!enabled) return
        synchronized(lock) {
            detachedItems++
            curDetached++
        }
    }

    /** 一个 item 的整树工作开始：复位节点计数。关闭时返回 [NO_TOKEN]。 */
    fun beginItem(): Long {
        if (!enabled) return NO_TOKEN
        itemNodes = 0
        itemBindable = 0
        return clock.nanoTime()
    }

    /** 访问了一个 View 节点。调用方必须先用本地布尔值短路，关闭时不应进入这里。 */
    fun countNode() {
        itemNodes++
    }

    /** 本次收集到的可绑定（或需清理）候选 View 数。 */
    fun noteBindable(count: Int) {
        itemBindable = count
    }

    /**
     * item 完成。[enqueuedAtNanos] 是 [stamp] 的返回值（0 表示入队时探针未打开）；
     * [valid] 为 HookEntry.applyFreeCopyListener 判定为评论并完成绑定。
     */
    fun endItem(token: Long, enqueuedAtNanos: Long, valid: Boolean) {
        if (token == NO_TOKEN) return
        val end = clock.nanoTime()
        val duration = end - token
        val wait = if (enqueuedAtNanos != 0L) end - enqueuedAtNanos else -1L
        val nodes = itemNodes
        val bindable = itemBindable
        synchronized(lock) {
            val ring = itemRing ?: return
            items++
            if (valid) {
                boundItems++
                curBound++
            } else {
                invalidItems++
                curInvalid++
            }
            totalNodes += nodes
            if (nodes > maxNodes) maxNodes = nodes
            if (duration > maxItemNs) maxItemNs = duration
            curNodes += nodes
            if (duration > curMaxItemNs) curMaxItemNs = duration
            val at = ring.next
            ring.durationNs[at] = duration
            ring.waitNs[at] = wait
            ring.nodes[at] = nodes
            ring.bindable[at] = bindable
            ring.valid[at] = valid
            ring.next = (at + 1) % ITEM_CAPACITY
            if (ring.size < ITEM_CAPACITY) ring.size++
        }
    }

    /** drain 结束。[queueBefore] 是取批前的队列长度，[taken] 是本批出队数，[queueAfter] 是结束时的队列长度。 */
    fun endDrain(token: Long, queueBefore: Int, taken: Int, queueAfter: Int) {
        if (token == NO_TOKEN) return
        val duration = clock.nanoTime() - token
        synchronized(lock) {
            val ring = drainRing ?: return
            drains++
            drainsSinceReport++
            if (duration > maxDrainNs) maxDrainNs = duration
            val at = ring.next
            ring.durationNs[at] = duration
            ring.maxItemNs[at] = curMaxItemNs
            ring.queueBefore[at] = queueBefore
            ring.taken[at] = taken
            ring.bound[at] = curBound
            ring.invalid[at] = curInvalid
            ring.detached[at] = curDetached
            ring.stale[at] = curStale
            ring.nodes[at] = curNodes
            ring.queueAfter[at] = queueAfter
            ring.next = (at + 1) % DRAIN_CAPACITY
            if (ring.size < DRAIN_CAPACITY) ring.size++
        }
    }

    /**
     * 限频的汇总文本：距上次汇总至少 [REPORT_MIN_INTERVAL_NS]，且（累计满 [REPORT_EVERY_DRAINS] 次 drain，
     * 或队列已排空 [queueEmpty]）时返回一份，否则返回 null。首次汇总不受间隔限制。
     * 日志输出由调用方在 drain 计时之外完成，避免日志本身污染被测量的区间。
     */
    fun takeReportIfDue(queueEmpty: Boolean): String? {
        if (!enabled) return null
        val now = clock.nanoTime()
        synchronized(lock) {
            if (drainsSinceReport == 0) return null
            if (!queueEmpty && drainsSinceReport < REPORT_EVERY_DRAINS) return null
            if (lastReportNs != NO_TOKEN && now - lastReportNs < REPORT_MIN_INTERVAL_NS) return null
            lastReportNs = now
            drainsSinceReport = 0
            return formatReport(snapshotLocked())
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    fun report(): String = formatReport(snapshot())

    /** 清空窗口与累计计数；保留 [enabled] 与已分配的缓冲。 */
    fun reset() {
        synchronized(lock) {
            drainRing?.let { it.size = 0; it.next = 0 }
            itemRing?.let { it.size = 0; it.next = 0 }
            drains = 0L; deferredByScroll = 0L; deferredByTouch = 0L; deferredByQuiet = 0L
            items = 0L; boundItems = 0L; invalidItems = 0L; detachedItems = 0L; staleItems = 0L
            enqueued = 0L; replacedDuplicates = 0L; overflowDropped = 0L; maxQueueLength = 0
            totalNodes = 0L; maxNodes = 0; maxDrainNs = 0L; maxItemNs = 0L
            curBound = 0; curInvalid = 0; curDetached = 0; curStale = 0; curNodes = 0; curMaxItemNs = 0L
            drainsSinceReport = 0
            lastReportNs = NO_TOKEN
        }
    }

    private fun snapshotLocked(): Snapshot {
        val drainSamples = ArrayList<DrainSample>()
        val drainValues = LongArray(drainRing?.size ?: 0)
        drainRing?.let { ring ->
            val start = if (ring.size < DRAIN_CAPACITY) 0 else ring.next
            for (i in 0 until ring.size) {
                val at = (start + i) % DRAIN_CAPACITY
                drainValues[i] = ring.durationNs[at]
                drainSamples.add(
                    DrainSample(
                        ring.durationNs[at], ring.maxItemNs[at], ring.queueBefore[at], ring.taken[at],
                        ring.bound[at], ring.invalid[at], ring.detached[at], ring.stale[at],
                        ring.nodes[at], ring.queueAfter[at]
                    )
                )
            }
        }
        val itemSamples = ArrayList<ItemSample>()
        val itemDurations = LongArray(itemRing?.size ?: 0)
        val itemNodeCounts = LongArray(itemRing?.size ?: 0)
        val waits = ArrayList<Long>()
        itemRing?.let { ring ->
            val start = if (ring.size < ITEM_CAPACITY) 0 else ring.next
            for (i in 0 until ring.size) {
                val at = (start + i) % ITEM_CAPACITY
                itemDurations[i] = ring.durationNs[at]
                itemNodeCounts[i] = ring.nodes[at].toLong()
                if (ring.waitNs[at] >= 0L) waits.add(ring.waitNs[at])
                itemSamples.add(
                    ItemSample(ring.durationNs[at], ring.waitNs[at], ring.nodes[at], ring.bindable[at], ring.valid[at])
                )
            }
        }
        return Snapshot(
            enabled = enabled,
            drains = drains,
            deferredByScroll = deferredByScroll,
            deferredByTouch = deferredByTouch,
            deferredByQuiet = deferredByQuiet,
            items = items,
            boundItems = boundItems,
            invalidItems = invalidItems,
            detachedItems = detachedItems,
            staleGenerationItems = staleItems,
            enqueued = enqueued,
            replacedDuplicates = replacedDuplicates,
            overflowDropped = overflowDropped,
            maxQueueLength = maxQueueLength,
            totalNodes = totalNodes,
            maxNodes = maxNodes,
            maxDrainNs = maxDrainNs,
            maxItemNs = maxItemNs,
            drainNs = series(drainValues),
            itemNs = series(itemDurations),
            itemNodes = series(itemNodeCounts),
            waitNs = series(waits.toLongArray()),
            recentDrains = drainSamples,
            recentItems = itemSamples
        )
    }

    companion object {
        const val DEFER_SCROLL = 1
        const val DEFER_TOUCH = 2
        const val DEFER_QUIET = 3

        const val DRAIN_CAPACITY = 64
        const val ITEM_CAPACITY = 256
        const val REPORT_EVERY_DRAINS = 32
        const val REPORT_MIN_INTERVAL_NS = 1_000_000_000L

        /** `Log.isLoggable` 的标签（≤23 字符）：`setprop log.tag.BilCommentBindProbe D` 后重启宿主。 */
        const val LOG_TAG = "BilCommentBindProbe"

        /** 探针关闭或未打点时的令牌。 */
        const val NO_TOKEN = Long.MIN_VALUE

        /** 最近邻（nearest-rank）分位；[values] 会被排序，调用方传入副本。 */
        internal fun series(values: LongArray): Series {
            if (values.isEmpty()) return Series(0, 0L, 0L, 0L, 0L)
            values.sort()
            fun rank(percent: Int): Long {
                val index = (percent * values.size + 99) / 100 - 1
                return values[index.coerceIn(0, values.lastIndex)]
            }
            return Series(values.size, rank(50), rank(95), rank(99), values.last())
        }

        internal fun formatReport(s: Snapshot): String {
            fun ms(ns: Long) = String.format(Locale.ROOT, "%.2f", ns / 1_000_000.0)
            fun msSeries(label: String, x: Series) =
                "$label(n=${x.count}) p50=${ms(x.p50)} p95=${ms(x.p95)} p99=${ms(x.p99)} max=${ms(x.max)}"
            fun countSeries(label: String, x: Series) =
                "$label(n=${x.count}) p50=${x.p50} p95=${x.p95} p99=${x.p99} max=${x.max}"
            return "[BIL] 评论绑定探针 drains=${s.drains} " +
                "defer(scroll/touch/quiet)=${s.deferredByScroll}/${s.deferredByTouch}/${s.deferredByQuiet} " +
                "items=${s.items}(bound=${s.boundItems} invalid=${s.invalidItems}) " +
                "detached=${s.detachedItems} stale=${s.staleGenerationItems} " +
                "enq=${s.enqueued} replaced=${s.replacedDuplicates} overflow=${s.overflowDropped} " +
                "queueMax=${s.maxQueueLength} nodes(total/max)=${s.totalNodes}/${s.maxNodes} | " +
                msSeries("drain_ms", s.drainNs) + " | " + msSeries("item_ms", s.itemNs) + " | " +
                countSeries("item_nodes", s.itemNodes) + " | " + msSeries("wait_ms", s.waitNs) +
                " maxDrain_ms=${ms(s.maxDrainNs)} maxItem_ms=${ms(s.maxItemNs)}"
        }
    }
}
