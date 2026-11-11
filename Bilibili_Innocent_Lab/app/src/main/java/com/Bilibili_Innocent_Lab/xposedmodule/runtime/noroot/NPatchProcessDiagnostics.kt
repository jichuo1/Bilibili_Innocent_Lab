package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationNative
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.CrossAppBroadcastCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryContract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 各个已加载进程就地回复；崩溃私有证据独立于授权和主进程心跳。 */
internal object NPatchProcessDiagnostics {
    private val action = "${BuildConfig.APPLICATION_ID}.QUERY_NPATCH_PROCESSES"
    private const val PROTOCOL = 4
    private const val TIMEOUT_MS = 1_500L
    private val queryInFlight = AtomicBoolean(false)
    @Volatile private var current: NPatchProcessSnapshot? = null
    private val startup = NPatchStartupTracker()

    fun recordAdmission(ms: Long, result: String, route: String, routes: List<NPatchRouteEvidence>) {
        if (current != null) startup.admission(ms, result, route, routes)
    }

    fun recordInstallation(ms: Long) {
        if (current != null) startup.installation(ms)
    }

    fun onApplicationReady(context: Context) {
        val row = current ?: return
        // Host SDKs can replace the early attach-time handler during Application.onCreate.
        // Chain their completed initialization without changing crash or Hook semantics.
        NPatchCrashHandler.install(NPatchCrashStore.forProcess(context, row.processName))
    }

    @Synchronized
    fun initialize(context: Context, processName: String, mode: NPatchMode) {
        if (current != null) return
        val app = context.applicationContext ?: context
        val info = app.packageManager.getPackageInfoOrNull(app.packageName) ?: return
        val initial = NPatchProcessSnapshot(processName, android.os.Process.myPid(), mode,
            NPatchProcessState.LOADED, targetUpdateTime = info.lastUpdateTime,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(), process64Bit = android.os.Process.is64Bit())
        // 进程名与数值同查询端校验，未知/隔离进程不能凭部分信息伪装成功。
        NPatchProcessCodec.encode(listOf(initial))
        // 只读内存快照不依赖 UI；宿主主线程启动缓慢/卡住时仍可诊断其它已加载进程。
        // 此 HandlerThread 与动态接收器同进程存活，注册失败必须退出，不能每次查询新建线程。
        val crashStore = NPatchCrashStore.forProcess(app, processName)
        NPatchCrashHandler.install(crashStore)
        val callbackThread = HandlerThread("bil-npatch-process-diagnostics").apply { start() }
        try {
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // 这个接收器跑在独立 HandlerThread 上：任何逃逸异常都会带走整个宿主进程，Intent 解析也要在守卫内。
                    HostThreadGuard.run("npatch.process_diagnostics.receive") {
                        if (com.Bilibili_Innocent_Lab.xposedmodule.runtime.StartupTrace.enabled &&
                            intent.action == NPatchStartupTraceQuery.action) {
                            if (!isOrderedBroadcast || intent.getIntExtra("v", 0) != 1) return@run
                            if (AndroidVersion.isAtLeast(AndroidVersion.U) && sentFromPackage != BuildConfig.APPLICATION_ID) return@run
                            val nonce = intent.getStringExtra("nonce").orEmpty()
                            if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce)) return@run
                            runCatching { current?.let { NPatchStartupTraceQuery.respond(this, it, nonce) } }
                            return@run
                        }
                        if (intent.action != action || (!isOrderedBroadcast && !intent.getBooleanExtra("testCrash", false)) ||
                            intent.getIntExtra("v", 0) != PROTOCOL) return@run
                        if (AndroidVersion.isAtLeast(AndroidVersion.U) && sentFromPackage != BuildConfig.APPLICATION_ID) return@run
                        val nonce = intent.getStringExtra("nonce").orEmpty()
                        if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce)) return@run
                        runCatching { NPatchCrashHandler.install(crashStore) }
                        if (intent.getBooleanExtra("testCrash", false)) {
                            if (BuildConfig.DEBUG && processName == NoRootSupportState.TARGET_PACKAGE) {
                                // Explicit DEBUG-only diagnostic action; deliberately outside HostThreadGuard.
                                Handler(Looper.getMainLooper()).postDelayed({
                                    NPatchCrashHandler.install(crashStore)
                                    error("BIL controlled diagnostic crash")
                                }, 300L)
                            }
                            return@run
                        }
                        runCatching {
                            val extras = getResultExtras(true)
                            val rows = NPatchProcessCodec.decode(extras.getString("rows") ?: "[]") ?: return@run
                            val row = current?.copy(dexKitState = DexMeguvizAssistEngine.nativeAvailability(),
                                frameNativeState = FrameInterpolationNative.status.name,
                                startups = startup.snapshot(),
                                guardedFailures = com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard.failureCounts()
                                    .values.sum().coerceIn(0L, 1_000_000_000L),
                                support = crashStore.read().let { evidence ->
                                    if (intent.getBooleanExtra("media", false) && processName == NoRootSupportState.TARGET_PACKAGE)
                                        NPatchCrashStore.readMedia(app, evidence) else evidence
                                }) ?: return@run
                            if (rows.size >= NPatchProcessCodec.MAX_PROCESSES || rows.any { it.processName == row.processName }) return@run
                            extras.putString("nonce", nonce)
                            extras.putString("rows", NPatchProcessCodec.encode(rows + row))
                            setResultExtras(extras)
                        }
                    }
                }
            }, IntentFilter(action).apply {
                if (com.Bilibili_Innocent_Lab.xposedmodule.runtime.StartupTrace.enabled) addAction(NPatchStartupTraceQuery.action)
            }, HostRuntimeDiagnosticsQueryContract.PERMISSION_QUERY,
                Handler(callbackThread.looper), ContextCompat.RECEIVER_EXPORTED)
        } catch (failure: Exception) {
            callbackThread.quitSafely()
            throw failure
        }
        current = initial
    }

    fun record(state: NPatchProcessState, source: String? = null, installedHooks: Int = 0) {
        current = current?.let { it.copy(state = state, source = source ?: it.source,
            installedHookCount = if (state == NPatchProcessState.INSTALLED) installedHooks.coerceIn(0, 4096) else 0) }
    }

    /** 仅在后台线程调用。结果只含本次已响应进程；超时/无响应保持未知，不沿用历史。 */
    fun query(context: Context, includeMedia: Boolean = false): List<NPatchProcessSnapshot> =
        queryRows(context, includeMedia, requireCurrentModule = true)

    fun observe(context: Context, includeMedia: Boolean = false): NPatchModuleObservation =
        NPatchModuleObservation.fromRows(queryRows(context, includeMedia, requireCurrentModule = false), BuildConfig.VERSION_CODE.toLong())

    private fun queryRows(context: Context, includeMedia: Boolean, requireCurrentModule: Boolean): List<NPatchProcessSnapshot> {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (!queryInFlight.compareAndSet(false, true)) return emptyList()
        try {
            val app = context.applicationContext ?: context
            if (!NPatchRuntimeProfiles.read(app).markerPresent) return emptyList()
            val target = app.packageManager.getPackageInfoOrNull(NoRootSupportState.TARGET_PACKAGE) ?: return emptyList()
            val nonce = UUID.randomUUID().toString()
            val latch = CountDownLatch(1)
            var rows = emptyList<NPatchProcessSnapshot>()
            val complete = AtomicBoolean(false)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!complete.compareAndSet(false, true)) return
                    rows = runCatching {
                        val extras = getResultExtras(false) ?: return@runCatching emptyList()
                        if (extras.getString("nonce") != nonce) return@runCatching emptyList()
                        val decoded = NPatchProcessCodec.decode(extras.getString("rows").orEmpty()) ?: return@runCatching emptyList()
                        // 内嵌模块可能落后于独立设置 App，不能把旧模块的运行状态冒充当前版本。
                        if (decoded.any { it.targetUpdateTime != target.lastUpdateTime || requireCurrentModule &&
                            it.moduleVersionCode != BuildConfig.VERSION_CODE.toLong() }) emptyList() else decoded.sortedBy { it.processName }
                    }.getOrDefault(emptyList())
                    latch.countDown()
                }
            }
            // setPackage 只分派给目标 UID 的动态接收器；结果接收器为系统持有的本次回调。
            // 有序广播累加各进程的有界记录，避免子进程覆盖主进程结果或争抢激活回执。
            CrossAppBroadcastCompat.sendOrderedBroadcast(app,
                Intent(action).setPackage(NoRootSupportState.TARGET_PACKAGE).putExtra("v", PROTOCOL).putExtra("nonce", nonce)
                    .putExtra("media", includeMedia),
                receiver, Handler(Looper.getMainLooper()), initialExtras = Bundle())
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) return emptyList()
            return rows
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        } finally {
            queryInFlight.set(false)
        }
    }

    /** Called only after an explicit DEBUG UI confirmation or opt-in instrumentation. */
    fun requestTestCrash(context: Context) {
        if (!BuildConfig.DEBUG) return
        CrossAppBroadcastCompat.sendBroadcast(context,
            Intent(action).setPackage(NoRootSupportState.TARGET_PACKAGE).putExtra("v", PROTOCOL)
                .putExtra("nonce", UUID.randomUUID().toString()).putExtra("testCrash", true))
    }
}
