package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator

/** 仅作用播放器业务对象；不 Hook 全局触摸，不轮询当前速度，不覆盖用户播放中的手动选择。 */
internal class PlayerSpeedFeatureInstaller(
    private val disableLongPress: Boolean,
    longPressPercent: Int,
    defaultPercent: Int
) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> get() = buildList {
        if (disableLongPress) add("player_long_press_disabled")
        if (pressSpeed != null) add("player_long_press_speed_percent")
        if (defaultSpeed != null) add("player_default_speed_percent")
    }
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
        val coverage = linkedMapOf<String, Pair<Int, Int>>()
        val sessions = defaultSpeed?.let(::PlayerSpeedSessions)
        val defaultPoint = if (defaultSpeed != null) PlayerSpeedLocator.defaultSpeed(loader) else null
        val preparedReady = java.util.concurrent.atomic.AtomicBoolean(false)
        fun attempt(unit: String, block: () -> Boolean) {
            val capability = when (unit) {
                "disable-long-press" -> "player_long_press_disabled"
                "long-press-speed" -> "player_long_press_speed_percent"
                else -> "player_default_speed_percent"
            }
            val beforeInstalled = installed
            expected++
            if (runCatching(block).getOrElse {
                    environment.logError("player_speed_register_$unit", "[BIL] 播放速度注册失败($unit): $it")
                    false
                }) {
                installed++
            } else {
                environment.logError("player_speed_missing_$unit", "[BIL] 播放速度缺少唯一可用结构($unit)")
            }
            synchronized(coverage) {
                val old = coverage[capability] ?: (0 to 0)
                coverage[capability] = (old.first + installed - beforeInstalled) to (old.second + 1)
            }
        }
        if (disableLongPress) attempt("disable-long-press") {
            val method = PlayerSpeedLocator.longPress(loader) ?: return@attempt false
            environment.registrar.exact("player.speed.disable_long_press", method.declaringClass,
                method.name, *method.parameterTypes) {
                before {
                    if (argOrNull(0) == null) return@before
                    environment.reportRuntimeEvidence("player_long_press_disabled", FeatureRuntimeStage.OBSERVED)
                    // 仅消费 TripleSpeed 的加速开始；松手清理仍交给宿主，其他手势处理器不变。
                    result = true
                    environment.reportRuntimeEvidence("player_long_press_disabled", FeatureRuntimeStage.APPLIED)
                }
            }
            true
        }
        pressSpeed?.let { requested -> attempt("long-press-speed") {
            val point = PlayerSpeedLocator.longPressSpeed(loader) ?: return@attempt false
            environment.registrar.constructor("player.speed.long_press_value", point.constructor) {
                before {
                    val original = argOrNull(point.speedIndex) as? Float ?: return@before
                    environment.reportRuntimeEvidence("player_long_press_speed_percent", FeatureRuntimeStage.OBSERVED)
                    if (original == requested) return@before
                    args[point.speedIndex] = requested
                    setObjectExtra(PRESS_CHANGED, true)
                }
                after {
                    if (hasThrowable || getObjectExtra(PRESS_CHANGED) != true) return@after
                    val target = instance ?: return@after
                    if (runCatching { point.speedField.getFloat(target) == requested }.getOrDefault(false)) {
                        environment.reportRuntimeEvidence("player_long_press_speed_percent", FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            true
        } }
        defaultSpeed?.let { requested -> attempt("default-speed") {
            val point = defaultPoint ?: return@attempt false
            environment.logInfo("player_speed_default_structure", "[BIL] 默认倍速已核对基础/临时双 Flow 与速度 getter 组")
            environment.registrar.constructor("player.speed.initial_value", point.constructor) {
                after {
                    if (hasThrowable) return@after
                    val target = instance ?: return@after
                    runCatching { sessions?.capture(target, point) }
                        .onFailure { environment.logError("player_speed_capture", "[BIL] 基础倍速槽位登记失败: $it") }
                    environment.reportRuntimeEvidence("player_default_speed_percent", FeatureRuntimeStage.OBSERVED)
                    val outcome = applyDefaultSpeed(target, point, requested)
                    if (outcome == DefaultSpeedResult.APPLIED || outcome == DefaultSpeedResult.UNCHANGED) {
                        sessions?.initialized(target)
                    } else {
                        // A failed initial semantic readback must not become an approved base later.
                        sessions?.discardCapture(target)
                    }
                    if (outcome == DefaultSpeedResult.APPLIED) {
                        environment.reportRuntimeEvidence("player_default_speed_percent", FeatureRuntimeStage.APPLIED)
                    } else if (outcome == DefaultSpeedResult.UNEXPECTED_STATE) {
                        environment.logInfo("player_speed_default_unexpected_state",
                            "[BIL] 默认倍速不满足初始状态，保留宿主当前速度")
                    } else if (outcome == DefaultSpeedResult.FAILED) {
                        environment.logError("player_speed_default_${outcome.name}",
                            "[BIL] 默认倍速保持宿主行为: ${outcome.name}")
                    }
                }
            }
            true
        } }
        if (defaultSpeed != null && sessions != null) {
            attempt("default-session") {
                val point = PlayerSpeedSessionLocator.modern(loader) ?: return@attempt false
                val speed = defaultPoint ?: return@attempt false
                sessions.installModern(environment, point, speed)
                point.wrapper != null
            }
            attempt("default-prepared") {
                val point = PlayerSpeedSessionLocator.prepared(loader) ?: return@attempt false
                sessions.installPrepared(environment, point, defaultPoint) {
                    if (!preparedReady.compareAndSet(false, true)) return@installPrepared
                    synchronized(coverage) { coverage[PlayerSpeedSessions.CAPABILITY] }?.let { counts ->
                        environment.reportCapabilityCoverage(PlayerSpeedSessions.CAPABILITY, true, counts.first + 1, counts.second + 1)
                    }
                    if (environment.runtimePhase?.invoke() == true) {
                        environment.installationEvidence?.invoke(FeatureInstallRecord(ID,
                            FeatureInstallResult.Installed(installed + 1, installed == expected), null))
                        environment.reportStatus(CHANNEL, if (installed == expected) "success" else "partial:$installed/$expected")
                    }
                }
                true
            }
        }
        coverage.forEach { (capability, counts) ->
            // Registering a discovery anchor is not proof that the concrete prepared callback installed.
            environment.reportCapabilityCoverage(capability, true,
                counts.first + if (capability == PlayerSpeedSessions.CAPABILITY && preparedReady.get()) 1 else 0,
                counts.second + if (capability == PlayerSpeedSessions.CAPABILITY) 1 else 0)
        }
        val deferred = defaultSpeed != null && !preparedReady.get()
        val expectedTotal = expected + if (defaultSpeed != null) 1 else 0
        val installedTotal = installed + if (preparedReady.get()) 1 else 0
        val status = if (installedTotal == expectedTotal) "success" else
            "partial:$installedTotal/$expectedTotal" + if (deferred) ":awaiting-prepared" else ""
        environment.reportStatus(CHANNEL, status)
        if (installed == 0) return FeatureInstallResult.Skipped("missing-player-speed-points")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.logInfo("player_speed_installed", "[BIL] 播放速度已安装($status)")
        return FeatureInstallResult.Installed(installedTotal, complete = installedTotal == expectedTotal)
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
