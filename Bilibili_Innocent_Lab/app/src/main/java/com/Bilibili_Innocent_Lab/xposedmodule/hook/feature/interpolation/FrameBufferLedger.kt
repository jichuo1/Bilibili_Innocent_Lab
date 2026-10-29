package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * 正式插帧路径的帧缓冲所有权账本：唯一负责分配、记账、回收与释放 RGB8 直接缓冲。
 *
 * - 分配：只有 [acquire] 分配，且总数不超过 maxBuffers；耗尽时返回 null（背压），绝不越界分配。
 * - 持有：每张帧由若干 [Holder] 共同持有；CAPTURE/TIMELINE/WORKER/COMPARISON 对同一帧至多一份，重复 retain 被拒绝；
 *   PRESENTER 可叠加（同一原帧连续占用多个高帧率槽位），每份各自 release 一次，多余的 release 被拒绝。
 * - 访问：[buffer] 只对当前持有者返回视图，释放后的 ID 永远拿不到缓冲，杜绝 use-after-release。
 * - 回收：最后一个持有者释放时，若格式代次未变且账本未关闭则回到空闲表并换发新帧 ID；
 *   否则直接丢弃引用（直接内存随 GC 归还）。帧 ID 单调递增、永不复用，满足 [FrameTimeline.Frame] 的约束。
 * - 代次：[configure] 改尺寸/步长时空闲缓冲立即丢弃，仍被持有的旧缓冲延后到最后一次释放时丢弃。
 * - 关闭：[close] 后不再分配；仍在 worker/presenter 手里的缓冲等它们归还后才丢弃，[drained] 报告是否已清空。
 */
internal class FrameBufferLedger(
    private val maxBuffers: Int,
    private val allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect
) {
    enum class Holder { CAPTURE, TIMELINE, WORKER, PRESENTER, COMPARISON }
    data class Format(val width: Int, val height: Int, val stride: Int) {
        val bytes: Int get() = ((height - 1L) * stride + width * 3L).toInt()
    }
    data class Slot(val frameId: Long, val format: Format)
    data class Stats(val allocated: Int, val live: Int, val idle: Int, val recycled: Long, val discarded: Long,
                     val peakLive: Int, val exhausted: Long)

    /**
     * 每帧的持有者计数：五类持有者各占 4 位（PRESENTER 至多 [MAX_PRESENTER_HOLDS] = 8，其余至多 1），
     * 打包进一个 Int，不再为每次 acquire 分配 HashMap 与装箱节点。
     * 过渡补丁：帧账本迁入 MeguvizMedia（M1.1 的 C++17 core，固定计数 / 位集合）后，由它的核心账本接替，
     * 迁移完成后删除本类的打包计数；薄 Kotlin/JNI binding 不再维护第二份核心账本。
     */
    private class Entry(val buffer: ByteBuffer, val generation: Long, val format: Format) {
        private var held = 0
        val isEmpty: Boolean get() = held == 0
        fun count(holder: Holder): Int = (held ushr (holder.ordinal * HOLDER_BITS)) and HOLDER_MASK
        fun set(holder: Holder, count: Int) {
            require(count in 0..HOLDER_MASK)
            val shift = holder.ordinal * HOLDER_BITS
            held = (held and (HOLDER_MASK shl shift).inv()) or (count shl shift)
        }
        fun holders(): Set<Holder> = Holder.values().filterTo(LinkedHashSet()) { count(it) > 0 }
    }

    private val live = HashMap<Long, Entry>()
    private val idle = ArrayDeque<ByteBuffer>()
    private var format: Format? = null
    private var generation = 0L
    private var allocated = 0
    private var recycled = 0L
    private var discarded = 0L
    private var peakLive = 0
    private var exhausted = 0L
    private var closed = false

    init { require(maxBuffers in 1..MAX_BUFFERS) }

    /** 新格式开新代次；相同格式重进不换代，避免 seek 时无谓重分配。 */
    @Synchronized fun configure(width: Int, height: Int, stride: Int = width * 3): Boolean {
        if (closed || width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS ||
            stride.toLong() < width * 3L || (height - 1L) * stride + width * 3L > Int.MAX_VALUE) return false
        val next = Format(width, height, stride)
        if (next == format) return true
        format = next
        generation++
        dropIdle()
        return true
    }

    /** 新帧的唯一入口；返回的帧只带 CAPTURE 持有，调用方必须 retain 下一持有者后再 release CAPTURE。 */
    @Synchronized fun acquire(): Slot? {
        val current = format ?: return null
        if (closed) return null
        val buffer = idle.removeFirstOrNull() ?: if (allocated < maxBuffers) {
            runCatching { allocate(current.bytes) }.getOrNull()?.also { allocated++ } ?: return null
        } else { exhausted++; return null }
        buffer.clear()
        // 多个播放器会话共享原生引擎；ID 必须跨账本唯一，否则显存缓存会命中上一段视频。
        val id = nextFrameId.getAndIncrement()
        live[id] = Entry(buffer, generation, current).also { it.set(Holder.CAPTURE, 1) }
        peakLive = maxOf(peakLive, live.size)
        return Slot(id, current)
    }

    /** 只能在已有其他持有者时追加；帧已被完全释放则失败，不会让旧 ID 复活。 */
    @Synchronized fun retain(frameId: Long, holder: Holder): Boolean {
        val entry = live[frameId] ?: return false
        if (entry.isEmpty) return false
        val count = entry.count(holder)
        if (count > 0 && holder != Holder.PRESENTER || count >= MAX_PRESENTER_HOLDS) return false
        entry.set(holder, count + 1)
        return true
    }

    /** 重复释放返回 false 且无副作用；Presenter 回调被重放也不会二次回收。 */
    @Synchronized fun release(frameId: Long, holder: Holder): Boolean {
        val entry = live[frameId] ?: return false
        val count = entry.count(holder)
        if (count == 0) return false
        entry.set(holder, count - 1)
        if (entry.isEmpty) {
            live.remove(frameId)
            if (!closed && entry.generation == generation) { idle.addLast(entry.buffer); recycled++ }
            else { allocated--; discarded++ }
        }
        return true
    }

    @Synchronized fun releaseAll(frameIds: Collection<Long>, holder: Holder) { frameIds.forEach { release(it, holder) } }

    /** 仅对当前持有者返回独立位置的视图；视图本身不构成持有，调用方不得在释放后继续使用。 */
    @Synchronized fun buffer(frameId: Long, holder: Holder): ByteBuffer? {
        val entry = live[frameId] ?: return null
        if (entry.count(holder) == 0) return null
        return entry.buffer.duplicate().also { it.clear() }
    }

    @Synchronized fun format(frameId: Long): Format? = live[frameId]?.format
    @Synchronized fun holders(frameId: Long): Set<Holder> = live[frameId]?.holders().orEmpty()
    @Synchronized fun outstanding(holder: Holder): List<Long> =
        live.filterValues { it.count(holder) > 0 }.keys.sorted()

    /** 关闭只撤销空闲缓冲；在持帧必须由各持有者按约释放，见 [drained]。 */
    @Synchronized fun close() {
        if (closed) return
        closed = true
        dropIdle()
    }

    @Synchronized fun drained(): Boolean = closed && live.isEmpty() && allocated == 0
    @Synchronized fun stats() = Stats(allocated, live.size, idle.size, recycled, discarded, peakLive, exhausted)

    private fun dropIdle() {
        discarded += idle.size
        allocated -= idle.size
        idle.clear()
    }

    companion object {
        private val nextFrameId = AtomicLong(1L)
        private const val MAX_BUFFERS = 16
        private const val MAX_PRESENTER_HOLDS = 8
        private const val HOLDER_BITS = 4
        private const val HOLDER_MASK = 0xF
        private const val MAX_PIXELS = 4096L * 2160L
    }
}
