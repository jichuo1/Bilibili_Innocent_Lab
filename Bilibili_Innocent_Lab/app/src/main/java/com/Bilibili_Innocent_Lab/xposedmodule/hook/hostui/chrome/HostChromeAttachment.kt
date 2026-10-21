package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Bounded retries for host trees populated after Fragment dispatch; exceptions never retry. */
internal class HostChromeAttachment(
    private val schedule: (Long, Runnable) -> Boolean,
    private val attempt: () -> Boolean,
    private val onApplied: () -> Unit,
    private val onMissing: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val maxAttempts: Int = 16
) {
    private var attempts = 0
    private var finished = false
    private val step = Runnable { runAttempt() }

    fun start() {
        try {
            if (!schedule(0L, step)) finish(onMissing)
        } catch (failure: Throwable) { finish { onError(failure) } }
    }

    private fun runAttempt() {
        if (finished) return
        try {
            attempts++
            if (attempt()) finish(onApplied)
            else if (attempts >= maxAttempts || !schedule(100L, step)) finish(onMissing)
        } catch (failure: Throwable) {
            finish { onError(failure) }
        }
    }

    private fun finish(callback: () -> Unit) {
        if (finished) return
        finished = true
        runCatching(callback)
    }
}

/** Both map ends are weak; pending View posts own their bounded attachment attempt. */
internal class HostChromeAttachments {
    private val pending = WeakHashMap<ViewGroup, WeakReference<HostChromeAttachment>>()

    fun post(root: ViewGroup, attempt: () -> Boolean, onApplied: () -> Unit,
        onMissing: () -> Unit, onError: (Throwable) -> Unit) {
        synchronized(pending) {
            if (pending[root]?.get() != null) return
            fun clear() { synchronized(pending) { pending.remove(root) } }
            val attachment = HostChromeAttachment(
                schedule = { delay, task -> if (delay == 0L) root.post(task) else root.postDelayed(task, delay) },
                attempt = attempt,
                onApplied = { clear(); onApplied() },
                onMissing = { clear(); onMissing() },
                onError = { failure -> clear(); onError(failure) }
            )
            pending[root] = WeakReference(attachment)
            attachment.start()
        }
    }
}
