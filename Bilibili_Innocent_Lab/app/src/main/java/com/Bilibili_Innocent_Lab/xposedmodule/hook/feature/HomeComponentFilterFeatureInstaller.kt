package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** 仅对父 Fragment 链属于首页容器的子 Fragment 按类名规则隐藏根 View。 */
internal class HomeComponentFilterFeatureInstaller(
    rules: String,
    selectors: String = "",
    private val points: VersionAdapter.HomeComponentPoints?
) : FeatureInstaller {

    override val id: String = ID
    private val tokens = RuleSetCodec.parse(rules)
    private val selectorSet = MineComponentSelectionCodec.decode(selectors)

    private fun hasHiddenConfiguration(): Boolean =
        tokens.isNotEmpty() || selectorSet.isNotEmpty()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        // 空配置也要装：不扫描就产不出勾选列表。
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val parentGetter = environment.hookPoints.resolveAdapted(
            "home.components.parent",
            adapted.parentFragmentGetter.className,
            adapted.parentFragmentGetter.methodName,
            adapted.parentFragmentGetter.paramClassNames
        ) ?: return missing(environment, "missing-parent-getter")

        val publisher = ScanSnapshotPublisher(
            environment,
            MineComponentSnapshotCodec.SURFACE_HOME_COMPONENTS,
            setOf("home_component_filter")
        )
        val firstObserved = AtomicBoolean(false)
        val firstApplied = AtomicBoolean(false)
        return runCatching {
            environment.registrar.adapted("home.components.view", adapted.onViewCreated) {
                after {
                    val fragment = instance ?: return@after
                    val root = args.firstOrNull() as? View ?: return@after
                    val className = fragment.javaClass.name
                    if (!isCandidate(className) || !isHomeChild(fragment, parentGetter)) {
                        return@after
                    }
                    if (firstObserved.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED, 1)
                        environment.reportStatus(CHANNEL_STATUS, "observed:path=component,count=1")
                    }
                    val hide = matches(className)
                    // 标识只有混淆类名，另外摸一个可读标题当副标题；摸不到就只显示类名。
                    publisher.accumulate(
                        MineComponentScanEntry.create(
                            kind = "home_component",
                            title = firstText(root, 0),
                            id = className,
                            uri = null,
                            showing = !hide
                        )
                    )
                    if (hide) {
                        val changed = root.visibility != View.GONE
                        root.visibility = View.GONE
                        if (changed && firstApplied.compareAndSet(false, true)) {
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, 1)
                            environment.reportStatus(CHANNEL_STATUS, "applied:path=component,count=1")
                        }
                    }
                }
            }
            val mode = if (hasHiddenConfiguration()) "filter+scan" else "scan"
            environment.reportStatus(CHANNEL_STATUS, "installed:mode=$mode")
            environment.logInfo("home_components_ok", "[BIL] 首页组件自定义隐藏已安装($mode)")
            FeatureInstallResult.Installed()
        }.getOrElse { throwable ->
            environment.logError(
                "home_components_error",
                "[BIL] 首页组件自定义隐藏 Hook 注册失败: $throwable"
            )
            missing(environment, "registration-failed")
        }
    }

    private fun isHomeChild(fragment: Any, parentGetter: Method): Boolean {
        var current: Any? = invokeParent(parentGetter, fragment)
        repeat(MAX_PARENT_DEPTH) {
            val parent = current ?: return false
            if (isHomeContainer(parent.javaClass)) return true
            current = invokeParent(parentGetter, parent)
        }
        return false
    }

    private fun invokeParent(method: Method, target: Any): Any? = runCatching {
        method.invoke(target)
    }.getOrNull()

    /** 手填规则与勾选选择器取并集。 */
    private fun matches(className: String): Boolean {
        if (tokens.isNotEmpty() &&
            RuleSetCodec.matches(tokens, className, className.substringAfterLast('.'))
        ) return true
        if (selectorSet.isEmpty()) return false
        val key = MineComponentSelector.key("home_component", null, className, null)
        return key != null && key in selectorSet
    }

    /** 一次性、深度受限地摸一个可读标题；只在扫描时走，不进任何逐帧路径。 */
    private fun firstText(view: View, depth: Int): String? {
        if (depth > MAX_TITLE_DEPTH) return null
        if (view is android.widget.TextView) {
            val text = view.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && text.length <= MAX_TITLE_CHARS) return text
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index) ?: continue
                firstText(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun isCandidate(className: String): Boolean {
        val lower = className.lowercase()
        if (!lower.startsWith("com.bilibili") && !lower.startsWith("tv.danmaku")) return false
        return EXCLUDED_MARKERS.none(lower::contains)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "home_components_missing",
            "[BIL] 首页组件自定义隐藏适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "home_component_filter"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_component_filter_status"
        private const val MAX_TITLE_DEPTH = 6
        private const val MAX_TITLE_CHARS = 24
        private const val MAX_PARENT_DEPTH = 12
        private val HOME_CONTAINER_MARKERS = listOf(
            "basehomefragment",
            "homefragmentv2",
            "main2.homefragment",
            "pagebuildcomponent"
        )
        private val EXCLUDED_MARKERS = listOf(
            "search",
            "dynamic",
            "following",
            "history",
            "favorite",
            "detail",
            "mine",
            "mainfragment"
        )

        /**
         * 父 Fragment 的**类继承链**里任一类名命中容器标记即算首页容器。
         *
         * 只看运行时类名会漏掉新首页框架：那边首页页面的运行时类是
         * `tv.danmaku.bili.home.tab.page.HomeFragment`，标记 `basehomefragment` 对应的是它的
         * 父类 `BaseHomeFragment`（8.92.1–9.12.0 全宿主里唯一名字含它的类）。
         * 旧框架的 `HomeFragmentV2` 父链上没有别的类名会因此新命中。
         */
        internal fun isHomeContainer(type: Class<*>): Boolean =
            generateSequence(type) { it.superclass }
                .take(MAX_CONTAINER_HIERARCHY)
                .any { owner ->
                    val name = owner.name.lowercase()
                    HOME_CONTAINER_MARKERS.any(name::contains)
                }

        private const val MAX_CONTAINER_HIERARCHY = 8

        internal fun isClassMatched(rules: String, className: String): Boolean =
            isStaticCandidate(className) && RuleSetCodec.matches(
                RuleSetCodec.parse(rules),
                className,
                className.substringAfterLast('.')
            )

        private fun isStaticCandidate(className: String): Boolean {
            val lower = className.lowercase()
            return (lower.startsWith("com.bilibili") || lower.startsWith("tv.danmaku")) &&
                EXCLUDED_MARKERS.none(lower::contains)
        }
    }
}
