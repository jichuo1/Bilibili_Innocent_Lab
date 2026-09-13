package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

/** 按通道保留最新快照；只有对应版本的应用 ACK 才能删掉，失败/冷却不清空。由调用方串行使用。 */
internal class LatestReceiptOutbox<T>(private val maxCount: Int = 8, private val maxBytes: Int = 3 * 1024 * 1024) {
    data class Entry<T>(val revision: Long, val bytes: Int, val value: T)
    private val values = linkedMapOf<String, Entry<T>>()
    fun put(key: String, revision: Long, bytes: Int, value: T): Boolean {
        if (bytes < 0 || revision <= 0 || key !in values && values.size >= maxCount) return false
        if (revision <= (values[key]?.revision ?: 0)) return false
        val total = values.values.sumOf { it.bytes.toLong() } - (values[key]?.bytes ?: 0) + bytes
        if (total > maxBytes) return false
        values[key] = Entry(revision, bytes, value)
        return true
    }
    fun ack(key: String, revision: Long) { if ((values[key]?.revision ?: Long.MAX_VALUE) <= revision) values.remove(key) }
    fun snapshot(): Map<String, Entry<T>> = values.toMap()
}
