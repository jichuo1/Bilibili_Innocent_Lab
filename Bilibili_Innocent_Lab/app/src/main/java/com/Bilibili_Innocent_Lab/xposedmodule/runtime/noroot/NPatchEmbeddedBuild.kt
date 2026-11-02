package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import androidx.annotation.WorkerThread
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 已安装 APK 内容的比较，不代表运行中进程已经重新加载，也不替代配置/协议授权。 */
internal enum class NPatchEmbeddedBuildState { NOT_APPLICABLE, MATCHED, DIFFERENT, MODULE_MISSING, UNKNOWN }

internal object NPatchEmbeddedBuild {
    private const val ENTRY = "assets/npatch/modules/${BuildConfig.APPLICATION_ID}.apk"
    private const val MAX_MODULE_BYTES = 128L * 1024 * 1024

    /** 只在诊断工作线程显式刷新时读取；不在宿主启动握手或 UI 主线程计算 APK 哈希。 */
    @WorkerThread
    fun read(context: Context): NPatchEmbeddedBuildState {
        if (NPatchRuntimeProfiles.read(context).mode != NPatchMode.EMBEDDED) {
            return NPatchEmbeddedBuildState.NOT_APPLICABLE
        }
        return runCatching {
            val host = context.packageManager.getPackageInfoOrNull(NoRootSupportState.TARGET_PACKAGE)
                ?.applicationInfo?.sourceDir ?: return NPatchEmbeddedBuildState.UNKNOWN
            val module = context.applicationInfo.sourceDir ?: return NPatchEmbeddedBuildState.UNKNOWN
            compare(File(host), File(module))
        }.getOrDefault(NPatchEmbeddedBuildState.UNKNOWN)
    }

    internal fun compare(host: File, module: File, maximumBytes: Long = MAX_MODULE_BYTES): NPatchEmbeddedBuildState =
        runCatching {
            require(maximumBytes > 0)
            // 读取期间检测到文件长度或修改时间变化时，丢弃本次结果。
            val before = listOf(host.length(), host.lastModified(), module.length(), module.lastModified())
            if (!host.isFile || !module.isFile) return NPatchEmbeddedBuildState.UNKNOWN
            val result = ZipFile(host).use { zip ->
                val entries = zip.entries().asSequence().filter { it.name == ENTRY }.toList()
                if (entries.isEmpty()) return@use NPatchEmbeddedBuildState.MODULE_MISSING
                if (entries.size != 1) return@use NPatchEmbeddedBuildState.UNKNOWN
                val entry = entries.single()
                if (entry.isDirectory || entry.size !in 1..maximumBytes || module.length() !in 1..maximumBytes) {
                    return@use NPatchEmbeddedBuildState.UNKNOWN
                }
                val embedded = zip.getInputStream(entry).use { digest(it, maximumBytes) }
                val installed = module.inputStream().use { digest(it, maximumBytes) }
                if (MessageDigest.isEqual(embedded, installed)) NPatchEmbeddedBuildState.MATCHED
                else NPatchEmbeddedBuildState.DIFFERENT
            }
            val after = listOf(host.length(), host.lastModified(), module.length(), module.lastModified())
            if (before == after) result else NPatchEmbeddedBuildState.UNKNOWN
        }.getOrDefault(NPatchEmbeddedBuildState.UNKNOWN)

    private fun digest(stream: InputStream, maximumBytes: Long): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            total += count
            check(total <= maximumBytes)
            hash.update(buffer, 0, count)
        }
        check(total > 0)
        return hash.digest()
    }
}
