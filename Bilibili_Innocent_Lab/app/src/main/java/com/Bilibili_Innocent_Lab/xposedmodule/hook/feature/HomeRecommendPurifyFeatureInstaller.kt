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
            cardGoto = resolveOptional(environment, "card_goto", adapted.cardGotoGetter),
            goTo = resolveOptional(environment, "goto", adapted.goToGetter),
            uri = resolveOptional(environment, "uri", adapted.uriGetter),
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
                        val filtered = CopyOnFilter.list(source) { item ->
                            shouldRemove(signals(item, accessors))
                        }
                        if (filtered !== source) {
                            result = filtered
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

    private fun shouldRemove(signals: Signals): Boolean =
        (removeAds && isAdvertisement(signals)) ||
            (removePictures && isPicture(signals)) ||
            (removeGamePromotions && isGamePromotion(signals)) ||
            RuleSetCodec.matches(titleKeywords, signals.title) ||
            (removeLive && isLive(signals)) ||
            (removeCourses && isCourse(signals)) ||
            (removeVertical && isVertical(signals)) ||
            (removeLarge && isLarge(signals)) ||
            durationRange.shouldRemove(signals.durationSeconds)

    private fun signals(item: Any, accessors: Accessors): Signals {
        val needsRoute = removePictures || removeGamePromotions || removeLive ||
            removeCourses || removeVertical || removeLarge
        return Signals(
            holderType = if (removeAds || removeLarge) {
                invokeString(accessors.holderType, item)
            } else {
                null
            },
            bizType = if (removeAds) invokeString(accessors.bizType, item) else null,
            cardGoto = if (removeAds || needsRoute) {
                invokeString(accessors.cardGoto, item)
            } else {
                null
            },
            goTo = if (needsRoute) invokeString(accessors.goTo, item) else null,
            uri = if (needsRoute) invokeString(accessors.uri, item) else null,
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
                    VideoDurationReader.fromField(
                        item,
                        duration.playerArgsGetter,
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
        val fieldName = points.playerArgsDurationField ?: return null
        val getter = resolve(environment, "player_args", getterPoint) ?: return null
        val field = environment.hookPoints.resolveField(
            "home.recommend.resolve.player_args_duration",
            getter.returnType,
            fieldName
        ) ?: return null
        return DurationAccessor(getter, field)
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
        val cardGoto: String? = null,
        val goTo: String? = null,
        val uri: String? = null,
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
        val cardGoto: Method?,
        val goTo: Method?,
        val uri: Method?,
        val title: Method?,
        val subtitle: Method?,
        val desc: Method?,
        val duration: DurationAccessor?
    )

    private data class DurationAccessor(
        val playerArgsGetter: Method,
        val durationField: Field
    )

    companion object {
        const val ID = "home_recommend_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_recommend_purify_status"

        internal fun isAdvertisement(value: Signals): Boolean {
            val holder = value.holderType.orEmpty().lowercase()
            val biz = value.bizType.orEmpty()
            val cardGoto = value.cardGoto.orEmpty().lowercase()
            return value.hasAdInfo || biz.equals("AD", ignoreCase = true) ||
                holder == "banner_v8" || holder.startsWith("cm_v2") ||
                cardGoto.startsWith("ad_")
        }

        internal fun isPicture(value: Signals): Boolean =
            value.cardGoto.equals("picture", ignoreCase = true) ||
                value.goTo.equals("picture", ignoreCase = true) ||
                value.uri?.startsWith("bilibili://opus/", ignoreCase = true) == true

        internal fun isGamePromotion(value: Signals): Boolean {
            val route = listOf(value.cardGoto, value.goTo, value.uri)
                .joinToString(" ")
                .lowercase()
            if (GAME_ROUTE_MARKERS.any(route::contains)) return true
            if (!value.hasAdInfo) return false
            val text = listOf(value.title, value.subtitle, value.desc)
                .joinToString(" ")
                .lowercase()
            return GAME_TEXT_MARKERS.any(text::contains)
        }

        internal fun isLive(value: Signals): Boolean =
            value.cardGoto.equals("live", ignoreCase = true) ||
                value.goTo.equals("live", ignoreCase = true) ||
                value.uri?.contains("live.bilibili.com/", ignoreCase = true) == true ||
                value.uri?.startsWith("bilibili://live/", ignoreCase = true) == true

        internal fun isCourse(value: Signals): Boolean =
            value.cardGoto.equals("ketang", ignoreCase = true) ||
                value.goTo.equals("ketang", ignoreCase = true) ||
                value.cardGoto.equals("cheese", ignoreCase = true) ||
                value.goTo.equals("cheese", ignoreCase = true) ||
                value.uri?.contains("/cheese/play/", ignoreCase = true) == true

        internal fun isVertical(value: Signals): Boolean =
            value.cardGoto.equals("vertical_av", ignoreCase = true) ||
                value.goTo.equals("vertical_av", ignoreCase = true) ||
                value.uri?.startsWith("bilibili://story/", ignoreCase = true) == true

        internal fun isLarge(value: Signals): Boolean {
            val holder = value.holderType.orEmpty()
            return holder.startsWith("large_cover", ignoreCase = true)
        }

        private val GAME_ROUTE_MARKERS = listOf(
            "game_center",
            "mini_game",
            "h5_game",
            "biligame",
            "game.bilibili",
            "promotion"
        )
        private val GAME_TEXT_MARKERS = listOf("小游戏", "游戏中心", "试玩", "game")
    }
}
