package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 在首页推荐响应的公开 List 边界过滤广告、图文和游戏推广。 */
internal class HomeRecommendPurifyFeatureInstaller(
    private val removeAds: Boolean,
    private val removePictures: Boolean,
    private val removeGamePromotions: Boolean,
    titleFilterEnabled: Boolean,
    rawTitleKeywords: String,
    private val removeLive: Boolean,
    private val removeCourses: Boolean,
    private val removeVertical: Boolean,
    private val removeLarge: Boolean,
    minDurationSeconds: Int,
    maxDurationSeconds: Int,
    private val points: VersionAdapter.HomeRecommendFeedPoints?
) : FeatureInstaller {

    private val titleKeywords = if (titleFilterEnabled) {
        RuleSetCodec.parse(rawTitleKeywords)
    } else {
        emptySet()
    }
    private val durationRange = VideoDurationRange(minDurationSeconds, maxDurationSeconds)

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val hasContentFilter = removeAds || removePictures || removeGamePromotions ||
            titleKeywords.isNotEmpty() || removeLive || removeCourses || removeVertical ||
            removeLarge
        if (durationRange.isConfigured && !durationRange.isValid) {
            environment.logError(
                "home_recommend_duration_invalid",
                "[BIL] 推荐视频时长范围无效，已保守放行: " +
                    "min=${durationRange.minSeconds},max=${durationRange.maxSeconds}"
            )
        }
        if (!hasContentFilter && !durationRange.isEnabled) {
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
        val accessors = Accessors(
            holderType = resolve(environment, "holder", adapted.holderTypeGetter)
                ?: return missing(environment, "missing-holder-getter"),
            bizType = resolveOptional(environment, "biz", adapted.bizTypeGetter),
            adInfo = resolveOptional(environment, "ad_info", adapted.adInfoGetter),
            cardType = resolveOptional(environment, "card_type", adapted.cardTypeGetter),
            cardGoto = resolveOptional(environment, "card_goto", adapted.cardGotoGetter),
            goTo = resolveOptional(environment, "goto", adapted.goToGetter),
            uri = resolveOptional(environment, "uri", adapted.uriGetter),
            param = resolveOptional(environment, "param", adapted.paramGetter),
            title = resolveOptional(environment, "title", adapted.titleGetter),
            subtitle = resolveOptional(environment, "subtitle", adapted.subtitleGetter),
            desc = resolveOptional(environment, "desc", adapted.descGetter),
            duration = resolveDuration(environment, adapted)
        )
        var partialReason: String? = null
        if (durationRange.isEnabled && accessors.duration == null) {
            if (!hasContentFilter) return missing(environment, "missing-duration-accessor")
            partialReason = "missing-duration-accessor"
            environment.logError(
                "home_recommend_duration_missing",
                "[BIL] 首页推荐时长读取适配不完整，其他推荐过滤继续生效"
            )
        }

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("home.recommend.purify.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val filtered = CopyOnFilter.list(source) { item ->
                            shouldRemove(signals(item, accessors))
                        }
                        if (filtered !== source) {
                            result = filtered
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                source.size - filtered.size
                            )
                            environment.logInfo(
                                "home_recommend_removed",
                                "[BIL] 首页推荐服务端数据已过滤 ${source.size - filtered.size} 项"
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_recommend_purify_$index",
                    "[BIL] 首页推荐服务端过滤 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(
            CHANNEL_STATUS,
            partialReason?.let { "partial:$it" } ?: "success"
        )
        environment.logInfo(
            "home_recommend_purify_ok",
            "[BIL] 首页推荐服务端过滤已安装，hooks=$installed," +
                "duration=${durationRange.isEnabled}"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun shouldRemove(signals: Signals): Boolean {
        val kinds = HostContentSemanticClassifier.classify(signals.toHostSignals())
        return (removeAds && HostContentKind.ADVERTISEMENT in kinds) ||
            (removePictures && HostContentKind.PICTURE in kinds) ||
            (removeGamePromotions && HostContentKind.GAME in kinds) ||
            RuleSetCodec.matches(titleKeywords, signals.title) ||
            (removeLive && HostContentKind.LIVE in kinds) ||
            (removeCourses && HostContentKind.COURSE in kinds) ||
            (removeVertical && HostContentKind.VERTICAL in kinds) ||
            (removeLarge && HostContentKind.LARGE in kinds) ||
            durationRange.shouldRemove(signals.durationSeconds)
    }

    private fun signals(item: Any, accessors: Accessors): Signals {
        val needsRoute = removePictures || removeGamePromotions || removeLive ||
            removeCourses || removeVertical || removeLarge
        val needsClassification = removeAds || needsRoute
        return Signals(
            holderType = if (removeAds || removeLarge) {
                invokeString(accessors.holderType, item)
            } else {
                null
            },
            bizType = if (removeAds) invokeString(accessors.bizType, item) else null,
            cardType = if (needsClassification) {
                invokeString(accessors.cardType, item)
            } else null,
            cardGoto = if (removeAds || needsRoute) {
                invokeString(accessors.cardGoto, item)
            } else {
                null
            },
            goTo = if (needsRoute) invokeString(accessors.goTo, item) else null,
            uri = if (needsRoute) invokeString(accessors.uri, item) else null,
            param = if (removeGamePromotions) invokeString(accessors.param, item) else null,
            title = if (titleKeywords.isNotEmpty() || removeGamePromotions) {
                invokeString(accessors.title, item)
            } else {
                null
            },
            subtitle = if (removeGamePromotions) invokeString(accessors.subtitle, item) else null,
            desc = if (removeGamePromotions) invokeString(accessors.desc, item) else null,
            hasAdInfo = (removeAds || removeGamePromotions) &&
                invokeCompatible(accessors.adInfo, item) != null,
            durationSeconds = if (durationRange.isEnabled) {
                accessors.duration?.let { duration ->
                    VideoDurationReader.fromContainer(
                        item,
                        duration.playerArgsGetter,
                        duration.durationGetter,
                        duration.durationField
                    )
                }
            } else {
                null
            }
        )
    }

    private fun resolveDuration(
        environment: HookEnvironment,
        points: VersionAdapter.HomeRecommendFeedPoints
    ): DurationAccessor? {
        if (!durationRange.isEnabled) return null
        val getterPoint = points.playerArgsGetter ?: return null
        val getter = resolve(environment, "player_args", getterPoint) ?: return null
        val durationGetter = points.playerArgsDurationGetter?.let { point ->
            resolve(environment, "player_args_duration", point)
        }
        val durationField = points.playerArgsDurationField?.let { fieldName ->
            environment.hookPoints.resolveField(
                "home.recommend.resolve.player_args_duration_field",
                getter.returnType,
                fieldName
            )
        }
        if (durationGetter == null && durationField == null) return null
        return DurationAccessor(getter, durationGetter, durationField)
    }

    private fun resolveOptional(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint?
    ): Method? = point?.let { resolve(environment, suffix, it) }

    private fun resolve(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint
    ): Method? = environment.hookPoints.resolveAdapted(
        "home.recommend.resolve.$suffix",
        point.className,
        point.methodName,
        point.paramClassNames
    )

    private fun invokeString(method: Method?, target: Any): String? =
        invokeCompatible(method, target)?.toString()

    private fun invokeCompatible(method: Method?, target: Any): Any? {
        if (method == null || !method.declaringClass.isInstance(target)) return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "home_recommend_purify_missing",
            "[BIL] 首页推荐服务端过滤适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    internal data class Signals(
        val holderType: String? = null,
        val bizType: String? = null,
        val cardType: String? = null,
        val cardGoto: String? = null,
        val goTo: String? = null,
        val uri: String? = null,
        val param: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val desc: String? = null,
        val hasAdInfo: Boolean = false,
        val durationSeconds: Long? = null
    )

    private data class Accessors(
        val holderType: Method,
        val bizType: Method?,
        val adInfo: Method?,
        val cardType: Method?,
        val cardGoto: Method?,
        val goTo: Method?,
        val uri: Method?,
        val param: Method?,
        val title: Method?,
        val subtitle: Method?,
        val desc: Method?,
        val duration: DurationAccessor?
    )

    private data class DurationAccessor(
        val playerArgsGetter: Method,
        val durationGetter: Method?,
        val durationField: Field?
    )

    companion object {
        const val ID = "home_recommend_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_recommend_purify_status"

        internal fun isAdvertisement(value: Signals): Boolean {
            return HostContentKind.ADVERTISEMENT in
                HostContentSemanticClassifier.classify(value.toHostSignals())
        }

        internal fun isPicture(value: Signals): Boolean =
            HostContentKind.PICTURE in HostContentSemanticClassifier.classify(
                value.toHostSignals()
            )

        internal fun isGamePromotion(value: Signals): Boolean =
            HostContentKind.GAME in HostContentSemanticClassifier.classify(value.toHostSignals())

        internal fun isLive(value: Signals): Boolean =
            HostContentKind.LIVE in HostContentSemanticClassifier.classify(value.toHostSignals())

        internal fun isCourse(value: Signals): Boolean =
            HostContentKind.COURSE in HostContentSemanticClassifier.classify(value.toHostSignals())

        internal fun isVertical(value: Signals): Boolean =
            HostContentKind.VERTICAL in HostContentSemanticClassifier.classify(value.toHostSignals())

        internal fun isLarge(value: Signals): Boolean =
            HostContentKind.LARGE in HostContentSemanticClassifier.classify(value.toHostSignals())

        private fun Signals.toHostSignals(): HostContentSignals = HostContentSignals(
            holderType = holderType,
            bizType = bizType,
            cardType = cardType,
            cardGoto = cardGoto,
            goTo = goTo,
            uri = uri,
            param = param,
            title = title,
            subtitle = subtitle,
            desc = desc,
            hasAdInfo = hasAdInfo
        )
    }
}
