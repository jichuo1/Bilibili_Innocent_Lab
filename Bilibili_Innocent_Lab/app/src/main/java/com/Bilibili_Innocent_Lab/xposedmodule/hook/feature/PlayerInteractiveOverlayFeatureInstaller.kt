package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/** 同步/异步响应为主路径；getter 补充缓存读取。各入口共用字段白名单和副本清理器。 */
internal class PlayerInteractiveOverlayFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PlayerInteractiveOverlayPoints?,
    private val kmpGrpcWireEngine: VersionAdapter.HookPoint? = null
) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> get() =
        if (enabled) DiagnosticCapabilityCatalog.childrenOf(ID).map { it.id } else emptyList()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return missing(environment, "disabled")
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val cleaner = PlayerInteractiveReplyCleaner(loader, adapted)
        val primaryPaths = hashMapOf<String, Int>()
        val fallbackIds = hashSetOf<String>()
        var hooks = 0
        val owners = VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES.associate {
            it.mossClassName to it.replyClassName
        } + (VersionAdapter.PLAYER_INTERACTIVE_DM_MOSS_CLASS to VersionAdapter.PLAYER_INTERACTIVE_DM_REPLY_CLASS)

        for (fallback in cleaner.fallbacks) {
            runCatching {
                environment.registrar.exact(
                    "player.interactive.fallback.${fallback.getter.declaringClass.name}.${fallback.getter.name}",
                    fallback.getter.declaringClass, fallback.getter.name
                ) {
                    after {
                        if (hasThrowable || cleaner.transformingResponse) return@after
                        val original = result ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val updated = fallback.transform(original, environment)
                        if (updated !== original) result = updated
                    }
                }
                hooks++
                fallbackIds += fallback.ids
            }.onFailure {
                environment.logError("player_interactive_fallback_${fallback.getter.name}", "[BIL] 互动层 getter 后备注册失败: $it")
            }
        }
        fun installResponse(point: VersionAdapter.HookPoint, async: Boolean) {
            val replyName = owners[point.className] ?: return
            val ids = cleaner.responseIds(replyName)
            if (ids.isEmpty()) return
            val handler = if (async) KavaMemberLookup.classOrNull(loader, HANDLER_CLASS) else null
            if (async && (handler == null || point.paramClassNames.orEmpty().size != 2 ||
                    point.paramClassNames.orEmpty()[1] != HANDLER_CLASS)) return
            runCatching {
                environment.registrar.adapted(
                    "player.interactive.${if (async) "async" else "sync"}.${point.className}.${point.methodName}", point
                ) {
                    if (async) {
                        before {
                            val original = argOrNull(1) ?: return@before
                            val proxy = MossResponseHandlerProxy.wrapTransform(handler!!, original) { reply ->
                                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                                cleaner.cleanResponse(reply, environment)
                            } ?: return@before
                            args[1] = proxy
                        }
                    } else {
                        after {
                            if (hasThrowable) return@after
                            val original = result ?: return@after
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            val updated = cleaner.cleanResponse(original, environment)
                            if (updated !== original) result = updated
                        }
                    }
                }
                hooks++
                ids.forEach { primaryPaths[it] = (primaryPaths[it] ?: 0) or (if (async) 2 else 1) }
            }.onFailure {
                environment.logError("player_interactive_response_${point.className}_$async", "[BIL] 互动层响应 Hook 注册失败: $it")
            }
        }
        adapted.mossExecutes.distinct().forEach { installResponse(it, false) }
        adapted.mossAsync.distinct().forEach { installResponse(it, true) }
        // Kotlin 通道是**附加覆盖**，不计入上面的 primaryPaths / expected，免得改变既有的成功/部分判定。
        val kotlinPaths = installKotlinMoss(environment, loader, cleaner, owners, adapted)
        val absentFamilies = VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES.filter {
            KavaMemberLookup.classOrNull(loader, it.replyClassName) == null &&
                KavaMemberLookup.classOrNull(loader, it.guideClassName) == null
        }.map { it.diagnosticFamilyId }
        val absentDm = KavaMemberLookup.classOrNull(loader, VersionAdapter.PLAYER_INTERACTIVE_DM_REPLY_CLASS) == null
        var expected = 0
        var installed = 0
        for (capability in DiagnosticCapabilityCatalog.childrenOf(ID)) {
            val absent = absentFamilies.any { capability.locatorKey?.startsWith("$it/") == true } ||
                (absentDm && capability.id in setOf("player_interactive_dm_commands", "player_interactive_activity_banner"))
            if (absent) {
                environment.reportCapability(capability.id, FeatureInstallResult.Skipped("not-applicable-host"))
                continue
            }
            expected += 2
            val paths = Integer.bitCount(primaryPaths[capability.id] ?: 0)
            installed += paths
            if (paths == 0 && capability.id in fallbackIds) {
                environment.reportCapability(capability.id, FeatureInstallResult.Installed(1, complete = false))
            } else environment.reportCapabilityCoverage(capability.id, paths > 0, paths, 2)
        }
        if (hooks == 0 && kotlinPaths == 0) return missing(environment, "no-safe-interactive-path")
        val complete = installed == expected && expected > 0
        environment.reportStatus(CHANNEL_STATUS, if (complete) "success" else "partial:$installed/$expected")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.logInfo("player_interactive_installed", "[BIL] 互动层响应覆盖=$installed/$expected，注册点=$hooks，Kotlin 通道=$kotlinPaths，getter 仅作后备")
        return FeatureInstallResult.Installed(if (hooks > 0) hooks else kotlinPaths, complete)
    }
    /**
     * Kotlin 版 moss（`K*Moss`）与 KMP gRPC wire 通道：优先经 `KmpGrpcWireChannel` 在字节边界清理，
     * 兜底再挂 `K*Moss` 回调入口（含 9.20 起 4 参 legacyCallbackEntry），与 Java 通道并行。
     */
    private fun installKotlinMoss(
        environment: HookEnvironment,
        loader: ClassLoader,
        cleaner: PlayerInteractiveReplyCleaner,
        owners: Map<String, String>,
        points: VersionAdapter.PlayerInteractiveOverlayPoints
    ): Int {
        val wireSurface = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter.resolve(loader, kmpGrpcWireEngine)
        val adaptedPairs = (points.mossAsync + points.mossExecutes).distinct().mapNotNull { point ->
            val replyName = owners[point.className] ?: return@mapNotNull null
            Triple(point.className, replyName, KotlinMossBridgeMembers.rpcName(point.methodName))
        }
        val knownPairs = VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES.map {
            Triple(it.mossClassName, it.replyClassName, "viewProgress")
        } + Triple(
            VersionAdapter.PLAYER_INTERACTIVE_DM_MOSS_CLASS,
            VersionAdapter.PLAYER_INTERACTIVE_DM_REPLY_CLASS,
            "dmView"
        )
        val seen = hashSetOf<String>()
        var installed = 0
        var members: KotlinMossBridgeMembers? = null
        var membersChecked = false
        (adaptedPairs + knownPairs).forEach { (javaMossName, replyName, rpc) ->
            val kotlinName = KotlinMossBridgeMembers.kotlinMossClassName(javaMossName) ?: return@forEach
            if (!seen.add("$kotlinName#$rpc")) return@forEach
            if (cleaner.responseIds(replyName).isEmpty()) return@forEach
            // 装不上不能再静默：每个候选只在真的缺东西时记一条原因（有界：候选个数）。
            fun skip(reason: String) = environment.logInfo(
                "player_interactive_kmoss_skip_$rpc",
                "[BIL] 互动层 Kotlin 通道跳过 ${kotlinName.substringAfterLast('.')}#$rpc: $reason"
            )
            val replyClass = KavaMemberLookup.classOrNull(loader, replyName) ?: return@forEach skip("no-reply-class")
            val wireActive = java.util.concurrent.atomic.AtomicBoolean(false)
            val transformWire: (Any) -> Any = { reply ->
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                cleaner.cleanResponse(reply, environment)
            }
            val wireInstalled = if (wireSurface != null) {
                KmpGrpcWireChannel.install(
                    environment = environment,
                    surface = wireSurface,
                    javaMossClassName = javaMossName,
                    rpc = rpc,
                    javaReplyClass = replyClass,
                    what = "互动层",
                    logKey = "player_interactive_kmoss",
                    wireActive = wireActive,
                    transformJava = transformWire
                )
            } else {
                KmpGrpcWireChannel.installOrDefer(
                    environment = environment,
                    surface = null,
                    javaMossClassName = javaMossName,
                    rpc = rpc,
                    javaReplyClass = replyClass,
                    what = "互动层",
                    logKey = "player_interactive_kmoss",
                    wireActive = wireActive,
                    transformJava = transformWire
                )
            }
            if (wireInstalled) {
                installed++
                return@forEach
            }
            val kotlinMoss = KavaMemberLookup.classOrNull(loader, kotlinName) ?: return@forEach skip("no-kotlin-class")
            if (!membersChecked) {
                membersChecked = true
                val resolved = KotlinMossBridgeMembers.resolve(loader)
                if (resolved == null) {
                    environment.logInfo("player_interactive_kmoss_skip", "[BIL] 互动层 Kotlin 通道未安装: kotlinx.serialization 成员缺失")
                } else if (KotlinMossBridgeSelfTest.allows(environment, loader, resolved, "互动层")) {
                    members = resolved
                }
            }
            val readyMembers = members ?: return@forEach
            val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(replyClass) ?: return@forEach skip("no-java-codec")
            val entry = KotlinMossBridgeMembers.callbackEntry(kotlinMoss, rpc)
                ?: KotlinMossBridgeMembers.legacyCallbackEntry(kotlinMoss, rpc)
                ?: return@forEach skip("no-callback-entry")
            val legacy = entry.parameterCount == 4
            val fallbackProtoBuf = if (legacy) readyMembers.defaultProtoBuf() ?: return@forEach skip("no-default-protobuf") else null
            val handlerClass = entry.parameterTypes[3]
            val reported = java.util.concurrent.atomic.AtomicBoolean(false)
            val called = java.util.concurrent.atomic.AtomicBoolean(false)
            runCatching {
                environment.registrar.exact(
                    "player.interactive.kmoss.$kotlinName.$rpc",
                    entry.declaringClass, entry.name, *entry.parameterTypes
                ) {
                    before {
                        if (wireActive.get()) return@before
                        if (called.compareAndSet(false, true)) {
                            environment.logInfo(
                                "player_interactive_kmoss_call",
                                "[BIL] 互动层 Kotlin 通道请求已进入 ${kotlinMoss.simpleName}#$rpc"
                            )
                        }
                        val delegate = args.getOrNull(3) ?: return@before
                        val format = if (legacy) fallbackProtoBuf else args.getOrNull(4)
                        val bridge = readyMembers.bridgeFor(format, args.getOrNull(2), codec) ?: return@before
                        val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, delegate) { reply ->
                            if (reported.compareAndSet(false, true)) {
                                environment.logInfo(
                                    "player_interactive_kmoss_active",
                                    "[BIL] 互动层 Kotlin 通道 ${kotlinMoss.simpleName}#$rpc 已收到响应并进入清理"
                                )
                            }
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            runCatching {
                                bridge.transform(reply) { javaReply -> cleaner.cleanResponse(javaReply, environment) }
                            }.getOrElse { throwable ->
                                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                                environment.logError(
                                    "player_interactive_kmoss_failed",
                                    "[BIL] 互动层 Kotlin 通道清理失败，已放行原响应(${kotlinMoss.simpleName}#$rpc): $throwable"
                                )
                                reply
                            }
                        } ?: return@before
                        args[3] = proxy
                    }
                }
                installed++
            }.onFailure {
                environment.logError("player_interactive_kmoss_$rpc", "[BIL] 互动层 Kotlin 通道注册失败(${kotlinMoss.simpleName}#$rpc): $it")
            }
        }
        return installed
    }

    private fun missing(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        return FeatureInstallResult.Skipped(reason)
    }
    companion object {
        const val ID = "player_interactive_overlay"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "player_interactive_overlay_status"
        private const val HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
    }
}
