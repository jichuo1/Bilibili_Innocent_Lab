package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * DexMeguviz 查询的"未完成闸门"。
 *
 * 一趟查询在宿主进程里有数百 MB 的瞬时峰值：2026-10-01 真机 9.14.0（APK 220 MB、33 个 DEX），
 * 冷启动 VmHWM 从 689 MB 升到 1.35 GB，查完 RSS 回落到约 420 MB。低内存设备上这一趟可能让宿主
 * 被系统杀掉；缓存还没写，下次启动又会再查、再被杀。
 *
 * 做法：查之前把 [key]（宿主指纹）写进 [marker]，查完删除。启动时发现同一宿主的标记还在，说明
 * 上次没查完（进程死在查询中），本宿主版本不再查，按缺失处理。宿主换版（key 变化）或手动重适配
 * （删除标记）后才会再试。标记读写失败不阻止查询。
 */
internal class DexAssistAttemptGuard(
    private val marker: File,
    private val key: String,
    private val delegate: DexAssistEngine,
    private val maxAttempts: Int = 1,
    private val isLowMemory: () -> Boolean = { false }
) : DexAssistEngine {
    constructor(marker: File, key: String, delegate: DexAssistEngine) : this(
        marker = marker,
        key = key,
        delegate = delegate,
        maxAttempts = 1,
        isLowMemory = { false }
    )

    private val attemptsFile: File
        get() = File(marker.parentFile, "${marker.name}.attempts")

    override fun resolve(request: DexAssistRequest): DexAssistResult =
        resolveAll(setOf(request.query), request.codePaths, request.classLoader).getValue(request.query)

    override fun resolveAll(
        queries: Set<DexAssistQuery>,
        codePaths: List<String>,
        classLoader: ClassLoader
    ): Map<DexAssistQuery, DexAssistResult> {
        if (queries.isEmpty()) return emptyMap()
        val effectiveMaxAttempts = maxAttempts.coerceAtLeast(1)
        val hasPendingMarker = runCatching { marker.isFile && marker.readText() == key }.getOrDefault(false)
        val previousAttempts = if (hasPendingMarker) {
            readRecordedAttempts(fallbackIfMissing = if (effectiveMaxAttempts == 1) 1 else effectiveMaxAttempts)
        } else {
            0
        }
        if (hasPendingMarker && previousAttempts >= effectiveMaxAttempts) {
            return queries.associateWith {
                DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED)
            }
        }
        val nextAttempt = previousAttempts + 1
        writeMarkerAtomically(marker, key)
        if (effectiveMaxAttempts > 1) {
            writeMarkerAtomically(attemptsFile, "$key\n$nextAttempt")
        }
        val allowTier2 = previousAttempts == 0 && !isLowMemory()
        try {
            return if (delegate is TieredDexAssistEngine) {
                delegate.resolveAll(queries, codePaths, classLoader, allowTier2 = allowTier2)
            } else {
                delegate.resolveAll(queries, codePaths, classLoader)
            }
        } finally {
            runCatching { marker.delete() }
            runCatching { attemptsFile.delete() }
        }
    }

    private fun readRecordedAttempts(fallbackIfMissing: Int): Int = runCatching {
        val file = attemptsFile
        if (!file.isFile) return@runCatching fallbackIfMissing
        val lines = file.readLines()
        if (lines.size >= 2 && lines[0] == key) {
            lines[1].toIntOrNull()?.coerceAtLeast(1) ?: fallbackIfMissing
        } else {
            fallbackIfMissing
        }
    }.getOrDefault(fallbackIfMissing)

    private fun writeMarkerAtomically(targetFile: File, content: String) {
        runCatching {
            val parent = targetFile.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) return@runCatching
            val temporary = File.createTempFile("${targetFile.name}.", ".tmp", parent)
            try {
                temporary.writeText(content)
                try {
                    Files.move(
                        temporary.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (_: UnsupportedOperationException) {
                    if (!temporary.renameTo(targetFile)) targetFile.writeText(content)
                } catch (_: IOException) {
                    if (!temporary.renameTo(targetFile)) targetFile.writeText(content)
                }
            } finally {
                temporary.delete()
            }
        }
    }

    companion object {
        const val TIERED_MAX_ATTEMPTS = 2
    }
}
