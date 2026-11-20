package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 长按自由复制的判定窗口策略（纯函数，不碰 Android 类型，JVM 单测直接覆盖）。
 *
 * 为什么所有表面（标题/简介/评论）共用同一条判定线：
 * 前两轮把“标题点击展开偶尔失灵”当成时间窗问题，给标题阈值 +300ms、UP 认领再 +150ms，结果长按
 * 要等 700ms 才弹泡（不跟手），点击仍然失灵——失灵的原因不在时间窗，而在模块自己改动了宿主的
 * 触摸链（见 HookEntry 的 finishDescLikeTouch 与标题监听注释）。根因拆掉之后不需要任何额外延时：
 * 判定线直接取系统长按线，与宿主/框架的长按语义一致，按住达到该线即弹泡并震动，不到就是点击。
 *
 * 此处只保留三个与“跟手”有关、且不会增加点击误判的量：
 * - [thresholdMs]：系统长按线（用户在无障碍里调整过长按时间也会被尊重）；
 * - [holdSlop]：按住期间允许的手指漂移，比点按坡度略宽，见常量说明；
 * - [dueDelayMs]：到期定时器的起点，输入派发延迟很小时按事件时间起算，拥塞时退回观测时间。
 */
internal object FreeCopyPressPolicy {
    /**
     * 按住期间的漂移容忍 = 设备触摸坡度 × 此系数。
     * 框架 View 的长按对“手指仍在控件范围内（+坡度）”就继续计时，而模块旧实现是离按下点 8dp（约 1.3mm）
     * 就取消，比宿主自己的长按更严；指腹在 400ms 内的滚动/压扁就能超过 1.3mm。滚动方向的真实拖动
     * 由宿主父容器在同一坡度处拦截并发 ACTION_CANCEL，模块的取消仍然会立刻生效，所以放宽不会抢滚动。
     * 未经真机验证的可调量；取 1 即恢复为纯设备坡度。
     */
    const val LONG_PRESS_SLOP_FACTOR = 1.5f

    /**
     * 输入派发延迟（观测时刻 - DOWN 事件时间）不超过此值时，定时器按 DOWN 事件时间起算，使气泡在
     * “手指真实落下后 T 毫秒”出现而不是“模块处理 DOWN 后 T 毫秒”；超过则视为主线程拥塞，
     * 退回观测时间起算——拥塞时 UP 往往已在队列里，宁可晚弹也不抢在 UP 前误弹。
     */
    const val MAX_INPUT_LAG_CREDIT_MS = 48L

    /** 长按线：系统长按超时；读取失败或非正值时回退默认。所有表面一律相同。 */
    fun thresholdMs(systemLongPressMs: Long): Long =
        if (systemLongPressMs > 0L) systemLongPressMs else FreeCopyGestureDetector.DEFAULT_THRESHOLD_MS

    /** 按住期间的漂移容忍半径（px）。非法坡度原样放大后交给后端，后端会按失败封闭处理。 */
    fun holdSlop(touchSlop: Float): Float = touchSlop * LONG_PRESS_SLOP_FACTOR

    /** 到期 Runnable 距现在还要等多久（ms，不小于 0）。 */
    fun dueDelayMs(downTime: Long, observedAtMs: Long, nowMs: Long, thresholdMs: Long): Long {
        val lag = observedAtMs - downTime
        val anchor = if (lag in 0L..MAX_INPUT_LAG_CREDIT_MS) downTime else observedAtMs
        return (anchor + thresholdMs - nowMs).coerceAtLeast(0L)
    }
}
