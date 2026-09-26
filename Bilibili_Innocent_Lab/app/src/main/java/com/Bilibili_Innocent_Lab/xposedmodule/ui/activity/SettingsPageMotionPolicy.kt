package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

internal data class SettingsSwitchTouchBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Motion can pause under DOWN or settle before UP; both ownerships must finish before interaction resumes. */
internal class SettingsPageMotionLifecycle {
    private var motionActive = false
    private var gestureActive = false
    val isSettled: Boolean get() = !motionActive && !gestureActive

    fun beginMotion(): Boolean {
        val started = !motionActive
        motionActive = true
        return started
    }

    fun beginUserGesture(): Boolean {
        val started = !gestureActive
        gestureActive = true
        return started
    }

    fun finishMotion() { motionActive = false }
    fun finishUserGesture() { gestureActive = false }
}

/** Keyboard and accessibility navigation cancel earlier user work before changing the visible page. */
internal object SettingsPageUserNavigation {
    fun request(
        current: Int, target: Int, count: Int,
        onUserInteraction: () -> Unit, selectPage: (Int) -> Unit
    ): Boolean {
        if (target == current || target !in 0 until count.coerceIn(0, SettingsPageMotionPolicy.MAX_PAGES)) return false
        onUserInteraction()
        selectPage(target)
        return true
    }
}

/** Page coordinates increase in reading order; pixels are converted only at the View boundary. */
internal object SettingsPageMotionPolicy {
    const val MAX_PAGES = 4
    const val EDGE_LIMIT = .18f
    private const val PAGE_THRESHOLD = .22f
    private const val FLING_THRESHOLD = .5f

    fun lastPage(count: Int): Int = (count.coerceIn(0, MAX_PAGES) - 1).coerceAtLeast(0)
    fun selected(index: Int, count: Int): Int = index.coerceIn(0, lastPage(count))
    fun direction(rtl: Boolean): Float = if (rtl) -1f else 1f

    fun physicalPageTarget(current: Int, physicalDirection: Int, rtl: Boolean): Int {
        val step = when {
            physicalDirection < 0 -> -1
            physicalDirection > 0 -> 1
            else -> 0
        }
        return current + step * direction(rtl).toInt()
    }

    fun isPageVisible(index: Int, position: Float, count: Int): Boolean =
        index in 0 until count.coerceIn(0, MAX_PAGES) && position.isFinite() && abs(index - position) < 1f

    /** Drawable bounds are already in switch-local coordinates, including the RTL placement. */
    fun protectsSwitchTouch(
        x: Float, y: Float, width: Int, height: Int, geometryReady: Boolean,
        track: SettingsSwitchTouchBounds?, thumb: SettingsSwitchTouchBounds?, slop: Int
    ): Boolean {
        fun valid(bounds: SettingsSwitchTouchBounds?) = bounds != null && bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right > bounds.left && bounds.bottom > bounds.top && bounds.right <= width && bounds.bottom <= height
        if (!geometryReady || width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite() || slop < 0 ||
            !valid(track) || !valid(thumb)) return true
        val left = minOf(track!!.left, thumb!!.left).toFloat() - slop
        val top = minOf(track.top, thumb.top).toFloat() - slop
        val right = maxOf(track.right, thumb.right).toFloat() + slop
        val bottom = maxOf(track.bottom, thumb.bottom).toFloat() + slop
        return x >= left && x <= right && y >= top && y <= bottom
    }

    fun logicalDelta(pixels: Float, width: Int, rtl: Boolean): Float =
        if (width <= 0 || !pixels.isFinite()) 0f else -pixels / width / direction(rtl)

    fun resistedPosition(raw: Float, count: Int): Float {
        if (!raw.isFinite() || count <= 1) return 0f
        val last = lastPage(count).toFloat()
        val edge = raw.coerceIn(0f, last)
        val outside = raw - edge
        return edge + outside / (1f + abs(outside) / EDGE_LIMIT)
    }

    /** A touch can interrupt an edge rebound; avoid applying resistance twice to that first frame. */
    fun dragPosition(start: Float, delta: Float, count: Int): Float {
        if (!start.isFinite() || !delta.isFinite() || count <= 1) return 0f
        val edge = start.coerceIn(0f, lastPage(count).toFloat())
        val outside = (start - edge).coerceIn(-EDGE_LIMIT + .0001f, EDGE_LIMIT - .0001f)
        if (delta == 0f) return edge + outside
        val unresisted = edge + outside / (1f - abs(outside) / EDGE_LIMIT)
        return resistedPosition(unresisted + delta, count)
    }

    /** One release advances at most one page. Reversing velocity retracts the current drag. */
    fun releasePage(selectedPage: Int, position: Float, velocity: Float, count: Int): Int {
        val current = selected(selectedPage, count)
        if (!position.isFinite() || !velocity.isFinite()) return current
        val offset = position - current
        val step = when {
            abs(velocity) >= FLING_THRESHOLD && offset * velocity < 0f -> 0
            abs(velocity) >= FLING_THRESHOLD -> if (velocity > 0f) 1 else -1
            offset >= PAGE_THRESHOLD -> 1
            offset <= -PAGE_THRESHOLD -> -1
            else -> 0
        }
        return selected(current + step, count)
    }

    fun duration(start: Float, target: Float): Long =
        if (!start.isFinite() || !target.isFinite()) 180L
        else (340f * sqrt(abs(target - start).coerceIn(0f, 2f))).roundToLong().coerceIn(180L, 420L)

    /**
     * 点击底栏/键盘/搜索跳转这类"导航"切页的时长：跨得越远越长，但增长放缓（d^0.45）——
     * 1 页约 320ms、2 页约 440ms、3 页约 520ms。远跳的额外距离主要由更陡的起步吸收，而不是拖时间。
     */
    fun navigationDuration(start: Float, target: Float): Long =
        if (!start.isFinite() || !target.isFinite()) NAVIGATION_MIN_MS
        else (320f * abs(target - start).coerceIn(0f, 3.4f).pow(.45f)).roundToLong()
            .coerceIn(NAVIGATION_MIN_MS, NAVIGATION_MAX_MS)

    /**
     * 导航曲线的陡度 a（临界阻尼弹簧 1-(1+at)e^-at 的 ωT）：距离越远"力度"越大——起步冲得更快、
     * 减速滑行的尾巴更长。1 页 6、3 页 7.5；峰值速度约为平均速度的 a/e 倍（2.2–2.8 倍）。
     */
    fun navigationSteepness(distance: Float): Float =
        if (!distance.isFinite()) NAVIGATION_MIN_STEEPNESS
        else (NAVIGATION_MIN_STEEPNESS + .75f * (abs(distance) - 1f))
            .coerceIn(NAVIGATION_MIN_STEEPNESS, NAVIGATION_MAX_STEEPNESS)

    const val NAVIGATION_MIN_MS = 240L
    const val NAVIGATION_MAX_MS = 560L
    const val NAVIGATION_MIN_STEEPNESS = 6f
    const val NAVIGATION_MAX_STEEPNESS = 7.5f
}

/** Interrupted motion retains its current position and bounded velocity, ending at rest. */
/**
 * [navigation] 为 true 时（点击底栏、键盘、搜索跳转）改用非线性的临界阻尼弹簧曲线：从静止短暂加速、随后
 * 长尾减速滑入，陡度随跨页距离增大，不过冲；拖动松手（false）保持原来的 Hermite 回弹。两种曲线都叠加
 * 同一个速度项，动画途中再次点击时从当前速度无缝接续。
 */
internal class SettingsPageMotionContinuation(
    start: Float, target: Int, velocity: Float, duration: Long, count: Int, private val navigation: Boolean = false
) {
    private val last = SettingsPageMotionPolicy.lastPage(count).toFloat()
    private val lower = -SettingsPageMotionPolicy.EDGE_LIMIT
    private val upper = last + SettingsPageMotionPolicy.EDGE_LIMIT
    private val from = if (start.isFinite()) start.coerceIn(lower, upper) else 0f
    private val to = SettingsPageMotionPolicy.selected(target, count).toFloat()
    private val delta = to - from
    private val tangent = (if (velocity.isFinite()) velocity.coerceIn(-8f, 8f) else 0f).let { speed ->
        val raw = speed * duration.coerceIn(0L, 420L) / 1000f
        val cap = if (raw * delta >= 0f) 3f * abs(delta)
        else 3f * (if (raw > 0f) upper - from else from - lower).coerceAtLeast(0f)
        raw.coerceIn(-cap, cap)
    }

    private val steepness = SettingsPageMotionPolicy.navigationSteepness(delta)
    private val springNorm = 1f - (1f + steepness) * exp(-steepness)

    fun value(fraction: Float): Float {
        val t = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 1f
        val t2 = t * t
        val t3 = t2 * t
        if (navigation) {
            // 归一化到 t=1 恰好落在目标；速度项 t(1-t)^2 在两端都不贡献位移，只在起点提供初速度。
            val at = steepness * t
            val spring = (1f - (1f + at) * exp(-at)) / springNorm
            return (from + delta * spring + tangent * (t3 - 2f * t2 + t)).coerceIn(lower, upper)
        }
        return ((2f * t3 - 3f * t2 + 1f) * from + (t3 - 2f * t2 + t) * tangent +
            (-2f * t3 + 3f * t2) * to).coerceIn(lower, upper)
    }
}
