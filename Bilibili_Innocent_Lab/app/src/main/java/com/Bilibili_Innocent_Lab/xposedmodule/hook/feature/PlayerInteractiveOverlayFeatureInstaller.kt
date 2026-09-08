package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/** 同步/异步响应为主路径；getter 补充缓存读取。各入口共用字段白名单和副本清理器。 */
internal class PlayerInteractiveOverlayFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PlayerInteractiveOverlayPoints?
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
        if (hooks == 0) return missing(environment, "no-safe-interactive-path")
        val complete = installed == expected && expected > 0
        environment.reportStatus(CHANNEL_STATUS, if (complete) "success" else "partial:$installed/$expected")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.logInfo("player_interactive_installed", "[BIL] 互动层响应覆盖=$installed/$expected，注册点=$hooks，getter 仅作后备")
        return FeatureInstallResult.Installed(hooks, complete)
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
