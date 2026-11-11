package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuPoints
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

/** 仅作用播放器业务对象；不 Hook 全局触摸，不轮询当前速度，不覆盖用户播放中的手动选择。 */
internal class PlayerSpeedFeatureInstaller(
    private val disableLongPress: Boolean,
    longPressPercent: Int,
    defaultPercent: Int,
    speedMenuSpec: String = "",
    private val cachedMenuPoints: PlayerSpeedMenuPoints? = null,
    private val hostVersionCode: Int = 0
) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> get() = buildList {
        if (disableLongPress) add("player_long_press_disabled")
        if (pressSpeed != null) add("player_long_press_speed_percent")
        if (defaultSpeed != null) add("player_default_speed_percent")
        if (menuMultipliers.isNotEmpty()) add(MENU_CAPABILITY)
    }
    private val pressSpeed = PlayerSpeedConfig.multiplier(
        PlayerSpeedConfig.effectiveLongPressPercent(disableLongPress, longPressPercent)
    )
    private val defaultSpeed = PlayerSpeedConfig.multiplier(defaultPercent)
    private val menuMultipliers = PlayerSpeedConfig.menuMultipliers(defaultPercent, speedMenuSpec)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!disableLongPress && pressSpeed == null && defaultSpeed == null && menuMultipliers.isEmpty()) {
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
                "disable-long-press", "disable-long-press-theseus2" -> "player_long_press_disabled"
                "long-press-speed", "long-press-speed-theseus2" -> "player_long_press_speed_percent"
                "speed-menu" -> MENU_CAPABILITY
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
        if (disableLongPress) PlayerSpeedLocator.theseus2LongPressService(loader)?.let { service ->
            attempt("disable-long-press-theseus2") {
                val method = PlayerSpeedLocator.theseus2LongPress(loader, service) ?: return@attempt false
                installTheseus2LongPressOff(environment, method)
                true
            }
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
        pressSpeed?.let { requested -> PlayerSpeedLocator.theseus2LongPressService(loader)?.let { service ->
            attempt("long-press-speed-theseus2") {
                val start = PlayerSpeedLocator.theseus2LongPress(loader, service) ?: return@attempt false
                val calculator = PlayerSpeedLocator.theseus2LongPressCalculator(loader, hostVersionCode) ?: return@attempt false
                installTheseus2LongPressSpeed(environment, start, calculator, requested)
                true
            }
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
        if (menuMultipliers.isNotEmpty()) {
            attempt("speed-menu") {
                val menuPoint = PlayerSpeedLocator.speedMenu(loader, cachedMenuPoints) ?: return@attempt false
                installSpeedMenu(environment, menuPoint)
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

    internal fun installTheseus2LongPressOff(environment: HookEnvironment, method: Method) {
        environment.registrar.exact("player.speed.disable_long_press_theseus2", method.declaringClass, method.name,
            *method.parameterTypes) {
            before {
                environment.reportRuntimeEvidence("player_long_press_disabled", FeatureRuntimeStage.OBSERVED)
                // The gesture caller restores its overlay on null and never opens a temporary-speed lease.
                result = null
                environment.reportRuntimeEvidence("player_long_press_disabled", FeatureRuntimeStage.APPLIED)
            }
        }
    }

    private class Theseus2Press {
        var original: Float? = null
        var effective: Float? = null
    }

    internal fun installTheseus2LongPressSpeed(environment: HookEnvironment, start: Method, calculator: Method, requested: Float) {
        val scope = ThreadLocal<Theseus2Press>()
        environment.registrar.exact("player.speed.theseus2_calculate_long_press", calculator.declaringClass,
            calculator.name, *calculator.parameterTypes) {
            after {
                val press = scope.get() ?: return@after
                if (hasThrowable || press.original != null || argOrNull(1) != null) return@after
                val original = result as? Float ?: return@after
                if (!original.isFinite() || original <= 0f) return@after
                press.original = original
                press.effective = requested
                // This single value is used for the host lease and boxed for the gesture UI.
                if (original != requested) result = requested
            }
        }
        environment.registrar.exact("player.speed.theseus2_long_press_scope", start.declaringClass, start.name,
            *start.parameterTypes) {
            before {
                setObjectExtra(PRESS_PREVIOUS_SCOPE, scope.get())
                if (start.declaringClass.isInstance(instance)) scope.set(Theseus2Press()) else scope.remove()
            }
            after {
                val press = scope.get()
                val previous = getObjectExtra(PRESS_PREVIOUS_SCOPE) as? Theseus2Press
                if (previous == null) scope.remove() else scope.set(previous)
                val effective = press?.effective ?: return@after
                if (hasThrowable || result != effective) return@after
                environment.reportRuntimeEvidence("player_long_press_speed_percent", FeatureRuntimeStage.OBSERVED)
                if (press.original != effective) {
                    environment.reportRuntimeEvidence("player_long_press_speed_percent", FeatureRuntimeStage.APPLIED)
                }
            }
        }
    }

    internal fun installSpeedMenu(
        environment: HookEnvironment,
        menuPoint: PlayerSpeedLocator.SpeedMenuPoint
    ): Boolean {
        if (menuMultipliers.isEmpty() || menuPoint.adapter == null) return false
        var hookedAny = false
        menuPoint.adapter?.let { adapterPoint ->
            val method = adapterPoint.populateMethod
            environment.registrar.exact(
                "player.speed.menu_adapter_populate",
                method.declaringClass,
                method.name,
                *method.parameterTypes
            ) {
                before {
                    val target = instance ?: return@before
                    val currentSpeed = argOrNull(0) as? Float ?: return@before
                    if (!currentSpeed.isFinite()) return@before
                    environment.reportRuntimeEvidence(MENU_CAPABILITY, FeatureRuntimeStage.OBSERVED)
                    val arrayField = adapterPoint.speedsArrayField
                    if (arrayField != null) {
                        val previous = runCatching { arrayField.get(target) as? FloatArray }.getOrNull() ?: return@before
                        setObjectExtra(MENU_PREV_ARRAY, previous)
                        if (runCatching { arrayField.set(target, menuMultipliers.toFloatArray()) }.isSuccess) {
                            setObjectExtra(MENU_CHANGED, true)
                        }
                    } else {
                        val ctor = adapterPoint.optionConstructor ?: return@before
                        val speedField = adapterPoint.optionSpeedField ?: return@before
                        val availField = adapterPoint.optionAvailableField ?: return@before
                        val incoming = argOrNull(1) as? List<*> ?: return@before
                        if (!matchesOptionModels(incoming, speedField, availField, menuMultipliers)) {
                            val replaced = buildOptionModels(ctor, speedField, availField, menuMultipliers) ?: return@before
                            args[1] = replaced
                        }
                        setObjectExtra(MENU_CHANGED, true)
                    }
                }
                after {
                    val target = instance ?: return@after
                    val arrayField = adapterPoint.speedsArrayField
                    if (hasThrowable) {
                        if (arrayField != null) {
                            (getObjectExtra(MENU_PREV_ARRAY) as? FloatArray)?.let { prev ->
                                runCatching { arrayField.set(target, prev) }
                            }
                        }
                        return@after
                    }
                    if (getObjectExtra(MENU_CHANGED) != true) return@after
                    val currentSpeed = argOrNull(0) as? Float ?: return@after
                    if (refineAdapterItems(target, adapterPoint, currentSpeed, menuMultipliers)) {
                        environment.reportRuntimeEvidence(MENU_CAPABILITY, FeatureRuntimeStage.APPLIED)
                    } else if (arrayField != null) {
                        (getObjectExtra(MENU_PREV_ARRAY) as? FloatArray)?.let { prev ->
                            runCatching { arrayField.set(target, prev) }
                        }
                    }
                }
            }
            hookedAny = true
        }
        menuPoint.compactRoute?.let { route ->
            val scope = MenuOpenScope()
            val getter = route.windowDelegateGetter
            environment.registrar.exact(
                "player.speed.menu_compact_window_delegate", getter.declaringClass, getter.name
            ) {
                after {
                    if (!hasThrowable && scope.contains(instance)) result = null
                }
            }
            val open = route.openMethod
            environment.registrar.exact(
                "player.speed.menu_compact_open", open.declaringClass, open.name, *open.parameterTypes
            ) {
                before { setObjectExtra(MENU_OPEN_PREVIOUS, scope.enter(argOrNull(0))) }
                after { scope.restore(getObjectExtra(MENU_OPEN_PREVIOUS)) }
            }
        }
        menuPoint.factory?.let { factoryPoint ->
            val method = factoryPoint.factoryMethod
            environment.registrar.exact(
                "player.speed.menu_options_factory",
                method.declaringClass,
                method.name,
                *method.parameterTypes
            ) {
                after {
                    if (hasThrowable || result !is List<*>) return@after
                    environment.reportRuntimeEvidence(MENU_CAPABILITY, FeatureRuntimeStage.OBSERVED)
                    val replaced = buildOptionModels(
                        factoryPoint.optionConstructor,
                        factoryPoint.optionSpeedField,
                        factoryPoint.optionAvailableField,
                        menuMultipliers
                    ) ?: return@after
                    result = replaced
                    environment.reportRuntimeEvidence(MENU_CAPABILITY, FeatureRuntimeStage.APPLIED)
                }
            }
            hookedAny = true
        }
        return hookedAny
    }

    companion object {
        const val ID = "player_speed"
        const val MENU_CAPABILITY = "player_speed_menu_percents"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL = "player_speed_status"
        private const val PRESS_CHANGED = "player_speed_changed"
        private const val PRESS_PREVIOUS_SCOPE = "player_speed_theseus2_previous_press"
        private const val MENU_CHANGED = "player_speed_menu_changed"
        private const val MENU_PREV_ARRAY = "player_speed_menu_prev_array"
        private const val MENU_OPEN_PREVIOUS = "player_speed_menu_open_previous"
        internal const val SELECTION_EPSILON = 0.005f

        internal class MenuOpenScope {
            private val current = ThreadLocal<Any?>()
            fun enter(target: Any?): Any? = current.get().also { current.set(target) }
            fun contains(target: Any?): Boolean = target != null && current.get() === target
            fun restore(previous: Any?) {
                if (previous == null) current.remove() else current.set(previous)
            }
        }

        internal fun buildOptionModels(
            constructor: Constructor<*>,
            speedField: Field,
            availableField: Field,
            multipliers: List<Float>
        ): ArrayList<Any>? = runCatching {
            if (multipliers.isEmpty()) return@runCatching null
            val list = ArrayList<Any>(multipliers.size)
            for (speed in multipliers) {
                if (!speed.isFinite() || speed !in PlayerSpeedConfig.MIN_MULTIPLIER..PlayerSpeedConfig.MAX_MULTIPLIER) {
                    return@runCatching null
                }
                val item = constructor.newInstance(speed, true) ?: return@runCatching null
                val actual = speedField.getFloat(item)
                if (!actual.isFinite() || abs(actual - speed) > 0.0001f || !availableField.getBoolean(item)) {
                    return@runCatching null
                }
                list.add(item)
            }
            list
        }.getOrNull()

        internal fun matchesOptionModels(
            list: List<*>,
            speedField: Field,
            availableField: Field,
            multipliers: List<Float>
        ): Boolean = runCatching {
            if (list.size != multipliers.size) return@runCatching false
            for (i in multipliers.indices) {
                val item = list[i] ?: return@runCatching false
                val speed = speedField.getFloat(item)
                if (!speed.isFinite() || abs(speed - multipliers[i]) > 0.0001f || !availableField.getBoolean(item)) {
                    return@runCatching false
                }
            }
            true
        }.getOrDefault(false)

        /**
         * 校验并修正 Adapter 内部条目列表：
         * 宿主原版使用 `< 0.1` 的宽松比较标记选中项，当用户配置了两位小数邻近档位（如 1.0 与 1.05）时，
         * 会把多项同时标为选中，进而导致点击邻近档位在 `onClick` 中因 `isSelected == true` 提前返回。
         * 这里改为仅在误差 `< SELECTION_EPSILON` 内挑选唯一最接近的档位高亮。
         */
        internal fun refineAdapterItems(
            adapterInstance: Any,
            point: PlayerSpeedLocator.SpeedMenuAdapterPoint,
            currentSpeed: Float,
            expectedMultipliers: List<Float>
        ): Boolean = runCatching {
            if (!currentSpeed.isFinite() || expectedMultipliers.isEmpty()) return@runCatching false
            val items = point.itemsField.get(adapterInstance) as? List<*> ?: return@runCatching false
            if (items.size != expectedMultipliers.size) return@runCatching false
            // 旧 core 将 2.0 写成 1.99；用户没有单列 1.99 档位时沿用宿主的 2.0 高亮语义。
            val selectionSpeed = if (abs(currentSpeed - 1.99f) < 0.0001f &&
                expectedMultipliers.none { abs(it - 1.99f) < 0.0001f } &&
                expectedMultipliers.any { abs(it - 2f) < 0.0001f }) 2f else currentSpeed
            var bestIndex = -1
            var bestDiff = SELECTION_EPSILON
            for (index in items.indices) {
                val item = items[index] ?: return@runCatching false
                val speed = point.itemSpeedField.getFloat(item)
                if (!speed.isFinite() || abs(speed - expectedMultipliers[index]) > 0.0001f) return@runCatching false
                val diff = abs(selectionSpeed - speed)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIndex = index
                }
            }
            for (index in items.indices) {
                val item = items[index] ?: return@runCatching false
                point.itemSelectedField.setBoolean(item, index == bestIndex)
                point.itemAvailableField?.setBoolean(item, true)
            }
            true
        }.getOrDefault(false)

        internal fun applyDefaultSpeed(
            target: Any,
            point: PlayerSpeedLocator.DefaultSpeedPoint,
            requested: Float
        ): DefaultSpeedResult {
            if (!requested.isFinite() || requested !in PlayerSpeedConfig.MIN_MULTIPLIER..PlayerSpeedConfig.MAX_MULTIPLIER ||
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
