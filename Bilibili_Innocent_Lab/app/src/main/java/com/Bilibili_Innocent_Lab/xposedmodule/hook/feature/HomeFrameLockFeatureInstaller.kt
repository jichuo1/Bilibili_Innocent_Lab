package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 固定使用旧版首页框架：服务端云控 `dd.use_new_home_frame` 为真时，宿主会把手机从旧框架
 * （`HomeFragmentV2` + `TabHost`）切到新框架（khome，Compose）。切换不需要宿主升级，
 * 所以依赖旧框架视图的模块功能会在毫无征兆的某一天失效。本功能只在读取这个 key 的位置把结果
 * 压回 false，其它云控 key 一律不碰。
 *
 * **读取边界**（2026-10-31 对 8.92.1–9.17.0 共 23 个带新框架的宿主逐个核对）：该 key 在每个宿主里
 * 只有一处读取，且都经 `KConfig.INSTANCE.ff("dd.use_new_home_frame", false)`，`KConfig#ff(String, boolean)`
 * 签名 23 版一致。没有再挂 `ConfigManager$Companion#isHitFF` 或 `DeviceDecision`：宿主从不经它们读这个
 * key，挂上只会让覆盖分母虚高。
 *
 * **只改云控这一项**：新框架还有另外三个入口，本功能管不到——大屏/中屏窗口、宿主自己的调试 SP
 * `sp_key_new_home_frame_test`（默认 false）以及旧框架缺失的宿主（8.92.1 以前根本没有新框架）。
 * 前两者仍会走新框架，这是宿主的屏幕尺寸规则，不是云控。
 *
 * **热路径提醒**：`ff` 在启动期被大量调用。回调体只比较一次 key，未命中立即返回；命中才读取
 * 原结果，只在宿主确实要求新框架时才写 false，并据此上报「已拦截一次云控切换」。
 */
internal class HomeFrameLockFeatureInstaller(
    private val enabled: Boolean
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
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        // 没有新框架的宿主没有可固定的东西，不是故障。
        if (KavaMemberLookup.classOrNull(loader, HomeFrameTabLocator.VIEW_MODEL_CLASS) == null) {
            environment.reportStatus(CHANNEL_STATUS, "not-applicable-host")
            return FeatureInstallResult.Skipped("not-applicable-host")
        }
        val owner = KavaMemberLookup.classOrNull(loader, CONFIG_CLASS)
            ?: return missing(environment, "missing-config-boundary")
        val method = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            !it.isStatic && it.name == CONFIG_METHOD && it.returnType == classOf<Boolean>() &&
                it.parameterTypes.contentEquals(arrayOf(classOf<String>(), classOf<Boolean>()))
        }.singleOrNull() ?: return missing(environment, "missing-config-boundary")

        val firstObserved = AtomicBoolean(false)
        val firstApplied = AtomicBoolean(false)
        val registered = runCatching {
            environment.registrar.exact(
                "home_frame_lock.flag",
                method.declaringClass,
                method.name,
                *method.parameterTypes
            ) {
                after {
                    if (hasThrowable || argOrNull(0) != FRAME_FLAG_KEY) return@after
                    if (firstObserved.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    }
                    if (result != true) return@after
                    result = false
                    if (firstApplied.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.reportStatus(CHANNEL_STATUS, "applied:blocked-cloud-switch")
                        environment.logInfo("home_frame_lock_hit", "[BIL] 已拦截云控切换到新版首页框架")
                    }
                }
            }
        }.onFailure { throwable ->
            environment.logError("home_frame_lock_register", "[BIL] 固定旧版首页框架 Hook 注册失败: $throwable")
        }.isSuccess
        if (!registered) return missing(environment, "registration-failed")

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, "installed")
        environment.logInfo("home_frame_lock_ok", "[BIL] 固定旧版首页框架已安装")
        return FeatureInstallResult.Installed(1)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("home_frame_lock_missing", "[BIL] 固定旧版首页框架适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "home_frame_lock"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_frame_lock_status"
        private const val CONFIG_CLASS = "kntr.base.config.KConfig"
        private const val CONFIG_METHOD = "ff"
        internal const val FRAME_FLAG_KEY = "dd.use_new_home_frame"
    }
}
