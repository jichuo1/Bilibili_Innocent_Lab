package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 动态页净化：在页签添加与位置映射边界同步移除“同城/校园”。 */
internal class DynamicTabsFeatureInstaller(
    private val hideCity: Boolean,
    private val hideSchool: Boolean,
    private val preferVideo: Boolean,
    private val point: VersionAdapter.DynamicTabsPoint?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!hideCity && !hideSchool && !preferVideo) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = point ?: return missing(environment, "missing-adapter-point")
        val titleField = environment.hookPoints.resolveField(
            "dynamic.tabs.item_title",
            adapted.itemClassName,
            adapted.itemTitleField
        ) ?: return missing(environment, "missing-title-field")
        val nameField = environment.hookPoints.resolveField(
            "dynamic.tabs.item_name",
            adapted.itemClassName,
            adapted.itemNameField
        ) ?: return missing(environment, "missing-name-field")
        val customViewGetter = environment.hookPoints.resolveAdapted(
            "dynamic.tabs.custom_view",
            adapted.tabCustomViewGetter.className,
            adapted.tabCustomViewGetter.methodName,
            adapted.tabCustomViewGetter.paramClassNames
        ) ?: return missing(environment, "missing-custom-view-getter")

        val forcedVisibleKinds = ConcurrentHashMap.newKeySet<HiddenKind>()
        val videoAvailable = AtomicBoolean(false)
        val cityLogged = AtomicBoolean(false)
        val schoolLogged = AtomicBoolean(false)
        val videoPreferredLogged = AtomicBoolean(false)
        var installedCount = 0

        val listInstalled = runCatching {
            environment.registrar.adapted("dynamic.tabs.list", adapted.listGetter) {
                after {
                    val source = result as? List<*> ?: return@after
                    var hasVideo = false
                    val filtered = CopyOnFilter.list(source) { item ->
                        val title = runCatching { titleField.get(item) as? String }.getOrNull()
                        val name = runCatching { nameField.get(item) as? String }.getOrNull()
                        if (isVideoTab(title, name)) hasVideo = true
                        val hidden = hiddenKind(title, name, hideCity, hideSchool)
                        hidden != null && hidden !in forcedVisibleKinds
                    }
                    if (preferVideo) videoAvailable.set(hasVideo)
                    if (filtered !== source && filtered.isNotEmpty()) {
                        result = filtered
                    }
                }
            }
            installedCount += 1
        }.isSuccess

        val addInstalled = runCatching {
            environment.registrar.adapted("dynamic.tabs.add", adapted.addTab) {
                before {
                    if (instance?.javaClass?.name != adapted.mediatorTabClassName) return@before
                    val tab = args.firstOrNull() ?: return@before
                    val customView = runCatching {
                        customViewGetter.invoke(tab) as? View
                    }.getOrNull() ?: return@before
                    val label = findTabLabel(customView) ?: return@before
                    val originalSelected = args.getOrNull(1) as? Boolean ?: false
                    val preferredSelected = selectedForVideoPreference(
                        label = label,
                        videoAvailable = videoAvailable.get(),
                        preferVideo = preferVideo,
                        originalSelected = originalSelected
                    )
                    if (preferredSelected != originalSelected) args[1] = preferredSelected
                    if (preferredSelected && preferVideo && isVideoTab(label, null) &&
                        videoPreferredLogged.compareAndSet(false, true)
                    ) {
                        environment.logInfo(
                            "dynamic_tab_preferred_video",
                            "[BIL] 动态页已将“视频”设为初始选中标签"
                        )
                    }
                    val hidden = hiddenKind(label, null, hideCity, hideSchool) ?: return@before
                    val selected = args.getOrNull(1) as? Boolean ?: false
                    if (selected) {
                        // 服务端若把待隐藏标签设为默认项则故障开放，避免无选中页或映射错位。
                        forcedVisibleKinds += hidden
                        environment.logError(
                            "dynamic_tabs_default_visible",
                            "[BIL] 动态页待隐藏标签“$label”是默认项，本次保留以避免页签错位"
                        )
                        return@before
                    }
                    result = null
                    val first = when (hidden) {
                        HiddenKind.CITY -> cityLogged.compareAndSet(false, true)
                        HiddenKind.SCHOOL -> schoolLogged.compareAndSet(false, true)
                    }
                    if (first) {
                        environment.logInfo(
                            "dynamic_tab_hidden_${hidden.name.lowercase()}",
                            "[BIL] 已隐藏动态页标签“$label”"
                        )
                    }
                }
            }
            installedCount += 1
        }.isSuccess

        if (!listInstalled || !addInstalled) {
            val reason = buildString {
                append("partial:")
                if (!listInstalled) append("list")
                if (!listInstalled && !addInstalled) append(',')
                if (!addInstalled) append("add")
            }
            environment.reportStatus(CHANNEL_STATUS, reason)
            environment.logError(
                "dynamic_tabs_partial",
                "[BIL] 动态页标签净化 Hook 未完整命中: $reason"
            )
            return FeatureInstallResult.Skipped(reason)
        }
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo(
            "dynamic_tabs_ok",
            "[BIL] 动态页标签净化已安装，hooks=$installedCount"
        )
        return FeatureInstallResult.Installed(installedCount)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("dynamic_tabs_missing", "[BIL] 动态页标签适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "dynamic_tabs_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "dynamic_tabs_status"
        private const val MAX_TAB_VIEW_NODES = 24

        internal enum class HiddenKind { CITY, SCHOOL }

        internal fun hiddenKind(
            title: String?,
            name: String?,
            hideCity: Boolean,
            hideSchool: Boolean
        ): HiddenKind? {
            val normalizedTitle = title?.trim()
            val normalizedName = name?.trim()?.lowercase()
            if (hideCity && (normalizedTitle == "同城" || normalizedName in CITY_NAMES)) {
                return HiddenKind.CITY
            }
            if (hideSchool && (normalizedTitle == "校园" || normalizedName in SCHOOL_NAMES)) {
                return HiddenKind.SCHOOL
            }
            return null
        }

        internal fun isVideoTab(title: String?, name: String?): Boolean =
            title?.trim() == "视频" || name?.trim()?.lowercase() == "video"

        internal fun selectedForVideoPreference(
            label: String?,
            videoAvailable: Boolean,
            preferVideo: Boolean,
            originalSelected: Boolean
        ): Boolean = if (preferVideo && videoAvailable) {
            isVideoTab(label, null)
        } else {
            originalSelected
        }

        private fun findTabLabel(root: View): String? {
            val pending = ArrayDeque<View>()
            pending.add(root)
            var visited = 0
            while (pending.isNotEmpty() && visited < MAX_TAB_VIEW_NODES) {
                val view = pending.removeFirst()
                visited += 1
                if (view is TextView) {
                    view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
                }
            }
            return null
        }

        private val CITY_NAMES = setOf("city", "same_city", "nearby")
        private val SCHOOL_NAMES = setOf("school", "campus")
    }
}
