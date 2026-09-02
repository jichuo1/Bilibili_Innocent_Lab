package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 同目录临时文件校验成功后再替换正式缓存，避免中断写入留下半截 JSON。 */
internal object AtomicJsonCache {

    fun write(target: File, payload: String, validator: (String) -> Boolean): Boolean {
        val parent = target.parentFile ?: return false
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) return false
        val temp = File(
            parent,
            ".${target.name}.tmp-${System.identityHashCode(Thread.currentThread())}-${System.nanoTime()}"
        )
        return try {
            FileOutputStream(temp).use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!validator(temp.readText(Charsets.UTF_8))) return false
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }
}
