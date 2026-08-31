package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.content.Context
import android.os.PowerManager
import androidx.annotation.RequiresApi

/** API 29+ Thermal 状态监听；回调由应用主线程 Executor 串行分发。 */
@RequiresApi(29)
internal class LiquidThermalMonitorApi29(
    private val context: Context,
    private val onStatusChanged: (Int) -> Unit
) : LiquidThermalMonitor {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        if (!started) return@OnThermalStatusChangedListener
        val normalized = LiquidPerformancePolicy.normalizeThermalStatus(status)
        currentThermalStatus = normalized
        onStatusChanged(normalized)
    }

    private var started = false

    override var currentThermalStatus: Int = LiquidPerformancePolicy.THERMAL_STATUS_NONE
        private set

    override fun start() {
        if (started || powerManager == null) return
        currentThermalStatus = runCatching { powerManager.currentThermalStatus }
            .map(LiquidPerformancePolicy::normalizeThermalStatus)
            .getOrDefault(LiquidPerformancePolicy.THERMAL_STATUS_NONE)
        started = true
        if (runCatching {
                powerManager.addThermalStatusListener(context.mainExecutor, listener)
            }.isFailure
        ) {
            started = false
        }
    }

    override fun stop() {
        if (!started || powerManager == null) return
        started = false
        runCatching { powerManager.removeThermalStatusListener(listener) }
    }

    override fun close() = stop()
}
