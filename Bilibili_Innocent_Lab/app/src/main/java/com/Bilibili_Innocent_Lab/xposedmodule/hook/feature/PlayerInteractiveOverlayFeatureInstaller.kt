package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/**
 * 在 protobuf 读边界清除播放器互动层：投票、关注引导、三连契约卡、指令弹幕，
 * 以及左上角的运营活动横幅（电视版推广）。
 *
 * 不替换整份 VideoGuide（viewunite 还带章节点），不扫描播放器 View 树。
 *
 * 四类载体（2026-09-04 补记二/三）：
 * 1. `ViewProgressReply.videoGuide` —— 两个家族各一份白名单。
 * 2. `ViewProgressReply.dm`（`DmResource`）—— 只有 viewunite 有，装关注引导/卡片/指令弹幕，
 *    与 Guide **相互独立**，Guide 解析失败不得连带跳过它。
 * 3. `DmViewReply.command` —— 指令弹幕。
 * 4. `DmViewReply.activityMeta` —— 运营活动横幅，**文案烘焙在下发的 PNG 里，协议里没有中文**，
 *    所以只能按字段清，按文案搜协议是搜不到的。
 *
 * 覆盖单位与状态口径：
 * - 每个已适配的 Guide 家族算一个单位，`clear*` 解析不到也照样计入 expected，
 *   否则"两个家族全灭 + 指令弹幕装上"会被算成 success。
 * - DmResource、指令弹幕、运营活动横幅各算一个单位。
 * - 指令弹幕只有能拿到 `Command.getDefaultInstance()` 才允许改写 getter 返回值，
 *   拿不到就退回 `clearCommand` + Moss 边界，不注册那个改不动返回值的 getter Hook。
 * - Moss execute 是双保险，每个点各算一个单位。
 *
 * **运营活动横幅刻意不上报 APPLIED**：`clearActivityMeta()` 对空列表同样静默成功，
 * 拿"invoke 没抛"当证据就是假阳性（本模块在 2026-09-04 补记一里刚为这个栽过）。
 * 要给它精确证据得再定位 `getActivityMetaCount()`，本轮没做——宁可少报，不可多报。
 */
internal class PlayerInteractiveOverlayFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PlayerInteractiveOverlayPoints?
) : FeatureInstaller {

    override val id: String = ID

    /** 一个家族在安装期解析出来的运行期成员（供 Moss 双保险路径使用）。 */
    private class GuideFamilyMembers(
        val clears: List<Method>,
        val getter: Method?
    )

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

        // 每个已适配家族都是一个覆盖单位，解析失败也要计入 expected。
        expected += adapted.families.size
        val hookedGuideGetters = HashSet<String>(adapted.families.size)
        // Guide 的默认实例只解析一次，getter 主路径与 Moss 双保险共用。
        val defaultGuides: Map<String, Any?> = adapted.families.associate { family ->
            family.replyClassName to resolveDefaultInstance(
                environment,
                "player.interactive.guide_default.${family.replyClassName}",
                family.guideDefault
            )
        }

        adapted.families.forEachIndexed { familyIndex, family ->
            val clears = family.guideClears.mapNotNull { point ->
                environment.hookPoints.resolveAdapted(
                    "player.interactive.clear.$familyIndex.${point.methodName}",
                    point.className,
                    point.methodName,
                    point.paramClassNames
                )
            }
            if (clears.isEmpty()) {
                environment.logError(
                    "player_interactive_family_$familyIndex",
                    "[BIL] 播放器互动层白名单解析为空，家族未安装: ${family.replyClassName}"
                )
                return@forEachIndexed
            }
            val defaultGuide = defaultGuides[family.replyClassName]
            runCatching {
                environment.registrar.adapted(
                    "player.interactive.guide.$familyIndex",
                    family.guideGetter
                ) {
                    after {
                        if (hasThrowable) return@after
                        val guide = result ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        // 字段未设置时 getter 返回进程级单例：既不该动它，也不能算生效。
                        if (defaultGuide != null && guide === defaultGuide) return@after
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
                hookedGuideGetters += family.replyClassName
            }.onFailure { throwable ->
                environment.logError(
                    "player_interactive_guide_$familyIndex",
                    "[BIL] 播放器互动层 Guide getter Hook 注册失败(" +
                        "${family.guideGetter.className}#${family.guideGetter.methodName}): $throwable"
                )
            }
        }

        // 第二载体与 Guide 相互独立：Guide 白名单解析失败不应连带跳过 DmResource，
        // 所以这里单独走一轮，而不是挂在上面那个 forEachIndexed 里。
        adapted.families.forEachIndexed { familyIndex, family ->
            // 第二载体 DmResource（只有 viewunite 有）：关注引导 / 卡片 / 指令弹幕的另一条出口。
            val dmPoint = family.dmGetter
            if (dmPoint != null && family.dmClears.isNotEmpty()) {
                expected += 1
                val dmClears = family.dmClears.mapNotNull { point ->
                    environment.hookPoints.resolveAdapted(
                        "player.interactive.dm_clear.$familyIndex.${point.methodName}",
                        point.className,
                        point.methodName,
                        point.paramClassNames
                    )
                }
                val defaultDm = resolveDefaultInstance(
                    environment,
                    "player.interactive.dm_default.$familyIndex",
                    family.dmDefault
                )
                if (dmClears.isEmpty()) {
                    environment.logError(
                        "player_interactive_dm_$familyIndex",
                        "[BIL] 播放器互动层 DmResource 白名单解析为空: ${family.replyClassName}"
                    )
                } else {
                    runCatching {
                        environment.registrar.adapted(
                            "player.interactive.dm.$familyIndex",
                            dmPoint
                        ) {
                            after {
                                if (hasThrowable) return@after
                                val resource = result ?: return@after
                                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                                if (defaultDm != null && resource === defaultDm) return@after
                                val applied = PlayerInteractiveOverlayPolicy.applyClears(
                                    resource,
                                    dmClears
                                )
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
                            "player_interactive_dm_reg_$familyIndex",
                            "[BIL] 播放器互动层 DmResource Hook 注册失败(" +
                                "${dmPoint.className}#${dmPoint.methodName}): $throwable"
                        )
                    }
                }
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
        val activityMetaClear = adapted.commandActivityMetaClear?.let { point ->
            environment.hookPoints.resolveAdapted(
                "player.interactive.activity_meta_clear",
                point.className,
                point.methodName,
                point.paramClassNames
            )
        }
        val emptyCommand = resolveDefaultInstance(
            environment,
            "player.interactive.command_default",
            adapted.commandDefault
        )
        val commandGetterPoint = adapted.commandGetter
        var commandGetterHooked = false
        if (commandGetterPoint != null && emptyCommand != null) {
            runCatching {
                environment.registrar.adapted(
                    "player.interactive.command_getter",
                    commandGetterPoint
                ) {
                    after {
                        if (hasThrowable) return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        // instance 就是 DmViewReply，顺手清掉运营活动横幅（TV 版推广那块图）。
                        instance?.let { reply ->
                            activityMetaClear?.let {
                                PlayerInteractiveOverlayPolicy.applyClears(reply, listOf(it))
                            }
                        }
                        if (result !== emptyCommand) {
                            result = emptyCommand
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        }
                    }
                }
                commandGetterHooked = true
            }.onFailure { throwable ->
                environment.logError(
                    "player_interactive_command_getter",
                    "[BIL] 播放器指令弹幕 getter Hook 注册失败(" +
                        "${commandGetterPoint.className}#${commandGetterPoint.methodName}): " +
                        "$throwable"
                )
            }
        } else if (commandGetterPoint != null) {
            // 拿不到默认 Command 就别注册 getter Hook：after 里改不动本次返回值，
            // 只清字段会让宿主第一次读到的仍是原指令弹幕。改由 Moss 边界在宿主读之前清。
            environment.logInfo(
                "player_interactive_command_fallback",
                "[BIL] 播放器指令弹幕改用 Moss 边界清除（默认实例不可用）"
            )
        }

        // Moss execute 只有在真的有点位时才值得解析，否则白记一批诊断。
        val guideMembersByReply: Map<String, GuideFamilyMembers> =
            if (adapted.mossExecutes.isEmpty()) {
                emptyMap()
            } else {
                adapted.families.associate { family ->
                    family.replyClassName to GuideFamilyMembers(
                        clears = family.guideClears.mapNotNull { point ->
                            environment.hookPoints.resolveAdapted(
                                "player.interactive.execute.clear." +
                                    "${family.replyClassName}.${point.methodName}",
                                point.className,
                                point.methodName,
                                point.paramClassNames
                            )
                        },
                        getter = environment.hookPoints.resolveAdapted(
                            "player.interactive.execute.getter.${family.replyClassName}",
                            family.guideGetter.className,
                            family.guideGetter.methodName,
                            family.guideGetter.paramClassNames
                        )
                    )
                }
            }
        var dmMossInstalled = false
        adapted.mossExecutes.forEachIndexed { index, point ->
            expected += 1
            runCatching {
                environment.registrar.adapted("player.interactive.moss.$index", point) {
                    after {
                        if (hasThrowable) return@after
                        val reply = result ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        var applied = 0
                        val replyName = reply.javaClass.name
                        val members = guideMembersByReply[replyName]
                        val getter = members?.getter
                        if (members != null && getter != null && members.clears.isNotEmpty()) {
                            // 主动读一次，让清理在宿主读之前发生。getter 已被 Hook 时清理
                            // 就在那条回调里完成，这里不能再跑一遍，否则重复计证据。
                            val guide = runCatching { getter.invoke(reply) }.getOrNull()
                            val defaultGuide = defaultGuides[replyName]
                            if (guide != null && replyName !in hookedGuideGetters &&
                                (defaultGuide == null || guide !== defaultGuide)
                            ) {
                                applied += PlayerInteractiveOverlayPolicy.applyClears(
                                    guide,
                                    members.clears
                                )
                            }
                        }
                        val dmClearsForReply = listOfNotNull(commandClear, activityMetaClear)
                            .filter { it.declaringClass.isInstance(reply) }
                        if (dmClearsForReply.isNotEmpty()) {
                            val cleared = PlayerInteractiveOverlayPolicy.applyClears(
                                reply,
                                dmClearsForReply
                            )
                            // getter Hook 已经能精确判断"本来就有指令弹幕"，证据以它为准。
                            if (!commandGetterHooked) applied += cleared
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
                if (point.className == VersionAdapter.PLAYER_INTERACTIVE_DM_MOSS_CLASS) {
                    dmMossInstalled = true
                }
            }.onFailure { throwable ->
                environment.logError(
                    "player_interactive_moss_$index",
                    "[BIL] 播放器互动层 Moss execute Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }

        // 运营活动横幅（TV 版推广）单独算一个覆盖单位：它和指令弹幕是 DmViewReply 上两个独立字段。
        if (adapted.commandActivityMetaClear != null) {
            expected += 1
            if (activityMetaClear != null && (commandGetterHooked || dmMossInstalled)) {
                installed += 1
            } else {
                environment.logError(
                    "player_interactive_activity_meta_missing",
                    "[BIL] 播放器运营活动横幅无可用清除路径"
                )
            }
        }

        // 指令弹幕算一个覆盖单位：getter 改写和 Moss 边界清除任一条真的装上才算覆盖。
        if (adapted.commandGetter != null || adapted.commandClear != null) {
            expected += 1
            if (commandGetterHooked || (commandClear != null && dmMossInstalled)) {
                installed += 1
            } else {
                environment.logError(
                    "player_interactive_command_missing",
                    "[BIL] 播放器指令弹幕无可用清除路径"
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

    /** 解析并调用静态 `getDefaultInstance()`；任何一步失败都降级为 null，不影响安装。 */
    private fun resolveDefaultInstance(
        environment: HookEnvironment,
        id: String,
        point: VersionAdapter.HookPoint?
    ): Any? = point?.let {
        runCatching {
            environment.hookPoints.resolveAdapted(
                id,
                it.className,
                it.methodName,
                it.paramClassNames
            )?.invoke(null)
        }.getOrNull()
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
