package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 列表 getter 过滤结果的有界记忆。
 *
 * 首页推荐的同一份响应在一次刷新/加载里会被宿主的解析、去空窗与状态归约读十几次 `getItems()`。
 * 每次都整页重新分类不但白做反射；删过卡片时还会每次返回一个**新**列表，宿主写进去的改动随之丢失，
 * 用身份判断“数据变没变”的宿主路径也会被误导。这里按“源列表身份 + 长度 + 动态判据快照身份”
 * 复用上一次的结果，命中时返回同一个列表实例，与宿主原生 getter 的语义一致。
 *
 * - 容量有界、按插入顺序淘汰，不随刷新增长；只引用宿主正在用的列表，不持有 View/Activity。
 * - 动态判据（本场面板选择、AI 声明登记、热更新规则）任一换成新快照即失效；另设 [MAX_AGE_MS]
 *   兜住按时间判定的规则（例如发布时间）。
 * - 结论未定（智能过滤仍在后台判）的结果由调用方决定不记，下一次调用照常补判。
 * - [Hit.settled] 为 false 表示结果还只是过滤结果、排列尚未最终确定（例如还没轮到宿主凑组）；
 *   调用方可以在确定后以同一个源列表重新 [remember]，此后命中都返回确定后的实例。
 */
internal class FilteredListMemo(private val clock: () -> Long = System::currentTimeMillis) {
    class Hit(val result: List<*>, val settled: Boolean)

    private class Entry(
        val source: List<*>,
        val size: Int,
        val rules: Array<out Any?>,
        val hit: Hit,
        val at: Long
    )

    private val entries = ArrayDeque<Entry>(CAPACITY)

    /** 命中返回上一次的记录；源列表长度、判据快照或时效任一不符都视为未命中并丢弃旧记录。 */
    fun reuse(source: List<*>, rules: Array<out Any?>): Hit? {
        val now = clock()
        synchronized(entries) {
            val hit = entries.firstOrNull { it.source === source } ?: return null
            if (hit.size == source.size && now - hit.at in 0 until MAX_AGE_MS && sameRules(hit.rules, rules)) {
                return hit.hit
            }
            entries.remove(hit)
            return null
        }
    }

    fun remember(source: List<*>, rules: Array<out Any?>, result: List<*>, settled: Boolean = true) {
        val now = clock()
        synchronized(entries) {
            entries.removeAll { it.source === source }
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(Entry(source, source.size, rules, Hit(result, settled), now))
        }
    }

    internal val size: Int get() = synchronized(entries) { entries.size }

    private fun sameRules(previous: Array<out Any?>, current: Array<out Any?>): Boolean {
        if (previous.size != current.size) return false
        for (index in previous.indices) if (previous[index] !== current[index]) return false
        return true
    }

    internal companion object {
        /** 一次刷新最多同时存活刷新、加载与少量补位几份响应；再多只是更早淘汰，不影响正确性。 */
        const val CAPACITY = 6
        const val MAX_AGE_MS = 60_000L
    }
}
