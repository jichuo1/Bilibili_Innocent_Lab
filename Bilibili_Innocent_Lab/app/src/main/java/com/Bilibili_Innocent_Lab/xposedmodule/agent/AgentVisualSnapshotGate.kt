package com.Bilibili_Innocent_Lab.xposedmodule.agent

/**
 * inspect_screen 的“视觉快照有效”记录，只在主线程读写。
 *
 * 像素编码挪到 worker 之后，TYPE_WINDOW_STATE_CHANGED 可能在编码期间到达并先清空记录，随后的交付又把它写回，
 * 窗口已经换了、记录却仍然有效。旧的同步实现里事件只能排在整段回调之后，这个失效保护是隐含成立的；
 * 这里用窗口代次把它显式保留下来：提交时记下 [token]，交付时代次变了就拒绝写回。
 *
 * 选代次而不是交付前重新 scan()：代次是 O(1)，不会把已经移出主线程的无障碍树遍历再放回主线程；
 * 且窗口 A→B→A 之类“边界又回到原样”的变化也会被事件捕获，而按 window/bounds 比较会漏掉。
 */
internal class AgentVisualSnapshotGate(private val maxAgeMs: Long = MAX_AGE_MS) {
    private var generation = 0L
    private var snapshotId: String? = null
    private var capturedAt = 0L

    /**
     * 窗口状态变化、新的无障碍快照、任务结束或服务断开：记录立即失效，并让所有已记下 token 的在途交付作废。
     */
    fun invalidate() {
        generation++
        snapshotId = null
    }

    /** 在把像素任务交给 worker 之前记下；交付时用 [isCurrent] / [commit] 核对。 */
    fun token(): Long = generation

    fun isCurrent(token: Long): Boolean = token == generation

    /** 窗口代次未变才写入并返回 true；否则不写入、返回 false。 */
    fun commit(token: Long, id: String, now: Long): Boolean {
        if (token != generation) return false
        snapshotId = id
        capturedAt = now
        return true
    }

    /** 当前记录是否对 [id] 有效：同一快照且不超过 [maxAgeMs]（时间倒退按无效处理）。 */
    fun allows(id: String, now: Long): Boolean = snapshotId == id && now - capturedAt in 0..maxAgeMs

    companion object {
        const val MAX_AGE_MS = 60_000L
    }
}
