package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.net.Uri
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 将首页推荐中的普通投稿型竖屏卡片安全改为普通详情页，并保留受首页来源约束的路由兜底。 */
internal class HomeVerticalDetailFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.HomeRecommendFeedPoints?
) : FeatureInstaller {

    override val id: String = ID

    private val recentHomeVideos = RecentHomeVideoRegistry()
    private val routeMutator = ConcreteHomeVerticalRouteMutator()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val accessors = ReadAccessors(
            holderType = resolve(environment, "holder_type", adapted.holderTypeGetter)
                ?: return missing(environment, "missing-holder-getter"),
            bizType = resolveOptional(environment, "biz_type", adapted.bizTypeGetter),
            adInfo = resolveOptional(environment, "ad_info", adapted.adInfoGetter),
            cardType = resolveOptional(environment, "card_type", adapted.cardTypeGetter),
            cardGoto = resolveOptional(environment, "card_goto", adapted.cardGotoGetter),
            goTo = resolveOptional(environment, "goto", adapted.goToGetter),
            uri = adapted.uriGetter?.let { resolve(environment, "uri", it) }
                ?: return missing(environment, "missing-uri-getter"),
            param = resolveOptional(environment, "param", adapted.paramGetter)
        )

        var responseHooks = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("home.vertical.response.$index", point) {
                    after {
                        val items = result as? List<*> ?: return@after
                        handleItems(items, accessors, environment)
                    }
                }
                responseHooks += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_response_$index",
                    "[BIL] 首页竖屏详情响应 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        if (responseHooks == 0) return missing(environment, "registration-failed")

        val routeHooks = installRouteFallback(environment)
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo(
            "home_vertical_ok",
            "[BIL] 首页竖屏详情路由已安装，response=$responseHooks,routeFallback=$routeHooks"
        )
        return FeatureInstallResult.Installed(responseHooks + routeHooks)
    }

    private fun handleItems(
        items: List<*>,
        accessors: ReadAccessors,
        environment: HookEnvironment
    ) {
        var observed = 0
        var eligible = 0
        var applied = 0
        var unsafe = 0
        var noAccessor = 0
        var rolledBack = 0
        var rollbackIncomplete = 0
        items.forEach { item ->
            if (item == null || !accessors.holderType.declaringClass.isInstance(item)) return@forEach
            val snapshot = snapshot(item, accessors)
            when (val decision = HomeVerticalDetailRoutePolicy.decide(snapshot)) {
                HomeVerticalRouteDecision.NotVertical -> Unit
                is HomeVerticalRouteDecision.KeepOriginal -> {
                    observed += 1
                    unsafe += 1
                }
                is HomeVerticalRouteDecision.Rewrite -> {
                    observed += 1
                    eligible += 1
                    recentHomeVideos.register(decision.plan.identity)
                    when (
                        routeMutator.apply(
                            item,
                            snapshot,
                            decision.plan,
                            HomeVerticalReadAccessors(
                                cardGoto = accessors.cardGoto,
                                goTo = accessors.goTo,
                                uri = accessors.uri
                            )
                        )
                    ) {
                        HomeVerticalMutationResult.APPLIED -> applied += 1
                        HomeVerticalMutationResult.NO_SAFE_ACCESSOR -> noAccessor += 1
                        HomeVerticalMutationResult.ROLLED_BACK -> rolledBack += 1
                        HomeVerticalMutationResult.ROLLBACK_INCOMPLETE -> rollbackIncomplete += 1
                    }
                }
            }
        }
        if (observed == 0) return
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED, observed)
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, applied)
        environment.logInfo(
            "home_vertical_runtime",
            "[BIL] 首页竖屏详情处理 observed=$observed,eligible=$eligible,applied=$applied," +
                "unsafe=$unsafe,noAccessor=$noAccessor,rolledBack=$rolledBack," +
                "recentIds=${recentHomeVideos.size()}"
        )
        if (rollbackIncomplete > 0) {
            environment.logError(
                "home_vertical_rollback_incomplete",
                "[BIL] 首页竖屏详情存在 $rollbackIncomplete 项未能完整回滚，已停止继续修改对应卡片"
            )
        }
    }

    private fun snapshot(item: Any, accessors: ReadAccessors): HomeVerticalRouteSnapshot =
        HomeVerticalRouteSnapshot(
            holderType = invokeString(accessors.holderType, item),
            bizType = invokeString(accessors.bizType, item),
            cardType = invokeString(accessors.cardType, item),
            cardGoto = invokeString(accessors.cardGoto, item),
            goTo = invokeString(accessors.goTo, item),
            uri = invokeString(accessors.uri, item),
            param = invokeString(accessors.param, item),
            hasAdInfo = invokeCompatible(accessors.adInfo, item) != null
        )

    private fun installRouteFallback(environment: HookEnvironment): Int {
        var installed = 0
        val stringConstructor = environment.hookPoints.resolveConstructor(
            "home.vertical.route.string.resolve",
            ROUTE_REQUEST_BUILDER_CLASS,
            listOf(String::class.java.name)
        )
        if (stringConstructor != null) {
            runCatching {
                environment.registrar.constructor(
                    "home.vertical.route.string",
                    stringConstructor
                ) {
                    before {
                        val original = args.getOrNull(0) as? String ?: return@before
                        val rewritten = recentHomeVideos.rewriteIfRegistered(original) ?: return@before
                        args[0] = rewritten
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "home_vertical_route_fallback",
                            "[BIL] 首页近期竖屏视频已在字符串路由构造阶段改为普通详情页"
                        )
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_route_string_failed",
                    "[BIL] 首页竖屏字符串路由兜底注册失败: $throwable"
                )
            }
        }

        val uriConstructor = environment.hookPoints.resolveConstructor(
            "home.vertical.route.uri.resolve",
            ROUTE_REQUEST_BUILDER_CLASS,
            listOf(Uri::class.java.name)
        )
        if (uriConstructor != null) {
            runCatching {
                environment.registrar.constructor("home.vertical.route.uri", uriConstructor) {
                    before {
                        val original = args.getOrNull(0) as? Uri ?: return@before
                        val rewritten = recentHomeVideos.rewriteIfRegistered(original.toString())
                            ?: return@before
                        args[0] = Uri.parse(rewritten)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "home_vertical_route_fallback",
                            "[BIL] 首页近期竖屏视频已在 URI 路由构造阶段改为普通详情页"
                        )
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_route_uri_failed",
                    "[BIL] 首页竖屏 URI 路由兜底注册失败: $throwable"
                )
            }
        }
        return installed
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
        "home.vertical.resolve.$suffix",
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
            "home_vertical_missing",
            "[BIL] 首页竖屏视频详情路由适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    private data class ReadAccessors(
        val holderType: Method,
        val bizType: Method?,
        val adInfo: Method?,
        val cardType: Method?,
        val cardGoto: Method?,
        val goTo: Method?,
        val uri: Method,
        val param: Method?
    )

    companion object {
        const val ID = "home_vertical_detail"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_vertical_detail_status"
        private const val ROUTE_REQUEST_BUILDER_CLASS =
            "com.bilibili.lib.blrouter.RouteRequest\$Builder"

        internal fun rewriteStoryUri(uri: String): String? =
            HomeVerticalDetailRoutePolicy.rewriteRegisteredStoryUri(uri) { true }
    }
}
