package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService

/**
 * Remote Files 的最小读写面。LSPosed 等经 `XposedServiceHelper` 推送来的服务和 NPatch 的认证拉取通道
 * 都只需要这两个调用；`openRemoteFile` 返回的描述符对模块 App 可写，调用方负责关闭。
 */
internal interface RemoteFileService {
    fun listRemoteFiles(): Array<String>

    fun openRemoteFile(name: String): ParcelFileDescriptor
}

internal fun XposedService.asRemoteFileService(): RemoteFileService = object : RemoteFileService {
    override fun listRemoteFiles(): Array<String> = this@asRemoteFileService.listRemoteFiles()

    override fun openRemoteFile(name: String): ParcelFileDescriptor =
        this@asRemoteFileService.openRemoteFile(name)
}

/** 拉取通道连接的结果：要么拿到可用的文件服务，要么给出与同步路径一致的失败分类。 */
internal sealed interface PulledRemoteFiles {
    class Ready(val files: RemoteFileService) : PulledRemoteFiles

    class Unavailable(val result: NPatchRemoteGateway.SyncResult) : PulledRemoteFiles
}
