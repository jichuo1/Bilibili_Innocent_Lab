package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/**
 * 串行化更新检查请求，并在用户切换渠道时只保留最后一次手动请求。
 *
 * 网络请求仍由调用方执行；该类只管理当前请求、待执行请求和结果是否仍属于当前渠道，
 * 因而可以在普通 JVM 单元测试中完整覆盖切换竞态。
 */
internal class UpdateCheckCoordinator {

    data class Request(
        val channel: GitHubReleaseChecker.UpdateChannel,
        val manual: Boolean
    )

    data class Completion(
        val shouldDeliverResult: Boolean,
        val nextRequest: Request?
    )

    private var activeRequest: Request? = null
    private var pendingRequest: Request? = null

    /**
     * 返回非 null 表示调用方应立即启动该请求；已有请求运行时，手动请求会覆盖排队项。
     */
    @Synchronized
    fun submit(request: Request): Request? {
        if (activeRequest == null) {
            activeRequest = request
            return request
        }
        if (request.manual) {
            pendingRequest = if (activeRequest?.channel == request.channel) null else request
        }
        return null
    }

    /**
     * 完成当前请求。只有完成渠道仍是用户当前选择时才允许展示结果；排队请求也必须与
     * 当前选择一致，否则直接丢弃，避免快速往返切换后启动过期检查。
     */
    @Synchronized
    fun complete(
        completedChannel: GitHubReleaseChecker.UpdateChannel,
        selectedChannel: GitHubReleaseChecker.UpdateChannel
    ): Completion {
        val completedCurrentRequest = activeRequest?.channel == completedChannel
        val shouldDeliver = completedCurrentRequest && completedChannel == selectedChannel
        if (completedCurrentRequest) activeRequest = null

        var next: Request? = null
        if (activeRequest == null) {
            val pending = pendingRequest
            pendingRequest = null
            if (pending?.channel == selectedChannel) {
                activeRequest = pending
                next = pending
            }
        }
        return Completion(shouldDeliver, next)
    }
}
