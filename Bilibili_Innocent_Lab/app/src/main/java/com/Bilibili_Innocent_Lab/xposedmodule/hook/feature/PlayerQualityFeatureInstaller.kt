package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter

/** 在已适配的播放器默认画质业务边界返回用户选择的 QN；默认关闭且不触碰网络响应。 */
internal class PlayerQualityFeatureInstaller(
    qualityQn: Int,
    private val points: VersionAdapter.PlayerQualityPoints?
) : FeatureInstaller {

    override val id: String = ID
    private val normalizedQn = PlayerQualityConfig.normalize(qualityQn)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (normalizedQn == 0) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val point = points?.defaultQualityMethod
            ?: return missing(environment, "missing-adapter-point")

        return runCatching {
            environment.registrar.adapted("player.default_quality", point) {
                before { result = normalizedQn }
            }
            environment.reportStatus(CHANNEL_STATUS, "success")
            environment.logInfo(
                "player_quality_ok",
                "[BIL] 播放器默认画质已安装，qn=$normalizedQn，" +
                    "point=${point.className}#${point.methodName}"
            )
            FeatureInstallResult.Installed(1)
        }.getOrElse { throwable ->
            environment.logError(
                "player_quality_registration",
                "[BIL] 播放器默认画质 Hook 注册失败(" +
                    "${point.className}#${point.methodName}): $throwable"
            )
            missing(environment, "registration-failed")
        }
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "player_quality_missing",
            "[BIL] 播放器默认画质适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "player_default_quality"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "player_quality_status"
    }
}
