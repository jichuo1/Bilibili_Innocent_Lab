package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MossRpcChannel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufReplyTreeVisitor
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologyHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologySeed
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 回复脉络 Compose 评论种子数据桥：通过 [MossRpcChannel] 统一接入 `KmpGrpcWireChannel` 与
 * `KotlinMossChannel`（共享 per-RPC `wireActive` 防双重处理），并维护有界 LRU 种子缓存。
 */
internal class ReplyTopologySeedBridge {

    private val composeSeeds = LinkedHashMap<Long, ReplyTopologySeed>(32, 0.75f, true)
    private val composeSeedWireActive = linkedMapOf(
        "mainList" to AtomicBoolean(false),
        "detailList" to AtomicBoolean(false),
        "dialogList" to AtomicBoolean(false)
    )
    private var composeSeedKotlinInstalled: Boolean = false
    private var composeSeedDeferredQueued: Boolean = false

    @Volatile
    var channelKind: String = ""
        private set

    fun findSeed(rootId: Long): ReplyTopologySeed? =
        synchronized(composeSeeds) { composeSeeds[rootId] }

    fun cacheSeed(seed: ReplyTopologySeed) {
        synchronized(composeSeeds) {
            composeSeeds[seed.key.rootRpid] = seed
            while (composeSeeds.size > MAX_COMPOSE_SEEDS) {
                composeSeeds.remove(composeSeeds.keys.first())
            }
        }
    }

    fun allWireBound(): Boolean = composeSeedWireActive.values.all { it.get() }

    fun isWireRetryNeeded(loader: ClassLoader): Boolean =
        !allWireBound() && KmpGrpcWireAdapter.applicable(loader)

    fun shouldRetryWire(kmpGrpcWireEngine: VersionAdapter.HookPoint?): Boolean =
        channelKind.isEmpty() || kmpGrpcWireEngine != null

    fun markAllWireBound() {
        channelKind = "wire"
    }

    fun installComposeSeeds(
        environment: HookEnvironment,
        host: ReplyTopologyHostAccess,
        kmpGrpcWireEngine: VersionAdapter.HookPoint?,
        wireOnly: Boolean = false
    ): FeatureInstallResult {
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val reader = ProtobufReplyTreeVisitor(host.replyInfoClass) { reply ->
            host.snapshotRoot(reply)?.let(::cacheSeed)
        }
        val wireSurface = MossRpcChannel.resolveWireSurface(loader, kmpGrpcWireEngine)
        var expected = 0
        var wireInstalled = 0
        RPCS.forEach { (rpc, name) ->
            expected++
            val wireActive = composeSeedWireActive.getValue(rpc)
            if (wireActive.get()) {
                wireInstalled++
                return@forEach
            }
            val response = KavaMemberLookup.classOrNull(loader, "$REPLY_V1_PACKAGE.$name") ?: return@forEach
            val mount = if (wireSurface != null || (!wireOnly && !composeSeedDeferredQueued)) {
                MossRpcChannel.mountKmpWireOrKotlin(
                    environment = environment,
                    loader = loader,
                    javaMossClassName = REPLY_MOSS_CLASS,
                    rpc = rpc,
                    javaReplyClass = response,
                    what = "回复脉络",
                    logKey = "comment_topology_kmoss",
                    kotlinHookId = "comment.topology.kmoss.$rpc",
                    wireSurface = wireSurface,
                    deferWireIfUnavailable = !wireOnly && !composeSeedDeferredQueued,
                    wireActive = wireActive,
                    prepareKotlinIfMissing = false,
                    allowKotlinFallback = false
                ) { reply -> reader.visit(reply); reply }
            } else {
                null
            }
            if (mount?.kmpWireInstalled == true) wireInstalled++
        }
        if (!wireOnly) composeSeedDeferredQueued = true
        if (wireInstalled > 0 && wireInstalled == expected) {
            channelKind = "wire"
            environment.logInfo(
                "comment_topology_wire_ready",
                "[BIL] 回复脉络 Compose 种子桥已接入(wire=$wireInstalled/$expected)"
            )
            return FeatureInstallResult.Installed(wireInstalled, complete = true)
        }
        if (wireOnly || composeSeedKotlinInstalled) {
            return if (wireInstalled > 0) {
                channelKind = "wire"
                FeatureInstallResult.Installed(wireInstalled, complete = wireInstalled == expected)
            } else {
                FeatureInstallResult.Skipped("missing-kotlin-reply-channel")
            }
        }
        val bridge = MossRpcChannel.prepareKotlinBridge(environment, loader, "回复脉络", "comment_topology_kmoss")
            ?: return if (wireInstalled > 0) {
                channelKind = "wire"
                FeatureInstallResult.Installed(wireInstalled, complete = wireInstalled == expected)
            } else {
                FeatureInstallResult.Skipped("missing-kotlin-bridge")
            }
        var installed = 0
        RPCS.forEach { (rpc, name) ->
            val wireActive = composeSeedWireActive.getValue(rpc)
            if (wireActive.get()) return@forEach
            val response = KavaMemberLookup.classOrNull(loader, "$REPLY_V1_PACKAGE.$name") ?: return@forEach
            val mount = MossRpcChannel.mountKmpWireOrKotlin(
                environment = environment,
                loader = loader,
                javaMossClassName = REPLY_MOSS_CLASS,
                rpc = rpc,
                javaReplyClass = response,
                what = "回复脉络",
                logKey = "comment_topology_kmoss",
                kotlinHookId = "comment.topology.kmoss.$rpc",
                wireSurface = null,
                deferWireIfUnavailable = false,
                wireActive = wireActive,
                kotlinBridge = bridge,
                prepareKotlinIfMissing = false,
                allowKotlinFallback = true,
                shareUnchangedReply = true
            ) { reply -> reader.visit(reply); reply }
            if (mount.kotlinMossInstalled) installed++
        }
        if (installed > 0) {
            composeSeedKotlinInstalled = true
            if (channelKind.isEmpty()) channelKind = "kmoss"
            val total = wireInstalled + installed
            return FeatureInstallResult.Installed(total, complete = total >= expected)
        }
        if (wireInstalled > 0) {
            channelKind = "wire"
            return FeatureInstallResult.Installed(wireInstalled, complete = wireInstalled == expected)
        }
        return FeatureInstallResult.Skipped("missing-kotlin-reply-channel")
    }

    companion object {
        private const val MAX_COMPOSE_SEEDS = 192
        private const val REPLY_V1_PACKAGE = "com.bapis.bilibili.main.community.reply.v1"
        private const val REPLY_MOSS_CLASS = "$REPLY_V1_PACKAGE.ReplyMoss"
        private val RPCS = listOf(
            "mainList" to "MainListReply",
            "detailList" to "DetailListReply",
            "dialogList" to "DialogListReply"
        )
    }
}
