package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 本次宿主进程内、用户从三点面板点过的分区。
 *
 * **为什么需要它**：面板劫持只能"观测 + 上报"，落库要等模块 App 里用户确认
 * （见 [MineComponentSnapshotCodec.SURFACE_SECTION_PICKS] 的说明）。但用户点完之后
 * 期望的是**立刻**不再刷到同分区，而不是"下次打开模块设置确认后才生效"。
 * 所以这里在宿主进程里留一份**内存**副本，让推荐过滤链当场就能用上。
 *
 * 纪律：
 * - 只增不减，且**不落盘**——进程重启即清空，持久化一律走模块 App 的确认路径，
 *   绝不变成第二个配置源；
 * - 有界（[MAX_PICKS]），防止异常路径把它撑成内存泄漏；
 * - 读路径是热路径（每张卡一次），所以用不可变快照 + `@Volatile`，读侧零加锁。
 */
internal object SectionPickSession {
    /** 一场会话里点几十个分区已经远超正常使用；超过就不再增长。 */
    private const val MAX_PICKS = 64

    @Volatile
    private var picks: Set<Long> = emptySet()

    val current: Set<Long> get() = picks

    /** 返回 true 表示这次确实新增了（调用方据此决定要不要上报与记日志）。 */
    @Synchronized
    fun add(tid: Long): Boolean {
        if (tid <= 0L) return false
        val snapshot = picks
        if (tid in snapshot || snapshot.size >= MAX_PICKS) return false
        // 复制而不是原地改：读侧拿到的永远是一个不会再变的集合。
        picks = snapshot + tid
        return true
    }

    fun contains(tid: Long?): Boolean {
        if (tid == null || tid <= 0L) return false
        return tid in picks
    }

    /** 仅供测试重置；生产路径没有"取消选择"，那属于模块 App 的编辑界面。 */
    @Synchronized
    fun resetForTest() {
        picks = emptySet()
    }
}
