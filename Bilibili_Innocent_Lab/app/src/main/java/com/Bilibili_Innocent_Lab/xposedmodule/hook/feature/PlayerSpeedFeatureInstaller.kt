package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator

/** 仅作用播放器业务对象；不 Hook 全局触摸，不轮询当前速度，不覆盖用户播放中的手动选择。 */
internal class PlayerSpeedFeatureInstaller(
    private val disableLongPress: Boolean,
    longPressPercent: Int,
    defaultPercent: Int
) : FeatureInstaller {
    override val id = ID
    private val pressSpeed = PlayerSpeedConfig.multiplier(
        PlayerSpeedConfig.effectiveLongPressPercent(disableLongPress, longPressPercent)
    )
    private val defaultSpeed = PlayerSpeedConfig.multiplier(defaultPercent)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!disableLongPress && pressSpeed == null && defaultSpeed == null) {
            environment.reportStatus(CHANNEL, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        var expected = 0
        var installed = 0
        fun attempt(unit: String, block: () -> Boolean) {
            expected++
            if (runCatching(block).getOrElse {
                    environment.logError("player_speed_register_$unit", "[BIL] 播放速度注册失败($unit): $it")
                    false
                }) {
                installed++
            } else {
                environment.logError("player_speed_missing_$unit", "[BIL] 播放速度缺少唯一可用结构($unit)")
            }
        }
        if (disableLongPress) attempt("disable-long-press") {
            val method = PlayerSpeedLocator.longPress(loader) ?: return@attempt false
            environment.registrar.exact("player.speed.disable_long_press", method.declaringClass,
                method.name, *method.parameterTypes) {
                before {
                    if (argOrNull(0) == null) return@before
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    // 仅消费 TripleSpeed 的加速开始；松手清理仍交给宿主，其他手势处理器不变。
                    result = true
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
            true
        }
        pressSpeed?.let { requested -> attempt("long-press-speed") {
            val point = PlayerSpeedLocator.longPressSpeed(loader) ?: return@attempt false
            environment.registrar.constructor("player.speed.long_press_value", point.constructor) {
                before {
                    val original = argOrNull(point.speedIndex) as? Float ?: return@before
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    if (original == requested) return@before
                    args[point.speedIndex] = requested
                    setObjectExtra(PRESS_CHANGED, true)
                }
                after {
                    if (hasThrowable || getObjectExtra(PRESS_CHANGED) != true) return@after
                    val target = instance ?: return@after
                    if (runCatching { point.speedField.getFloat(target) == requested }.getOrDefault(false)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            true
        } }
        defaultSpeed?.let { requested -> attempt("default-speed") {
            val point = PlayerSpeedLocator.defaultSpeed(loader) ?: return@attempt false
            environment.logInfo("player_speed_default_structure", "[BIL] 默认倍速已核对基础/临时双 Flow 与速度 getter 组")
            environment.registrar.constructor("player.speed.initial_value", point.constructor) {
                after {
                    if (hasThrowable) return@after
                    val target = instance ?: return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    val outcome = applyDefaultSpeed(target, point, requested)
                    if (outcome == DefaultSpeedResult.APPLIED) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    } else if (outcome != DefaultSpeedResult.UNCHANGED) {
                        environment.logError("player_speed_default_${outcome.name}",
                            "[BIL] 默认倍速保持宿主行为: ${outcome.name}")
                    }
                }
            }
            true
        } }
        val status = if (installed == expected) "success" else "partial:$installed/$expected"
        environment.reportStatus(CHANNEL, status)
        if (installed == 0) return FeatureInstallResult.Skipped("missing-player-speed-points")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.logInfo("player_speed_installed", "[BIL] 播放速度已安装($status)")
        return FeatureInstallResult.Installed(installed)
    }

    internal enum class DefaultSpeedResult { APPLIED, UNCHANGED, UNEXPECTED_STATE, FAILED }

    companion object {
        const val ID = "player_speed"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL = "player_speed_status"
        private const val PRESS_CHANGED = "player_speed_changed"

        internal fun applyDefaultSpeed(
            target: Any,
            point: PlayerSpeedLocator.DefaultSpeedPoint,
            requested: Float
        ): DefaultSpeedResult {
            if (!requested.isFinite() || requested !in 0.25f..4f ||
                point.flows.size != 2 || point.speedGetters.size !in 1..2) {
                return DefaultSpeedResult.UNEXPECTED_STATE
            }
            var baseFlow: Any? = null
            return runCatching {
                if (point.speedGetters.any { it.invoke(target) != 1f }) return@runCatching DefaultSpeedResult.UNEXPECTED_STATE
                for (field in point.flows) {
                    val flow = field.get(target) ?: return@runCatching DefaultSpeedResult.UNEXPECTED_STATE
                    when (point.readValue.invoke(flow)) {
                        null -> Unit // 临时加速槽位，绝不能改成默认速度。
                        1f -> {
                            if (baseFlow != null) return@runCatching DefaultSpeedResult.UNEXPECTED_STATE
                            baseFlow = flow
                        }
                        else -> return@runCatching DefaultSpeedResult.UNEXPECTED_STATE
                    }
                }
                val selected = baseFlow ?: return@runCatching DefaultSpeedResult.UNEXPECTED_STATE
                if (requested == 1f) return@runCatching DefaultSpeedResult.UNCHANGED
                point.writeValue.invoke(selected, requested)
                if (point.readValue.invoke(selected) != requested || point.speedGetters.any { it.invoke(target) != requested }) {
                    error("Default speed readback failed")
                }
                DefaultSpeedResult.APPLIED
            }.getOrElse {
                // 构造器阶段唯一候选原值是 1f；写后校验失败时尽力恢复，不报虚假的 APPLIED。
                baseFlow?.let { flow -> runCatching {
                    if (point.readValue.invoke(flow) == requested) point.writeValue.invoke(flow, 1f)
                } }
                DefaultSpeedResult.FAILED
            }
        }
    }
}
