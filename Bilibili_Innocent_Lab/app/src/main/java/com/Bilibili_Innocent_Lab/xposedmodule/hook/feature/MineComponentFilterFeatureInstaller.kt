package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 在 MenuGroup(V2)#getItemList 的公开返回边界按 Item 标题自定义过滤。 */
internal class MineComponentFilterFeatureInstaller(
    rules: String,
    private val points: VersionAdapter.MineComponentPoints?
) : FeatureInstaller {

    override val id: String = ID
    private val tokens = RuleSetCodec.parse(rules)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (tokens.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val titleMethods = adapted.itemTitleGetters.mapIndexedNotNull { index, point ->
            environment.hookPoints.resolveAdapted(
                "mine.components.title.$index",
                point.className,
                point.methodName,
                point.paramClassNames
            )
        }.distinctBy(Method::toGenericString)
        if (titleMethods.isEmpty()) return missing(environment, "missing-title-getter")

        var installed = 0
        adapted.itemListGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("mine.components.list.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        val filtered = source.filterNot { item ->
                            item != null && RuleSetCodec.matches(
                                tokens,
                                readTitle(item, titleMethods)
                            )
                        }
                        if (filtered.size != source.size) result = ArrayList(filtered)
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "mine_components_$index",
                    "[BIL] “我的”页组件过滤 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo("mine_components_ok", "[BIL] “我的”页组件自定义隐藏已安装")
        return FeatureInstallResult.Installed(installed)
    }

    private fun readTitle(item: Any, methods: List<Method>): String? {
        methods.forEach { method ->
            if (!method.declaringClass.isInstance(item)) return@forEach
            return runCatching { method.invoke(item) as? String }.getOrNull()
        }
        return null
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "mine_components_missing",
            "[BIL] “我的”页组件自定义隐藏适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "mine_component_filter"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "mine_component_filter_status"
    }
}
