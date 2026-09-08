package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf

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
            listOf(classOf<String>().name)
        ) ?: return missing(environment, "missing-latest-exception")
        if (!(exceptionConstructor.declaringClass isSubclassOf classOf<Throwable>())) {
            return missing(environment, "invalid-latest-exception")
        }

        return runCatching {
            // 唯一使用 DELIVER_TO_HOST 的 Hook：本功能的全部作用就是让宿主的更新检查以
            // 它自己的 LatestVersionException 结束。默认的 PROTECT_HOST
            // （framework `ExceptionMode.PROTECTIVE`）契约是“捕获并按没装 Hook 继续”，
            // 在那个模式下 `throwable` 永远到不了宿主，屏蔽会静默失效。
            //
            // 代价是这里没有框架兜底，所以回调体必须保证**只有**这条刻意的异常会逃逸：
            // 消息解析、构造和失败日志全部收在 runCatching 内，构造失败即放行官方检查。
            environment.registrar.adapted(
                "update.block.check",
                adapted,
                HookExceptionPolicy.DELIVER_TO_HOST
            ) {
                before {
                    val exception = runCatching {
                        val message = InjectedUiLocale.messages(
                            ReflectAccess.currentApplication()
                        ).latestVersionMessage
                        exceptionConstructor.newInstance(message) as Throwable
                    }.getOrElse { failure ->
                        // 日志本身也不能逃逸——它和上面的构造同处 PASSTHROUGH 回调内。
                        runCatching {
                            environment.logError(
                                "block_update_exception_err",
                                "[BIL] 构造宿主最新版状态失败，已放行官方更新检查: $failure"
                            )
                        }
                        null
                    }
                    if (exception != null) throwable = exception
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
    }
}
