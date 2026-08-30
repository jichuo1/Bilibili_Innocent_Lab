package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/** 重启前 flush 只能接受仍对应当前用户意图与精确快照 revision 的远端写入。 */
internal object NoRootRestartFlushGuard {
    fun accepts(
        generationMatches: Boolean,
        desiredEnabled: Boolean,
        expectedEnabled: Boolean,
        snapshotEnabled: Boolean?,
        snapshotRevision: Long,
        expectedRevision: Long,
        remoteWriteConfirmed: Boolean
    ): Boolean = remoteWriteConfirmed &&
        generationMatches &&
        desiredEnabled == expectedEnabled &&
        snapshotEnabled == expectedEnabled &&
        expectedRevision > 0L &&
        snapshotRevision == expectedRevision
}
