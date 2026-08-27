package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 在首页推荐响应的公开 List 边界过滤广告、图文和游戏推广。 */
internal class HomeRecommendPurifyFeatureInstaller(
    private val removeAds: Boolean,
    private val removePictures: Boolean,
    private val removeGamePromotions: Boolean,
    private val points: VersionAdapter.HomeRecommendFeedPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!removeAds && !removePictures && !removeGamePromotions) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
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
            desc = resolveOptional(environment, "desc", adapted.descGetter)
        )

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("home.recommend.purify.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        if (source.isEmpty()) return@after
                        val filtered = ArrayList<Any?>(source.size)
                        var removed = 0
                        source.forEach { item ->
                            if (item != null && shouldRemove(signals(item, accessors))) {
                                removed += 1
                            } else {
                                filtered += item
                            }
                        }
                        if (removed > 0) {
                            result = filtered
                            environment.logInfo(
                                "home_recommend_removed",
                                "[BIL] 首页推荐服务端数据已过滤 $removed 项"
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
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo(
            "home_recommend_purify_ok",
            "[BIL] 首页推荐服务端过滤已安装，hooks=$installed"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun shouldRemove(signals: Signals): Boolean =
        (removeAds && isAdvertisement(signals)) ||
            (removePictures && isPicture(signals)) ||
            (removeGamePromotions && isGamePromotion(signals))

    private fun signals(item: Any, accessors: Accessors): Signals = Signals(
        holderType = invokeString(accessors.holderType, item),
        bizType = invokeString(accessors.bizType, item),
        cardGoto = invokeString(accessors.cardGoto, item),
        goTo = invokeString(accessors.goTo, item),
        uri = invokeString(accessors.uri, item),
        title = invokeString(accessors.title, item),
        subtitle = invokeString(accessors.subtitle, item),
        desc = invokeString(accessors.desc, item),
        hasAdInfo = invokeCompatible(accessors.adInfo, item) != null
    )

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
        val hasAdInfo: Boolean = false
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
        val desc: Method?
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
