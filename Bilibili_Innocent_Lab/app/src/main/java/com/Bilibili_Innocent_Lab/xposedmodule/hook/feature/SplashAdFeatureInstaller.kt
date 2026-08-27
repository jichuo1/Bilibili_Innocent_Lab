package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter

/** 仅清空宿主开屏响应的广告/策略列表，不介入全局序列化流程。 */
internal class SplashAdFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.SplashAdPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        var installed = 0
        adapted.listGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("splash.purify.$index", point) {
                    after {
                        if (result is List<*>) result = ArrayList<Any>(0)
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
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo("splash_purify_ok", "[BIL] 开屏广告净化已安装，hooks=$installed")
        return FeatureInstallResult.Installed(installed)
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
    }
}
