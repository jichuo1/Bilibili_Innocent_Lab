package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import de.robv.android.xposed.XposedHelpers

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
                            runCatching { XposedHelpers.callMethod(instance, "dismissPanel") }
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
                            XposedHelpers.callMethod(instance, "dismissPanel")
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
    }
}
