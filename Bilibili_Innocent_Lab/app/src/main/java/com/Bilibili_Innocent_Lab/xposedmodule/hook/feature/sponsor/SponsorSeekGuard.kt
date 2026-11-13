package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 只标记模块主动创建的 seek 协程；普通宿主/用户跳转不进入门禁。 */
internal class SponsorSeekGuard {
    private class Frame(owner: Any, val target: Int, val valid: () -> Boolean, val executing: () -> Unit) {
        val owner = WeakReference(owner)
        var captured = false
    }
    private val scope = ThreadLocal<Frame?>()
    private val queued = WeakHashMap<Any, Frame>()

    fun isSubmitting(owner: Any): Boolean = scope.get()?.owner?.get() === owner

    /** 最近一次 [submit] 被拒的原因（queue-full / invalid / not-captured），成功时为空；只用于诊断。 */
    var lastRefusal: String = ""
        private set

    fun submit(owner: Any, target: Int, valid: () -> Boolean, executing: () -> Unit, invoke: () -> Unit): Boolean {
        lastRefusal = ""
        // 活跃协程的硬上限；空间耗尽时在创建请求之前保持宿主进度。
        if (synchronized(queued) { queued.size >= 64 }) { lastRefusal = "queue-full"; return false }
        if (!valid()) { lastRefusal = "invalid"; return false }
        val previous = scope.get()
        val frame = Frame(owner, target, valid, executing)
        scope.set(frame)
        return try { invoke(); frame.captured.also { if (!it) lastRefusal = "not-captured" } } finally { scope.set(previous) }
    }

    fun capture(coroutine: Any, owner: Any, target: Int) {
        val frame = scope.get()?.takeIf { it.owner.get() === owner && it.target == target } ?: return
        synchronized(queued) { queued[coroutine] = frame }
        frame.captured = true
    }

    fun carry(original: Any, created: Any) {
        synchronized(queued) { queued.remove(original)?.let { queued[created] = it } }
    }

    fun allow(coroutine: Any): Boolean {
        val frame = synchronized(queued) { queued.remove(coroutine) } ?: return true
        if (frame.owner.get() == null || !runCatching(frame.valid).getOrDefault(false)) return false
        frame.executing()
        return true
    }
}
