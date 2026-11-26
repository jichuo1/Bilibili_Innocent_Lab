package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAsset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAssetSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAssetStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader
import io.github.jichuo1.meguviz.memory.android.ManagedMemoryDomain
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Cold model worker only. ORT synchronously parses the pinned ONNX protobuf during session creation. */
internal object AudioModelInput {
    private const val ALIGNMENT = 64L
    private const val SKIP_NATIVE_LOAD_PROP = "io.github.jichuo1.meguviz.memory.skipNativeLoad"
    private val domain by lazy {
        try {
            if (!java.lang.Boolean.getBoolean(SKIP_NATIVE_LOAD_PROP)) {
                ModuleNativeLoader.load("mgmem")
                ModuleNativeLoader.load("mgmem_pressure_jni")
                System.setProperty(SKIP_NATIVE_LOAD_PROP, "true")
            }
            ManagedMemoryDomain.system((AudioAssetSpec.model.size + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT)
        } catch (_: LinkageError) {
            null
        }
    }

    fun withVerified(file: File, createSession: (ByteBuffer) -> Unit) {
        val part = AudioAssetSpec.model
        val managed = domain
        if (managed != null) {
            managed.allocate(part.size, ALIGNMENT).use { owner ->
                owner.borrowWrite().use { lease ->
                    lease.withDirectBuffer { buffer ->
                        readVerified(file, part, buffer)
                        createSession(buffer.asReadOnlyBuffer())
                    }
                }
            }
            return
        }
        val fallback = ByteBuffer.allocateDirect(part.size.toInt())
        readVerified(file, part, fallback)
        createSession(fallback.asReadOnlyBuffer())
    }

    internal fun readVerified(file: File, part: AudioAsset, buffer: ByteBuffer) {
        require(buffer.isDirect && !buffer.isReadOnly && buffer.position() == 0 && buffer.remaining().toLong() == part.size)
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            AudioAssetStore.verify(part, input, object : OutputStream() {
                override fun write(value: Int) { buffer.put(value.toByte()) }
                override fun write(bytes: ByteArray, offset: Int, count: Int) { buffer.put(bytes, offset, count) }
            })
        }
        buffer.flip()
    }
}
