package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

internal enum class NoRootDisplayState {
    UNSUPPORTED_OS,
    DISABLED,
    CHECKING,
    MANAGER_MISSING,
    MODULE_NOT_REGISTERED,
    SYNCING,
    RESTART_REQUIRED,
    DISABLE_RESTART_REQUIRED,
    DISABLE_RESTART_REQUIRED_ACTIVE,
    ACTIVE,
    CONNECTION_TIMEOUT,
    ERROR
}

internal data class ActivationDecision(
    val activated: Boolean,
    val byNoRoot: Boolean
)

/** 纯状态归并，避免把“开关已开”或“Manager 可连接”误显示成已激活。 */
internal object NoRootSupportState {
    const val MIN_SUPPORTED_SDK = 28
    const val TARGET_PACKAGE = "tv.danmaku.bili"

    fun displayState(
        sdkInt: Int,
        status: NoRootSupportStore.Status,
        currentSnapshot: NoRootConfigSnapshot?,
        currentTargetVersionCode: Long,
        currentTargetUpdateTime: Long
    ): NoRootDisplayState {
        if (sdkInt < MIN_SUPPORTED_SDK) return NoRootDisplayState.UNSUPPORTED_OS
        if (!status.desiredEnabled) {
            return if (status.syncState == NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED) {
                if (status.disableWasActive) {
                    NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE
                } else {
                    NoRootDisplayState.DISABLE_RESTART_REQUIRED
                }
            } else {
                NoRootDisplayState.DISABLED
            }
        }
        val heartbeatMatches = currentSnapshot?.let { snapshot ->
            snapshot.enabled &&
                status.heartbeatRevision == snapshot.revision &&
                status.heartbeatModuleVersion == snapshot.moduleVersionCode &&
                status.heartbeatTargetPackage == TARGET_PACKAGE &&
                currentTargetVersionCode > 0L &&
                status.heartbeatTargetVersion == currentTargetVersionCode &&
                currentTargetUpdateTime > 0L &&
                status.heartbeatTargetUpdateTime == currentTargetUpdateTime &&
                status.heartbeatReceivedAt > 0L
        } == true
        if (heartbeatMatches) return NoRootDisplayState.ACTIVE
        return when (status.syncState) {
            NoRootSupportStore.SyncState.DISABLED -> NoRootDisplayState.CHECKING
            NoRootSupportStore.SyncState.CHECKING -> NoRootDisplayState.CHECKING
            NoRootSupportStore.SyncState.MANAGER_MISSING -> NoRootDisplayState.MANAGER_MISSING
            NoRootSupportStore.SyncState.MODULE_NOT_REGISTERED ->
                NoRootDisplayState.MODULE_NOT_REGISTERED
            NoRootSupportStore.SyncState.SYNCING -> NoRootDisplayState.SYNCING
            NoRootSupportStore.SyncState.RESTART_REQUIRED -> NoRootDisplayState.RESTART_REQUIRED
            NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED ->
                NoRootDisplayState.DISABLE_RESTART_REQUIRED
            NoRootSupportStore.SyncState.ACTIVE ->
                if (heartbeatMatches) NoRootDisplayState.ACTIVE
                else NoRootDisplayState.RESTART_REQUIRED
            NoRootSupportStore.SyncState.CONNECTION_TIMEOUT ->
                NoRootDisplayState.CONNECTION_TIMEOUT
            NoRootSupportStore.SyncState.ERROR -> NoRootDisplayState.ERROR
        }
    }

    fun activationDecision(
        rootActive: Boolean,
        displayState: NoRootDisplayState
    ): ActivationDecision = when {
        rootActive -> ActivationDecision(activated = true, byNoRoot = false)
        displayState == NoRootDisplayState.ACTIVE ||
            displayState == NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE ->
            ActivationDecision(activated = true, byNoRoot = true)
        else -> ActivationDecision(activated = false, byNoRoot = false)
    }
}
