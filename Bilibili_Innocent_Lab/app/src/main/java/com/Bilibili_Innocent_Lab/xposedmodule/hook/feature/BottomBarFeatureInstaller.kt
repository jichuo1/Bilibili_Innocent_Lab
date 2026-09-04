package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 在 TabHost 单项绑定完成后按条目元数据隐藏底栏项，保留宿主页码与索引。 */
internal class BottomBarFeatureInstaller(
    rules: String,
    selectors: String = "",
    private val points: VersionAdapter.BottomBarPoints?
) : FeatureInstaller {

    override val id: String = ID
    private val tokens = RuleSetCodec.parse(rules)
    private val selectorSet = MineComponentSelectionCodec.decode(selectors)

    private fun hasHiddenConfiguration(): Boolean =
        tokens.isNotEmpty() || selectorSet.isNotEmpty()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        // 空配置也要装：不扫描就产不出勾选列表（沿用"我的"页 scan / filter+scan 口径）。
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

        val publisher = ScanSnapshotPublisher(
            environment,
            MineComponentSnapshotCodec.SURFACE_BOTTOM_BAR,
            setOf("bottom_tab_filter")
        )
        return runCatching {
            environment.registrar.adapted("bottom.bind", adapted.bindTabMethod) {
                after {
                    val host = instance ?: return@after
                    val index = (args.getOrNull(0) as? Number)?.toInt() ?: return@after
                    val view = args.getOrNull(1) as? View ?: return@after
                    val tabs = readTabs(tabsGetter, host) ?: return@after
                    publisher.publish(
                        tabs.mapNotNull { candidate ->
                            if (candidate == null || !itemClass.isInstance(candidate)) {
                                return@mapNotNull null
                            }
                            entryOf(candidate, fields)
                        }
                    )
                    if (!hasHiddenConfiguration()) return@after
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
            val mode = if (hasHiddenConfiguration()) "filter+scan" else "scan"
            environment.reportStatus(CHANNEL_STATUS, "success:$mode")
            environment.logInfo("bottom_bar_ok", "[BIL] 底栏自定义隐藏已安装($mode)")
            FeatureInstallResult.Installed()
        }.getOrElse { throwable ->
            environment.logError("bottom_bar_error", "[BIL] 底栏自定义隐藏失败: $throwable")
            missing(environment, "registration-failed")
        }
    }

    private fun readTabs(method: Method, host: Any): List<*>? = runCatching {
        method.invoke(host) as? List<*>
    }.getOrNull()

    private fun values(item: Any, fields: List<Field>): List<String> = fields.mapNotNull { field ->
        runCatching { field.get(item) as? String }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }

    /**
     * 宿主的条目类只暴露一串**无语义**的 String 字段，只能按值的形状分工：
     * 带 `://` 的当路由，短且不含 `://` 的当显示名。这是值形状判断，不是按混淆字段名写死。
     */
    private fun entryOf(item: Any, fields: List<Field>): MineComponentScanEntry? {
        val all = values(item, fields)
        if (all.isEmpty()) return null
        val uri = all.firstOrNull { it.contains("://") }
        val title = all.firstOrNull { !it.contains("://") && it.length <= MAX_TITLE_CHARS }
        return MineComponentScanEntry.create(
            kind = "bottom_tab",
            title = title,
            id = null,
            uri = uri,
            showing = !matches(item, fields)
        )
    }

    /** 手填规则与勾选选择器取并集。 */
    private fun matches(item: Any, fields: List<Field>): Boolean {
        val all = values(item, fields)
        if (tokens.isNotEmpty() && RuleSetCodec.matches(tokens, *all.toTypedArray())) return true
        if (selectorSet.isEmpty()) return false
        val uri = all.firstOrNull { it.contains("://") }
        val title = all.firstOrNull { !it.contains("://") && it.length <= MAX_TITLE_CHARS }
        val key = MineComponentSelector.key("bottom_tab", title, null, uri)
        return key != null && key in selectorSet
    }

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
        private const val MAX_TITLE_CHARS = 12

        internal fun canHide(total: Int, matched: Int): Boolean = matched in 1 until total
    }
}
