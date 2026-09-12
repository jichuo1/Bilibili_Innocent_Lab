package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 本次宿主进程内、用户从三点面板点过的 UP 主。
 *
 * 与 [SectionPickSession] 同一套纪律，只是承载的是字符串而不是数字：
 *
 * - 只增不减，且**不落盘**——进程重启即清空，持久化一律走模块 App 的确认路径
 *   （见 [MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS]），绝不变成第二个配置源；
 * - 有界（[MAX_PICKS]），防止异常路径把它撑成内存泄漏；
 * - 读路径是热路径（每张卡一次），所以用不可变快照 + `@Volatile`，读侧零加锁。
 *
 * 归一方式与 [ExactRuleSetCodec] 一致（整串小写），这样"点面板记下的"和
 * "用户手填的"在同一个集合语义下比较，不会出现大小写导致的漏判。
 */
internal object AuthorPickSession {
    /** 一场会话里点几十个 UP 已经远超正常使用；超过就不再增长。 */
    private const val MAX_PICKS = 64

    @Volatile
    private var picks: Set<String> = emptySet()

    val current: Set<String> get() = picks

    /** 返回 true 表示这次确实新增了（调用方据此决定要不要上报与记日志）。 */
    @Synchronized
    fun add(author: String?): Boolean {
        val value = author?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return false
        val snapshot = picks
        if (value in snapshot || snapshot.size >= MAX_PICKS) return false
        // 复制而不是原地改：读侧拿到的永远是一个不会再变的集合。
        picks = snapshot + value
        return true
    }

    /**
     * 任一取值命中即算命中。
     *
     * UP 一档同时承认名字与 mid（与详情页那档一致），所以调用方会把
     * `upName` 与 `upId` 的十进制串一起传进来。
     */
    fun matches(vararg values: String?): Boolean {
        val snapshot = picks
        if (snapshot.isEmpty()) return false
        for (raw in values) {
            if (raw.isNullOrBlank()) continue
            if (raw.trim().lowercase() in snapshot) return true
        }
        return false
    }

    /** 仅供测试重置；生产路径没有"取消选择"，那属于模块 App 的编辑界面。 */
    @Synchronized
    fun resetForTest() {
        picks = emptySet()
    }
}
