package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 一次引擎准备会话（warm-up、能力测量）内复用的独立 direct buffer。
 *
 * - 槽位彼此独立：不同槽位永远是不同的 ByteBuffer，调用方用固定槽位表示角色（四份输入 + 一份输出）。
 * - 容量只增不减，最多 [MAX_SLOTS] 个槽、单个不超过 [MAX_BYTES]；会话结束 [close] 释放引用，
 *   不把最大尺寸缓冲绑到应用生命周期。direct 内存随 GC 归还，不保证引用释放后立即回到系统。
 * - 每次取用都显式设置 position = 0、limit = 请求字节数、字节序，因为 [ByteBuffer.clear] 既不清零也不收缩 limit。
 * - 与 [FrameBufferLedger] 无关：这里的缓冲从不进入帧账本，也不会借用账本里在途的帧。
 * - 只在推理 worker 上单线程使用，不做同步。
 *
 * 过渡实现：视频管线迁移后由 MeguvizMedia（准备资源 owner）/ MeguvizCompute（物理资源预算）接替，
 * 迁移完成后删除本类。
 */
internal class FrameScratchArena(
    private val allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect
) : AutoCloseable {
    private val slots = arrayOfNulls<ByteBuffer>(MAX_SLOTS)
    private var closed = false

    /** 本会话内 allocateDirect 的次数与累计字节（诊断 / 验收用）。 */
    var allocations = 0
        private set
    var allocatedBytes = 0L
        private set
    /** 为复用输入而显式清零的字节数；计入准备总时长，不在单次推理计时里。 */
    var zeroedBytes = 0L
        private set

    /** 当前仍被本 arena 引用的容量（字节）。 */
    val heldBytes: Long get() = slots.sumOf { (it?.capacity() ?: 0).toLong() }

    /**
     * 取 [slot] 号缓冲，保证容量不小于 [bytes]；内容不保证。
     * [capacityHint] 大于 [bytes] 时，首次/扩容直接按提示分配，避免在升序尺寸中反复重分配。
     */
    fun scratch(slot: Int, bytes: Int, capacityHint: Int = bytes): ByteBuffer {
        check(!closed) { "arena closed" }
        require(slot in slots.indices) { "slot $slot" }
        require(bytes in 1..MAX_BYTES && capacityHint <= MAX_BYTES) { "bytes $bytes hint $capacityHint" }
        var buffer = slots[slot]
        if (buffer == null || buffer.capacity() < bytes) {
            slots[slot] = null // 先放掉旧引用，旧缓冲不与新缓冲同时被持有
            val capacity = maxOf(bytes, capacityHint)
            buffer = allocate(capacity)
            check(buffer.isDirect && buffer.capacity() >= bytes) { "allocator must return a direct buffer of at least $bytes bytes" }
            slots[slot] = buffer
            allocations++
            allocatedBytes += buffer.capacity().toLong()
        }
        buffer.clear()
        buffer.order(ByteOrder.BIG_ENDIAN) // 与新分配的 direct buffer 默认字节序一致
        buffer.limit(bytes)
        return buffer
    }

    /** 同 [scratch]，并把 [0, bytes) 显式清零——新分配的 direct buffer 是零，复用的不是。 */
    fun zeroed(slot: Int, bytes: Int, capacityHint: Int = bytes): ByteBuffer {
        val buffer = scratch(slot, bytes, capacityHint)
        var offset = 0
        while (offset < bytes) {
            val count = minOf(ZERO.size, bytes - offset)
            buffer.position(offset)
            buffer.put(ZERO, 0, count)
            offset += count
        }
        buffer.position(0)
        zeroedBytes += bytes.toLong()
        return buffer
    }

    override fun close() {
        closed = true
        slots.fill(null)
    }

    companion object {
        const val MAX_SLOTS = 5
        /** 与 native MaxPixels（4096 x 2160）× 3 字节一致。 */
        const val MAX_BYTES = 4096 * 2160 * 3
        private val ZERO = ByteArray(64 * 1024)
    }
}
