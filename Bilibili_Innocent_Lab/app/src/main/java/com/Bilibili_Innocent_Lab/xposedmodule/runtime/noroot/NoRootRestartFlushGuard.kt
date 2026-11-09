package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/** 重启前 flush 接受精确快照的当前直达发布；远端镜像成功仍是兼容的独立证据。 */
internal object NoRootRestartFlushGuard {
    fun accepts(
        generationMatches: Boolean,
        desiredEnabled: Boolean,
        expectedEnabled: Boolean,
        snapshotEnabled: Boolean?,
        snapshotRevision: Long,
        expectedRevision: Long,
        remoteWriteConfirmed: Boolean,
        directWriteConfirmed: Boolean = false
    ): Boolean = (remoteWriteConfirmed || directWriteConfirmed) &&
        generationMatches &&
        desiredEnabled == expectedEnabled &&
        snapshotEnabled == expectedEnabled &&
        expectedRevision > 0L &&
        snapshotRevision == expectedRevision
}
