package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticReportCodec
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticsCollector
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinSessionDiagnostics
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
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

internal class DiagnosticsViewModel : ViewModel() {
    val screenState = MutableLiveData<DiagnosticsScreenState>(DiagnosticsScreenState.Loading)
    val exportState = MutableLiveData<DiagnosticsExportState>(DiagnosticsExportState.Idle)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "module-diagnostics").apply { isDaemon = true }
    }
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
        worker.submit {
            runCatching {
                ModuleDiagnosticsCollector.collect(
                    context = appContext,
                    skin = skin,
                    frameworkCheckPending = frameworkCheckPending
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

    fun export(context: Context, uri: Uri, snapshot: ModuleDiagnosticSnapshot) {
        if (exportState.value is DiagnosticsExportState.Running) return
        exportState.value = DiagnosticsExportState.Running
        val appContext = context.applicationContext ?: context
        worker.submit {
            runCatching {
                val bytes = DiagnosticReportCodec.encode(snapshot)
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
                        require(output.size() <= DiagnosticReportCodec.MAX_FILE_BYTES) {
                            "readback_too_large"
                        }
                    }
                    output.toByteArray()
                } ?: error("readback_unavailable")
                require(readBack.contentEquals(bytes)) { "readback_mismatch" }
                val metadata = DiagnosticReportCodec.validate(readBack)
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
