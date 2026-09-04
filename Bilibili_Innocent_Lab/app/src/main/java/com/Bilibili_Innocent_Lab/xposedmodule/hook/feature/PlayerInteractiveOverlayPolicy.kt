package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/**
 * 播放器互动层清除策略的运行期边界。
 *
 * **白名单不在这里**：`clear*` 名单与被保留的章节点方法名唯一定义在
 * [VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES] 与
 * [VersionAdapter.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR]。本对象只做最后一道
 * 运行期兜底：无论谁传进来什么方法，章节点和带参方法都不会被调用。
 */
internal object PlayerInteractiveOverlayPolicy {

    /** 进度章节点的 `clear*`，永远不允许被调用。 */
    const val PRESERVED_VIDEO_POINT_CLEAR =
        VersionAdapter.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR

    /**
     * 依次调用无参 `clear*`，返回**实际调用成功**的条数。
     *
     * 注意返回值只代表"调用成功"，不代表字段原来有内容——protobuf 的 `clear*` 对空字段
     * 同样静默成功。调用方要判断"是否真的清掉了东西"，必须自己先排除默认实例，
     * 见 `PlayerInteractiveOverlayFeatureInstaller` 里的 defaultGuide 判定。
     */
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
