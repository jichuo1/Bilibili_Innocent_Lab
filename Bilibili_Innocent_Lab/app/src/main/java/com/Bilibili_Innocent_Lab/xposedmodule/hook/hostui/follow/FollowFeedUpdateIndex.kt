package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

/** 引用卡片的复合身份：两个 Long 完整保存，不压成一个 Long。 */
internal data class FollowFeedReferenceKey(val owner: Long, val reference: Long)

internal fun FollowFeedRow.referenceKey(): FollowFeedReferenceKey? =
    reference?.let { FollowFeedReferenceKey(identity, it.identity) }

/**
 * 一次 update 内的行快照：每个附着子 View 的行与引用键只计算一次，面板收集、引用收集和装饰决策各遍直接复用。
 *
 * 之前每遍都重新 `rows[child]`（WeakHashMap 查找）并重新分配引用键，清理离屏引用时对每个存量引用再扫描全部
 * 附着子 View，成员关系比较是 R×N 次带查找与分配的比较。现在 rebuild 做 N 次查找和至多 N 个键，
 * [hasReference] 在已建好的键上比较，不再查找、不再分配。结构变化与几何变化分开处理：这里只记结构（行与键），
 * 滚动时仍更新必需的 panel.place。
 *
 * 单线程（主线程）使用；数组按需增长并复用，空列表走短路径。
 */
internal class FollowFeedUpdateIndex {
    private var rows = arrayOfNulls<FollowFeedRow>(INITIAL)
    private var keys = arrayOfNulls<FollowFeedReferenceKey>(INITIAL)

    var size = 0
        private set

    /** 累计 rowAt 调用次数与键分配次数（诊断 / 验收）。 */
    var lookups = 0L
        private set
    var keyAllocations = 0L
        private set

    fun rebuild(count: Int, rowAt: (Int) -> FollowFeedRow?) {
        if (count > rows.size) {
            val capacity = maxOf(count, rows.size * 2)
            rows = rows.copyOf(capacity)
            keys = keys.copyOf(capacity)
        }
        for (index in 0 until count) {
            val row = rowAt(index)
            lookups++
            rows[index] = row
            val key = row?.referenceKey()
            if (key != null) keyAllocations++
            keys[index] = key
        }
        // 上一屏更长时清掉尾部，避免长期持有已离屏的行。
        if (size > count) { rows.fill(null, count, size); keys.fill(null, count, size) }
        size = count
    }

    fun rowAt(index: Int): FollowFeedRow? = rows[index]
    fun keyAt(index: Int): FollowFeedReferenceKey? = keys[index]

    /** 当前附着的任一行是否带有该引用键；在已建好的键上比较，不查找、不分配。 */
    fun hasReference(key: FollowFeedReferenceKey): Boolean {
        for (index in 0 until size) if (keys[index] == key) return true
        return false
    }

    private companion object {
        const val INITIAL = 16
    }
}
