package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.content.Context
import android.os.Build
import android.os.PowerManager
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion

/**
 * 功耗与温控：
 * - 温控余量（API 30+）：每秒最多查询一次 getThermalHeadroom(10 s 预测)；更频繁的调用没有收益且可能返回 NaN。
 * - ADPF（API 31+）：为捕获线程建立性能提示会话，目标时长为一个输出周期，按帧回报实际 CPU 工作时长，
 *   让系统按实际负载选择频点与核心类型，而不是靠调度器猜（读回、RGBA→RGB 紧凑与上传是每帧固定的 CPU 工作）。
 * - 电池温度：真机（OnePlus/ColorOS）上 getThermalHeadroom 恒为 0、温控状态恒为 NONE，而 CPU 传感器已约 99 °C；
 *   电池温度在所有机型都可无权限读取（ACTION_BATTERY_CHANGED 粘性广播），达到 [BATTERY_LIMIT_C] 视同严重温控。
 * 设备不支持时各项都静默不启用。
 */
internal class FramePowerAdvisor(private val context: Context?, private val targetNs: Long) {
    private val power = context?.getSystemService(PowerManager::class.java)
    /** 只留 Any?：API 31 类型一律在 [FramePowerHintApi31] 里，不能进本类的字段和方法体。 */
    private var session: Any? = null
    private var attached = false
    private var lastQueryNs = Long.MIN_VALUE
    @Volatile var lastHeadroom: Float = Float.NaN
        private set
    @Volatile var lastBatteryC: Float = Float.NaN
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

    /** 距上次查询不足 1 s 时返回 null；不支持时返回 null。 */
    fun thermalHeadroom(nowNs: Long): Float? {
        val manager = power
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || manager == null) return null
        if (lastQueryNs != Long.MIN_VALUE && nowNs - lastQueryNs < 1_000_000_000L) return null
        lastQueryNs = nowNs
        val headroom = runCatching { manager.getThermalHeadroom(FORECAST_SECONDS) }.getOrDefault(Float.NaN)
        lastHeadroom = headroom
        // 部分 OEM 不提供余量预测；NaN 不能让明确的严重温控状态失效。查询同样受 1 s 节流。
        val severe = runCatching { manager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE }.getOrDefault(false)
        val battery = batteryCelsius().also { lastBatteryC = it }
        val hot = battery.isFinite() && battery >= BATTERY_LIMIT_C
        return if (severe || hot) maxOf(1f, headroom.takeIf { it.isFinite() } ?: 1f) else headroom.takeIf { it.isFinite() }
    }

    private fun batteryCelsius(): Float = runCatching {
        val intent = context?.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f
    }.getOrDefault(Float.NaN)

    fun close() {
        val current = session
        session = null
        if (current != null && AndroidVersion.isAtLeast(AndroidVersion.S)) runCatching { FramePowerHintApi31.close(current) }
    }

    private companion object {
        const val FORECAST_SECONDS = 10
        /** 电池温度门限：多数机型 45 °C 起限制充电与性能，提前 2 °C 退出插帧。 */
        const val BATTERY_LIMIT_C = 43f
    }
}
