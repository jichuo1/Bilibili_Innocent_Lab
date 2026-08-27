package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Field

/** 在首页 Tab 构建参数进入宿主前按 id/title/uri/reporterId 自定义过滤。 */
internal class HomeTabFilterFeatureInstaller(
    rules: String,
    private val points: VersionAdapter.HomeTabPoints?
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

        return runCatching {
            environment.registrar.adapted("home.tabs.build", adapted.buildMethod) {
                before {
                    val source = args.firstOrNull() as? List<*> ?: return@before
                    if (source.isEmpty()) return@before
                    val filtered = source.filterNot { item ->
                        item != null && resource.isInstance(item) && matches(item, fields)
                    }
                    if (filtered.isNotEmpty() && filtered.size != source.size) {
                        args[0] = ArrayList(filtered)
                    }
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "success")
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

    private fun matches(item: Any, fields: Fields): Boolean = RuleSetCodec.matches(
        tokens,
        readString(fields.id, item),
        readString(fields.title, item),
        readString(fields.uri, item),
        readString(fields.reporter, item)
    )

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
