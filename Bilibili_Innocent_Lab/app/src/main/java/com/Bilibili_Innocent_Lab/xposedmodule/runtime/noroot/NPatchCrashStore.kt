package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Files belong to this host process; settings never tries to read another UID's private storage. */
internal class NPatchCrashStore(private val directory: File) {
    @Synchronized fun save(error: Throwable) {
        directory.mkdirs()
        val crash = NPatchSupportCodec.crash(error, System.currentTimeMillis())
        val bytes = NPatchSupportCodec.encode(NPatchSupportEvidence("AVAILABLE", listOf(crash))).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
        val temporary = File(directory, "pending.tmp")
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        File(directory, "crash-2.json").delete()
        File(directory, "crash-1.json").renameTo(File(directory, "crash-2.json"))
        File(directory, "crash-0.json").renameTo(File(directory, "crash-1.json"))
        check(temporary.renameTo(File(directory, "crash-0.json")))
    }

    @Synchronized fun read(): NPatchSupportEvidence = runCatching {
        val files = (0..2).map { File(directory, "crash-$it.json") }.filter { it.isFile }
        val crashes = files.map { file ->
            require(file.length() <= MAX_FILE_BYTES)
            val json = file.inputStream().use { it.readBytesBounded(MAX_FILE_BYTES).toString(Charsets.UTF_8) }
            NPatchSupportCodec.decode(JSONObject(json)).crashes.single()
        }
        NPatchSupportEvidence(if (crashes.isEmpty()) "EMPTY" else "AVAILABLE", crashes)
    }.getOrElse { NPatchSupportEvidence(crashState = "INVALID") }

    companion object {
        const val MAX_FILE_BYTES = 32 * 1024
        fun forProcess(context: Context, process: String): NPatchCrashStore {
            require(process.matches(Regex("tv\\.danmaku\\.bili(?::[A-Za-z0-9_.-]{1,96})?")))
            return NPatchCrashStore(File(context.filesDir, "bil-support/${process.substringAfter(':', "main")}"))
        }

        fun readMedia(context: Context, evidence: NPatchSupportEvidence): NPatchSupportEvidence = runCatching {
            // NPatch 833 writes daily YYYYMMDD.log files here; never request all-files access.
            @Suppress("DEPRECATION") // externalMediaDirs is NPatch's actual file contract, not a media discovery API.
            val roots = context.externalMediaDirs.filterNotNull()
            val file = roots.asSequence().flatMap { root ->
                File(root, "npatch/log").listFiles().orEmpty().asSequence().filter {
                    it.isFile && Regex("[0-9]{8}\\.log").matches(it.name) && it.canonicalFile.parentFile == File(root, "npatch/log").canonicalFile
                }
            }.maxByOrNull { it.name } ?: return@runCatching evidence.copy(mediaState = "EMPTY")
            val raw = RandomAccessFile(file, "r").use {
                val size = it.length()
                val offset = (size - 64 * 1024).coerceAtLeast(0)
                it.seek(offset)
                val bytes = ByteArray((size - offset).toInt())
                it.readFully(bytes)
                bytes.toString(Charsets.UTF_8).let { text -> if (offset > 0) text.substringAfter('\n', "") else text }
            }
            val lines = NPatchSupportCodec.media(raw)
            evidence.copy(mediaState = if (lines.isEmpty()) "EMPTY" else "AVAILABLE", mediaLines = lines)
        }.getOrElse { evidence.copy(mediaState = "UNAVAILABLE") }
    }
}

internal object NPatchCrashHandler {
    @Volatile private var installed: Thread.UncaughtExceptionHandler? = null
    private val recording = ThreadLocal<Boolean>()
    @Synchronized fun install(store: NPatchCrashStore) {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        if (previous === installed) return
        val handler = chained(store::save, previous)
        Thread.setDefaultUncaughtExceptionHandler(handler)
        installed = handler
    }

    internal fun chained(save: (Throwable) -> Unit, previous: Thread.UncaughtExceptionHandler) =
        Thread.UncaughtExceptionHandler { thread, error ->
            var ownsRecording = false
            try { runCatching {
                if (recording.get() != true) {
                    ownsRecording = true
                    recording.set(true)
                    save(error)
                }
            } }
            finally {
                try { previous.uncaughtException(thread, error) }
                finally { if (ownsRecording) recording.remove() }
            }
        }
}
