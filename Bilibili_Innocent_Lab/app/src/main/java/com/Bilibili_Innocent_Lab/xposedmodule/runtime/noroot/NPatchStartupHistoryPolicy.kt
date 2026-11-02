package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

internal data class NPatchStartupHistoryUpdate(
    val current: List<NPatchProcessSnapshot>,
    val persisted: List<NPatchProcessSnapshot>
)

/** 历史可以保留暂未响应的进程，但它们不能成为本次运行证据。 */
internal object NPatchStartupHistoryPolicy {
    fun merge(old: List<NPatchProcessSnapshot>, fresh: List<NPatchProcessSnapshot>): NPatchStartupHistoryUpdate {
        require(fresh.isNotEmpty() && fresh.size <= NPatchProcessCodec.MAX_PROCESSES)
        require(fresh.map { it.processName }.distinct().size == fresh.size)
        val identity = fresh.first()
        fun sameInstallation(row: NPatchProcessSnapshot) =
            row.targetUpdateTime == identity.targetUpdateTime &&
                row.moduleVersionCode == identity.moduleVersionCode && row.mode == identity.mode
        require(fresh.all(::sameInstallation))
        val reusable = old.filter(::sameInstallation).associateBy { it.processName }
        val current = fresh.map { row ->
            row.copy(startups = NPatchStartupCodec.merge(reusable[row.processName]?.startups.orEmpty(), row.startups))
        }
        val observedNames = fresh.mapTo(hashSetOf()) { it.processName }
        val absent = reusable.values.filter { it.processName !in observedNames }
            .sortedWith(compareByDescending<NPatchProcessSnapshot> {
                it.startups.maxOfOrNull { sample -> sample.startedAt } ?: 0L
            }.thenBy { it.processName })
        // 本次真实响应优先；剩余名额保留最近观测的历史，文件总量仍遵守原协议上限。
        return NPatchStartupHistoryUpdate(current,
            (current + absent).take(NPatchProcessCodec.MAX_PROCESSES))
    }
}
