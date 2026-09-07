package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** 每个键只保留最新值；单消费者发布，成功后确认，失败最多立即重试一次。 */
internal class LatestValuePublisher<K, V>(
    private val schedule: (() -> Unit) -> Unit,
    private val publish: (K, V) -> Boolean
) {
    private data class Pending<V>(val value: V, var attempts: Int = 0)
    private val lock = Any()
    private val latest = linkedMapOf<K, Pending<V>>()
    private val acknowledged = hashMapOf<K, V>()
    private var scheduled = false

    fun submit(key: K, value: V): Boolean = synchronized(lock) {
        val previous = latest[key]
        if (previous?.value != value) latest[key] = Pending(value)
        else if (!scheduled) previous.attempts = 0
        if (scheduled || acknowledged[key] == value) return@synchronized true
        scheduled = true
        runCatching { schedule(::drain) }.fold(
            onSuccess = { true },
            onFailure = { scheduled = false; false }
        )
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                latest.entries.firstOrNull { (key, item) ->
                    acknowledged[key] != item.value && item.attempts < 2
                }?.let { it.key to it.value } ?: run { scheduled = false; return }
            }
            val success = runCatching { publish(next.first, next.second.value) }.getOrDefault(false)
            synchronized(lock) {
                if (success) acknowledged[next.first] = next.second.value
                next.second.attempts++
            }
        }
    }
}
