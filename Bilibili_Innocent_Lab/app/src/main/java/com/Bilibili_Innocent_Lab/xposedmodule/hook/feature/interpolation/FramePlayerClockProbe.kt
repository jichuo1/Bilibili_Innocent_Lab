package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 仅显式 debug 标记启用的播放器时钟探针；不能作为已验真的时间线时钟。
 * 查询可能等播放器锁，故放在独立采样线程；捕获/规划/呈现线程不调用这些查询。
 * 每个播放器独立统计，释放或撤掉标记时停止查询；不读取原生对象布局。
 */
internal class FramePlayerClockProbe(private val log: (String, String) -> Unit, private val enabled: () -> Boolean) {
    private data class Observation(val positionUs: Long, val playing: Boolean, val speed: Double,
                           val beforeNs: Long, val afterNs: Long) {
        val atNs: Long get() = beforeNs + (afterNs - beforeNs) / 2
    }

    private val executor = ScheduledThreadPoolExecutor(1) { work ->
        Thread(work, "innocent-frame-clock").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(1, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private val sources = WeakHashMap<Any, Source>()

    @Synchronized fun attach(player: Any) {
        if (sources.containsKey(player)) return
        val type = player.javaClass
        val position = runCatching { type.getMethod("getCurrentPosition") }.getOrNull() ?: return
        val playing = runCatching { type.getMethod("isPlaying") }.getOrNull() ?: return
        val speed = runCatching { type.getMethod("getPropertyFloat", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType) }
            .getOrNull() ?: return
        if (position.returnType != Long::class.javaPrimitiveType || playing.returnType != Boolean::class.javaPrimitiveType ||
            speed.returnType != Float::class.javaPrimitiveType) return
        val source = Source(player, position, playing, speed)
        sources[player] = source
        source.start()
        log("clock.attached", "${type.name} client=${System.identityHashCode(player)} clients=${sources.size}")
    }

    @Synchronized fun remove(player: Any) {
        sources.remove(player)?.let { it.close(); log("clock.detached", "clients=${sources.size}") }
    }

    private inner class Source(target: Any, private val position: Method, private val playing: Method,
                                            private val speed: Method) {
        private val player = WeakReference(target)
        private val clientId = System.identityHashCode(target)
        @Volatile private var closed = false
        @Volatile private var task: java.util.concurrent.ScheduledFuture<*>? = null
        private var checkedEnabledNs = 0L
        private var previous: Observation? = null
        private var lastLogNs = 0L
        private var maxResidualUs = 0L
        private var maxQueryUs = 0L
        private var samples = 0L
        private var failures = 0L
        private var jumps = 0L
        private val property = runCatching { position.declaringClass.getMethod("getPropertyLong", Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType) }.getOrNull()

        fun start() {
            task = executor.scheduleWithFixedDelay(::sample, 0, 16, TimeUnit.MILLISECONDS)
            if (closed) task?.cancel(false)
        }

        private fun sample() {
            if (closed) return
            val target = player.get() ?: run { close(); return }
            val before = System.nanoTime()
            if (before - checkedEnabledNs >= 1_000_000_000L) {
                checkedEnabledNs = before
                if (!runCatching(enabled).getOrDefault(false)) { remove(target); return }
            }
            val value = runCatching {
                val at = (position.invoke(target) as Long)
                val isPlaying = playing.invoke(target) as Boolean
                val rate = (speed.invoke(target, IJK_PLAYBACK_RATE, Float.NaN) as Float).toDouble()
                Observation(Math.multiplyExact(at, 1_000L), isPlaying, rate, before, System.nanoTime())
            }.getOrNull()
            if (closed) return
            val current = value?.takeIf { it.positionUs >= 0L && it.speed.isFinite() && it.speed > 0.0 }
            if (current == null) failures++
            else {
                samples++
                maxQueryUs = maxOf(maxQueryUs, (current.afterNs - current.beforeNs) / 1_000)
                previous?.takeIf { it.playing && current.playing && it.speed == current.speed }?.let {
                    val residual = current.positionUs - it.positionUs - (current.atNs - it.atNs) / 1_000.0 * current.speed
                    maxResidualUs = maxOf(maxResidualUs, kotlin.math.abs(residual).toLong())
                    if (kotlin.math.abs(residual) > 8_000) {
                        jumps++
                        val raw = runCatching { property?.invoke(target, 20251, -1L) }.getOrNull()
                        val real = runCatching { property?.invoke(target, 20241, -1L) }.getOrNull()
                        log("clock.jump", "client=$clientId from=${it.positionUs} to=${current.positionUs} dtUs=${(current.atNs - it.atNs) / 1000} " +
                            "residualUs=${residual.toLong()} current=$raw real=$real")
                    }
                }
                previous = current
            }
            val now = System.nanoTime()
            if (now - lastLogNs >= 5_000_000_000L) {
                lastLogNs = now
                log("clock.player", "client=$clientId samples=$samples failures=$failures positionUs=${value?.positionUs} " +
                    "playing=${value?.playing} speed=${value?.speed} queryMaxUs=$maxQueryUs residualMaxUs=$maxResidualUs jumps=$jumps")
                maxQueryUs = 0L; maxResidualUs = 0L
            }
        }

        fun close() { closed = true; task?.cancel(false) }
    }

}
