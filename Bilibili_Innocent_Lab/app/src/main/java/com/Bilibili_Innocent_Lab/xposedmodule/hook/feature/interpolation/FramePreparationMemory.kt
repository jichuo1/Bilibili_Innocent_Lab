package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader
import io.github.jichuo1.meguviz.memory.android.ManagedMemoryDomain
import java.nio.ByteBuffer

/** 引擎随宿主进程存续。模型加载和探测共享预算，每次归还所有者后仅域元数据跨会话保留。 */
internal object FramePreparationMemory {
    const val ALIGNMENT = 64
    private const val SKIP_NATIVE_LOAD_PROP = "io.github.jichuo1.meguviz.memory.skipNativeLoad"
    @Volatile
    private var nativeMemoryUnavailable = false
    private val domain: ManagedMemoryDomain? by lazy {
        try {
            if (!java.lang.Boolean.getBoolean(SKIP_NATIVE_LOAD_PROP)) {
                ModuleNativeLoader.load("mgmem")
                ModuleNativeLoader.load("mgmem_pressure_jni")
                System.setProperty(SKIP_NATIVE_LOAD_PROP, "true")
            }
            ManagedMemoryDomain.system(FrameScratchArena.storageBytes(FrameScratchArena.MAX_BYTES, FrameCapabilityProbe.INPUTS).toLong())
        } catch (_: LinkageError) {
            nativeMemoryUnavailable = true
            null
        }
    }

    fun aligned(bytes: Int): Int = (bytes + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT

    fun region(storage: ByteBuffer, offset: Int, bytes: Int): ByteBuffer =
        storage.duplicate().apply {
            position(offset)
            limit(offset + bytes)
        }.slice()

    fun withStorage(bytes: Int, run: (ByteBuffer) -> Unit) =
        withStorageUsing(bytes = bytes, run = run)

    internal fun withStorageUsing(
        bytes: Int,
        allocateManaged: (Int, (ByteBuffer) -> Unit) -> Unit = ::allocateManagedStorage,
        run: (ByteBuffer) -> Unit
    ) {
        if (!nativeMemoryUnavailable || allocateManaged !== ::allocateManagedStorage) {
            var callbackEntered = false
            try {
                allocateManaged(bytes) { storage ->
                    callbackEntered = true
                    run(storage)
                }
                return
            } catch (error: LinkageError) {
                if (callbackEntered) throw error
                if (allocateManaged === ::allocateManagedStorage) {
                    nativeMemoryUnavailable = true
                }
            }
        }
        run(ByteBuffer.allocateDirect(bytes))
    }

    internal fun resetFallbackForTest() {
        nativeMemoryUnavailable = false
    }

    private fun allocateManagedStorage(bytes: Int, consume: (ByteBuffer) -> Unit) {
        val currentDomain = domain ?: throw UnsatisfiedLinkError("mgmem_unavailable")
        currentDomain.allocate(bytes.toLong(), ALIGNMENT.toLong()).use { allocation ->
            allocation.borrowWrite().use { lease ->
                lease.withDirectBuffer { storage -> consume(storage) }
            }
        }
    }
}
