package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter

/** 完整数字显示：在宿主统一数字格式化边界返回原始非负整数，避免“万/亿”缩写。 */
internal class FullNumberFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.FullNumberPoints?
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
        val adapted = points?.formatterMethods?.takeIf { it.isNotEmpty() }
            ?: return missing(environment, "missing-adapter-point")

        var installedCount = 0
        adapted.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("number.full.format.$index", point) {
                    before {
                        rawNumberText(args.firstOrNull())?.let { result = it }
                    }
                }
                installedCount += 1
            }.onFailure { throwable ->
                environment.logError(
                    "full_number_method_$index",
                    "[BIL] 完整数字格式化 Hook 注册失败(" +
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
                "full_number_partial",
                "[BIL] 完整数字显示部分安装，hooks=$installedCount/${adapted.size}"
            )
        } else {
            environment.logInfo(
                "full_number_ok",
                "[BIL] 完整数字显示已安装，hooks=$installedCount"
            )
        }
        return FeatureInstallResult.Installed(installedCount)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("full_number_missing", "[BIL] 完整数字显示适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "full_number_display"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "full_number_status"

        /** 只接管非负整数；负数、空值和非纯数字字符串故障开放给宿主。 */
        internal fun rawNumberText(value: Any?): String? = when (value) {
            is Byte -> value.toLong().takeIf { it >= 0L }?.toString()
            is Short -> value.toLong().takeIf { it >= 0L }?.toString()
            is Int -> value.takeIf { it >= 0 }?.toString()
            is Long -> value.takeIf { it >= 0L }?.toString()
            is String -> value.takeIf { text ->
                text.isNotEmpty() && text.all { it in '0'..'9' }
            }
            else -> null
        }
    }
}
