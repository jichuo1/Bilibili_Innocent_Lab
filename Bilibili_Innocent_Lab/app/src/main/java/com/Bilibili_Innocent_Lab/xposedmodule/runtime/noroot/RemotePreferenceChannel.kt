package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/**
 * 一次 Remote Preferences 写入的纯数据形式。
 *
 * Bundle 转换留在 Binder 适配层，因此 JVM 单测可以直接断言差异本身。
 * NPatch 的 `NPatchRemoteStore.updatePreferences` 按 `clear` → `delete` → `put` 解释它，
 * 其中只有 `delete` + `put` 在同一个 SQLite 事务里；`clear` 是事务之前单独执行的一条
 * DELETE，读者可能在两步之间读到空文档。因此发布路径不得依赖 `clear`。
 */
internal data class RemotePreferenceDiff(
    val clear: Boolean,
    val delete: Set<String>,
    val put: Map<String, Any>
)

/** 网关发布逻辑依赖的最小传输面；生产实现是 Binder，测试实现是内存参考模型。 */
internal interface RemotePreferenceChannel {
    /** 读取一个 group 的当前快照；没有任何条目时返回空 Map。 */
    fun request(group: String): Map<String, Any>

    fun update(group: String, diff: RemotePreferenceDiff)
}

/**
 * 把"当前文档 → 目标文档"表达成单事务可原子应用的差异：显式删除过期键，再整体写入。
 *
 * 不使用 `clear`：它在 NPatch 里先于事务单独生效，宿主冷启动恰好落在两步之间时会读到空文档，
 * [com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract.decode]
 * 因键集不符判为无效并 fail-closed，需要用户再次重启宿主才能恢复。
 * 标准（LSPosed）发布路径同样是"显式删除过期键 + 全量 put"，见 docs/vector_compatibility.md。
 */
internal fun buildAtomicPublishDiff(
    currentKeys: Set<String>,
    target: Map<String, Any>
): RemotePreferenceDiff = RemotePreferenceDiff(
    clear = false,
    delete = currentKeys - target.keys,
    put = target
)
