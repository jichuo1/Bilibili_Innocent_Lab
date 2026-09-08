package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主线程防波堤。
 *
 * Hook 回调由框架的 `ExceptionMode.PROTECTIVE` 兜底（`module.prop` 与每个注册点各声明一次）：
 * 回调抛出的任何异常都会被框架捕获并按“没装 Hook”继续。但模块投递到**宿主调度器**上的代码
 * 不在这层保护里——`Handler.post`/`postDelayed`、`MessageQueue.IdleHandler`、`View.post`、
 * 装在宿主 View 上的监听器和模块自建线程/Executor 的任务体，都直接跑在宿主自己的栈上；
 * Android 的 `RuntimeInit` 给**所有线程**（不只主线程）装了杀进程的默认 handler，
 * 任一逃逸异常就是宿主闪退。
 *
 * 免 Root 框架（NPatch/LSPatch）下宿主结构漂移与反射失败率更高，这里是它们和 LSPosed
 * 体感差异最大的地方；但缺陷本身与框架无关，两种后端都会崩。
 *
 * 纪律：
 * - 只包裹**模块自己调度**的入口。绝不能用它包住 Hook 回调里对宿主原方法的调用——
 *   那会吞掉宿主自身的异常、改变宿主语义（见 `development_experience.md` 2026-08-30
 *   “模块回调异常 vs 传递给宿主的 throwable”）。
 * - 日志直连 [ModernHookLog]，不受模块日志开关辖制，但按 key 限量避免高频路径刷屏。
 *   **不做“一次性去重”**：重复崩溃必须仍然可见，这正是 2026-08-29 宿主崩溃哨兵的教训。
 * - 计数只留在内存，不落盘、不进诊断报告格式，避免牵动 `DiagnosticReportCodec` 版本。
 */
internal object HostThreadGuard {

    /** 每个 key 最多打印的堆栈条数；超过后只累计计数。 */
    internal const val LOG_LIMIT_PER_KEY = 3L

    private val failures = ConcurrentHashMap<String, AtomicLong>()

    /** 吞掉 [block] 的任何 Throwable，保证调度它的宿主线程不被终止。成功路径零分配。 */
    fun run(key: String, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            report(key, throwable)
        }
    }

    /** 有返回值的变体；失败时返回 [fallback]（故障开放由调用方决定语义）。 */
    fun <T> call(key: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (throwable: Throwable) {
        report(key, throwable)
        fallback
    }

    /**
     * 包装成**可复用**的 [Runnable]。
     *
     * 延迟任务必须持有同一个实例，否则 `removeCallbacks` 撤销不掉——评论/简介长按判定和
     * 绑定重试都依赖这一点。
     */
    fun runnable(key: String, block: () -> Unit): Runnable = Runnable { run(key, block) }

    /** 已拦下的失败计数快照，供诊断读取；不清零、不落盘。 */
    fun failureCounts(): Map<String, Long> =
        failures.entries.associate { (key, count) -> key to count.get() }

    /** 仅供单元测试隔离状态。 */
    internal fun resetForTests() {
        failures.clear()
    }

    private fun report(key: String, throwable: Throwable) {
        val count = failures.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        // 记录本身绝不能把异常放回宿主栈：框架 binder 断开时 `module.log` 会抛，
        // 未绑定 sink 时还会退到 `android.util.Log`（JVM 单测里是 Stub! 异常）。
        try {
            when {
                count <= LOG_LIMIT_PER_KEY -> ModernHookLog.error(
                    "[BIL] 宿主线程任务失败已拦下(key=$key, count=$count)",
                    throwable
                )
                count == LOG_LIMIT_PER_KEY + 1L -> ModernHookLog.error(
                    "[BIL] 宿主线程任务持续失败(key=$key)，后续堆栈不再打印，仅累计计数"
                )
            }
        } catch (_: Throwable) {
            // 计数已经记下，日志通道不可用时静默即可。
        }
    }
}
