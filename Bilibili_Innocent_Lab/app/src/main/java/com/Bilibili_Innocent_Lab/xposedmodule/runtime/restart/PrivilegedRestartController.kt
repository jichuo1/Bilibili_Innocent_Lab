package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.startActivityOrElse
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostRuntimeProbeClient
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.AuthorityPublication
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 由重启弹窗持有。权限回调/服务重连只更新状态，绝不自动执行下一次重启。 */
internal class PrivilegedRestartController(
    context: Context,
    preferences: SharedPreferences,
    noRoot: Boolean,
    foreground: () -> Boolean,
    changed: (State, Int?) -> Unit,
    route: RestartRoute = RestartRoute.SHIZUKU
) : Closeable {
    // 显式属性避免当前 Kotlin/UAST 对构造参数属性生成 light methods 时崩溃；不关闭 Lint 检测器。
    private val preferences = preferences
    private val noRoot = noRoot
    private val foreground = foreground
    private val changed = changed
    private val route = route
    enum class State {
        CHECKING, ROOT_AVAILABLE, ROOT_READY, ROOT_REQUESTING, ROOT_UNAVAILABLE, ROOT_MISSING,
        MISSING, STOPPED, UNSUPPORTED, UNAUTHORIZED, DENIED, READY, DISCONNECTED,
        PERMISSION_REQUESTED, AUTHORIZED, SAVING, BINDING, STOPPING, LAUNCHING, VERIFYING,
        SAVE_FAILED, STOP_FAILED, LAUNCH_FAILED, RECEIPT_TIMEOUT, CONFIG_CHANGED, CANCELLED, CONFIRMED, DISABLED_CONFIRMED, BUSY
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "bil-host-restart").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile private var operation = 0L
    @Volatile private var busy = false
    private var bound = false
    private var state = State.STOPPED
    private var uid: Int? = null
    private var transport: HostRestartTransport? = null
    private var authorizing = false
    private var manualOldPid: Int? = null
    private var publication: AuthorityPublication? = null
    private var disabledRevision: Long? = null
    private var startedAt = 0L
    private var newPid = 0
    private var deadline = 0L
    private val requestCode = nextRequestCode++
    private val args = Shizuku.UserServiceArgs(ComponentName(app, HostRestartUserService::class.java))
        .tag("host-restart-v1").version(BuildConfig.VERSION_CODE).daemon(false).processNameSuffix("host_restart")
    private val received = Shizuku.OnBinderReceivedListener { if (!closed && !busy) refresh() }
    private val died = Shizuku.OnBinderDeadListener {
        if (!closed) { if (busy) finish(State.DISCONNECTED) else emit(State.DISCONNECTED) }
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (!closed && !busy && code == requestCode) {
            // 只完成授权；用户须再次点击，不把迟到的系统回调当成重启意图。
            emit(if (result == PackageManager.PERMISSION_GRANTED) State.AUTHORIZED else State.DENIED)
            RestartCapabilities.invalidate(app)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || !busy || state != State.BINDING) { unbind(); return }
            transport = ShizukuRestartTransport(IHostRestartService.Stub.asInterface(binder))
            stop(operation)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            transport = null
            if (!closed && busy && bound) finish(State.DISCONNECTED)
        }
    }

    private val probe = RestartCapabilities.observe(app) {
        if (!closed && !busy && !authorizing && state in setOf(State.CHECKING, State.ROOT_AVAILABLE,
                State.ROOT_READY, State.ROOT_UNAVAILABLE, State.ROOT_MISSING, State.MISSING, State.STOPPED,
                State.UNSUPPORTED, State.UNAUTHORIZED, State.DENIED, State.READY, State.DISCONNECTED, State.AUTHORIZED)) refresh()
    }

    init {
        require(route != RestartRoute.MANUAL || !noRoot) // NPatch 手动恢复沿用现有停用握手向导。
        if (route == RestartRoute.SHIZUKU) {
            Shizuku.addBinderReceivedListener(received, main)
            Shizuku.addBinderDeadListener(died, main)
            Shizuku.addRequestPermissionResultListener(permission, main)
        }
    }

    fun refresh() {
        if (closed || busy || authorizing || state == State.PERMISSION_REQUESTED) return
        if (route == RestartRoute.MANUAL) { emit(State.READY); return }
        if (route == RestartRoute.ROOT) {
            val root = RestartCapabilities.snapshot().root
            uid = if (root?.status == RootRestartAccess.Status.READY) 0 else null
            emit(when (root?.status) {
                null -> State.CHECKING
                RootRestartAccess.Status.READY -> State.ROOT_READY
                RootRestartAccess.Status.CANDIDATE -> State.ROOT_AVAILABLE
                RootRestartAccess.Status.MISSING -> State.ROOT_MISSING
                else -> State.ROOT_UNAVAILABLE
            })
            return
        }
        val available = RestartCapabilities.snapshot().shizuku ?: return emit(State.CHECKING)
        uid = available.uid
        emit(when (available.status) {
            ShizukuAvailability.Status.MISSING -> State.MISSING
            ShizukuAvailability.Status.STOPPED -> State.STOPPED
            ShizukuAvailability.Status.UNSUPPORTED -> State.UNSUPPORTED
            ShizukuAvailability.Status.UNAUTHORIZED -> State.UNAUTHORIZED
            ShizukuAvailability.Status.DENIED -> State.DENIED
            ShizukuAvailability.Status.READY -> State.READY
            ShizukuAvailability.Status.DISCONNECTED -> State.DISCONNECTED
        })
    }

    fun act() {
        if (closed || busy || authorizing || state == State.PERMISSION_REQUESTED || !foreground()) return
        refresh()
        when (state) {
            State.UNAUTHORIZED -> runCatching {
                emit(State.PERMISSION_REQUESTED)
                Shizuku.requestPermission(requestCode)
                main.postDelayed({ if (!closed && state == State.PERMISSION_REQUESTED) {
                    emit(State.DISCONNECTED)
                    RestartCapabilities.invalidate(app)
                } }, 120_000)
            }.onFailure { emit(State.DISCONNECTED) }
            State.READY -> begin()
            State.ROOT_AVAILABLE, State.ROOT_UNAVAILABLE -> authorizeRoot()
            State.ROOT_READY -> begin()
            else -> Unit
        }
    }

    private fun authorizeRoot() {
        val path = RestartCapabilities.snapshot().root?.path ?: return emit(State.ROOT_MISSING)
        authorizing = true
        emit(State.ROOT_REQUESTING)
        worker.execute {
            val granted = RootRestartAccess.authorize(path)
            main.post {
                if (closed) return@post
                authorizing = false
                uid = if (granted) 0 else null
                emit(if (granted) State.ROOT_READY else State.ROOT_UNAVAILABLE)
                // 权限结果只更新入口，不延续为重启；迟到的授权不能操作宿主。
                RestartCapabilities.invalidate(app)
            }
        }
    }

    private fun begin() {
        if (!flight.compareAndSet(false, true)) { emit(State.BUSY); return }
        busy = true
        val id = ++operation
        startedAt = SystemClock.elapsedRealtime()
        manualOldPid = if (route == RestartRoute.MANUAL) HostReceiptRegistry.current()?.pid else null
        deadline = startedAt + 45_000
        emit(State.SAVING)
        main.postDelayed({
            if (current(id)) finish(when (state) {
                State.SAVING -> State.SAVE_FAILED
                State.STOPPING -> State.STOP_FAILED
                State.LAUNCHING -> State.LAUNCH_FAILED
                State.VERIFYING -> State.RECEIPT_TIMEOUT
                else -> State.DISCONNECTED
            })
        }, 45_000)
        fun saved(success: Boolean) {
            main.post {
                if (!current(id)) return@post
                if (!success) { finish(State.SAVE_FAILED); return@post }
                worker.execute {
                    val captured = runCatching { PublicationAuthorityStore.current(app) }.getOrNull()
                    val disabled = if (noRoot && !NoRootSupportStore.isDesiredEnabled(app))
                        NoRootSupportStore.readSnapshot(app)?.takeUnless { it.enabled }?.revision else null
                    main.post {
                        if (!current(id)) return@post
                        publication = captured
                        disabledRevision = disabled
                        if (!foreground()) { finish(State.CANCELLED); return@post }
                        if (captured == null || !captured.decision.isAuthorized) { finish(State.SAVE_FAILED); return@post }
                        connect(id)
                    }
                }
            }
        }
        RestartConfigBarrier.flush(app, preferences, noRoot, ::saved)
    }

    private fun connect(id: Long) {
        if (route == RestartRoute.MANUAL) {
            if (!foreground() || !matches()) { finish(State.CONFIG_CHANGED); return }
            emit(State.LAUNCHING)
            val opened = app.startActivityOrElse(Intent().setClassName(HostRestartCommands.TARGET,
                "${HostRestartCommands.TARGET}.MainActivityV2").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (!opened) { finish(State.LAUNCH_FAILED); return }
            emit(State.VERIFYING)
            if (disabledRevision != null) HostRuntimeProbeClient.probeAsync(app, verify = true) {}
            verify(id)
            return
        }
        emit(State.BINDING)
        worker.execute {
            val backend = runCatching {
                if (!current(id) || !matches()) return@runCatching null
                if (route == RestartRoute.ROOT) {
                    val root = RootRestartAccess.read()
                    check(root.status == RootRestartAccess.Status.READY)
                    val hostUid = requireNotNull(app.packageManager.getPackageInfoOrNull(HostRestartCommands.TARGET)?.applicationInfo).uid
                    RootRestartTransport(requireNotNull(root.path), hostUid) { current(id) && matches() }
                } else {
                    check(ShizukuAvailability.read(app).status == ShizukuAvailability.Status.READY)
                    null
                }
            }
            main.post {
                if (!current(id)) return@post
                if (backend.isFailure) { finish(if (route == RestartRoute.ROOT) State.ROOT_UNAVAILABLE else State.DISCONNECTED); return@post }
                if (!foreground() || !matches()) { finish(State.CANCELLED); return@post }
                if (route == RestartRoute.ROOT) {
                    transport = backend.getOrNull() ?: return@post finish(State.ROOT_UNAVAILABLE)
                    stop(id)
                } else runCatching { bound = true; Shizuku.bindUserService(args, connection) }
                    .onFailure { finish(State.DISCONNECTED) }
            }
        }
    }

    private fun matches(): Boolean {
        val captured = publication ?: return false
        if (!PublicationAuthorityStore.matches(app, captured)) return false
        return disabledRevision?.let { revision ->
            !NoRootSupportStore.isDesiredEnabled(app) && NoRootSupportStore.readSnapshot(app)?.let { !it.enabled && it.revision == revision } == true
        } ?: true
    }

    private fun stop(id: Long) {
        if (!foreground()) { finish(State.CANCELLED); return }
        emit(State.STOPPING)
        val remote = transport ?: return finish(State.DISCONNECTED)
        worker.execute {
            val attempt = runCatching { if (!current(id) || !matches()) null else remote.stop() }
            main.post {
                if (!current(id)) return@post
                if (attempt.isFailure) { finish(State.STOP_FAILED); return@post }
                val token = attempt.getOrNull()
                if (token == null) { finish(State.CONFIG_CHANGED); return@post }
                uid = remote.uid
                if (!foreground()) { finish(State.CANCELLED); return@post }
                emit(State.LAUNCHING)
                worker.execute {
                    val launch = runCatching { if (!current(id) || !matches()) null else remote.start(token) }
                    main.post {
                        if (!current(id)) return@post
                        if (launch.isFailure) { finish(State.LAUNCH_FAILED); return@post }
                        val started = launch.getOrNull()
                        if (started == null) { finish(State.CONFIG_CHANGED); return@post }
                        newPid = started
                        if (newPid <= 0) { finish(State.LAUNCH_FAILED); return@post }
                        emit(State.VERIFYING)
                        // 只剩只读验证；及时销毁特权进程，避免持有无用服务。
                        unbind()
                        // 停用回执沿用现有带 nonce 的握手；仅查询进程状态不会更新关闭确认。
                        if (disabledRevision != null) HostRuntimeProbeClient.probeAsync(app, verify = true) {}
                        verify(id)
                    }
                }
            }
        }
    }

    private fun verify(id: Long) {
        if (!current(id)) return
        worker.execute {
            val same = runCatching(::matches).getOrDefault(false)
            val disabled = disabledRevision
            if (route == RestartRoute.MANUAL) newPid = HostReceiptRegistry.current()?.let {
                if (it.pid != manualOldPid && it.started >= startedAt) it.pid else 0
            } ?: 0
            val rows = if (noRoot) NPatchProcessDiagnostics.query(app) else emptyList()
            val row = rows.firstOrNull { it.processName == HostRestartCommands.TARGET && it.pid == newPid && newPid > 0 }
            main.post {
                if (!current(id)) return@post
                if (!same) { finish(State.CONFIG_CHANGED); return@post }
                if (disabled != null) {
                    val status = NoRootSupportStore.readStatus(app)
                    if (row?.state == NPatchProcessState.DISABLED && status.syncState == NoRootSupportStore.SyncState.DISABLED && status.syncRevision == disabled) {
                        finish(State.DISABLED_CONFIRMED); return@post
                    }
                    retry(id)
                } else HostRuntimeDiagnosticsQueryClient.query(app) { result ->
                    if (!current(id)) return@query
                    val snapshot = result.snapshot
                    val session = HostReceiptRegistry.current()
                    val accepted = RestartReceiptPolicy.accepts(publication?.identity, snapshot,
                        result.status == HostRuntimeDiagnosticsQueryClient.Status.READY,
                        newPid, session?.pid, session?.started, startedAt,
                        !noRoot || row?.state == NPatchProcessState.INSTALLED)
                    if (accepted) {
                        worker.execute {
                            val stillCurrent = runCatching(::matches).getOrDefault(false)
                            main.post {
                                if (current(id)) finish(if (stillCurrent) State.CONFIRMED else State.CONFIG_CHANGED)
                            }
                        }
                    } else retry(id)
                }
            }
        }
    }

    private fun retry(id: Long) {
        if (SystemClock.elapsedRealtime() >= deadline - 1_000) finish(State.RECEIPT_TIMEOUT)
        else main.postDelayed({ verify(id) }, 700)
    }
    private fun current(id: Long) = !closed && busy && id == operation
    private fun emit(next: State) { state = next; changed(next, uid) }
    private fun finish(next: State) {
        operation++
        if (busy) { busy = false; flight.set(false) }
        unbind()
        emit(next)
        if (route == RestartRoute.ROOT) RestartCapabilities.invalidate(app)
    }
    private fun unbind() {
        transport = null
        if (!bound) return
        bound = false
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }
    fun onBackground() {
        if (busy && state !in setOf(State.LAUNCHING, State.VERIFYING)) finish(State.CANCELLED)
    }
    override fun close() {
        if (closed) return
        closed = true
        operation++
        if (busy) { busy = false; flight.set(false) }
        unbind()
        probe.close()
        if (route == RestartRoute.SHIZUKU) {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(died)
            Shizuku.removeRequestPermissionResultListener(permission)
        }
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
    companion object {
        const val MANAGER = "moe.shizuku.privileged.api"
        private val flight = AtomicBoolean(false)
        private var nextRequestCode = 8210
    }
}
