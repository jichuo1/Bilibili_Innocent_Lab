package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess

/** 暂停页广告 P1 请求层 + P2 面板层 + P3 倒计时层安装器。 */
internal class PausedAdFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PausePoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }

        var hookCount = 0
        var primaryCount = 0
        points?.requestMethods.orEmpty().forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("paused.request.$index", point) {
                    before { result = null }
                }
                primaryCount++
                hookCount++
                environment.logInfo(
                    "paused_request_$index",
                    "[BIL] 已注册暂停页请求拦截 ${point.className}#${point.methodName}"
                )
            }.onFailure { throwable ->
                environment.logInfo(
                    "paused_request_${index}_err",
                    "[BIL] 暂停页请求入口注册失败: $throwable"
                )
            }
        }

        points?.legacyCallback?.let { point ->
            runCatching {
                environment.registrar.adapted("paused.legacy_callback", point) {
                    before { result = null }
                }
                primaryCount++
                hookCount++
                environment.logInfo("paused_legacy", "[BIL] 已注册旧版暂停页 Function0 拦截")
            }.onFailure { throwable ->
                environment.logInfo(
                    "paused_legacy_err",
                    "[BIL] 旧版暂停页入口注册失败: $throwable"
                )
            }
        }

        if (points == null) {
            listOf(REQUEST_CLASS_V2, REQUEST_CLASS_V3).forEachIndexed { index, className ->
                if (KavaMemberLookup.classOrNull(environment.classLoader, className) != null) {
                    runCatching {
                        environment.registrar.first(
                            "paused.request_fallback.$index",
                            className,
                            INVOKE_SUSPEND
                        ) { before { result = null } }
                        primaryCount++
                        hookCount++
                        environment.logInfo(
                            "paused_fallback_$index",
                            "[BIL] 已注册暂停页请求兜底 $className"
                        )
                    }
                }
            }
        }

        var panelRegistered = false
        runCatching {
            val panelPoint = points?.panelShow
            if (panelPoint != null) {
                environment.registrar.adapted("paused.panel_show", panelPoint) {
                    after {
                        val data = args.firstOrNull {
                            it?.javaClass?.name?.contains(PANEL_DATA_NAME) == true
                        }
                        if (data != null) {
                            runCatching { ReflectAccess.callMethod(instance, "dismissPanel") }
                                .onSuccess {
                                    environment.logInfo(
                                        "paused_p2_dismiss",
                                        "[BIL] 已丢弃暂停页广告面板（P2 兜底）"
                                    )
                                }
                        }
                    }
                }
            } else {
                environment.registrar.all(
                    "paused.panel_show_fallback",
                    PANEL_REPOSITORY_CLASS,
                    "showPanel"
                ) {
                    after {
                        val data = args.firstOrNull {
                            it?.javaClass?.name?.contains(PANEL_DATA_NAME) == true
                        }
                        if (data != null) runCatching {
                            ReflectAccess.callMethod(instance, "dismissPanel")
                        }
                    }
                }
            }
            panelRegistered = true
            hookCount++
            environment.logInfo(
                "paused_p2",
                "[BIL] 已注册暂停页广告面板拦截兜底（AdPanelRepository.showPanel）"
            )
        }.onFailure { throwable ->
            environment.logInfo(
                "paused_p2_reg_err",
                "[BIL] 暂停页广告 P2 兜底注册失败: $throwable"
            )
        }

        runCatching {
            val countdownPoint = points?.countdown
            if (countdownPoint != null) {
                environment.registrar.adapted("paused.countdown", countdownPoint) {
                    before { result = null }
                }
            } else {
                environment.registrar.first(
                    "paused.countdown_fallback",
                    COUNTDOWN_CLASS,
                    INVOKE_SUSPEND
                ) { before { result = null } }
            }
            hookCount++
            environment.logInfo("paused_p3", "[BIL] 已屏蔽暂停页「3 秒后展示广告」倒计时 toast")
        }.onFailure { throwable ->
            environment.logInfo(
                "paused_p3_reg_err",
                "[BIL] 暂停页倒计时 toast 屏蔽注册失败: $throwable"
            )
        }

        // P4：协议响应层。与 P1/P2/P3 互不知情——它们全是"按宿主实现定位"
        // （混淆类名一漂移就落空），这一层打在未混淆的 protobuf 生成类上。
        if (installResponseLayer(environment)) hookCount++

        environment.reportStatus(
            CHANNEL_STATUS,
            if (primaryCount > 0 || panelRegistered) "success" else "failed"
        )
        return if (hookCount > 0) {
            FeatureInstallResult.Installed(hookCount)
        } else {
            FeatureInstallResult.Skipped("no_hook_point")
        }
    }

    /**
     * P4：在 `ViewMoss#executePlayPause` 的响应上清掉广告载荷。
     *
     * `PlayPauseReply` 是 oneof（`dataCase_` + `data_`）承载 `ads_`，
     * 而**暂停进度条 `bar_` 是独立声明字段**——25 版逐版核对确认，
     * 所以 `clearAds()` 在结构上就碰不到进度条，不存在"顺手删掉正常功能"的风险。
     *
     * 定位面只有两个未混淆的 protobuf 类名，不依赖任何混淆实现类，
     * 这是它比 P1/P2/P3 稳的原因。
     *
     * ⚠️ `PlayPauseReply` **从 8.91.0 才有**（8.84.0–8.89.0 那 5 版没有），
     * 缺失时哨兵降级记 info 不记 error，P1/P2/P3 照常工作。
     */
    private fun installResponseLayer(environment: HookEnvironment): Boolean {
        val loader = environment.classLoader ?: return false
        val reply = KavaMemberLookup.classOrNull(loader, PLAY_PAUSE_REPLY_CLASS)
        val moss = KavaMemberLookup.classOrNull(loader, VIEW_MOSS_CLASS)
        val request = KavaMemberLookup.classOrNull(loader, PLAY_PAUSE_REQ_CLASS)
        if (reply == null || moss == null || request == null) {
            environment.reportStatus(CHANNEL_RESPONSE, "not-applicable-host")
            environment.logInfo(
                "paused_p4_absent",
                "[BIL] 暂停页响应层不可用（本宿主无 $PLAY_PAUSE_REPLY_CLASS），仅保留 P1/P2/P3"
            )
            return false
        }
        val cleaner = PausePayloadCleaner.resolve(reply) ?: run {
            environment.reportStatus(CHANNEL_RESPONSE, "not-applicable-host")
            environment.logInfo(
                "paused_p4_shape",
                "[BIL] 暂停页响应层形状不符（缺 hasAds/clearAds），仅保留 P1/P2/P3"
            )
            return false
        }
        return runCatching {
            environment.registrar.exact(
                "paused.response.execute_play_pause",
                moss,
                EXECUTE_PLAY_PAUSE,
                request
            ) {
                after {
                    if (hasThrowable) return@after
                    val original = result ?: return@after
                    val updated = cleaner.clearAds(original, environment)
                    if (updated !== original) result = updated
                }
            }
            environment.reportStatus(CHANNEL_RESPONSE, "success")
            environment.logInfo("paused_p4", "[BIL] 已注册暂停页响应层广告清理（P4）")
            true
        }.getOrElse { throwable ->
            environment.reportStatus(CHANNEL_RESPONSE, "registration-failed")
            environment.logError(
                "paused_p4_reg_err",
                "[BIL] 暂停页响应层注册失败: $throwable"
            )
            false
        }
    }

    companion object {
        const val ID = "paused_ad"
        private const val CHANNEL_STATUS = "adskip_status"
        private const val INVOKE_SUSPEND = "invokeSuspend"
        private const val REQUEST_CLASS_V2 =
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2"
        private const val REQUEST_CLASS_V3 =
            "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService\$requestPausedPageData\$2"
        private const val PANEL_REPOSITORY_CLASS =
            "com.bilibili.ship.theseus.united.page.ad.AdPanelRepository"
        private const val COUNTDOWN_CLASS =
            "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService\$showPauseBarCountdownToast\$3"
        private const val PANEL_DATA_NAME = "AdPausedPagePanelData"

        /** P4 用的通道与锚点。全是未混淆的 protobuf 生成类。 */
        private const val CHANNEL_RESPONSE = "adskip_response_status"
        private const val VIEW_MOSS_CLASS = "com.bapis.bilibili.app.viewunite.v1.ViewMoss"
        private const val PLAY_PAUSE_REQ_CLASS =
            "com.bapis.bilibili.app.viewunite.v1.PlayPauseReq"
        private const val PLAY_PAUSE_REPLY_CLASS =
            "com.bapis.bilibili.app.viewunite.v1.PlayPauseReply"
        private const val EXECUTE_PLAY_PAUSE = "executePlayPause"
        internal const val HAS_ADS = "hasAds"
        internal const val CLEAR_ADS = "clearAds"

        /** 暂停进度条：正常功能，**绝不能动**。留常量是为了让测试能钉住"没碰它"。 */
        internal const val PAUSE_BAR_PRESENCE = "hasBar"
    }
}

/**
 * 只清 `PlayPauseReply.ads`，一个字段都不多动。
 *
 * 安装期解析全部反射；无广告载荷时返回原实例（不分配），异常交付原响应。
 */
internal class PausePayloadCleaner private constructor(
    private val reply: Class<*>,
    private val plan: ProtobufBuilderPlan,
    private val hasAds: java.lang.reflect.Method,
    private val clearAds: java.lang.reflect.Method,
    private val defaultInstance: Any?
) {

    fun clearAds(original: Any, environment: HookEnvironment): Any = runCatching {
        if (!reply.isInstance(original)) return@runCatching original
        if (defaultInstance != null && original === defaultInstance) return@runCatching original
        if (hasAds.invoke(original) as? Boolean != true) return@runCatching original
        environment.reportRuntimeEvidence(
            PausedAdFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED
        )
        val updated = plan.edit(original) { target -> clearAds.invoke(target) }
        // 回读确认：clear* 对空字段静默成功，不回读就没有"真的清掉了"的证据。
        check(hasAds.invoke(updated) as? Boolean != true) { "Pause ads clear readback failed" }
        environment.reportRuntimeEvidence(
            PausedAdFeatureInstaller.ID, FeatureRuntimeStage.APPLIED
        )
        updated
    }.getOrElse {
        environment.reportRuntimeEvidence(
            PausedAdFeatureInstaller.ID, FeatureRuntimeStage.ERROR
        )
        environment.logError(
            "paused_p4_clean_err",
            "[BIL] 暂停页响应层清理失败，保留原响应: $it"
        )
        original
    }

    companion object {
        fun resolve(reply: Class<*>): PausePayloadCleaner? = runCatching {
            val plan = ProtobufBuilderPlan.resolve(reply) ?: return null
            val hasAds = KavaMemberLookup.methodOrNull(reply, PausedAdFeatureInstaller.HAS_ADS)
                ?.takeIf { it.returnType == java.lang.Boolean.TYPE } ?: return null
            val clearAds = plan.method(PausedAdFeatureInstaller.CLEAR_ADS) ?: return null
            val defaultInstance = runCatching {
                KavaMemberLookup.methodOrNull(reply, "getDefaultInstance")?.invoke(null)
            }.getOrNull()
            PausePayloadCleaner(reply, plan, hasAds, clearAds, defaultInstance)
        }.getOrNull()
    }
}
