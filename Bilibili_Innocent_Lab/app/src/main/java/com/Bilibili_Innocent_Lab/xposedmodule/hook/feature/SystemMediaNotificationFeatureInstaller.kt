package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

/**
 * 打开宿主自带的「后台播放使用系统媒体控制」开关。
 *
 * 这个能力宿主已经实现，只是被设备决策位关着。所以这里不自己造通知，只在
 * `DeviceDecision#getBoolean(String, ...)` 这一个查询边界上，对
 * [DEVICE_DECISION_KEY] 这一个 key 返回 true。
 *
 * **为什么不挂 `ConfigManager$Companion#isHitFF`**（哔哩漫游的做法）：
 * 2026-09-06 对 8.84.0–9.11.0 共 26 个宿主版本逐个扫描 dex 字符串，
 * `ff_background_use_system_media_controls` **一次都没有出现过**，而
 * `dd_enable_system_media_control` 26 版全都在。`isHitFF` 又是启动期被 20 个 dex 调用的热
 * 路径，挂一个永远不可能命中的拦截器既白付开销，又会让覆盖分母虚高（"2/2 success"里有
 * 一半永远不干活）。将来某次宿主接入若在 dex 里扫到了那个 key，再按同样的
 * [installKeyOverride] 形状加回来即可。
 *
 * **版本覆盖（2026-09-06 离线核对 8.84.0–9.11.0 共 26 版）**：`getBoolean(String, boolean)`
 * 26 版齐全；`dd_enable_system_media_control` 字符串 26 版都在。
 *
 * **热路径提醒**：这个方法在启动期会被调用成百上千次。回调体因此只做一次字符串相等
 * 判断，命中才写返回值，未命中立刻回落到原实现；不要在这里加任何解析、日志或集合分配。
 */
internal class SystemMediaNotificationFeatureInstaller(
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
        val owner = KavaMemberLookup.classOrNull(loader, DEVICE_DECISION_CLASS)
            ?: return missing(environment, "missing-config-boundary")

        // 同名多重载（当前版本有 (String, boolean) 与 (String, boolean, Function1) 两个），
        // 每个都是独立的查询入口，各算一个覆盖单位。
        val methods = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
            !method.isStatic && method.name == DEVICE_DECISION_METHOD &&
                method.returnType == classOf<Boolean>() &&
                method.parameterTypes.firstOrNull() == classOf<String>()
        }
        if (methods.isEmpty()) return missing(environment, "missing-config-boundary")

        var installed = 0
        methods.forEachIndexed { index, method ->
            if (installKeyOverride(environment, "system.media.dd.$index", method)) {
                installed += 1
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed == methods.size) {
            "success"
        } else {
            "partial:$installed/${methods.size}"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "system_media_ok",
                "[BIL] 系统媒体控制通知已安装，hooks=$installed"
            )
        } else {
            environment.logError(
                "system_media_partial",
                "[BIL] 系统媒体控制通知部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed)
    }

    private fun installKeyOverride(
        environment: HookEnvironment,
        id: String,
        method: Method
    ): Boolean = runCatching {
        environment.registrar.exact(
            id,
            method.declaringClass,
            method.name,
            *method.parameterTypes
        ) {
            before {
                if (argOrNull(0) != DEVICE_DECISION_KEY) return@before
                result = true
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
            }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "system_media_register_${id.substringAfterLast('.')}",
            "[BIL] 系统媒体控制通知 Hook 注册失败($id): $throwable"
        )
        false
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "system_media_missing",
            "[BIL] 系统媒体控制通知适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "system_media_notification"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "system_media_notification_status"
        private const val DEVICE_DECISION_CLASS = "com.bilibili.lib.dd.DeviceDecision"
        private const val DEVICE_DECISION_METHOD = "getBoolean"
        private const val DEVICE_DECISION_KEY = "dd_enable_system_media_control"
    }
}
