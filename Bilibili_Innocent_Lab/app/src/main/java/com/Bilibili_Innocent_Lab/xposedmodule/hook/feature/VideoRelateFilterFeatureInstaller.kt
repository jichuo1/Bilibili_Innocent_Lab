package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 按相关推荐卡片的公开枚举/路由类型做精确过滤。 */
internal class VideoRelateFilterFeatureInstaller(
    private val hiddenTypes: Set<String>,
    private val points: VersionAdapter.VideoRelatePoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val normalizedHidden = hiddenTypes.mapTo(linkedSetOf(), ::normalizeType)
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (normalizedHidden.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val typeMethods = buildList {
            adapted.cardCaseGetters.forEachIndexed { index, point ->
                resolve(environment, "case.$index", point)?.let(::add)
            }
            adapted.gotoGetters.forEachIndexed { index, point ->
                resolve(environment, "goto.$index", point)?.let(::add)
            }
            adapted.cardTypeGetters.forEachIndexed { index, point ->
                resolve(environment, "type.$index", point)?.let(::add)
            }
        }.distinctBy(Method::toGenericString)
        if (typeMethods.isEmpty()) return missing(environment, "missing-type-getter")

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("video.relate.response.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        if (source.isEmpty()) return@after
                        val filtered = ArrayList<Any?>(source.size)
                        var removed = 0
                        source.forEach { item ->
                            val type = item?.let { extractType(it, typeMethods) }
                            if (type != null && normalizeType(type) in normalizedHidden) {
                                removed += 1
                            } else {
                                filtered += item
                            }
                        }
                        if (removed > 0) {
                            result = filtered
                            environment.logInfo(
                                "video_relate_removed",
                                "[BIL] 视频相关推荐精确类型过滤已移除 $removed 项"
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "video_relate_$index",
                    "[BIL] 视频相关推荐类型过滤 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo(
            "video_relate_ok",
            "[BIL] 视频相关推荐精确类型过滤已安装，types=$normalizedHidden"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun extractType(item: Any, methods: List<Method>): String? {
        methods.forEach { method ->
            if (!method.declaringClass.isInstance(item)) return@forEach
            val raw = runCatching { method.invoke(item) }.getOrNull() ?: return@forEach
            val value = (raw as? Enum<*>)?.name ?: raw.toString()
            val normalized = normalizeType(value)
            if (normalized.isNotBlank() && normalized !in UNKNOWN_TYPES) return normalized
        }
        return null
    }

    private fun resolve(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint
    ): Method? = environment.hookPoints.resolveAdapted(
        "video.relate.resolve.$suffix",
        point.className,
        point.methodName,
        point.paramClassNames
    )

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "video_relate_missing",
            "[BIL] 视频相关推荐类型过滤适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "video_relate_filter"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "video_relate_filter_status"
        private val UNKNOWN_TYPES = setOf("UNKNOWN", "CARD_NOT_SET")

        internal fun normalizeType(raw: String): String = raw.trim().uppercase()
            .removePrefix("CARD_TYPE_")

        internal fun shouldRemove(type: String?, hiddenTypes: Set<String>): Boolean =
            type != null && hiddenTypes.any { normalizeType(it) == normalizeType(type) }
    }
}
