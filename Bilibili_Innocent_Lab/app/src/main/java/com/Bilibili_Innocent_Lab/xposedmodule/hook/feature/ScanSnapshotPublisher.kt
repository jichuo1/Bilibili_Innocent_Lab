package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.util.concurrent.atomic.AtomicReference

/**
 * 宿主 UI 面扫描结果的去重发布器。
 *
 * 三个面（底栏 / 首页 Tab / 首页组件）的 Hook 点都在**每次绑定或构建**时被调用，
 * 而快照内容通常一整场都不变。这里按"内容变了才发"过滤，避免每帧往跨进程桥灌同样的数据。
 *
 * "我的"页有自己的 `SnapshotAccumulator`（带 capabilities 合并语义），不走这里。
 *
 * 线程安全：Hook 回调可能来自不同线程，用 [AtomicReference] 做无锁比较替换。
 */
internal class ScanSnapshotPublisher(
    private val environment: HookEnvironment,
    private val surface: String,
    private val capabilities: Set<String>
) {
    private val published = AtomicReference<List<MineComponentScanEntry>>(emptyList())
    private val accumulated =
        java.util.concurrent.ConcurrentHashMap<String, MineComponentScanEntry>()

    /**
     * 逐条累积后发布并集。
     *
     * 有些面（首页子组件）的 Hook 点一次只能看到一个候选，不能像列表型那样整批替换；
     * 累积集按 key 去重，同 key 后来者覆盖（`showing` 可能随配置变化）。
     */
    fun accumulate(entry: MineComponentScanEntry?) {
        if (entry == null) return
        if (accumulated.size >= MineComponentSnapshotCodec.MAX_ENTRY_COUNT &&
            !accumulated.containsKey(entry.key)
        ) return
        if (accumulated.put(entry.key, entry) == entry) return
        publish(accumulated.values.sortedBy(MineComponentScanEntry::key))
    }

    /**
     * 发布一批扫描结果；内容与上次相同则不做任何事。
     *
     * 调用方只管把"这次看到的全部候选"传进来，去重、截断、编码都在这里。
     */
    fun publish(entries: List<MineComponentScanEntry>) {
        if (entries.isEmpty()) return
        val normalized = entries
            .distinctBy(MineComponentScanEntry::key)
            .take(MineComponentSnapshotCodec.MAX_ENTRY_COUNT)
        val previous = published.get()
        if (previous == normalized) return
        if (!published.compareAndSet(previous, normalized)) return
        val sink = environment.writeScanSnapshot ?: return
        runCatching {
            sink(
                surface,
                MineComponentSnapshotCodec.encode(
                    processName = environment.processName,
                    capabilities = capabilities,
                    entries = normalized,
                    surface = surface
                )
            )
        }.onFailure { throwable ->
            environment.logError(
                "scan_snapshot_${surface}",
                "[BIL] $surface 扫描快照发布失败: $throwable"
            )
        }
    }
}
