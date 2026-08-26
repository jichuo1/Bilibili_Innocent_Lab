package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 自由复制开关的跨进程权威镜像。
 *
 * LSPosed API 93+ 会重定向模块的默认 SharedPreferences；部分实现中模块 UI 能读到
 * 托管值，目标应用在 package-load 早期却只能读到旧快照。这个文件位于模块自身
 * filesDir，不参与 LSPosed prefs 重定向，只由模块进程读写，再由导出的只读 Provider
 * 提供给目标进程。写入仅发生在模块 UI 启动或用户切换开关时，不在宿主热路径上。
 */
object FreeCopyConfigStore {

    data class Snapshot(
        val commentEnabled: Boolean,
        val descriptionEnabled: Boolean,
        val revision: Long
    )

    private const val FILE_NAME = "free_copy_config.bin"
    private const val MAGIC = 0x42494C46 // "BILF"
    private const val FORMAT_VERSION = 1
    private val ioLock = Any()

    fun read(context: Context): Snapshot? = synchronized(ioLock) {
        val atomicFile = AtomicFile(File(context.filesDir, FILE_NAME))
        if (!atomicFile.baseFile.isFile) return@synchronized null
        runCatching {
            DataInputStream(atomicFile.openRead().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return@runCatching null
                val revision = input.readLong()
                val commentEnabled = input.readBoolean()
                val descriptionEnabled = input.readBoolean()
                if (revision <= 0L) null else Snapshot(commentEnabled, descriptionEnabled, revision)
            }
        }.getOrNull()
    }

    fun write(
        context: Context,
        commentEnabled: Boolean,
        descriptionEnabled: Boolean,
        revision: Long = System.currentTimeMillis().coerceAtLeast(1L)
    ): Boolean =
        synchronized(ioLock) {
            if (revision <= 0L) return@synchronized false
            val atomicFile = AtomicFile(File(context.filesDir, FILE_NAME))
            val output = runCatching { atomicFile.startWrite() }.getOrNull() ?: return@synchronized false
            try {
                val data = DataOutputStream(output.buffered())
                data.writeInt(MAGIC)
                data.writeInt(FORMAT_VERSION)
                data.writeLong(revision)
                data.writeBoolean(commentEnabled)
                data.writeBoolean(descriptionEnabled)
                data.flush()
                atomicFile.finishWrite(output)
                true
            } catch (_: Throwable) {
                runCatching { atomicFile.failWrite(output) }
                false
            }
        }
}
