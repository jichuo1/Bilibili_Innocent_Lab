package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import io.github.jichuo1.meguviz.memory.android.ManagedMemoryDomain
import java.nio.ByteBuffer

/** 引擎随宿主进程存续。模型加载和探测共享预算，每次归还所有者后仅域元数据跨会话保留。 */
internal object FramePreparationMemory {
    const val ALIGNMENT = 64
    private val domain by lazy {
        ManagedMemoryDomain.system(FrameScratchArena.storageBytes(FrameScratchArena.MAX_BYTES, FrameCapabilityProbe.INPUTS).toLong())
    }

    fun aligned(bytes: Int): Int = (bytes + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT

    fun region(storage: ByteBuffer, offset: Int, bytes: Int): ByteBuffer =
        storage.duplicate().apply {
            position(offset)
            limit(offset + bytes)
        }.slice()

    fun withStorage(bytes: Int, run: (ByteBuffer) -> Unit) {
        domain.allocate(bytes.toLong(), ALIGNMENT.toLong()).use { allocation ->
            allocation.borrowWrite().use { lease ->
                lease.withDirectBuffer { storage -> run(storage) }
            }
        }
    }
}
