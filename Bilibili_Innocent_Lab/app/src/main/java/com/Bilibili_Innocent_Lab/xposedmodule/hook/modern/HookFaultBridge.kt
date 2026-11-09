package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import io.github.jichuo1.meguviz.hook.api.AfterCallback
import io.github.jichuo1.meguviz.hook.api.AroundCallback
import io.github.jichuo1.meguviz.hook.api.BeforeCallback
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * 把“逃出功能回调的异常”归因到所属功能，交给既有的运行时错误证据。
 *
 * 保护模式下框架会吞掉这类异常，模块自己此前看不到：功能自带的 try/catch 报得出来，漏网的则只留在框架日志里。
 * 这里只用 SDK 的 [CallbackExceptionTracker] 做有界登记，不做任何输出：
 * - 热路径只多一层 try/catch 包装，成功时不分配；
 * - 队列满时丢弃新记录并计数，记录数有界；
 * - 没有后台线程、定时器和 I/O：由诊断快照的读取方在**自己的后台线程**调用 [drain] 取走；
 * - 对外只给出功能 ID，不带异常消息、堆栈或宿主成员名，线协议不变（落在既有的 `runtimeError` 位上）。
 */
internal class HookFaultBridge(processName: String, capacity: Int = DEFAULT_CAPACITY) {
    private val tracker = CallbackExceptionTracker(processName.take(MAX_SOURCE).ifEmpty { "unknown" }, capacity)
    private val owners = ConcurrentHashMap<HookId, String>()
    private val directFaults = ConcurrentHashMap.newKeySet<HookId>()

    /** 同一逻辑点重复安装时以最后一次的归属为准。 */
    fun bind(hookId: HookId, featureId: String) {
        owners[hookId] = featureId
    }

    fun unbind(hookId: HookId) {
        owners.remove(hookId)
    }

    fun guard(callback: BeforeCallback): BeforeCallback = tracker.before(callback)

    fun guard(callback: AfterCallback): AfterCallback = tracker.after(callback, false)

    fun guard(callback: AroundCallback): AroundCallback = tracker.around(callback)

    /** 不经 SDK 调度器的 Hook 自行登记：只记哪个 Hook 出过故障，不保留异常对象，归属在取走时才解析。 */
    fun noteEscaped(hookId: HookId) {
        directFaults.add(hookId)
    }

    val pendingCount: Int get() = tracker.pendingCount() + directFaults.size

    val droppedCount: Long get() = tracker.droppedCount()

    /**
     * 取走已登记的异常并按功能去重后回调 [report]；必须在后台线程调用，且不得在 Hook 回调内。
     * 归属已解除或未知的记录只被丢弃。返回处理的记录数（含重复）。
     */
    fun drain(report: (String) -> Unit): Int {
        var handled = 0
        val reported = HashSet<String>()
        val iterator = directFaults.iterator()
        while (iterator.hasNext()) {
            val hookId = iterator.next()
            iterator.remove()
            handled++
            val featureId = owners[hookId]
            if (featureId != null && reported.add(featureId)) runCatching { report(featureId) }
        }
        val transport = CallbackExceptionTracker.Transport { event ->
            val featureId = owners[event.hookId()]
            // 上报方的失败不能让记录滞留在队首，否则之后每次取走都会重复失败。
            if (featureId != null && reported.add(featureId)) runCatching { report(featureId) }
        }
        while (true) {
            val batch = tracker.deliver(transport, BATCH)
            handled += batch
            if (batch < BATCH) break
        }
        return handled
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val BATCH = 32
        const val MAX_SOURCE = 128
    }
}
