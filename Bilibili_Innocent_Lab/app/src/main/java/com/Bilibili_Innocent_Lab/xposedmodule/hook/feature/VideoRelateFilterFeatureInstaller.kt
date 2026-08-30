package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 按相关推荐卡片的公开枚举/路由类型做精确过滤。 */
internal class VideoRelateFilterFeatureInstaller(
    private val hiddenTypes: Set<String>,
    minDurationSeconds: Int,
    maxDurationSeconds: Int,
    private val points: VersionAdapter.VideoRelatePoints?
) : FeatureInstaller {

    private val durationRange = VideoDurationRange(minDurationSeconds, maxDurationSeconds)

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val normalizedHidden = hiddenTypes.mapTo(linkedSetOf(), ::normalizeType)
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (durationRange.isConfigured && !durationRange.isValid) {
            environment.logError(
                "video_relate_duration_invalid",
                "[BIL] 推荐视频时长范围无效，已保守放行: " +
                    "min=${durationRange.minSeconds},max=${durationRange.maxSeconds}"
            )
        }
        if (normalizedHidden.isEmpty() && !durationRange.isEnabled) {
            val reason = if (durationRange.isConfigured && !durationRange.isValid) {
                "invalid-duration-range"
            } else {
                "disabled"
            }
            environment.reportStatus(CHANNEL_STATUS, reason)
            return FeatureInstallResult.Skipped(reason)
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val typeMethods = if (normalizedHidden.isNotEmpty()) {
            buildList {
                adapted.cardCaseGetters.forEachIndexed { index, point ->
                    resolve(environment, "case.$index", point)?.let(::add)
                }
                adapted.gotoGetters.forEachIndexed { index, point ->
                    resolve(environment, "goto.$index", point)?.let(::add)
                }
                adapted.cardTypeGetters.forEachIndexed { index, point ->
                    resolve(environment, "type.$index", point)?.let(::add)
                }
            }
                .distinctBy(Method::toGenericString)
        } else {
            emptyList()
        }
        val durationPaths = resolveDurationPaths(environment, adapted)
        var partialReason: String? = null
        if (normalizedHidden.isNotEmpty() && typeMethods.isEmpty()) {
            if (!durationRange.isEnabled || durationPaths.isEmpty()) {
                return missing(environment, "missing-type-getter")
            }
            partialReason = "missing-type-getter"
            environment.logError(
                "video_relate_type_missing",
                "[BIL] 详情页推荐类型读取适配不完整，时长过滤继续生效"
            )
        }
        if (durationRange.isEnabled && durationPaths.isEmpty()) {
            if (typeMethods.isEmpty()) return missing(environment, "missing-duration-accessor")
            partialReason = "missing-duration-accessor"
            environment.logError(
                "video_relate_duration_missing",
                "[BIL] 详情页推荐时长读取适配不完整，现有类型过滤继续生效"
            )
        }

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("video.relate.response.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        val filtered = CopyOnFilter.list(source) { item ->
                            val typeMatched = if (normalizedHidden.isNotEmpty()) {
                                extractType(item, typeMethods)?.let { type ->
                                    normalizeType(type) in normalizedHidden
                                } == true
                            } else {
                                false
                            }
                            typeMatched || durationRange.shouldRemove(
                                VideoDurationReader.fromMethods(item, durationPaths)
                            )
                        }
                        if (filtered !== source) {
                            result = filtered
                            environment.logInfo(
                                "video_relate_removed",
                                "[BIL] 视频相关推荐过滤已移除 " +
                                    "${source.size - filtered.size} 项"
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
        environment.reportStatus(
            CHANNEL_STATUS,
            partialReason?.let { "partial:$it" } ?: "success"
        )
        environment.logInfo(
            "video_relate_ok",
            "[BIL] 视频相关推荐过滤已安装，types=$normalizedHidden," +
                "duration=${durationRange.isEnabled}"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun resolveDurationPaths(
        environment: HookEnvironment,
        points: VersionAdapter.VideoRelatePoints
    ): List<VideoDurationMethodPath> {
        if (!durationRange.isEnabled) return emptyList()
        val direct = points.directDurationGetters.mapIndexedNotNull { index, point ->
            resolve(environment, "duration.direct.$index", point)
        }
        val chains = points.durationChains.mapIndexedNotNull { index, chain ->
            val itemGetter = resolve(environment, "duration.item.$index", chain.itemGetter)
                ?: return@mapIndexedNotNull null
            val durationGetter = resolve(
                environment,
                "duration.value.$index",
                chain.durationGetter
            ) ?: return@mapIndexedNotNull null
            itemGetter to durationGetter
        }
        return VideoDurationReader.buildMethodPaths(direct, chains)
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
