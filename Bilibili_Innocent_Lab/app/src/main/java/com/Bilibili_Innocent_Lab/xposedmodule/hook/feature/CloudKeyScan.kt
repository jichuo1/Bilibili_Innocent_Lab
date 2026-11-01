package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 扫描快照里 `cloud_key` 条目的取值标记；只有这三种，字符串云控值永远不出现在这里。 */
internal object CloudKeyScan {
    const val KIND = "cloud_key"
    const val VALUE_TRUE = "true"
    const val VALUE_FALSE = "false"

    /** 本进程里同一个键读到过 true 也读到过 false：调用处的默认值不一致，或云控值在会话中变过。 */
    const val VALUE_MIXED = "mixed"
    const val CAPABILITY = "cloud_key_scan"

    fun entryOf(key: String, state: String): MineComponentScanEntry? =
        MineComponentScanEntry.create(
            kind = KIND, title = null, id = key, uri = null, showing = true,
            selectable = state != VALUE_MIXED, value = state
        )
}

/**
 * 把宿主读到的布尔云控键收集成快照条目。
 *
 * 云控读取是宿主最热的路径之一（启动期几千次，一次读取可能连过几个钩子），所以 [record]
 * 的常见情形只做一次 [ConcurrentHashMap] 查表，不分配、不加锁、不碰日志。
 * 新键和状态变化才会登记，并最多每 [flushDelayMs] 毫秒发布一次：状态只会
 * `未见 → true/false → mixed` 单向推进，所以一个键最多触发两次发布，不会因为读到不稳定的值而反复落盘。
 *
 * 只收录能写成冻结规则的键（字符集、长度合法，不属于受保护前缀）：网络、登录、风控与初始化类键
 * 即使被读到也不会出现在面板里。已被拒绝的键进入有界的负缓存，避免热路径上反复校验。
 */
internal class CloudKeyScanCollector(
    private val capacity: Int,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val publish: (List<MineComponentScanEntry>) -> Unit,
    private val flushDelayMs: Long = DEFAULT_FLUSH_DELAY_MS
) {
    private val states = ConcurrentHashMap<String, Int>()
    private val rejected = ConcurrentHashMap<String, Boolean>()
    private val dirty = AtomicBoolean(false)

    val size: Int get() = states.size

    fun record(key: String, value: Boolean) {
        val state = if (value) TRUE else FALSE
        val previous = states[key]
        when {
            previous == null -> add(key, state)
            previous == state || previous == MIXED -> Unit
            else -> {
                states[key] = MIXED
                markDirty()
            }
        }
    }

    private fun add(key: String, state: Int) {
        // 容量满了或已知不可冻结的键，在做任何校验之前就返回。
        if (states.size >= capacity || rejected.containsKey(key)) return
        if (!CloudKeyFreezeRules.isFreezable(key)) {
            if (rejected.size < REJECTED_CAPACITY) rejected[key] = true
            return
        }
        if (states.putIfAbsent(key, state) == null) markDirty()
    }

    private fun markDirty() {
        if (dirty.compareAndSet(false, true)) schedule(flushDelayMs, ::flush)
    }

    /** 由后台调度器调用；也可在测试里直接触发。 */
    fun flush() {
        dirty.set(false)
        val entries = states.entries.sortedBy { it.key }.mapNotNull { (key, state) ->
            CloudKeyScan.entryOf(
                key, when (state) {
                    TRUE -> CloudKeyScan.VALUE_TRUE
                    FALSE -> CloudKeyScan.VALUE_FALSE
                    else -> CloudKeyScan.VALUE_MIXED
                }
            )
        }
        if (entries.isNotEmpty()) publish(entries)
    }

    companion object {
        const val DEFAULT_FLUSH_DELAY_MS = 3_000L
        private const val FALSE = 0
        private const val TRUE = 1
        private const val MIXED = 2
        private const val REJECTED_CAPACITY = 2_048
    }
}
