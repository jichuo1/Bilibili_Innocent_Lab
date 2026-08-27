package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
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
        if (adapted.itemListGetters.isEmpty() && adapted.legacyBuildMethods.isNotEmpty()) {
            return installLegacyFieldPath(environment, adapted)
        }
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
                        val filtered = CopyOnFilter.list(source) { item ->
                            RuleSetCodec.matches(
                                tokens,
                                readTitle(item, titleMethods)
                            )
                        }
                        if (filtered !== source) result = filtered
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

    /**
     * 8.84.0–8.91.0 的 MenuGroup/Item 仅暴露公开字段。字段和构建 Method 在安装期一次
     * 解析，afterHook 只做有限菜单列表过滤；不缓存 Fragment、Adapter 或菜单实例。
     */
    private fun installLegacyFieldPath(
        environment: HookEnvironment,
        adapted: VersionAdapter.MineComponentPoints
    ): FeatureInstallResult {
        val groupClassName = adapted.legacyGroupClassName
            ?: return missing(environment, "missing-legacy-group-class")
        val itemClassName = adapted.legacyItemClassName
            ?: return missing(environment, "missing-legacy-item-class")
        val itemListName = adapted.legacyItemListField
            ?: return missing(environment, "missing-legacy-item-list")
        val titleName = adapted.legacyItemTitleField
            ?: return missing(environment, "missing-legacy-title")
        val groupListName = adapted.legacyGroupListField
            ?: return missing(environment, "missing-legacy-group-list")
        val groupClass = KavaMemberLookup.classOrNull(environment.classLoader, groupClassName)
            ?: return missing(environment, "missing-legacy-group-class")
        val itemClass = KavaMemberLookup.classOrNull(environment.classLoader, itemClassName)
            ?: return missing(environment, "missing-legacy-item-class")
        val itemListField = KavaMemberLookup.fieldOrNull(groupClass, itemListName)
            ?: return missing(environment, "missing-legacy-item-list")
        val titleField = KavaMemberLookup.fieldOrNull(itemClass, titleName)
            ?: return missing(environment, "missing-legacy-title")

        var installed = 0
        adapted.legacyBuildMethods.forEachIndexed { index, point ->
            val owner = KavaMemberLookup.classOrNull(environment.classLoader, point.className)
                ?: return@forEachIndexed
            val groupListField = KavaMemberLookup.fieldOrNull(owner, groupListName)
                ?: return@forEachIndexed
            val adapterField = adapted.legacyAdapterField?.let { name ->
                KavaMemberLookup.fieldOrNull(owner, name)
            }
            val notifyChanged = adapterField?.type?.let { adapterClass ->
                KavaMemberLookup.inheritedMethodOrNull(adapterClass, "notifyDataSetChanged")
            }
            runCatching {
                environment.registrar.adapted("mine.components.legacy.$index", point) {
                    after {
                        val fragment = instance
                        val groups = runCatching {
                            groupListField.get(fragment) as? List<*>
                        }.getOrNull() ?: return@after
                        var changed = false
                        groups.forEach { group ->
                            if (group == null || !groupClass.isInstance(group)) return@forEach
                            val source = runCatching {
                                itemListField.get(group) as? List<*>
                            }.getOrNull() ?: return@forEach
                            val filtered = CopyOnFilter.list(source) { item ->
                                itemClass.isInstance(item) && RuleSetCodec.matches(
                                    tokens,
                                    readTitle(item, titleField)
                                )
                            }
                            if (filtered !== source) {
                                runCatching { itemListField.set(group, filtered) }
                                    .onSuccess { changed = true }
                            }
                        }
                        if (changed) {
                            runCatching {
                                adapterField?.get(fragment)?.let { adapter ->
                                    notifyChanged?.invoke(adapter)
                                }
                            }
                            environment.logInfo(
                                "mine_components_legacy_filtered",
                                "[BIL] 已按旧版字段模型过滤“我的”页组件"
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "mine_components_legacy_$index",
                    "[BIL] 旧版“我的”页组件过滤 Hook 注册失败: $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "legacy-registration-failed")
        environment.reportStatus(CHANNEL_STATUS, "success:legacy-fields")
        environment.logInfo(
            "mine_components_legacy_ok",
            "[BIL] “我的”页组件自定义隐藏已安装（旧版字段边界）"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun readTitle(item: Any, methods: List<Method>): String? {
        methods.forEach { method ->
            if (!method.declaringClass.isInstance(item)) return@forEach
            return runCatching { method.invoke(item) as? String }.getOrNull()
        }
        return null
    }

    private fun readTitle(item: Any, field: Field): String? = runCatching {
        field.get(item) as? String
    }.getOrNull()

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
