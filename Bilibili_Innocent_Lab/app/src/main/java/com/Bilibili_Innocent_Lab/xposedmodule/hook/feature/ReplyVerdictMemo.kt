package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.util.IdentityHashMap

/**
 * 评论列表 getter 在滚动、绑定时会被宿主反复调用：同一个列表实例（按引用、长度未变）的规则判定只算一次。
 *
 * 判定只取决于每条评论自身的字段（protobuf 消息不可变），所以复用是安全的；和 [SemanticListMemo] 不同，
 * 这里没有"未决"状态，不需要过期。容量很小，只持有最近几个列表的强引用——它们本就被宿主持有、正在显示。
 */
internal class ReplyVerdictMemo {
    private class Entry(val source: List<*>, val size: Int, val verdicts: IdentityHashMap<Any, Boolean>)

    private val entries = ArrayDeque<Entry>(CAPACITY)

    /** [compute] 在锁外执行：并发的两次首算最多重复一次，不会把宿主线程挡在锁后。 */
    fun getOrCompute(source: List<*>, compute: () -> IdentityHashMap<Any, Boolean>): IdentityHashMap<Any, Boolean> {
        synchronized(entries) {
            // 同一引用且长度未变才复用：防宿主复用同一个可变列表换内容。
            entries.firstOrNull { it.source === source && it.size == source.size }?.let { return it.verdicts }
        }
        val verdicts = compute()
        synchronized(entries) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(Entry(source, source.size, verdicts))
        }
        return verdicts
    }

    private companion object {
        const val CAPACITY = 6
    }
}
