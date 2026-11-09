package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticReportCodec
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostQueryState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticsCollector
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinSessionDiagnostics
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

internal sealed interface DiagnosticsScreenState {
    data object Loading : DiagnosticsScreenState
    data class Ready(val snapshot: ModuleDiagnosticSnapshot) : DiagnosticsScreenState
    data class Failed(val reasonCode: String) : DiagnosticsScreenState
}

internal sealed interface DiagnosticsExportState {
    data object Idle : DiagnosticsExportState
    data object Running : DiagnosticsExportState
    data class Finished(val uri: Uri) : DiagnosticsExportState
    data class Failed(val reasonCode: String) : DiagnosticsExportState
}

/**
 * 关闭后提交即丢弃的单线程执行器。
 *
 * ViewModel 在 [ViewModel.onCleared] 里关闭执行器，但宿主诊断查询是异步回调，可能在关闭之后才
 * 送达主线程；直接 `submit` 会抛 [RejectedExecutionException] 并杀掉整个进程（2026-09-23 真机
 * logcat：快速进出诊断页即可触发）。页面已经销毁，这类迟到的工作没有任何接收方，丢弃即可。
 */
internal class ShutdownSafeExecutor(threadName: String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    @Volatile
    var isShutdown = false
        private set

    /** @return false 表示已关闭、任务被丢弃。 */
    fun submit(task: () -> Unit): Boolean {
        if (isShutdown) return false
        return try {
            executor.submit(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun shutdownNow() {
        isShutdown = true
        executor.shutdownNow()
    }
}

internal class DiagnosticsViewModel : ViewModel() {
    val screenState = MutableLiveData<DiagnosticsScreenState>(DiagnosticsScreenState.Loading)
    val exportState = MutableLiveData<DiagnosticsExportState>(DiagnosticsExportState.Idle)

    private val worker = ShutdownSafeExecutor("module-diagnostics")
    private val generation = AtomicLong(0L)

    fun refresh(
        context: Context,
        skin: SkinSessionDiagnostics?,
        frameworkCheckPending: Boolean
    ) {
        val request = generation.incrementAndGet()
        if (screenState.value !is DiagnosticsScreenState.Ready) {
            screenState.value = DiagnosticsScreenState.Loading
        }
        val appContext = context.applicationContext ?: context
        HostRuntimeDiagnosticsQueryClient.query(appContext) { queryResult ->
            // 回调可能在页面销毁、执行器关闭之后才送达；那时没有任何接收方。
            if (worker.isShutdown) return@query
            val hostRuntime = queryResult.snapshot
                .takeIf { queryResult.status == HostRuntimeDiagnosticsQueryClient.Status.READY }
            collectAsync(
                request,
                appContext,
                skin,
                frameworkCheckPending,
                hostRuntime,
                DiagnosticHostQueryState.valueOf(queryResult.status.name),
                queryResult.failure
            )
        }
    }

    private fun collectAsync(
        request: Long,
        context: Context,
        skin: SkinSessionDiagnostics?,
        frameworkCheckPending: Boolean,
        hostRuntime: HostRuntimeDiagnosticsSnapshot?,
        hostQueryState: DiagnosticHostQueryState,
        hostQueryFailure: com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure
    ) {
        worker.submit {
            if (worker.isShutdown || generation.get() != request) return@submit
            runCatching {
                val observation = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessDiagnostics.observe(context)
                ModuleDiagnosticsCollector.collect(
                    context = context,
                    skin = skin,
                    frameworkCheckPending = frameworkCheckPending,
                    hostRuntime = hostRuntime,
                    hostQueryState = hostQueryState,
                    hostQueryFailure = hostQueryFailure,
                    nPatchProcesses = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchStartupHistory.enrich(context,
                        observation.processes),
                    nPatchObservedModuleVersions = observation.versions,
                    nPatchManagerReachability = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchScopeProbe.probeReachability(context),
                    nPatchEmbeddedBuild = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchEmbeddedBuild.read(context)
                )
            }.onSuccess { snapshot ->
                if (generation.get() == request) {
                    screenState.postValue(DiagnosticsScreenState.Ready(snapshot))
                }
            }.onFailure {
                if (generation.get() == request) {
                    screenState.postValue(DiagnosticsScreenState.Failed("collection_failed"))
                }
            }
        }
    }

    fun export(context: Context, uri: Uri, snapshot: ModuleDiagnosticSnapshot, supportBundle: Boolean = false) {
        if (exportState.value is DiagnosticsExportState.Running) return
        exportState.value = DiagnosticsExportState.Running
        val appContext = context.applicationContext ?: context
        worker.submit {
            runCatching {
                val bytes = if (supportBundle) {
                    com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.NPatchSupportBundleCollector.collect(appContext, snapshot)
                } else DiagnosticReportCodec.encode(snapshot)
                val maximum = if (supportBundle) com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.NPatchSupportArchive.MAX_FILE_BYTES
                    else DiagnosticReportCodec.MAX_FILE_BYTES
                val resolver = appContext.contentResolver
                resolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(bytes)
                    output.flush()
                } ?: error("output_unavailable")
                val readBack = resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    val output = ByteArrayOutputStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        require(output.size() <= maximum) {
                            "readback_too_large"
                        }
                    }
                    output.toByteArray()
                } ?: error("readback_unavailable")
                require(readBack.contentEquals(bytes)) { "readback_mismatch" }
                val metadata = if (supportBundle) com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.NPatchSupportArchive.validate(readBack)
                    else DiagnosticReportCodec.validate(readBack)
                require(metadata.collectedAtEpochMs == snapshot.inputs.collectedAtEpochMs) {
                    "readback_mismatch"
                }
            }.onSuccess {
                exportState.postValue(DiagnosticsExportState.Finished(uri))
            }.onFailure {
                exportState.postValue(DiagnosticsExportState.Failed("export_failed"))
            }
        }
    }

    fun consumeExportResult() {
        if (exportState.value !is DiagnosticsExportState.Running) {
            exportState.value = DiagnosticsExportState.Idle
        }
    }

    override fun onCleared() {
        generation.incrementAndGet()
        worker.shutdownNow()
        super.onCleared()
    }
}
