package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 仅对父 Fragment 链属于首页容器的子 Fragment 按类名规则隐藏根 View。 */
internal class HomeComponentFilterFeatureInstaller(
    rules: String,
    private val points: VersionAdapter.HomeComponentPoints?
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
        val parentGetter = environment.hookPoints.resolveAdapted(
            "home.components.parent",
            adapted.parentFragmentGetter.className,
            adapted.parentFragmentGetter.methodName,
            adapted.parentFragmentGetter.paramClassNames
        ) ?: return missing(environment, "missing-parent-getter")

        return runCatching {
            environment.registrar.adapted("home.components.view", adapted.onViewCreated) {
                after {
                    val fragment = instance ?: return@after
                    val root = args.firstOrNull() as? View ?: return@after
                    val className = fragment.javaClass.name
                    if (!isCandidate(className) || !isHomeChild(fragment, parentGetter)) {
                        return@after
                    }
                    if (RuleSetCodec.matches(tokens, className, className.substringAfterLast('.'))) {
                        root.visibility = View.GONE
                    }
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "success")
            environment.logInfo("home_components_ok", "[BIL] 首页组件自定义隐藏已安装")
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
            val name = parent.javaClass.name.lowercase()
            if (HOME_CONTAINER_MARKERS.any(name::contains)) return true
            current = invokeParent(parentGetter, parent)
        }
        return false
    }

    private fun invokeParent(method: Method, target: Any): Any? = runCatching {
        method.invoke(target)
    }.getOrNull()

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
