package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.reflect.Method

/**
 * 播放器互动层清除白名单。
 *
 * 只清投票/关注/契约/指令弹幕对应的 protobuf `clear*`；进度章节点
 * [PRESERVED_VIDEO_POINT_CLEAR] 绝不能进入 viewunite 列表。
 */
internal object PlayerInteractiveOverlayPolicy {
    const val PRESERVED_VIDEO_POINT_CLEAR = "clearVideoPoint"

    val viewV1GuideClears: List<String> = listOf(
        "clearAttention",
        "clearCommandDms",
        "clearContractCard",
        "clearOperationCard",
        "clearOperationCardNew",
        "clearCardsSecond"
    )

    val viewUniteGuideClears: List<String> = listOf(
        "clearContractCard",
        "clearMaterial",
        "clearRightMaterial"
    )

    val dmReplyClears: List<String> = listOf("clearCommand")

    fun acceptedClears(names: Collection<String>): List<String> =
        names.filter { it != PRESERVED_VIDEO_POINT_CLEAR && it.startsWith("clear") }

    fun applyClears(target: Any, methods: Collection<Method>): Int {
        var applied = 0
        methods.forEach { method ->
            if (method.name == PRESERVED_VIDEO_POINT_CLEAR) return@forEach
            if (method.parameterCount != 0) return@forEach
            runCatching {
                method.invoke(target)
                applied += 1
            }
        }
        return applied
    }
}
