package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import android.content.Context
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import java.io.Closeable
import rikka.shizuku.Shizuku

/** 只持有 Application；预热、前台刷新和弹窗共享同一探测，不阻塞 UI，不申请授权。 */
internal object RestartCapabilities {
    private val main = Handler(Looper.getMainLooper())
    private val rootWorker = HostReceiptWire.executor("bil-root-probe")
    private val shizukuWorker = HostReceiptWire.executor("bil-shizuku-probe")
    private val state = RestartProbeState()
    private val listeners = linkedSetOf<(RestartProbeState.Snapshot) -> Unit>()
    private var app: Application? = null
    private val received = Shizuku.OnBinderReceivedListener { app?.let(::invalidate) }
    private val dead = Shizuku.OnBinderDeadListener { app?.let(::invalidate) }

    fun snapshot(): RestartProbeState.Snapshot = state.snapshot

    fun invalidate(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        state.invalidate()
        warm(context, force = true)
    }

    fun warm(context: Context, force: Boolean = false) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (app == null) {
            app = context.applicationContext as Application
            Shizuku.addBinderReceivedListener(received, main)
            Shizuku.addBinderDeadListener(dead, main)
        }
        val token = state.begin(SystemClock.elapsedRealtime(), force) ?: return
        notifyChanged()
        val root = runCatching { rootWorker.submit {
            val result = runCatching { RootRestartAccess.read() }.getOrDefault(RootRestartAccess.Snapshot(RootRestartAccess.Status.UNKNOWN))
            main.post { if (state.root(token, result, SystemClock.elapsedRealtime())) notifyChanged() }
        } }.getOrNull()
        val shizuku = runCatching { shizukuWorker.submit {
            val result = ShizukuAvailability.read(requireNotNull(app))
            main.post { if (state.shizuku(token, result, SystemClock.elapsedRealtime())) notifyChanged() }
        } }.getOrNull()
        main.postDelayed({
            if (state.timeout(token, SystemClock.elapsedRealtime())) {
                root?.cancel(true); shizuku?.cancel(true)
                notifyChanged()
            }
        }, 2_000)
    }

    fun observe(context: Context, listener: (RestartProbeState.Snapshot) -> Unit): Closeable {
        warm(context)
        listeners += listener
        listener(state.snapshot)
        return Closeable { listeners -= listener }
    }
    private fun notifyChanged() { listeners.toList().forEach { it(state.snapshot) } }
}
