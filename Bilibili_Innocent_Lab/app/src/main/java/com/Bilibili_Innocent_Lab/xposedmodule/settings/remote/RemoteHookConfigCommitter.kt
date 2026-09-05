package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import kotlin.math.max

/** Service 102 的读取返回客户端缓存；commit 返回值才是本次远端调用的确认。 */
internal interface RemoteHookConfigBackend {
    fun readCached(): Map<String, *>
    fun commit(document: Map<String, Any>, removedKeys: Set<String>): Boolean
}

/**
 * 由发布器的同一把锁串行调用。只记成功提交的摘要，不另存设置值或框架对象。
 *
 * libxposed commit 会先更新缓存，再进行 IPC；失败后缓存也可能等于目标。因此缓存相等
 * 只能与当前连接的成功确认一起用于去重，绝不能单独作为一次成功发布的证据。
 */
internal class RemoteHookConfigCommitter {
    private data class Acknowledgement(
        val connectionId: Long,
        val generation: Long,
        val digest: String
    )

    private var acknowledged: Acknowledgement? = null
    private var cleanupConnectionId = 0L
    private val pendingRemovals = linkedSetOf<String>()

    fun invalidate() {
        acknowledged = null
    }

    fun publish(
        connectionId: Long,
        moduleVersionCode: Long,
        decision: UserTermsDecision,
        values: Map<String, Any>,
        nowEpochMs: Long,
        backend: RemoteHookConfigBackend
    ): RemoteHookConfigPublishResult = runCatching {
        check(connectionId > 0L) { "remote service connection is unavailable" }
        if (cleanupConnectionId != connectionId) {
            cleanupConnectionId = connectionId
            pendingRemovals.clear()
        }
        val cached = backend.readCached()
        // 只清理专用 group 中当前协议以外的键，失败后仍保留删除意图。
        // SDK 可能已从本地缓存删掉它们，但 Irena 远端仍保留旧键，不能在重试时丢失。
        pendingRemovals.addAll(cached.keys - RemoteHookConfigContract.persistedKeys)
        val current = RemoteHookConfigContract.decode(cached)
        val matchingSnapshot = (current as? RemoteHookConfigDecodeResult.Ready)?.snapshot?.takeIf {
            it.moduleVersionCode == moduleVersionCode && it.deliveryEnabled &&
                it.noRootRevision == 0L && it.decision == decision && it.values == values
        }
        val confirmation = acknowledged
        if (matchingSnapshot != null && pendingRemovals.isEmpty() &&
            confirmation?.connectionId == connectionId &&
            confirmation.generation == matchingSnapshot.generation &&
            confirmation.digest == cached[RemoteHookConfigContract.KEY_DIGEST]
        ) {
            return@runCatching RemoteHookConfigPublishResult.Success(
                matchingSnapshot.generation, changed = false
            )
        }

        invalidate()
        val previousGeneration = (cached[RemoteHookConfigContract.KEY_GENERATION] as? Long)
            ?.coerceAtLeast(0L) ?: 0L
        // 重连/冷启动必须重新提交确认，但文档相同时保留代次，避免无设置变化也提示重启宿主。
        val generation = matchingSnapshot?.generation ?: run {
            check(previousGeneration < Long.MAX_VALUE) { "remote generation exhausted" }
            max(nowEpochMs.coerceAtLeast(1L), previousGeneration + 1L)
        }
        val document = RemoteHookConfigContract.encode(
            generation = generation,
            moduleVersionCode = moduleVersionCode,
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = decision,
            values = values
        )
        check(backend.commit(document, pendingRemovals.toSet())) {
            "remote preferences commit returned false"
        }
        val localCopy = backend.readCached()
        val decoded = RemoteHookConfigContract.decode(localCopy)
        check(decoded is RemoteHookConfigDecodeResult.Ready && localCopy == document) {
            "remote client cache verification failed"
        }
        acknowledged = Acknowledgement(
            connectionId, generation, document.getValue(RemoteHookConfigContract.KEY_DIGEST) as String
        )
        pendingRemovals.clear()
        RemoteHookConfigPublishResult.Success(generation, changed = true)
    }.getOrElse { throwable ->
        invalidate()
        RemoteHookConfigPublishResult.Failure(
            throwable.message ?: throwable.javaClass.simpleName, throwable
        )
    }
}
