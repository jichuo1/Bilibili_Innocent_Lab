package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf

/**
 * 开屏广告净化：两道**互不知情**的防线。
 *
 * ```
 * 第一道（数据层）：清空宿主开屏响应里的广告/策略列表
 *   SplashListResponse.getSplashList / getStrategyList
 *   BrandSplashData/PreloadBrandData.getShowList
 *   event 的 getSplashList
 *   —— 经 VersionAdapter 定位（混淆 owner 候选表），覆盖"服务端下发的列表"
 *
 * 第二道（决策层）：让选片决策直接判定"无广告可展"
 *   SplashOrder.isEmptyAd() -> true
 *   —— 硬编码未混淆类名 + 运行期哨兵，覆盖**不经过列表 getter** 的构造路径
 *      （品牌预加载 PreloadBrandData、事件开屏、本地缓存恢复）
 * ```
 *
 * ### 为什么两道要互不知情
 *
 * 第一道依赖 VersionAdapter 的混淆 owner 候选表，宿主换实现就会定位落空；
 * 第二道打在未混淆的模型类上。**任何一道失败都不许影响另一道**——
 * 早期实现里 `points == null` 会直接 return，那等于把两道串联成"与"，
 * 可用性反而变成两道之积。现在各自 try/catch、各自记状态。
 *
 * ### 为什么用 `isEmptyAd` 而不是 `isAd` / `isAdLoc`
 *
 * `isAd()` 的语义是"**这**是不是广告"，把真广告标成"不是广告"会污染
 * `ad_cb` 曝光上报链；`isEmptyAd()` 的语义是"**有没有**广告可展"，
 * 返回 true 让上报链自然静默。两者不能互换。
 *
 * ### 版本覆盖（25 个存档宿主实测，2026-09-11）
 *
 * `SplashOrder.isEmptyAd():boolean` **从 8.98.0 才有**：8.84.0–8.97.0 那 12 版
 * 没有这个方法（原方案只在 9.11.0 上核过，据此推广到全版本是不成立的）。
 * 所以第二道在旧版本上按哨兵降级为 skipped，第一道照常工作。
 */
internal class SplashAdFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.SplashAdPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            environment.reportStatus(CHANNEL_DECISION, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }

        val listInstalled = installListLayer(environment)
        val decisionInstalled = installDecisionLayer(environment)

        if (listInstalled == 0 && !decisionInstalled) {
            return missing(environment, if (points == null) "missing-adapter-point" else "registration-failed")
        }
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        // 第一道的状态语义保持原样（历史诊断与遥测按它对齐），第二道单独一条通道。
        environment.reportStatus(
            CHANNEL_STATUS,
            if (listInstalled > 0) "success" else "decision-only"
        )
        environment.logInfo(
            "splash_purify_ok",
            "[BIL] 开屏广告净化已安装，列表层 hooks=$listInstalled，决策层=$decisionInstalled"
        )
        return FeatureInstallResult.Installed(listInstalled + if (decisionInstalled) 1 else 0)
    }

    /** 第一道：清列表。@return 实际装上的 hook 数。 */
    private fun installListLayer(environment: HookEnvironment): Int {
        val adapted = points ?: run {
            environment.logInfo(
                "splash_purify_list_absent",
                "[BIL] 开屏列表层未适配（无 VersionAdapter 点位），仅保留决策层"
            )
            return 0
        }
        var installed = 0
        adapted.listGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("splash.purify.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        if (source.isNotEmpty()) {
                            result = ArrayList<Any>(0)
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                source.size
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "splash_purify_$index",
                    "[BIL] 开屏广告净化 Hook 注册失败(${point.className}#${point.methodName}): " +
                        throwable
                )
            }
        }
        return installed
    }

    /**
     * 第二道：决策层。
     *
     * `replaceToTrue()` 是纯返回值改写——无参、无副作用，所以不需要线程门禁；
     * 每次选片才走一次，不在任何帧预算热路径上。
     */
    private fun installDecisionLayer(environment: HookEnvironment): Boolean {
        val loader = environment.classLoader ?: run {
            environment.reportStatus(CHANNEL_DECISION, "missing-class-loader")
            return false
        }
        val orderClass = KavaMemberLookup.classOrNull(loader, SPLASH_ORDER_CLASS)
        val method = orderClass?.let {
            KavaMemberLookup.methodOrNull(it, IS_EMPTY_AD)
                ?.takeIf { candidate ->
                    candidate.returnType == classOf<Boolean>() &&
                        candidate.parameterTypes.isEmpty()
                }
        }
        if (method == null) {
            // 8.84.0–8.97.0 本来就没有这个方法，属于预期降级，记 info 不记 error。
            environment.reportStatus(CHANNEL_DECISION, "not-applicable-host")
            environment.logInfo(
                "splash_decision_absent",
                "[BIL] 开屏决策层不可用（本宿主无 $SPLASH_ORDER_CLASS#$IS_EMPTY_AD），" +
                    "仅保留列表层"
            )
            return false
        }
        return runCatching {
            environment.registrar.exact(
                "splash.decision.$IS_EMPTY_AD",
                method.declaringClass,
                IS_EMPTY_AD
            ) {
                replaceToTrue()
            }
            environment.reportStatus(CHANNEL_DECISION, "success")
            true
        }.getOrElse { throwable ->
            environment.reportStatus(CHANNEL_DECISION, "registration-failed")
            environment.logError(
                "splash_decision_register",
                "[BIL] 开屏决策层 Hook 注册失败: $throwable"
            )
            false
        }
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("splash_purify_missing", "[BIL] 开屏广告净化适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "splash_ad_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "splash_ad_purify_status"
        private const val CHANNEL_DECISION = "splash_decision_status"

        /**
         * 决策汇合点。`splash.ad.model` 包在 25 个存档宿主里都是明文类名
         * （现有 `SPLASH_RESPONSE_CLASS_CANDIDATES` 也依赖这个事实）。
         * `SplashShowStrategy.isEmptyAd()` 内部调的就是它，所以一个点覆盖全部策略类型；
         * 刻意**不碰** `SplashShowStrategy.isEmptyAd()`，留作"只想屏蔽某类策略"时的第二粒度。
         */
        private const val SPLASH_ORDER_CLASS = "tv.danmaku.bili.splash.ad.model.SplashOrder"
        private const val IS_EMPTY_AD = "isEmptyAd"
    }
}
