package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 宿主 UI 面扫描结果的收口器：把 Hook 点每次看到的候选整理成一份完整快照交给宿主桥。
 *
 * 三个面（底栏 / 首页 Tab / 首页组件）的 Hook 点都在**每次绑定或构建**时被调用，
 * 而快照内容通常一整场都不变。**内容去重不在本类**——它在下游
 * `MineComponentSnapshotHostBridge` 的 `LatestValuePublisher` 上按 `ScanSnapshotContent`
 * 的值相等判断：内容没变就不会触发编码、校验和落盘。因此这里只做按 key 去重、排序和截断
 * 这点纯内存工作，既不比较上一次的内容，也不缓存已发布状态。
 *
 * "我的"页有自己的 `SnapshotAccumulator`（带 capabilities 合并语义），不走这里。
 *
 * 累积和提交顺序在同一把锁内串行；编码、校验与落盘都在后台单线程完成。sink 返回 false
 * 只表示"没能进入发布队列"，此时记一次有界错误日志，不提前宣称已发布。
 */
internal class ScanSnapshotPublisher(
    private val environment: HookEnvironment,
    private val surface: String,
    private val capabilities: Set<String>
) {
    private val accumulated =
        java.util.concurrent.ConcurrentHashMap<String, MineComponentScanEntry>()

    /**
     * 逐条累积后发布并集。
     *
     * 有些面（首页子组件）的 Hook 点一次只能看到一个候选，不能像列表型那样整批替换；
     * 累积集按 key 去重，同 key 后来者覆盖（`showing` 可能随配置变化）。
     */
    @Synchronized fun accumulate(entry: MineComponentScanEntry?) {
        if (entry == null) return
        if (accumulated.size >= MineComponentSnapshotCodec.MAX_ENTRY_COUNT &&
            !accumulated.containsKey(entry.key)
        ) return
        accumulated[entry.key] = entry
        publish(accumulated.values.sortedBy(MineComponentScanEntry::key))
    }

    /**
     * 交付一批扫描结果。
     *
     * 调用方只管把"这次看到的全部候选"传进来，按 key 去重和截断在这里做；
     * 与上一次内容是否相同由下游发布器判断，本方法每次都会把整理好的纯数据交给 sink。
     */
    @Synchronized fun publish(entries: List<MineComponentScanEntry>) {
        if (entries.isEmpty()) return
        val normalized = entries
            .distinctBy(MineComponentScanEntry::key)
            .take(MineComponentSnapshotCodec.MAX_ENTRY_COUNT)
        val sink = environment.writeScanSnapshot ?: return
        runCatching {
            check(sink(surface, ScanSnapshotContent(environment.processName, capabilities.toSet(), normalized)))
        }.onFailure { throwable ->
            environment.logError(
                "scan_snapshot_${surface}",
                "[BIL] $surface 扫描快照发布失败: $throwable"
            )
        }
    }
}
