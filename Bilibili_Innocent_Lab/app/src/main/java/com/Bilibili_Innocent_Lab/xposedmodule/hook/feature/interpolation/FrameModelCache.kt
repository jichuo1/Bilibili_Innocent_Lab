package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.DualChannelFdOpener
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.SecurePayloadStore
import java.io.File
import java.io.InputStream

/**
 * 宿主进程侧的模型落地：从 LSPosed 远程文件（模块设置中下载并校验后写入）复制到宿主私有
 * noBackupFilesDir，逐文件校验大小与 SHA-256 后原子改名。已存在且校验通过则直接复用。
 * 只在推理 worker 上调用（10 MB 读写），绝不在宿主主线程或解码线程执行。
 */
internal object FrameModelCache {
    private val publicationLock = Any()

    sealed interface Result {
        data class Ready(val directory: File) : Result
        data class Missing(val part: String) : Result
        data class Invalid(val part: String) : Result
    }

    /** [openRemote] 按远程文件名打开输入流（宿主侧包装 LSPosed 远程文件描述符），没有则返回 null。 */
    fun materialize(
        hostPrivateRoot: File,
        parts: List<FrameModelSpec.Part> = FrameModelSpec.PARTS,
        openRemote: (String) -> InputStream?
    ): Result {
        val directory = SecurePayloadStore.prepareDirectory(
            root = hostPrivateRoot,
            relativePath = "innocent-frame-model/${FrameModelSpec.NAME}",
            enforcePermissions = true
        ).getOrElse { return Result.Invalid("directory") }

        for (part in parts) {
            val target = File(directory, part.fileName)
            if (SecurePayloadStore.isSafeRegularFile(target) && FrameModelSpec.matches(part, target)) continue
            val source = DualChannelFdOpener.openReadOnly(
                openRemote = { openRemote(part.remoteName) },
                openProvider = { null }
            ) ?: return Result.Missing(part.fileName)
            var consumed = false
            val installed = try {
                SecurePayloadStore.installVerified(
                    directory = directory,
                    targetFileName = part.fileName,
                    expectedSize = part.size.toLong(),
                    expectedSha256 = part.sha256,
                    input = {
                        consumed = true
                        source
                    },
                    tempPrefix = "${part.fileName}.",
                    tempSuffix = ".tmp",
                    enforcePermissions = true,
                    publicationLock = publicationLock,
                    isExistingValid = { existing ->
                        SecurePayloadStore.isSafeRegularFile(existing) && FrameModelSpec.matches(part, existing)
                    }
                )
            } finally {
                if (!consumed) runCatching { source.close() }
            }
            // Another process may have atomically published the same pinned content first.
            if (installed.isFailure && (!SecurePayloadStore.isSafeRegularFile(target) || !FrameModelSpec.matches(part, target))) {
                return Result.Invalid(part.fileName)
            }
        }
        return Result.Ready(directory)
    }
}
