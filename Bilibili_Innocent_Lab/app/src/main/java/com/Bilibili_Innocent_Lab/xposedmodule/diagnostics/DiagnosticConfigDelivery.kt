package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

/** 仅说明标准服务提交与已有宿主回执的关系，不改变授权、功能状态或 NPatch 证据。 */
internal enum class DiagnosticConfigDelivery {
    NOT_APPLICABLE,
    NOT_PUBLISHED,
    HOST_UNAVAILABLE,
    HOST_NOT_CHECKED,
    HOST_REJECTED,
    MATCHED,
    HOST_OLDER,
    HOST_NEWER
}

internal fun hasCurrentRemoteCommit(input: ModuleDiagnosticInputs): Boolean =
    input.frameworkConnected && input.frameworkCapable &&
        input.frameworkConnectionId > 0L &&
        input.remoteConnectionId == input.frameworkConnectionId &&
        input.remotePublishState == DiagnosticRemotePublishState.READY &&
        !input.remotePublishPending && input.remoteGeneration > 0L

internal fun configDelivery(input: ModuleDiagnosticInputs): DiagnosticConfigDelivery = when {
    input.activationState == DiagnosticActivationState.ACTIVE_NPATCH ->
        DiagnosticConfigDelivery.NOT_APPLICABLE
    !hasCurrentRemoteCommit(input) -> DiagnosticConfigDelivery.NOT_PUBLISHED
    !input.hostRuntimeReceiptAvailable || input.hostQueryState != DiagnosticHostQueryState.READY ->
        DiagnosticConfigDelivery.HOST_UNAVAILABLE
    input.hostConfigState == DiagnosticHostConfigState.REJECTED ->
        DiagnosticConfigDelivery.HOST_REJECTED
    input.hostConfigState == DiagnosticHostConfigState.NOT_CHECKED || input.hostConfigGeneration <= 0L ->
        DiagnosticConfigDelivery.HOST_NOT_CHECKED
    input.hostConfigGeneration == input.remoteGeneration -> DiagnosticConfigDelivery.MATCHED
    input.hostConfigGeneration < input.remoteGeneration -> DiagnosticConfigDelivery.HOST_OLDER
    else -> DiagnosticConfigDelivery.HOST_NEWER
}
