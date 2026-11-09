package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.util.Queue

/**
 * 启动窗口内多路结果的折叠。
 *
 * 第一份合格 grant 返回前先抽已到达的否定结果，不等待其他通道。权威拒绝一旦出现，
 * 后续 grant 一律丢弃，并立刻 fail-closed，避免「一路拒绝、一路许可」的竞态
 * 被安装进宿主。窗口结束后只抽已到达的结果：迟到 grant 不安装，迟到拒绝仍闩回退。
 */
internal data class HostAdmissionCollect(
    val sawDenied: Boolean = false,
    val grant: HostAdmissionClient.Result? = null,
    val failure: HostAdmissionClient.Result = HostAdmissionClient.Result(),
    val fallbackBlocked: Boolean = false,
    val diagnostics: List<HostAdmissionDiagnostic> = emptyList(),
) {
    fun accept(response: HostAdmissionClient.Result, acceptGrant: Boolean): HostAdmissionCollect {
        val next = copy(diagnostics = (diagnostics + response.diagnostics).take(4))
        if (sawDenied) {
            return if (HostAdmissionRouteClassifier.latchesDenial(response.reason) || response.disabledRevision > 0L) {
                next.copy(failure = if (response.disabledRevision > failure.disabledRevision) response else failure)
            } else {
                next
            }
        }
        if (HostAdmissionRouteClassifier.latchesDenial(response.reason) || response.disabledRevision > 0L) {
            return next.copy(sawDenied = true, fallbackBlocked = true, grant = null, failure = response)
        }
        if (response.grant != null) {
            return if (acceptGrant) next.copy(grant = response) else next
        }
        val blocks = HostAdmissionRouteClassifier.blocksFallback(response.reason)
        return next.copy(
            fallbackBlocked = fallbackBlocked || blocks,
            failure = if (!fallbackBlocked || blocks) response else failure
        )
    }

    fun drain(queue: Queue<HostAdmissionClient.Result>, acceptGrant: Boolean): HostAdmissionCollect {
        var next = this
        while (true) {
            val extra = queue.poll() ?: break
            next = next.accept(extra, acceptGrant)
        }
        return next
    }
}

/** 后续重试不能借运输失联抹掉已验证的拒绝或同步要求。 */
internal class HostAdmissionFallbackState {
    private var blockedReason: String? = null

    @Synchronized fun newAttempt(): HostAdmissionCollect = HostAdmissionCollect(
        fallbackBlocked = blockedReason != null,
        failure = HostAdmissionClient.Result(reason = blockedReason ?: HostAdmissionRouteClassifier.REASON_UNAVAILABLE)
    )

    @Synchronized fun observe(result: HostAdmissionClient.Result) {
        when {
            HostAdmissionRouteClassifier.blocksFallback(result.reason) -> {
                if (blockedReason != HostAdmissionRouteClassifier.REASON_DENIED) blockedReason = result.reason
            }
            result.grant?.let { it.identity != null && it.source in setOf(HostAdmissionContract.NORMAL, HostAdmissionContract.DIRECT) } == true ->
                blockedReason = null
        }
    }
}
