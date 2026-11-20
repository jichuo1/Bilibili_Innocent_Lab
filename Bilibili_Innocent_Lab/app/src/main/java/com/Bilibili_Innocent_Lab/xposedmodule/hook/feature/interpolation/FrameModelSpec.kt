package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 插帧模型的唯一规格：RIFE v4.6（ncnn 转换版），按固定大小与 SHA-256 校验。
 * 用户在模块设置中主动下载权重，
 * 来源固定为 nihui/rife-ncnn-vulkan 的指定提交，下载后写入 LSPosed 远程文件，宿主进程再复制到自身私有目录。
 */
internal object FrameModelSpec {
    const val NAME = "rife-v4.6"
    const val SOURCE_COMMIT = "a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7"
    private const val SOURCE_BASE =
        "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/$SOURCE_COMMIT/models/rife-v4.6/"

    data class Part(val fileName: String, val size: Int, val sha256: String) {
        /** LSPosed 远程文件名：扁平、带模型名，避免与其他远程文件冲突。 */
        val remoteName: String get() = "frame_model_${NAME.replace('.', '_').replace('-', '_')}_$fileName"
        val sourceUrl: String get() = SOURCE_BASE + fileName
    }

    val PARAM = Part("flownet.param", 16_532, "724569596bcd1e7b9fa50455c604777ebed99746d2ef40aa86e31b5725f1053c")
    val WEIGHTS = Part("flownet.bin", 10_614_320, "f334ed2260149ce0188a6dcf049844e8b0cdd912e01cbcfb63553157d2508958")
    val PARTS = listOf(PARAM, WEIGHTS)

    /** 流式读取并校验大小与哈希；超出声明大小立即失败。 */
    fun matches(part: Part, input: InputStream, sink: ((ByteArray, Int) -> Unit)? = null): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            total += count
            if (total > part.size) return false
            digest.update(chunk, 0, count)
            sink?.invoke(chunk, count)
        }
        return total == part.size.toLong() && hex(digest.digest()) == part.sha256
    }

    fun matches(part: Part, file: File): Boolean =
        file.isFile && file.length() == part.size.toLong() && runCatching { file.inputStream().buffered().use { matches(part, it) } }.getOrDefault(false)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
