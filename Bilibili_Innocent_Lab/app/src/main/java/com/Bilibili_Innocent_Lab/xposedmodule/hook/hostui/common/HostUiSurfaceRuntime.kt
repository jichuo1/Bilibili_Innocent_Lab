package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.bilab.plugin.api.model.HostThemeSnapshot
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import java.lang.ref.WeakReference

/**
 * 宿主 UI 表面内部公共运行时（仅供内部核心与 Tier-A 使用，严禁暴露给外部 `:plugin-api`）。
 *
 * 复用 [HostResourceRegistry] 提供统一可回收资源注册封装，并向外裁剪提供只读 [HostThemeSnapshot]，
 * 隔离 `HostSurfaceScope`、`LumenSurfaceSession` 与宿主 `View`。
 */
internal object HostUiSurfaceRuntime {
    private const val DEFAULT_RESOURCE_TAG = "host_ui_surface"
    internal const val SEVERE_THERMAL_PRESSURE = 0.75f
    internal const val CRITICAL_THERMAL_PRESSURE = 1.0f
    internal const val DEFAULT_SLOW_FRAME_THRESHOLD_MS = 32L
    internal const val DEFAULT_SLOW_FRAME_TRIP_COUNT = 3
    internal const val DEFAULT_GPU_FAILURE_TRIP_COUNT = 2
    internal const val DEFAULT_RECOVERY_HEALTHY_FRAMES = 12
    internal const val DEFAULT_RECOVERY_COOLDOWN_MS = 5_000L

    /**
     * 宿主 UI 表面自动降级档位：完整Requested材质 -> Frosted 模糊 -> 软件绘制。
     */
    internal enum class DegradeTier(val level: Int) {
        NONE(0),
        FROSTED(1),
        SOFTWARE(2);

        internal fun nextDown(requested: HostChromeAppearance? = null): DegradeTier = when (this) {
            NONE -> if (requested?.material == HostGlassMaterial.SOFT) SOFTWARE else FROSTED
            FROSTED -> SOFTWARE
            SOFTWARE -> SOFTWARE
        }

        internal fun nextUp(): DegradeTier = when (this) {
            NONE -> NONE
            FROSTED -> NONE
            SOFTWARE -> FROSTED
        }
    }

    /**
     * 纯 JVM 可单测的连续慢帧 / GPU 绘制失败 / 热降级与内存压力断路器状态机。
     *
     * 将瞬时故障档位与热/内存压力下限分离，避免软件绘制恢复后在无退避下震荡。
     */
    internal class SurfaceDegradeCircuitBreaker(
        private val slowFrameThresholdMs: Long = DEFAULT_SLOW_FRAME_THRESHOLD_MS,
        private val slowFrameTripCount: Int = DEFAULT_SLOW_FRAME_TRIP_COUNT,
        private val gpuFailureTripCount: Int = DEFAULT_GPU_FAILURE_TRIP_COUNT,
        private val recoveryHealthyFrames: Int = DEFAULT_RECOVERY_HEALTHY_FRAMES,
        private val recoveryCooldownMs: Long = DEFAULT_RECOVERY_COOLDOWN_MS
    ) {
        private var faultTier: DegradeTier = DegradeTier.NONE
        private var thermalTier: DegradeTier = DegradeTier.NONE
        private var trimTier: DegradeTier = DegradeTier.NONE
        private var consecutiveSlowFrames = 0
        private var consecutiveGpuFailures = 0
        private var consecutiveHealthyFrames = 0
        private var lastFaultDegradeMs = 0L

        val currentTier: DegradeTier
            get() = maxOf(faultTier, thermalTier, trimTier)

        val slowFrameStreak: Int
            get() = consecutiveSlowFrames

        val gpuFailureStreak: Int
            get() = consecutiveGpuFailures

        val isCriticalTrimActive: Boolean
            get() = trimTier == DegradeTier.SOFTWARE

        fun recordFrameDuration(
            frameDurationMs: Long,
            nowMs: Long = 0L,
            requested: HostChromeAppearance? = null
        ): DegradeTier {
            if (frameDurationMs >= slowFrameThresholdMs) {
                consecutiveHealthyFrames = 0
                consecutiveSlowFrames++
                if (consecutiveSlowFrames >= slowFrameTripCount) {
                    stepDownFault(nowMs, requested)
                }
            } else {
                consecutiveSlowFrames = 0
                consecutiveGpuFailures = 0
                consecutiveHealthyFrames++
            }
            return currentTier
        }

        fun recordGpuFailure(
            nowMs: Long = 0L,
            requested: HostChromeAppearance? = null
        ): DegradeTier {
            consecutiveHealthyFrames = 0
            consecutiveGpuFailures++
            if (consecutiveGpuFailures >= gpuFailureTripCount) {
                stepDownFault(nowMs, requested)
            }
            return currentTier
        }

        fun recordThermalPressure(pressure: Float, @Suppress("UNUSED_PARAMETER") nowMs: Long = 0L): DegradeTier {
            thermalTier = when {
                pressure >= CRITICAL_THERMAL_PRESSURE -> DegradeTier.SOFTWARE
                pressure >= SEVERE_THERMAL_PRESSURE -> DegradeTier.FROSTED
                else -> DegradeTier.NONE
            }
            return currentTier
        }

        fun recordTrimSeverity(severity: HostTrimSeverity, @Suppress("UNUSED_PARAMETER") nowMs: Long = 0L): DegradeTier {
            trimTier = when (severity) {
                HostTrimSeverity.CRITICAL -> DegradeTier.SOFTWARE
                HostTrimSeverity.MODERATE -> if (trimTier < DegradeTier.FROSTED) DegradeTier.FROSTED else trimTier
                HostTrimSeverity.HIDDEN -> trimTier
            }
            return currentTier
        }

        fun resolveAppearance(requested: HostChromeAppearance): HostChromeAppearance {
            val tier = currentTier
            if (tier == DegradeTier.NONE) return requested
            if (requested.material == HostGlassMaterial.SOFT && requested.effectiveRenderer == HostGlassRenderer.SOFTWARE) {
                return requested
            }
            return when (tier) {
                DegradeTier.NONE -> requested
                DegradeTier.FROSTED -> when (requested.material) {
                    HostGlassMaterial.PRISM, HostGlassMaterial.LIQUID -> HostChromeAppearance(
                        material = HostGlassMaterial.SOFT,
                        renderer = HostGlassRenderer.AUTO
                    )
                    HostGlassMaterial.SOFT -> requested
                }
                DegradeTier.SOFTWARE -> HostChromeAppearance(
                    material = HostGlassMaterial.SOFT,
                    renderer = HostGlassRenderer.SOFTWARE
                )
            }
        }

        fun tryRecover(
            visible: Boolean = true,
            hasWindowFocus: Boolean = true,
            criticalPressure: Boolean = false,
            thermalPressure: Float = 0f,
            nowMs: Long = 0L
        ): Boolean {
            recordThermalPressure(thermalPressure, nowMs)
            if (criticalPressure || thermalPressure >= CRITICAL_THERMAL_PRESSURE) {
                if (criticalPressure && trimTier < DegradeTier.SOFTWARE) {
                    trimTier = DegradeTier.SOFTWARE
                }
                return false
            }
            val before = currentTier
            if (!visible || !hasWindowFocus) return false

            if (trimTier != DegradeTier.NONE) {
                trimTier = DegradeTier.NONE
            }
            if (faultTier != DegradeTier.NONE && (nowMs - lastFaultDegradeMs) >= recoveryCooldownMs) {
                faultTier = if (nowMs - lastFaultDegradeMs >= recoveryCooldownMs * 2L ||
                    consecutiveHealthyFrames >= recoveryHealthyFrames
                ) {
                    DegradeTier.NONE
                } else {
                    faultTier.nextUp()
                }
                consecutiveSlowFrames = 0
                consecutiveGpuFailures = 0
                consecutiveHealthyFrames = 0
            }
            return currentTier != before
        }

        fun reset() {
            faultTier = DegradeTier.NONE
            thermalTier = DegradeTier.NONE
            trimTier = DegradeTier.NONE
            consecutiveSlowFrames = 0
            consecutiveGpuFailures = 0
            consecutiveHealthyFrames = 0
            lastFaultDegradeMs = 0L
        }

        private fun stepDownFault(nowMs: Long, requested: HostChromeAppearance?) {
            faultTier = faultTier.nextDown(requested)
            consecutiveSlowFrames = 0
            consecutiveGpuFailures = 0
            consecutiveHealthyFrames = 0
            lastFaultDegradeMs = nowMs
        }
    }

    /**
     * 向 [HostResourceRegistry] 登记统一裁剪回调并返回幂等 [AutoCloseable] 租约句柄。
     */
    fun registerResource(
        tag: String = DEFAULT_RESOURCE_TAG,
        onTrim: (HostTrimSeverity) -> Unit
    ): AutoCloseable = HostResourceRegistry.register(tag, onTrim)

    /**
     * 按分级裁剪回调登记宿主 UI 资源；未单独指定 [onCritical] 时自动回退至 [onModerate]。
     */
    fun registerResource(
        tag: String = DEFAULT_RESOURCE_TAG,
        onHidden: (() -> Unit)? = null,
        onModerate: (() -> Unit)? = null,
        onCritical: (() -> Unit)? = null
    ): AutoCloseable = HostResourceRegistry.register(
        tag = tag,
        onHidden = onHidden,
        onModerate = onModerate,
        onCritical = onCritical
    )

    /**
     * 弱引用绑定 [HostSurfaceScope] 至 [HostResourceRegistry]，在中度压力时 `detach`，
     * 紧急压力时 `close`，避免反向强持有宿主视图树。
     */
    fun bindSurfaceScope(
        scope: HostSurfaceScope,
        tag: String = "host_surface_scope",
        detachOnHidden: Boolean = false
    ): AutoCloseable {
        val scopeRef = WeakReference(scope)
        val leaseHolder = arrayOfNulls<AutoCloseable>(1)
        val lease = HostResourceRegistry.register(tag) { severity ->
            val target = scopeRef.get()
            if (target == null) {
                leaseHolder[0]?.close()
                return@register
            }
            when (severity) {
                HostTrimSeverity.HIDDEN -> if (detachOnHidden) target.detach()
                HostTrimSeverity.MODERATE -> target.detach()
                HostTrimSeverity.CRITICAL -> target.close()
            }
        }
        leaseHolder[0] = lease
        return lease
    }

    /**
     * 创建受 [HostResourceRegistry] 托管的局部 [HostSurfaceScope] 及对应统一清理句柄。
     */
    fun createManagedSurfaceScope(
        anchor: View,
        colors: HostChromeColors,
        tag: String = "host_surface_scope",
        samplingBackend: LumenSurfaceBackend = LumenSurfaceBackend.AUTO,
        appearance: HostChromeAppearance = HostChromeAppearance(),
        reuseGpuFrames: Boolean = HostUiPowerPolicy.current.retainsGpuFrames,
        detachOnHidden: Boolean = false
    ): Pair<HostSurfaceScope, AutoCloseable> {
        val scope = HostSurfaceScope(
            anchor = anchor,
            colors = colors,
            samplingBackend = samplingBackend,
            appearance = appearance,
            reuseGpuFrames = reuseGpuFrames
        )
        val lease = bindSurfaceScope(scope, tag = tag, detachOnHidden = detachOnHidden)
        return scope to AutoCloseable {
            try {
                lease.close()
            } finally {
                scope.close()
            }
        }
    }

    /**
     * 当前仍处于活跃状态的宿主资源租约数量。
     */
    fun activeResourceCount(): Int = HostResourceRegistry.activeCount()

    /**
     * 读取宿主当前归一化热压力（0.0 = 正常，0.75 = Severe，1.0 = Critical）。
     */
    fun currentThermalPressure(): Float = HostMemoryPressureBridge.currentThermalPressure()

    /**
     * 判断当前是否处于严重图形压力或 Critical 热降级区间。
     */
    fun isCriticalGraphicsPressure(
        thermalPressure: Float = currentThermalPressure(),
        criticalTrimActive: Boolean = false
    ): Boolean = criticalTrimActive || thermalPressure >= CRITICAL_THERMAL_PRESSURE

    /**
     * 将热压力与内存裁剪事件接入指定表面断路器（按调用方租约随 attach/detach 回收，不常驻空租约）。
     */
    fun registerSurfacePressureObserver(
        tag: String = DEFAULT_RESOURCE_TAG,
        onPressureChanged: (HostTrimSeverity, Float) -> Unit
    ): AutoCloseable = HostResourceRegistry.register(tag) { severity ->
        onPressureChanged(severity, currentThermalPressure())
    }

    /**
     * 根据当前系统热状态刷新断路器档位。
     */
    fun syncThermalPressure(
        breaker: SurfaceDegradeCircuitBreaker,
        nowMs: Long = 0L
    ): DegradeTier = breaker.recordThermalPressure(currentThermalPressure(), nowMs)

    /**
     * 构建只读宿主主题快照，仅向插件与外观配方暴露纯数值配色与密度信息。
     */
    fun snapshotTheme(
        isDark: Boolean,
        surfaceColor: Int,
        accentColor: Int,
        textPrimaryColor: Int,
        textSecondaryColor: Int,
        density: Float,
        cornerRadiusDp: Float = 16f
    ): HostThemeSnapshot = HostThemeSnapshot(
        isDark = isDark,
        surfaceColor = surfaceColor,
        accentColor = accentColor,
        textPrimaryColor = textPrimaryColor,
        textSecondaryColor = textSecondaryColor,
        density = density,
        cornerRadiusDp = cornerRadiusDp
    )
}
