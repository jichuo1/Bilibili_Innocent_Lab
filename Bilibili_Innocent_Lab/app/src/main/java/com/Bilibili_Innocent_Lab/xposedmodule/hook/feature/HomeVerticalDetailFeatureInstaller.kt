package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.net.Uri
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** 将首页推荐中的竖屏稿件路由改为普通视频详情页，不改变卡片内容或其它页面对象。 */
internal class HomeVerticalDetailFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.HomeRecommendFeedPoints?
) : FeatureInstaller {

    override val id: String = ID

    private val verticalItems = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val holderType = resolve(environment, "holder_type", adapted.holderTypeGetter)
            ?: return missing(environment, "missing-holder-getter")
        val cardGoto = adapted.cardGotoGetter?.let {
            resolve(environment, "card_goto", it)
        }
        val goTo = adapted.goToGetter?.let { resolve(environment, "goto", it) }
        val uriPoint = adapted.uriGetter ?: return missing(environment, "missing-uri-point")
        val uri = resolve(environment, "uri", uriPoint)
            ?: return missing(environment, "missing-uri-getter")
        val param = adapted.paramGetter?.let { resolve(environment, "param", it) }

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("home.vertical.response.$index", point) {
                    after {
                        val items = result as? List<*> ?: return@after
                        items.forEach { item ->
                            if (item != null && holderType.declaringClass.isInstance(item) &&
                                isVerticalItem(item, cardGoto, goTo, uri)) {
                                verticalItems[item] = true
                            }
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_response_$index",
                    "[BIL] 首页竖屏卡片登记 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        adapted.cardGotoGetter?.let { point ->
            runCatching {
                installStringRewrite(environment, "card_goto_rewrite", point) { AV_GOTO }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_card_goto",
                    "[BIL] 首页竖屏卡片 cardGoto 改写 Hook 注册失败: $throwable"
                )
            }
        }
        adapted.goToGetter?.let { point ->
            runCatching {
                installStringRewrite(environment, "goto_rewrite", point) { AV_GOTO }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_goto",
                    "[BIL] 首页竖屏卡片 goTo 改写 Hook 注册失败: $throwable"
                )
            }
        }
        runCatching {
            environment.registrar.adapted("home.vertical.uri_rewrite", uriPoint) {
                after {
                    val item = instance ?: return@after
                    if (!verticalItems.containsKey(item)) return@after
                    val original = result as? String
                    detailUri(item, original, param)?.let { result = it }
                }
            }
            installed += 1
        }.onFailure { throwable ->
            environment.logError(
                "home_vertical_uri",
                "[BIL] 首页竖屏卡片 URI 改写 Hook 注册失败: $throwable"
            )
        }

        if (installed <= 1) return missing(environment, "registration-failed")
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo(
            "home_vertical_ok",
            "[BIL] 首页竖屏视频默认进入详情页已安装，hooks=$installed"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun installStringRewrite(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint,
        value: () -> String
    ) {
        environment.registrar.adapted("home.vertical.$suffix", point) {
            after {
                val item = instance ?: return@after
                if (verticalItems.containsKey(item)) result = value()
            }
        }
    }

    private fun resolve(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint
    ): Method? = environment.hookPoints.resolveAdapted(
        "home.vertical.resolve.$suffix",
        point.className,
        point.methodName,
        point.paramClassNames
    )

    private fun isVerticalItem(
        item: Any,
        cardGoto: Method?,
        goTo: Method?,
        uri: Method
    ): Boolean {
        val cardRoute = invokeString(cardGoto, item)
        val route = invokeString(goTo, item)
        val originalUri = invokeString(uri, item)
        return cardRoute == VERTICAL_AV_GOTO || route == VERTICAL_AV_GOTO ||
            originalUri?.startsWith(STORY_URI_PREFIX) == true
    }

    private fun detailUri(item: Any, original: String?, param: Method?): String? {
        if (original?.startsWith(STORY_URI_PREFIX) == true) {
            return VIDEO_URI_PREFIX + original.removePrefix(STORY_URI_PREFIX)
        }
        val id = invokeString(param, item)?.takeIf { it.isNotBlank() } ?: return null
        return VIDEO_URI_PREFIX + Uri.encode(id)
    }

    private fun invokeString(method: Method?, target: Any): String? = runCatching {
        method?.invoke(target) as? String
    }.getOrNull()

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "home_vertical_missing",
            "[BIL] 首页竖屏视频详情路由适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "home_vertical_detail"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_vertical_detail_status"
        private const val VERTICAL_AV_GOTO = "vertical_av"
        private const val AV_GOTO = "av"
        private const val STORY_URI_PREFIX = "bilibili://story/"
        private const val VIDEO_URI_PREFIX = "bilibili://video/"

        internal fun rewriteStoryUri(uri: String): String? =
            uri.takeIf { it.startsWith(STORY_URI_PREFIX) }
                ?.let { VIDEO_URI_PREFIX + it.removePrefix(STORY_URI_PREFIX) }
    }
}
