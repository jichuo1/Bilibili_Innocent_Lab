package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import java.io.File

/**
 * 目标 App 私有缓存路径的统一计算。
 *
 * Hook 运行在 B 站自身 uid 下，因此可读写该 uid 对应的 data 目录；不能把用户
 * 固定为 0，否则工作资料、双开或多用户环境会落到错误的目录。
 */
object TargetAppStorage {

    private const val TARGET_PACKAGE = "tv.danmaku.bili"

    /** Android uid 的 userId 部分；公开以便 JVM 单测覆盖多用户路径。 */
    fun userIdFromUid(uid: Int): Int = AndroidUserSpace.userIdFromUid(uid)

    /** 生成目标 App 缓存文件的绝对路径，不依赖 Context。 */
    @SuppressLint("SdCardPath") // Hook 运行时无模块 Context，且目标 App uid 目录是有意路径。
    fun cachePath(fileName: String, userId: Int): String {
        requireValidCacheFileName(fileName)
        require(userId >= 0) { "userId must not be negative" }
        return "/data/user/$userId/$TARGET_PACKAGE/cache/$fileName"
    }

    /** 使用当前宿主 Context cacheDir 或进程 uid 回退解析目标 App 缓存文件。 */
    fun cacheFile(fileName: String, context: Context? = null): File {
        requireValidCacheFileName(fileName)
        val cacheDir = runCatching {
            context?.cacheDir ?: ReflectAccess.currentApplication()?.cacheDir
        }.getOrNull()
        if (cacheDir != null) return File(cacheDir, fileName)
        return File(cachePath(fileName, userIdFromUid(Process.myUid())))
    }

    private fun requireValidCacheFileName(fileName: String) {
        require(
            fileName.isNotBlank() &&
                !fileName.contains('/') &&
                !fileName.contains('\\') &&
                fileName != "." &&
                fileName != ".."
        ) {
            "cache file name must be a single path segment"
        }
    }
}
