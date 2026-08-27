package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 在 Story 响应与播放器列表边界按 StoryDetail 的公开类型判断过滤。 */
internal class StoryPurifyFeatureInstaller(
    private val removeAds: Boolean,
    private val removeLive: Boolean,
    private val removeGames: Boolean,
    private val removeBangumi: Boolean,
    private val removeCourses: Boolean,
    private val removeShortDrama: Boolean,
    private val removeShopping: Boolean,
    private val removeMovies: Boolean,
    private val removeDocumentaries: Boolean,
    private val removeTv: Boolean,
    private val removeVariety: Boolean,
    private val removeMusic: Boolean,
    private val points: VersionAdapter.StoryFeedPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!removeAds && !removeLive && !removeGames && !removeBangumi && !removeCourses &&
            !removeShortDrama && !removeShopping && !removeMovies && !removeDocumentaries &&
            !removeTv && !removeVariety && !removeMusic) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val accessors = Accessors(
            ad = resolveOptional(environment, "ad", adapted.adGetter),
            live = resolveOptional(environment, "live", adapted.liveGetter),
            game = resolveOptional(environment, "game", adapted.gameGetter),
            bangumi = resolveOptional(environment, "bangumi", adapted.bangumiGetter),
            course = resolveOptional(environment, "course", adapted.courseGetter),
            music = resolveOptional(environment, "music", adapted.musicGetter),
            cartInfo = resolveOptional(environment, "cart_info", adapted.cartInfoGetter),
            dramaPrompt = resolveOptional(environment, "drama_prompt", adapted.dramaPromptGetter),
            seasonInfo = resolveOptional(environment, "season_info", adapted.seasonInfoGetter),
            seasonType = resolveOptional(environment, "season_type", adapted.seasonTypeGetter)
        )
        val removeSpecificSeason = removeMovies || removeDocumentaries || removeTv || removeVariety
        if ((removeAds && accessors.ad == null) ||
            (removeLive && accessors.live == null) ||
            (removeGames && accessors.game == null) ||
            (removeBangumi && accessors.bangumi == null) ||
            (removeCourses && accessors.course == null) ||
            (removeShortDrama && accessors.dramaPrompt == null) ||
            (removeShopping && accessors.cartInfo == null) ||
            (removeMusic && accessors.music == null) ||
            (removeSpecificSeason &&
                (accessors.seasonInfo == null || accessors.seasonType == null))) {
            return missing(environment, "missing-enabled-type-getter")
        }

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("story.response.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        filter(source, accessors)?.let { filtered -> result = filtered }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "story_response_$index",
                    "[BIL] Story 响应过滤 Hook 注册失败: $throwable"
                )
            }
        }
        adapted.pagerListMethods.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("story.pager.$index", point) {
                    before {
                        val source = args.firstOrNull() as? List<*> ?: return@before
                        filter(source, accessors)?.let { filtered -> args[0] = filtered }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "story_pager_$index",
                    "[BIL] Story 播放器过滤 Hook 注册失败: $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo("story_purify_ok", "[BIL] Story 竖屏视频净化已安装")
        return FeatureInstallResult.Installed(installed)
    }

    private fun filter(source: List<*>, accessors: Accessors): List<*>? {
        val filtered = CopyOnFilter.list(source) { item -> shouldRemove(signals(item, accessors)) }
        return filtered.takeIf { it !== source }
    }

    private fun signals(item: Any, accessors: Accessors): Signals = Signals(
        ad = removeAds && invokeBoolean(accessors.ad, item),
        live = removeLive && invokeBoolean(accessors.live, item),
        game = removeGames && invokeBoolean(accessors.game, item),
        bangumi = removeBangumi && invokeBoolean(accessors.bangumi, item),
        course = removeCourses && invokeBoolean(accessors.course, item),
        shortDrama = removeShortDrama && invokePresent(accessors.dramaPrompt, item),
        shopping = removeShopping && invokePresent(accessors.cartInfo, item),
        music = removeMusic && invokeBoolean(accessors.music, item),
        seasonType = if (removeMovies || removeDocumentaries || removeTv || removeVariety) {
            invokeInt(accessors.seasonType, invokeCompatible(accessors.seasonInfo, item))
        } else {
            null
        }
    )

    private fun shouldRemove(signals: Signals): Boolean =
        (removeAds && signals.ad) ||
            (removeLive && signals.live) ||
            (removeGames && signals.game) ||
            (removeBangumi && signals.bangumi) ||
            (removeCourses && signals.course) ||
            (removeShortDrama && signals.shortDrama) ||
            (removeShopping && signals.shopping) ||
            (removeMusic && signals.music) ||
            (removeMovies && signals.seasonType == SEASON_TYPE_MOVIE) ||
            (removeDocumentaries && signals.seasonType == SEASON_TYPE_DOCUMENTARY) ||
            (removeTv && signals.seasonType == SEASON_TYPE_TV) ||
            (removeVariety && signals.seasonType == SEASON_TYPE_VARIETY)

    private fun resolveOptional(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint?
    ): Method? = point?.let {
        environment.hookPoints.resolveAdapted(
            "story.resolve.$suffix",
            it.className,
            it.methodName,
            it.paramClassNames
        )
    }

    private fun invokeBoolean(method: Method?, target: Any): Boolean {
        return (invokeCompatible(method, target) as? Boolean) == true
    }

    private fun invokePresent(method: Method?, target: Any): Boolean =
        invokeCompatible(method, target) != null

    private fun invokeInt(method: Method?, target: Any?): Int? =
        invokeCompatible(method, target) as? Int

    private fun invokeCompatible(method: Method?, target: Any?): Any? {
        if (method == null || target == null || !method.declaringClass.isInstance(target)) return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("story_purify_missing", "[BIL] Story 净化适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    internal data class Signals(
        val ad: Boolean = false,
        val live: Boolean = false,
        val game: Boolean = false,
        val bangumi: Boolean = false,
        val course: Boolean = false,
        val shortDrama: Boolean = false,
        val shopping: Boolean = false,
        val music: Boolean = false,
        val seasonType: Int? = null
    )

    private data class Accessors(
        val ad: Method?,
        val live: Method?,
        val game: Method?,
        val bangumi: Method?,
        val course: Method?,
        val music: Method?,
        val cartInfo: Method?,
        val dramaPrompt: Method?,
        val seasonInfo: Method?,
        val seasonType: Method?
    )

    companion object {
        const val ID = "story_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "story_purify_status"
        internal const val SEASON_TYPE_MOVIE = 2
        internal const val SEASON_TYPE_DOCUMENTARY = 3
        internal const val SEASON_TYPE_TV = 5
        internal const val SEASON_TYPE_VARIETY = 7

        internal fun shouldRemove(
            signals: Signals,
            removeAds: Boolean,
            removeLive: Boolean,
            removeGames: Boolean,
            removeBangumi: Boolean = false,
            removeCourses: Boolean = false,
            removeShortDrama: Boolean = false,
            removeShopping: Boolean = false,
            removeMovies: Boolean = false,
            removeDocumentaries: Boolean = false,
            removeTv: Boolean = false,
            removeVariety: Boolean = false,
            removeMusic: Boolean = false
        ): Boolean = (removeAds && signals.ad) ||
            (removeLive && signals.live) ||
            (removeGames && signals.game) ||
            (removeBangumi && signals.bangumi) ||
            (removeCourses && signals.course) ||
            (removeShortDrama && signals.shortDrama) ||
            (removeShopping && signals.shopping) ||
            (removeMusic && signals.music) ||
            (removeMovies && signals.seasonType == SEASON_TYPE_MOVIE) ||
            (removeDocumentaries && signals.seasonType == SEASON_TYPE_DOCUMENTARY) ||
            (removeTv && signals.seasonType == SEASON_TYPE_TV) ||
            (removeVariety && signals.seasonType == SEASON_TYPE_VARIETY)
    }
}
