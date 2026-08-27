package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 在 TabHost 单项绑定完成后按条目元数据隐藏底栏项，保留宿主页码与索引。 */
internal class BottomBarFeatureInstaller(
    rules: String,
    private val points: VersionAdapter.BottomBarPoints?
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
        val itemClass = environment.hookPoints.resolveClass(
            "bottom.item",
            adapted.itemClassName
        ) ?: return missing(environment, "missing-item-class")
        val fields = adapted.itemStringFields.mapIndexedNotNull { index, name ->
            environment.hookPoints.resolveField("bottom.item.string.$index", itemClass, name)
        }
        if (fields.isEmpty()) return missing(environment, "missing-item-fields")
        val tabsGetter = environment.hookPoints.resolveAdapted(
            "bottom.tabs",
            adapted.tabsGetter.className,
            adapted.tabsGetter.methodName,
            adapted.tabsGetter.paramClassNames
        ) ?: return missing(environment, "missing-tabs-getter")

        return runCatching {
            environment.registrar.adapted("bottom.bind", adapted.bindTabMethod) {
                after {
                    val host = instance
                    val index = (args.getOrNull(0) as? Number)?.toInt() ?: return@after
                    val view = args.getOrNull(1) as? View ?: return@after
                    val tabs = readTabs(tabsGetter, host) ?: return@after
                    val item = tabs.getOrNull(index) ?: return@after
                    if (!itemClass.isInstance(item)) return@after
                    val eligibleCount = tabs.count { candidate ->
                        candidate != null && itemClass.isInstance(candidate)
                    }
                    val matchedCount = tabs.count { candidate ->
                        candidate != null && itemClass.isInstance(candidate) &&
                            matches(candidate, fields)
                    }
                    if (!canHide(eligibleCount, matchedCount) || !matches(item, fields)) {
                        return@after
                    }
                    view.visibility = View.GONE
                    view.isClickable = false
                    view.isEnabled = false
                    view.alpha = 0f
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "success")
            environment.logInfo("bottom_bar_ok", "[BIL] 底栏自定义隐藏已安装")
            FeatureInstallResult.Installed()
        }.getOrElse { throwable ->
            environment.logError("bottom_bar_error", "[BIL] 底栏自定义隐藏失败: $throwable")
            missing(environment, "registration-failed")
        }
    }

    private fun readTabs(method: Method, host: Any): List<*>? = runCatching {
        method.invoke(host) as? List<*>
    }.getOrNull()

    private fun matches(item: Any, fields: List<Field>): Boolean = RuleSetCodec.matches(
        tokens,
        *fields.mapNotNull { field ->
            runCatching { field.get(item) as? String }.getOrNull()
        }.toTypedArray()
    )

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("bottom_bar_missing", "[BIL] 底栏自定义隐藏适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "bottom_bar"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "bottom_bar_status"

        internal fun canHide(total: Int, matched: Int): Boolean = matched in 1 until total
    }
}
