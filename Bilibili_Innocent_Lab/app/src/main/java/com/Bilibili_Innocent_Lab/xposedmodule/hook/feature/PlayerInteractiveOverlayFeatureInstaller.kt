package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter

/**
 * 在 protobuf 读边界清除播放器互动层：投票、关注引导、三连契约卡、指令弹幕。
 *
 * 不替换整份 VideoGuide（viewunite 还带章节点），不扫描播放器 View 树。
 */
internal class PlayerInteractiveOverlayFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PlayerInteractiveOverlayPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        if (adapted.families.isEmpty() && adapted.commandClear == null) {
            return missing(environment, "missing-adapter-point")
        }

        var installed = 0
        var expected = 0

        adapted.families.forEachIndexed { familyIndex, family ->
            val clears = family.guideClears.mapNotNull { point ->
                environment.hookPoints.resolveAdapted(
                    "player.interactive.clear.$familyIndex.${point.methodName}",
                    point.className,
                    point.methodName,
                    point.paramClassNames
                )
            }
            if (clears.isEmpty()) return@forEachIndexed
            expected += 1
            runCatching {
                environment.registrar.adapted(
                    "player.interactive.guide.$familyIndex",
                    family.guideGetter
                ) {
                    after {
                        val guide = result ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val applied = PlayerInteractiveOverlayPolicy.applyClears(guide, clears)
                        if (applied > 0) {
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                applied
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "player_interactive_guide_$familyIndex",
                    "[BIL] 播放器互动层 Guide getter Hook 注册失败(" +
                        "${family.guideGetter.className}#${family.guideGetter.methodName}): $throwable"
                )
            }
        }

        val commandClear = adapted.commandClear?.let { point ->
            environment.hookPoints.resolveAdapted(
                "player.interactive.command_clear",
                point.className,
                point.methodName,
                point.paramClassNames
            )
        }
        val commandDefault = adapted.commandDefault?.let { point ->
            runCatching {
                environment.hookPoints.resolveAdapted(
                    "player.interactive.command_default",
                    point.className,
                    point.methodName,
                    point.paramClassNames
                )?.invoke(null)
            }.getOrNull()
        }
        adapted.commandGetter?.let { point ->
            expected += 1
            runCatching {
                environment.registrar.adapted("player.interactive.command_getter", point) {
                    after {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val current = result
                        val empty = commandDefault
                        if (empty != null && current !== empty) {
                            result = empty
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        } else {
                            val reply = instance ?: return@after
                            val cleared = commandClear?.let { method ->
                                PlayerInteractiveOverlayPolicy.applyClears(reply, listOf(method))
                            } ?: 0
                            if (cleared > 0) {
                                environment.reportRuntimeEvidence(
                                    ID,
                                    FeatureRuntimeStage.APPLIED,
                                    cleared
                                )
                            }
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "player_interactive_command_getter",
                    "[BIL] 播放器指令弹幕 getter Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }

        val guideByReply = adapted.families.associate { family ->
            family.replyClassName to family.guideClears.mapNotNull { point ->
                environment.hookPoints.resolveAdapted(
                    "player.interactive.execute.clear.${family.replyClassName}.${point.methodName}",
                    point.className,
                    point.methodName,
                    point.paramClassNames
                )
            }
        }
        val guideGetterByReply = adapted.families.associate { family ->
            family.replyClassName to environment.hookPoints.resolveAdapted(
                "player.interactive.execute.getter.${family.replyClassName}",
                family.guideGetter.className,
                family.guideGetter.methodName,
                family.guideGetter.paramClassNames
            )
        }
        adapted.mossExecutes.forEachIndexed { index, point ->
            expected += 1
            runCatching {
                environment.registrar.adapted("player.interactive.moss.$index", point) {
                    after {
                        val reply = result ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        var applied = 0
                        val replyName = reply.javaClass.name
                        val getter = guideGetterByReply[replyName]
                        val clears = guideByReply[replyName].orEmpty()
                        if (getter != null && clears.isNotEmpty()) {
                            val guide = runCatching { getter.invoke(reply) }.getOrNull()
                            if (guide != null) {
                                applied += PlayerInteractiveOverlayPolicy.applyClears(guide, clears)
                            }
                        }
                        if (commandClear != null &&
                            commandClear.declaringClass.isInstance(reply)
                        ) {
                            applied += PlayerInteractiveOverlayPolicy.applyClears(
                                reply,
                                listOf(commandClear)
                            )
                        }
                        if (applied > 0) {
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                applied
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "player_interactive_moss_$index",
                    "[BIL] 播放器互动层 Moss execute Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }

        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed >= expected && expected > 0) {
            "success"
        } else {
            "partial:$installed/$expected"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "player_interactive_ok",
                "[BIL] 播放器互动层隐藏已安装，hooks=$installed"
            )
        } else {
            environment.logError(
                "player_interactive_partial",
                "[BIL] 播放器互动层部分安装，hooks=$installed/$expected"
            )
        }
        return FeatureInstallResult.Installed(installed)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "player_interactive_missing",
            "[BIL] 播放器互动层适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "player_interactive_overlay"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "player_interactive_overlay_status"
    }
}
