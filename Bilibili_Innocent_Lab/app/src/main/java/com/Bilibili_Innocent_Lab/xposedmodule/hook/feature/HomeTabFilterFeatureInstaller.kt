package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Field

/** 在首页 Tab 构建参数进入宿主前按 id/title/uri/reporterId 自定义过滤。 */
internal class HomeTabFilterFeatureInstaller(
    rules: String,
    selectors: String = "",
    private val points: VersionAdapter.HomeTabPoints?
) : FeatureInstaller {

    override val id: String = ID
    private val tokens = RuleSetCodec.parse(rules)
    private val selectorSet = MineComponentSelectionCodec.decode(selectors)

    private fun hasHiddenConfiguration(): Boolean =
        tokens.isNotEmpty() || selectorSet.isNotEmpty()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        // 即使一项都没勾也要装：不扫描就永远产不出勾选列表（沿用"我的"页的 scan / filter+scan 口径）。
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val resource = environment.hookPoints.resolveClass(
            "home.tabs.resource",
            adapted.resourceClassName
        ) ?: return missing(environment, "missing-resource-class")
        val fields = Fields(
            id = resolveField(environment, resource, adapted.idField, "id"),
            title = resolveField(environment, resource, adapted.titleField, "title"),
            uri = resolveField(environment, resource, adapted.uriField, "uri"),
            reporter = adapted.reporterIdField?.let {
                resolveField(environment, resource, it, "reporter")
            }
        )
        if (fields.id == null || fields.title == null || fields.uri == null) {
            return missing(environment, "missing-resource-fields")
        }

        val publisher = ScanSnapshotPublisher(
            environment,
            MineComponentSnapshotCodec.SURFACE_HOME_TABS,
            setOf("home_tab_filter")
        )
        return runCatching {
            environment.registrar.adapted("home.tabs.build", adapted.buildMethod) {
                before {
                    val source = args.firstOrNull() as? List<*> ?: return@before
                    if (source.isEmpty()) return@before
                    // 先按未过滤的完整列表出快照，勾选面板才看得到"当前被隐藏的那几项"。
                    publisher.publish(
                        source.mapNotNull { item ->
                            if (!resource.isInstance(item) || item == null) return@mapNotNull null
                            MineComponentScanEntry.create(
                                kind = "home_tab",
                                title = readString(fields.title, item),
                                id = readString(fields.id, item),
                                uri = readString(fields.uri, item),
                                showing = !matches(item, fields)
                            )
                        }
                    )
                    if (!hasHiddenConfiguration()) return@before
                    val filtered = CopyOnFilter.list(source) { item ->
                        resource.isInstance(item) && matches(item, fields)
                    }
                    if (filtered !== source && filtered.isNotEmpty()) {
                        args[0] = filtered
                    }
                }
            }
            val mode = if (hasHiddenConfiguration()) "filter+scan" else "scan"
            environment.reportStatus(CHANNEL_STATUS, "success:$mode")
            environment.logInfo("home_tabs_ok", "[BIL] 首页 Tab 自定义隐藏已安装")
            FeatureInstallResult.Installed()
        }.getOrElse { throwable ->
            environment.logError("home_tabs_error", "[BIL] 首页 Tab 自定义隐藏失败: $throwable")
            missing(environment, "registration-failed")
        }
    }

    private fun resolveField(
        environment: HookEnvironment,
        owner: Class<*>,
        name: String,
        suffix: String
    ): Field? = environment.hookPoints.resolveField("home.tabs.$suffix", owner, name)

    /** 手填规则与勾选选择器取并集；两条来源互不依赖，任一命中即隐藏。 */
    private fun matches(item: Any, fields: Fields): Boolean {
        if (tokens.isNotEmpty() && RuleSetCodec.matches(
                tokens,
                readString(fields.id, item),
                readString(fields.title, item),
                readString(fields.uri, item),
                readString(fields.reporter, item)
            )
        ) return true
        if (selectorSet.isEmpty()) return false
        val key = MineComponentSelector.key(
            "home_tab",
            readString(fields.title, item),
            readString(fields.id, item),
            readString(fields.uri, item)
        )
        return key != null && key in selectorSet
    }

    private fun readString(field: Field?, target: Any): String? = runCatching {
        field?.get(target) as? String
    }.getOrNull()

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("home_tabs_missing", "[BIL] 首页 Tab 自定义隐藏适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    private data class Fields(
        val id: Field?,
        val title: Field?,
        val uri: Field?,
        val reporter: Field?
    )

    companion object {
        const val ID = "home_tab_filter"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_tab_filter_status"
    }
}
