package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter

/** 仅关闭青少年模式提示页，不修改青少年模式状态、账户数据或设置页面。 */
internal class TeenagersModeFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.TeenagersModePoints?
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
        val adapted = points?.onCreateMethods?.takeIf { it.isNotEmpty() }
            ?: return missing(environment, "missing-adapter-point")

        var installedCount = 0
        adapted.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("teenagers.mode.on_create.$index", point) {
                    after {
                        val activity = instance as? Activity ?: return@after
                        if (activity.isFinishing || activity.isDestroyed) return@after
                        runCatching { activity.finish() }
                            .onFailure { throwable ->
                                environment.logError(
                                    "teenagers_mode_finish_$index",
                                    "[BIL] 关闭青少年模式提示页失败(${activity.javaClass.name}): " +
                                        throwable
                                )
                            }
                    }
                }
                installedCount += 1
            }.onFailure { throwable ->
                environment.logError(
                    "teenagers_mode_method_$index",
                    "[BIL] 青少年模式提示页 Hook 注册失败(" +
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
        if (installedCount == adapted.size) {
            environment.logInfo(
                "teenagers_mode_ok",
                "[BIL] 青少年模式提示页关闭功能已安装，hooks=$installedCount"
            )
        } else {
            environment.logError(
                "teenagers_mode_partial",
                "[BIL] 青少年模式提示页关闭功能部分安装，hooks=$installedCount/${adapted.size}"
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
            "teenagers_mode_missing",
            "[BIL] 青少年模式提示页适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "teenagers_mode_prompt"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "teenagers_mode_status"
    }
}
