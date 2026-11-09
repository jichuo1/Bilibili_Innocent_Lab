package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

/**
 * 同键在途生成任务（Flight）表：多个订阅者对同一个 tile key 冷 miss 时只生成一次。
 *
 * - [request] 在一把锁内三选一：加入已有任务 / 注册唯一生产者 / 因容量被拒；调用方应在同一把外层锁内先查缓存，
 *   再调用它，这样「缓存命中 / 加入 / 注册」对并发完成是原子的。
 * - [retain] 让订阅者只保留仍需要的键（始终追随最新视口）：被放弃且无人订阅、尚未开始的任务直接移出待办；
 *   已经开始的任务让它跑完（结果会进共享缓存），但不再发布给放弃它的订阅者。
 * - [take] 取走最旧的未开始任务交给工作线程；[finish] 在锁内移除任务并返回完成时仍订阅它的订阅者。
 * - 任务数有硬上限 [capacity]；表自身不执行任何生成、不持有位图。
 *
 * 订阅者类型 [S] 应是轻量句柄（例如只含一个弱引用），表不会因它而延长 Scene 或位图的寿命。
 */
internal class TileFlightTable<K : Any, S : Any>(private val capacity: Int = DEFAULT_CAPACITY) {
    enum class Outcome { CREATED, JOINED, REJECTED }

    private class Flight<K : Any, S : Any>(val key: K) {
        val subscribers = LinkedHashSet<S>()
        var started = false
    }

    private val flights = LinkedHashMap<K, Flight<K, S>>()

    var created = 0L
        private set
    var joined = 0L
        private set
    var cancelled = 0L
        private set
    var completed = 0L
        private set
    var rejected = 0L
        private set

    init { require(capacity > 0) }

    @Synchronized fun request(key: K, subscriber: S): Outcome {
        flights[key]?.let { flight ->
            flight.subscribers += subscriber
            joined++
            return Outcome.JOINED
        }
        if (flights.size >= capacity) {
            rejected++
            return Outcome.REJECTED
        }
        flights[key] = Flight<K, S>(key).also { it.subscribers += subscriber }
        created++
        return Outcome.CREATED
    }

    /** [subscriber] 只保留 [keep] 中的键；被放弃后无订阅者且未开始的任务被移出待办。 */
    @Synchronized fun retain(subscriber: S, keep: Collection<K>) {
        val iterator = flights.values.iterator()
        while (iterator.hasNext()) {
            val flight = iterator.next()
            if (flight.key in keep) continue
            if (!flight.subscribers.remove(subscriber)) continue
            if (flight.subscribers.isEmpty() && !flight.started) {
                iterator.remove()
                cancelled++
            }
        }
    }

    /** 订阅者整体离开（detach / close）。 */
    @Synchronized fun release(subscriber: S) = retain(subscriber, emptyList())

    /**
     * 取走最旧的未开始任务并标记为已开始；没有则返回 null。
     * 订阅者已不存活（例如 Scene 已被回收）且尚未开始的任务不再生成，直接移出待办。
     */
    @Synchronized fun take(alive: (S) -> Boolean = { true }): K? {
        val iterator = flights.values.iterator()
        while (iterator.hasNext()) {
            val flight = iterator.next()
            if (flight.started) continue
            if (flight.subscribers.none(alive)) {
                iterator.remove()
                cancelled++
                continue
            }
            flight.started = true
            return flight.key
        }
        return null
    }

    /** 任务结束（成功或失败）：移除任务，返回此刻仍订阅它的订阅者快照。 */
    @Synchronized fun finish(key: K): List<S> {
        val flight = flights.remove(key) ?: return emptyList()
        completed++
        return flight.subscribers.toList()
    }

    @Synchronized fun isPending(key: K): Boolean = flights.containsKey(key)
    @Synchronized fun pendingCount(): Int = flights.size
    @Synchronized fun unstartedCount(): Int = flights.values.count { !it.started }
    @Synchronized fun subscriberCount(key: K): Int = flights[key]?.subscribers?.size ?: 0

    companion object {
        /** 远大于任何视口所需：每个 Scene 至多「可见带 + 前后各一带」。 */
        const val DEFAULT_CAPACITY = 32
    }
}
