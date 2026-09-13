package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

/** 只在模块进程内流转的成功凭据，不进入管理器线协议。 */
internal data class RemotePublicationProof(
    val identity: PublicationIdentity,
    val connectionId: Long,
    val intentEpoch: Long,
    val operationCurrent: () -> Boolean
) {
    fun matches(currentIdentity: PublicationIdentity?, connection: Long, epoch: Long): Boolean =
        identity == currentIdentity && connectionId == connection && intentEpoch == epoch && operationCurrent()
}
