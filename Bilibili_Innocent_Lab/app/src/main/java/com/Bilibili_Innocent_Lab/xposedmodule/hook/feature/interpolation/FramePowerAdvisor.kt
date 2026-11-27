package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 功耗与温控：
 * - 温控余量（API 30+，API 35+ 结合 getThermalHeadroomThresholds 归一化）：后台每秒最多采样一次并更新
 *   @Volatile 快照；渲染/GL 热路径仅无阻塞读取缓存快照，绝不在回调线程发起 Binder IPC。
 * - ADPF（API 31+）：为捕获线程建立性能提示会话，目标时长为一个输出周期，按帧回报实际 CPU 工作时长，
 *   让系统按实际负载选择频点与核心类型，而不是靠调度器猜（读回、RGBA→RGB 紧凑与上传是每帧固定的 CPU 工作）。
 * - 电池温度（API 27+ 全版本）：真机上 getThermalHeadroom 可能恒为 0、温控状态恒为 NONE，而传感器已过热；
 *   电池温度在 API 27–29 及以上均可无权限读取（ACTION_BATTERY_CHANGED 粘性广播），达到 [BATTERY_LIMIT_C] 视同严重温控。
 * 设备不支持时各项都静默不启用。
 */
internal class FramePowerAdvisor internal constructor(
    private val context: Context?,
    private val targetNs: Long,
    private val thermalSampler: (() -> ThermalSample)?,
    private val thermalExecutor: Executor
) {
    constructor(context: Context?, targetNs: Long) : this(context, targetNs, null, THERMAL_EXECUTOR)

    data class ThermalSample(
        val headroom: Float = Float.NaN,
        val severeStatus: Boolean = false,
        val batteryC: Float = Float.NaN,
        val severeThreshold: Float? = null
    )

    private val power: PowerManager? by lazy {
        runCatching { context?.getSystemService(PowerManager::class.java) }.getOrNull()
    }
    /** 只留 Any?：API 31 类型一律在 [FramePowerHintApi31] 里，不能进本类的字段和方法体。 */
    private var session: Any? = null
    private var attached = false
    @Volatile private var closed = false
    @Volatile private var lastScheduleNs = Long.MIN_VALUE
    @Volatile private var latestSnapshot: Float? = null
    private val refreshPending = AtomicBoolean(false)
    @Volatile var lastHeadroom: Float = Float.NaN
        private set
    @Volatile var lastBatteryC: Float = Float.NaN
        private set
    @Volatile var lastSevereThreshold: Float = 1f
        private set

    /** 在捕获线程上调用一次，之后的回报计入该线程。 */
    fun attachCurrentThread() {
        if (attached || !AndroidVersion.isAtLeast(AndroidVersion.S)) return
        attached = true
        session = runCatching { context?.let { FramePowerHintApi31.createSession(it, targetNs) } }.getOrNull()
    }

    fun reportWork(durationNs: Long) {
        if (!AndroidVersion.isAtLeast(AndroidVersion.S) || durationNs <= 0L) return
        val current = session ?: return
        runCatching { FramePowerHintApi31.report(current, durationNs) }
    }

    /** 热路径无阻塞读取最近一次后台采样快照；距离上次调度满 1 s 时触发异步刷新。 */
    fun thermalHeadroom(nowNs: Long): Float? {
        if (closed) return null
        val snapshot = latestSnapshot
        val shouldSchedule = lastScheduleNs == Long.MIN_VALUE || nowNs - lastScheduleNs >= REFRESH_INTERVAL_NS
        if (shouldSchedule && refreshPending.compareAndSet(false, true)) {
            lastScheduleNs = nowNs
            try {
                thermalExecutor.execute { refreshThermalSnapshot() }
            } catch (_: Throwable) {
                refreshPending.set(false)
            }
        }
        return snapshot
    }

    private fun refreshThermalSnapshot() {
        try {
            if (closed) return
            val sample = thermalSampler?.invoke() ?: sampleDeviceThermal()
            lastHeadroom = sample.headroom
            lastBatteryC = sample.batteryC
            sample.severeThreshold?.takeIf { it.isFinite() && it > 0f }?.let { lastSevereThreshold = it }
            if (!closed) {
                latestSnapshot = computeEffectiveHeadroom(sample)
            }
        } catch (_: Throwable) {
            // Keep existing snapshot unchanged on unexpected sampler failure.
        } finally {
            refreshPending.set(false)
        }
    }

    private fun sampleDeviceThermal(): ThermalSample {
        val manager = power
        val battery = batteryCelsius()
        val severeStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager != null) {
            runCatching { manager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE }.getOrDefault(false)
        } else {
            false
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && manager != null) {
            runCatching { manager.getThermalHeadroom(FORECAST_SECONDS) }.getOrDefault(Float.NaN)
        } else {
            Float.NaN
        }
        val severeThreshold = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && manager != null) {
            runCatching { manager.getThermalHeadroomThresholds()[PowerManager.THERMAL_STATUS_SEVERE] }.getOrNull()
        } else {
            null
        }
        return ThermalSample(headroom = headroom, severeStatus = severeStatus, batteryC = battery, severeThreshold = severeThreshold)
    }

    private fun batteryCelsius(): Float = runCatching {
        val intent = context?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f
    }.getOrDefault(Float.NaN)

    fun close() {
        closed = true
        latestSnapshot = null
        val current = session
        session = null
        if (current != null && AndroidVersion.isAtLeast(AndroidVersion.S)) runCatching { FramePowerHintApi31.close(current) }
    }

    internal companion object {
        const val FORECAST_SECONDS = 10
        const val REFRESH_INTERVAL_NS = 1_000_000_000L
        /** 电池温度门限：多数机型 45 °C 起限制充电与性能，提前 2 °C 退出插帧。 */
        const val BATTERY_LIMIT_C = 43f

        private val THERMAL_EXECUTOR: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "bil-frame-thermal").apply { isDaemon = true }
            }
        }

        internal fun computeEffectiveHeadroom(sample: ThermalSample): Float? {
            val hot = sample.batteryC.isFinite() && sample.batteryC >= BATTERY_LIMIT_C
            val threshold = sample.severeThreshold?.takeIf { it.isFinite() && it > 0f } ?: 1f
            val normalized = sample.headroom.takeIf { it.isFinite() }?.let { it / threshold }
            return if (sample.severeStatus || hot) maxOf(1f, normalized ?: 1f) else normalized
        }
    }
}
