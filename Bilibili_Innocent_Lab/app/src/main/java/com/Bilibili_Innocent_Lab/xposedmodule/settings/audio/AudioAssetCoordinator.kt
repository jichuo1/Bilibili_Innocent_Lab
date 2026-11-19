package com.Bilibili_Innocent_Lab.xposedmodule.settings.audio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.FrameworkServiceCall
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import io.github.libxposed.service.XposedService
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Application-owned work survives a closed/recreated dialog; no view, Activity or callback is captured by a job. */
internal object AudioAssetCoordinator {
    data class Snapshot(val code: String = "unchecked", val part: String = "", val bytes: Long = 0,
                        val total: Long = 0, val busy: Boolean = false)
    private val executor = HostReceiptWire.executor("bil-audio-assets")
    private val watchdog = java.util.concurrent.ScheduledThreadPoolExecutor(1) {
        Thread(it, "bil-audio-assets-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val running = AtomicBoolean()
    private val recheck = AtomicBoolean()
    private val pendingImport = java.util.concurrent.atomic.AtomicReference<List<Uri>?>()
    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    private val history = ArrayDeque<String>()
    @Volatile private var snapshot = Snapshot()
    @Volatile private var control = AudioDownloadControl()
    @Volatile private var app: Context? = null
    @Volatile private var previousReport = ""
    private var previousRead = false // worker only
    @Volatile private var hostReport = "host_query=not_requested"
    private val queryingHost = AtomicBoolean()
    @Volatile private var phaseStarted = System.nanoTime()
    fun current() = snapshot
    fun queryHost() {
        val context = app ?: return
        if (!queryingHost.compareAndSet(false, true)) return
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryClient.query(context) { result ->
            hostReport = "host_query=${result.status} failure=${result.failure} audio_model=${result.snapshot?.audioModelState ?: "unknown"} queried_at=${System.currentTimeMillis()} process=tv.danmaku.bili"
            queryingHost.set(false)
            update(snapshot)
        }
    }

    @Synchronized fun initialize(context: Context) {
        if (app != null) return
        val application = context.applicationContext
        app = application
        // Service listeners only schedule I/O; a recovered connection republishes cached content, never downloads.
        RemoteHookConfigStore.addStatusListener { if (it.capable) refresh(recoverAfterJob = true) }
    }
    fun addListener(listener: (Snapshot) -> Unit) { listeners.add(listener); listener(snapshot) }
    fun removeListener(listener: (Snapshot) -> Unit) { listeners.remove(listener) }
    fun cancel() { pendingImport.set(null); recheck.set(false); control.cancel() }
    @Synchronized fun refresh(recoverAfterJob: Boolean = false) {
        if (running.get()) { if (recoverAfterJob) recheck.set(true); return }
        submit { store -> restoreAndPublish(store) }
    }
    fun download() = submit { store ->
        val missing = AudioAssetSpec.allParts.any { !store.valid(it) }
        if (missing) {
            if (store.directory.usableSpace < 180L * 1024 * 1024) throw AudioAssetFailure("storage_space")
            val fetcher = AudioAssetDownload()
            for (part in listOf(AudioAssetSpec.model, AudioAssetSpec.runtime)) {
                if (part == AudioAssetSpec.runtime && AudioAssetSpec.native.values.flatten().all { store.valid(it) }) continue
                fetcher.fetch(store, part, AudioAssetSpec.sources.getValue(part), control, ::record) { count ->
                    val before = snapshot
                    if (before.part != part.name || count == part.size || count - before.bytes >= 256 * 1024)
                        update(Snapshot("downloading", part.name, count, part.size, true))
                }
            }
            if (AudioAssetSpec.native.values.flatten().any { !store.valid(it) }) {
                update(Snapshot("extracting", busy = true)); store.unpackRuntime()
            }
        }
        restoreAndPublish(store)
    }

    /** Accept the two original upstream files in either order; filenames and document-provider MIME types are not trusted. */
    @Synchronized fun importFiles(uris: List<Uri>): Boolean {
        if (uris.size !in 1..2) { update(Snapshot("import_count")); return false }
        // Activity ON_START can schedule a check before a document result is delivered.
        // Retain that explicit result until the check completes instead of silently dropping it.
        if (running.get()) { pendingImport.set(uris.toList()); return true }
        return submit { store ->
        val context = checkNotNull(app)
        if (store.directory.usableSpace < 180L * 1024 * 1024) throw AudioAssetFailure("storage_space")
        if (uris.size !in 1..2) throw AudioAssetFailure("import_count")
        for (uri in uris) {
            control.check()
            update(Snapshot("importing", busy = true))
            val temporary = Files.createTempFile(store.directory.toPath(), ".import-", ".part")
            try {
                var bytes = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    Files.newOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            control.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > AudioAssetSpec.runtime.size) throw AudioAssetFailure("import_size")
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    }
                } ?: throw AudioAssetFailure("import_unreadable")
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                val part = listOf(AudioAssetSpec.model, AudioAssetSpec.runtime).find { it.size == bytes && it.sha256 == hash }
                    ?: throw AudioAssetFailure("import_identity", detail = "bytes=$bytes sha256=$hash")
                store.install(part) { Files.newInputStream(temporary) }
                record("import_verified part=${part.name}")
                if (part == AudioAssetSpec.runtime) store.unpackRuntime()
            } finally { Files.deleteIfExists(temporary) }
        }
        restoreAndPublish(store)
        }
    }

    @Synchronized private fun submit(work: (AudioAssetStore) -> Unit): Boolean {
        val context = app ?: return false
        if (!running.compareAndSet(false, true)) return false
        control = AudioDownloadControl()
        update(Snapshot("checking", busy = true))
        return runCatching {
            executor.execute {
                // Binder/document providers cannot be forcibly interrupted. Detect a stalled phase,
                // retain its single worker, cancel acceptance of late results, and give a recovery action.
                val monitor = watchdog.scheduleWithFixedDelay({
                    if (snapshot.busy && snapshot.code !in setOf("downloading", "operation_stalled") &&
                        System.nanoTime() - phaseStarted > 45_000_000_000L) {
                        control.cancel()
                        record("code=operation_stalled phase=${snapshot.code}; late_result_cancelled=true")
                        update(Snapshot("operation_stalled", busy = true)); persist(context)
                    }
                }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
                try {
                    if (!previousRead) {
                        previousRead = true
                        previousReport = runCatching {
                            File(context.noBackupFilesDir, "audio-assets-diagnostics.txt").inputStream().use {
                                val bytes = ByteArray(20 * 1024); val count = it.read(bytes)
                                if (count > 0) String(bytes, 0, count, Charsets.UTF_8).substringBefore("\nprevious_process:\n") else ""
                            }
                        }.getOrDefault("")
                    }
                    val store = AudioAssetStore(context.noBackupFilesDir)
                    store.locked { work(store) }
                } catch (failure: Exception) {
                    val known = failure as? AudioAssetFailure
                    record("code=${known?.code ?: "local_io"} part=${known?.part.orEmpty()} ${known?.detail.orEmpty()} cause=${failure.javaClass.simpleName}")
                    update(Snapshot(known?.code ?: "local_io", known?.part.orEmpty()))
                } finally {
                    monitor.cancel(false)
                    update(snapshot.copy(busy = false))
                    persist(context)
                    running.set(false)
                    val imported = pendingImport.getAndSet(null)
                    if (imported != null) importFiles(imported)
                    else if (recheck.getAndSet(false)) refresh()
                }
            }; true
        }.getOrElse { running.set(false); update(Snapshot("queue_busy")); false }
    }

    private fun restoreAndPublish(store: AudioAssetStore) {
        control.check()
        // An interrupted extraction can be repaired from a verified archive without another network request.
        if (AudioAssetSpec.native.values.flatten().any { !store.valid(it) } && store.valid(AudioAssetSpec.runtime)) {
            update(Snapshot("extracting", busy = true)); store.unpackRuntime()
        }
        val local = AudioAssetSpec.allParts.all { store.valid(it) }
        update(Snapshot(if (local) "publishing" else "checking", busy = true))
        val result = RemoteHookConfigStore.withServiceResult { service ->
            val remote = FrameworkFiles(service)
            try {
                if (local) AudioAssetPublication.publish(store, AudioAssetSpec.allParts, remote, control)
                AudioAssetPublication.inspect(AudioAssetSpec.allParts, remote)
            } catch (failure: Exception) {
                record("remote_operation code=${(failure as? AudioAssetFailure)?.code ?: "remote_io"} cause=${failure.javaClass.simpleName}")
                throw failure
            }
        }
        control.check()
        val code = when (result) {
            is FrameworkServiceCall.Success -> result.value
            is FrameworkServiceCall.Failure -> when (result.reason) {
                FrameworkServiceCall.Reason.NOT_CONNECTED, FrameworkServiceCall.Reason.SERVICE_DIED -> "framework_disconnected"
                FrameworkServiceCall.Reason.METADATA_UNAVAILABLE, FrameworkServiceCall.Reason.CONNECTION_CHANGED -> "framework_pending"
                FrameworkServiceCall.Reason.UNSUPPORTED -> "framework_unsupported"
                FrameworkServiceCall.Reason.OPERATION_FAILED -> "remote_io"
            }
        }
        record("code=$code local_verified=$local connection=${RemoteHookConfigStore.status().connectionId}")
        update(Snapshot(code))
    }

    private class FrameworkFiles(private val service: XposedService) : AudioRemoteFiles {
        override fun names() = service.listRemoteFiles().toSet()
        override fun open(name: String) = ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(name))
        override fun write(name: String, copy: (java.io.OutputStream) -> Unit) {
            ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use { output ->
                output.channel.truncate(0); copy(output); output.fd.sync()
            }
        }
    }

    private fun update(value: Snapshot) {
        if (value.code != snapshot.code) phaseStarted = System.nanoTime()
        snapshot = value
        listeners.forEach { runCatching { it(value) } }
    }
    @Synchronized private fun record(line: String) {
        history.addLast("${System.currentTimeMillis()} ${line.take(400)}")
        while (history.size > 32) history.removeFirst()
        Log.i("BILAudioAssets", line.take(400))
    }
    @Synchronized fun diagnostics(): String {
        val framework = RemoteHookConfigStore.status()
        return "audio_assets=${AudioAssetSpec.ID} app=${BuildConfig.VERSION_NAME}\n" +
            "state=${snapshot.code} part=${snapshot.part} bytes=${snapshot.bytes}/${snapshot.total} busy=${snapshot.busy}\n" +
            "framework=${framework.name} api=${framework.apiVersion} capable=${framework.capable} connection=${framework.connectionId} failure=${framework.failureCode}\n" +
            "$hostReport\nFor ijkservice or failure details inspect host log key player_audio_rescue.model.*\n" + history.joinToString("\n") +
            (if (previousReport.isBlank()) "" else "\nprevious_process:\n$previousReport")
    }
    private fun persist(context: Context) {
        runCatching {
            val directory = context.noBackupFilesDir.toPath()
            val temporary = Files.createTempFile(directory, ".audio-diagnostic-", ".tmp")
            try {
                Files.write(temporary, diagnostics().toByteArray(Charsets.UTF_8))
                Files.move(temporary, directory.resolve("audio-assets-diagnostics.txt"), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }.onFailure { Log.w("BILAudioAssets", "diagnostic_persist_failed:${it.javaClass.simpleName}") }
    }
}
