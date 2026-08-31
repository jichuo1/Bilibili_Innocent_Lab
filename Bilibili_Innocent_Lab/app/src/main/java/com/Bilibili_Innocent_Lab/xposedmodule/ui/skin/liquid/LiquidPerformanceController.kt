package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.content.Context
import androidx.annotation.MainThread
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion

internal interface LiquidPerformanceHintSession : AutoCloseable {
    val isActive: Boolean

    fun updateTargetWorkDuration(targetDurationNanos: Long)

    fun reportActualWorkDuration(actualDurationNanos: Long)
}

internal interface LiquidThermalMonitor : AutoCloseable {
    val currentThermalStatus: Int

    fun start()

    fun stop()
}

internal data class LiquidPerformanceDiagnostics(
    val active: Boolean,
    val adpfSessionActive: Boolean,
    val thermalMonitorActive: Boolean,
    val thermalStatus: Int,
    val targetWorkDurationNanos: Long,
    val lastReportedWorkDurationNanos: Long
)

/**
 * 实时取样的进程内性能协调器。
 *
 * ADPF 只描述本进程主线程实际完成的周期工作；PixelCopy 异步等待和系统合成耗时不伪装成 CPU
 * 工作量。Thermal 监听与 ADPF 会话严格跟随前台实时会话启停，标准 Liquid/Material 不会启动。
 */
internal class LiquidPerformanceController(
    context: Context,
    private val onThermalStatusChanged: (Int) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private val thermalMonitor = createThermalMonitor()

    private var hintSession: LiquidPerformanceHintSession? = null
    private var active = false
    private var closed = false
    private var targetWorkDurationNanos = 0L
    private var lastReportedWorkDurationNanos = 0L
    private var thermalStatus = LiquidPerformancePolicy.THERMAL_STATUS_NONE

    val currentThermalStatus: Int
        get() = thermalStatus

    val diagnostics: LiquidPerformanceDiagnostics
        get() = LiquidPerformanceDiagnostics(
            active = active,
            adpfSessionActive = hintSession?.isActive == true,
            thermalMonitorActive = active && thermalMonitor != null,
            thermalStatus = thermalStatus,
            targetWorkDurationNanos = targetWorkDurationNanos,
            lastReportedWorkDurationNanos = lastReportedWorkDurationNanos
        )

    @MainThread
    fun start(initialTargetWorkDurationNanos: Long) {
        if (closed) return
        val target = initialTargetWorkDurationNanos.coerceAtLeast(1L)
        if (active) {
            updateTargetWorkDuration(target)
            return
        }
        active = true
        targetWorkDurationNanos = target
        thermalMonitor?.start()
        thermalStatus = LiquidPerformancePolicy.normalizeThermalStatus(
            thermalMonitor?.currentThermalStatus
                ?: LiquidPerformancePolicy.THERMAL_STATUS_NONE
        )
        hintSession = createPerformanceHintSession(targetWorkDurationNanos)
    }

    @MainThread
    fun updateTargetWorkDuration(targetDurationNanos: Long) {
        if (closed) return
        val target = targetDurationNanos.coerceAtLeast(1L)
        if (targetWorkDurationNanos == target) return
        targetWorkDurationNanos = target
        if (active) hintSession?.updateTargetWorkDuration(target)
    }

    @MainThread
    fun reportActualWorkDuration(actualDurationNanos: Long) {
        if (!active || closed) return
        val actual = actualDurationNanos.coerceAtLeast(1L)
        lastReportedWorkDurationNanos = actual
        hintSession?.reportActualWorkDuration(actual)
    }

    @MainThread
    fun stop() {
        if (!active) return
        active = false
        hintSession?.close()
        hintSession = null
        thermalMonitor?.stop()
        targetWorkDurationNanos = 0L
        lastReportedWorkDurationNanos = 0L
    }

    @MainThread
    override fun close() {
        if (closed) return
        stop()
        closed = true
        thermalMonitor?.close()
    }

    private fun handleThermalStatusChanged(status: Int) {
        if (!active || closed) return
        val normalized = LiquidPerformancePolicy.normalizeThermalStatus(status)
        if (thermalStatus == normalized) return
        thermalStatus = normalized
        onThermalStatusChanged(normalized)
    }

    private fun createThermalMonitor(): LiquidThermalMonitor? =
        if (AndroidVersion.isAtLeast(AndroidVersion.Q)) {
            LiquidThermalMonitorApi29(appContext, ::handleThermalStatusChanged)
        } else null

    private fun createPerformanceHintSession(
        targetDurationNanos: Long
    ): LiquidPerformanceHintSession? = if (AndroidVersion.isAtLeast(AndroidVersion.S)) {
        LiquidPerformanceHintApi31.create(appContext, targetDurationNanos)
    } else null
}
