package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import kotlin.math.roundToLong

internal enum class NavigationMotionPhase {
    PREPARING_ENTRY, ENTERING, EXPANDED, PREDICTIVE_BACK, CANCELLING_BACK, CLOSING, FINISHED
}

internal object NavigationMotionPolicy {
    fun keepInputBlocked(animationBlocked: Boolean, backPressActive: Boolean): Boolean =
        animationBlocked || backPressActive

    fun canNavigate(phase: NavigationMotionPhase, businessBlocked: Boolean): Boolean =
        !businessBlocked && when (phase) {
            NavigationMotionPhase.ENTERING, NavigationMotionPhase.EXPANDED,
            NavigationMotionPhase.CANCELLING_BACK -> true
            else -> false
        }
    fun preserveFrame(phase: NavigationMotionPhase): Boolean = when (phase) {
        NavigationMotionPhase.ENTERING, NavigationMotionPhase.CANCELLING_BACK,
        NavigationMotionPhase.PREDICTIVE_BACK -> true
        else -> false
    }
    fun remainingDuration(base: Long, start: Float, target: Float): Long =
        (base * abs(target - start).coerceIn(0f, 1f)).roundToLong().coerceIn(minOf(80L, base), base)
}

/** Generation also invalidates posted entry/finish work, not only Animator callbacks. */
internal class NavigationMotionSession {
    var generation = 0L
        private set
    private var lastTime = -1L
    private var lastValue = 1f
    private var speed = 0f
    fun invalidate(): Long { generation++; return generation }
    fun owns(token: Long): Boolean = generation == token
    fun reset(value: Float, now: Long) { lastValue = value; lastTime = now; speed = 0f }
    fun sample(value: Float, now: Long) {
        val elapsed = now - lastTime
        if (elapsed == 0L) { lastValue = value; return }
        if (elapsed < 0L) return
        speed = if (lastTime >= 0 && elapsed <= 100L) ((value - lastValue) * 1000f / elapsed).coerceIn(-8f, 8f) else 0f
        lastTime = now
        lastValue = value
    }
    fun velocity(now: Long): Float = if (now - lastTime in 0L..100L) speed else 0f
}

/** Cubic continuation, allocated only on retarget. Tangents are bounded to avoid overshoot. */
internal class NavigationMotionContinuation(
    private val start: Float, private val target: Float, velocity: Float, durationMs: Long
) {
    private val delta = target - start
    private val tangent = (velocity * durationMs / 1000f).let { raw ->
        val cap = if (raw * delta >= 0f) 3f * abs(delta)
            else 3f * (if (raw > 0f) 1f - start else start).coerceAtLeast(0f)
        raw.coerceIn(-cap, cap)
    }
    fun value(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t
        return ((2f*t3 - 3f*t2 + 1f)*start + (t3 - 2f*t2 + t)*tangent +
            (-2f*t3 + 3f*t2)*target).coerceIn(0f, 1f)
    }
}
