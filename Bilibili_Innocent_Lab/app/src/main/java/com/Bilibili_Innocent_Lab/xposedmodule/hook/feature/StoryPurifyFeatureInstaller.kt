package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 在 Story 响应与播放器列表边界按 StoryDetail 的公开类型判断过滤。 */
internal class StoryPurifyFeatureInstaller(
    private val removeAds: Boolean,
    private val removeLive: Boolean,
    private val removeGames: Boolean,
    private val points: VersionAdapter.StoryFeedPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!removeAds && !removeLive && !removeGames) {
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
            game = resolveOptional(environment, "game", adapted.gameGetter)
        )
        if ((removeAds && accessors.ad == null) ||
            (removeLive && accessors.live == null) ||
            (removeGames && accessors.game == null)) {
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

    private fun filter(source: List<*>, accessors: Accessors): ArrayList<Any?>? {
        if (source.isEmpty()) return null
        val filtered = ArrayList<Any?>(source.size)
        var removed = 0
        source.forEach { item ->
            if (item != null && shouldRemove(signals(item, accessors))) {
                removed += 1
            } else {
                filtered += item
            }
        }
        return filtered.takeIf { removed > 0 }
    }

    private fun signals(item: Any, accessors: Accessors): Signals = Signals(
        ad = invokeBoolean(accessors.ad, item),
        live = invokeBoolean(accessors.live, item),
        game = invokeBoolean(accessors.game, item)
    )

    private fun shouldRemove(signals: Signals): Boolean =
        (removeAds && signals.ad) ||
            (removeLive && signals.live) ||
            (removeGames && signals.game)

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
        if (method == null || !method.declaringClass.isInstance(target)) return false
        return runCatching { method.invoke(target) as? Boolean }.getOrNull() == true
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
        val game: Boolean = false
    )

    private data class Accessors(
        val ad: Method?,
        val live: Method?,
        val game: Method?
    )

    companion object {
        const val ID = "story_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "story_purify_status"

        internal fun shouldRemove(
            signals: Signals,
            removeAds: Boolean,
            removeLive: Boolean,
            removeGames: Boolean
        ): Boolean = (removeAds && signals.ad) ||
            (removeLive && signals.live) ||
            (removeGames && signals.game)
    }
}
