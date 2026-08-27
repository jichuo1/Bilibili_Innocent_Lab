package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter

/** 屏蔽官方客户端更新：在同步检查入口抛出宿主自己的“已是最新版”异常。 */
internal class BlockUpdateFeatureInstaller(
    private val enabled: Boolean,
    private val point: VersionAdapter.HookPoint?
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
        val adapted = point ?: return missing(environment, "missing-adapter-point")
        val exceptionConstructor = environment.hookPoints.resolveConstructor(
            "update.block.latest_exception",
            LATEST_VERSION_EXCEPTION_CLASS,
            listOf(String::class.java.name)
        ) ?: return missing(environment, "missing-latest-exception")
        if (!Throwable::class.java.isAssignableFrom(exceptionConstructor.declaringClass)) {
            return missing(environment, "invalid-latest-exception")
        }

        return runCatching {
            environment.registrar.adapted("update.block.check", adapted) {
                before {
                    val exception = runCatching {
                        exceptionConstructor.newInstance(BLOCK_MESSAGE) as Throwable
                    }.onFailure { throwable ->
                        environment.logError(
                            "block_update_exception_err",
                            "[BIL] 构造宿主最新版状态失败，已放行官方更新检查: $throwable"
                        )
                    }.getOrNull()
                    if (exception != null) exception.throwToApp()
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "success")
            environment.logInfo("block_update_ok", "[BIL] 官方客户端更新检查屏蔽已安装")
            FeatureInstallResult.Installed()
        }.getOrElse { throwable ->
            environment.reportStatus(CHANNEL_STATUS, "failed:${throwable.javaClass.simpleName}")
            environment.logError(
                "block_update_install_err",
                "[BIL] 官方客户端更新检查 Hook 注册失败: $throwable"
            )
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("block_update_missing", "[BIL] 更新检查屏蔽适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "block_app_update"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "block_update_status"
        private const val LATEST_VERSION_EXCEPTION_CLASS =
            "tv.danmaku.bili.update.internal.exception.LatestVersionException"
        private const val BLOCK_MESSAGE = "当前已是最新版本"
    }
}
