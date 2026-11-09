package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Settings-app private, bounded history. Called only by the diagnostics worker, never on host startup. */
internal object NPatchStartupHistory {
    @Synchronized fun enrich(context: Context, rows: List<NPatchProcessSnapshot>): List<NPatchProcessSnapshot> {
        if (rows.isEmpty()) return rows
        return runCatching {
            val file = AtomicFile(File(context.filesDir, "npatch-startup-history.json"))
            val old = runCatching {
                file.openRead().use { input ->
                    val bytes = input.readBytesBounded(NPatchProcessCodec.MAX_CHARS)
                    NPatchProcessCodec.decode(bytes.toString(Charsets.UTF_8)).orEmpty()
                }
            }.getOrDefault(emptyList())
            val update = NPatchStartupHistoryPolicy.merge(old, rows)
            val bytes = NPatchProcessCodec.encode(update.persisted.map { it.copy(support = NPatchSupportEvidence()) }).toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
            update.current
        }.getOrDefault(rows)
    }
}

internal fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val read = read(buffer)
        if (read < 0) return output.toByteArray()
        require(output.size() + read <= max)
        output.write(buffer, 0, read)
    }
}
