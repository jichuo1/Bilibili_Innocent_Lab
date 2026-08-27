package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter

/** 隐藏 Gemini 播放器的“进入看一看”竖屏切换控件，并保留宿主可见性状态机。 */
internal class PlayerPortraitFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PlayerPortraitPoints?
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
        val adapted = points?.visibilityMethods?.takeIf { it.isNotEmpty() }
            ?: return missing(environment, "missing-adapter-point")

        var installedCount = 0
        adapted.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("player.portrait.visibility.$index", point) {
                    before {
                        val requested = args.firstOrNull() as? Int ?: return@before
                        val hidden = hiddenVisibility(requested)
                        if (hidden != requested) args[0] = hidden
                    }
                }
                installedCount += 1
            }.onFailure { throwable ->
                environment.logError(
                    "player_portrait_method_$index",
                    "[BIL] 播放器竖屏切换控件 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }

        if (installedCount == 0) return missing(environment, "registration-failed")
        val status = if (installedCount == adapted.size) {
            "success"
        } else {
            "partial:$installedCount/${adapted.size}"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (installedCount != adapted.size) {
            environment.logError(
                "player_portrait_partial",
                "[BIL] 播放器竖屏切换控件部分安装，hooks=$installedCount/${adapted.size}"
            )
        } else {
            environment.logInfo(
                "player_portrait_ok",
                "[BIL] 播放器竖屏切换控件隐藏已安装，hooks=$installedCount"
            )
        }
        return FeatureInstallResult.Installed(installedCount)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "player_portrait_missing",
            "[BIL] 播放器竖屏切换控件适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "player_portrait_control"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "player_portrait_status"

        /** 保留原方法调用，只把宿主请求的可见性收敛为 GONE。 */
        internal fun hiddenVisibility(requested: Int): Int =
            if (requested == View.GONE) requested else View.GONE
    }
}
