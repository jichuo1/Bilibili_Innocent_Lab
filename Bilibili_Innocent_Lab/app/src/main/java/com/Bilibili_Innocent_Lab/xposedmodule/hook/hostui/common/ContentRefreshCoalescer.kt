package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

/**
 * 内容变化的首沿立即、尾沿补刷合并器：离散变化立即刷新，连续变化只在安静满一个采样间隔后补刷一次。
 * 只管决策，不碰 View、定时器和线程；时钟由调用方传入，所以可以在 JVM 上完整验证。
 */
internal class ContentRefreshCoalescer(private val intervalMs: Long) {
    private var changedAt = 0L

    /** 已有一个尾沿定时器在途；调用方据此决定是否需要取消它。 */
    var armed = false
        private set

    init {
        require(intervalMs > 0L)
    }

    /**
     * 登记一次内容变化。返回 [NOW]：立即刷新；[ARMED]：尾沿定时器已在途，什么也不用做；
     * 其余正数：调用方需要在这么多毫秒后触发 [onTimer]。
     * [coalesce] 为 false 时每次变化都立即刷新（API 31、软件后端、关闭省电等完整刷新路径）。
     */
    fun onChange(now: Long, coalesce: Boolean): Long {
        val isolated = changedAt == 0L || now - changedAt >= intervalMs
        changedAt = now
        if (!coalesce || isolated) {
            armed = false
            return NOW
        }
        if (armed) return ARMED
        armed = true
        return intervalMs
    }

    /** 定时器触发。返回 [NOW]：已安静满一个间隔，现在补刷；正数：期间又有变化，按这个延迟重新等待。 */
    fun onTimer(now: Long): Long {
        val remaining = intervalMs - (now - changedAt)
        if (remaining > 0L) return remaining
        armed = false
        return NOW
    }

    /** 隐藏、关闭时放弃尾沿补刷。 */
    fun cancel() {
        armed = false
    }

    companion object {
        const val NOW = 0L
        const val ARMED = -1L
    }
}
