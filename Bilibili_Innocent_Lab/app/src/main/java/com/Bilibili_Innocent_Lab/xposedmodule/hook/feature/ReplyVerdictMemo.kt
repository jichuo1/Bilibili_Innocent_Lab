package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.util.IdentityHashMap

/** 不可变的发布快照。缓存命中只比较引用，避免滚动时遍历三张表。 */
internal class FishSnapshots(
    val videos: Map<Long, FishRootEcho.Video>,
    val peers: Map<Long, Map<Long, Int>>,
    val accounts: Map<Long, Int>
) {
    fun sameAs(other: FishSnapshots): Boolean =
        videos === other.videos && peers === other.peers && accounts === other.accounts
}

/**
 * 评论列表 getter 在滚动、绑定时会被宿主反复调用：同一列表和同一视频资料快照的规则结论可以复用。
 *
 * 视频详情可能比评论晚到，快照替换后按新资料重算。容量很小，只持有最近几个列表的强引用。
 */
internal class ReplyVerdictMemo {
    private class Entry(
        val source: List<*>,
        val size: Int,
        val snapshots: FishSnapshots?,
        val verdicts: IdentityHashMap<Any, Boolean>
    )

    private val entries = ArrayDeque<Entry>(CAPACITY)

    /** [compute] 在锁外执行：并发的两次首算最多重复一次，不会把宿主线程挡在锁后。 */
    fun getOrCompute(
        source: List<*>,
        snapshots: FishSnapshots? = null,
        compute: () -> IdentityHashMap<Any, Boolean>
    ): IdentityHashMap<Any, Boolean> {
        synchronized(entries) {
            // 同一引用且长度未变才复用：防宿主复用同一个可变列表换内容。
            entries.firstOrNull {
                it.source === source && it.size == source.size &&
                    (it.snapshots === snapshots || (snapshots != null && it.snapshots?.sameAs(snapshots) == true))
            }?.let { return it.verdicts }
        }
        val verdicts = compute()
        synchronized(entries) {
            entries.removeAll { it.source === source }
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(Entry(source, source.size, snapshots, verdicts))
        }
        return verdicts
    }

    private companion object {
        const val CAPACITY = 6
    }
}
