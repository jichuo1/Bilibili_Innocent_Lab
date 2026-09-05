package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PgcAutoActivityPopupLocator
import java.util.concurrent.atomic.AtomicBoolean

/** 影视页运营半屏的独立安装单元；不接管播放器、支付或通用 Web 浮层。 */
internal class PgcAutoActivityPopupFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PgcAutoActivityPopupPoints?
) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != "tv.danmaku.bili") {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val runtime = PgcAutoActivityPopupLocator.resolveRuntime(loader, adapted)
            ?: return missing(environment, "missing-verified-nullable-structure")
        val shapeFailure = AtomicBoolean(false)
        val typeFailure = AtomicBoolean(false)
        val resultFailure = AtomicBoolean(false)
        val observed = AtomicBoolean(false)
        val applied = AtomicBoolean(false)

        fun reportOnce(flag: AtomicBoolean, reason: String) {
            if (flag.compareAndSet(false, true)) {
                runCatching {
                    environment.logError("pgc_auto_activity_$reason", "[BIL] 影视活动半屏过滤: $reason")
                }
            }
        }
        fun evidenceOnce(flag: AtomicBoolean, stage: FeatureRuntimeStage) {
            if (flag.compareAndSet(false, true)) {
                runCatching { environment.reportRuntimeEvidence(ID, stage) }
            }
        }

        return runCatching {
            environment.registrar.adapted("pgc.auto_activity.construct", adapted.construct) {
                before {
                    val decision = PgcAutoActivityPopupPolicy.filter(
                        args.singleOrNull(), runtime.parameterCount, runtime.popupIndex,
                        runtime.popupType
                    )
                    when (decision) {
                        PgcAutoActivityPopupPolicy.Decision.Absent -> Unit
                        PgcAutoActivityPopupPolicy.Decision.InvalidShape ->
                            reportOnce(shapeFailure, "argument-shape")
                        PgcAutoActivityPopupPolicy.Decision.UnexpectedType ->
                            reportOnce(typeFailure, "popup-type")
                        is PgcAutoActivityPopupPolicy.Decision.Filtered -> {
                            args[0] = decision.values
                            setObjectExtra(FILTERED_EXTRA, true)
                            evidenceOnce(observed, FeatureRuntimeStage.OBSERVED)
                        }
                    }
                }
                after {
                    if (getObjectExtra(FILTERED_EXTRA) != true) return@after
                    // 原方法成功且实际模型已没有半屏才计入 APPLIED，不能拿改参数当效果证明。
                    val removed = !hasThrowable && runCatching {
                        runtime.modelType.isInstance(result) && runtime.popupField.get(result) == null
                    }.getOrDefault(false)
                    if (removed) evidenceOnce(applied, FeatureRuntimeStage.APPLIED)
                    else reportOnce(resultFailure, "construction-unconfirmed")
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "success")
            environment.logInfo("pgc_auto_activity_ready", "[BIL] 影视页自动活动半屏过滤已注册")
            FeatureInstallResult.Installed(1)
        }.getOrElse { missing(environment, "registration-failed") }
    }

    private fun missing(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("pgc_auto_activity_missing", "[BIL] 影视活动半屏适配未就绪: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "pgc_auto_activity_popup"
        private const val CHANNEL_STATUS = "pgc_auto_activity_popup_status"
        private const val FILTERED_EXTRA = "pgc_auto_activity_filtered"
    }
}
