package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 单个推理 worker 在一次同步准备会话中使用这些视图，禁止引擎或调用方保存它们。 */
internal class FrameScratchArena(storage: ByteBuffer, private val frameBytes: Int, private val inputs: Int) {
    val allocatedBytes = storageBytes(frameBytes, inputs)
    private val slots: Array<ByteBuffer>
    private var closed = false

    init {
        require(storage.isDirect && !storage.isReadOnly && storage.remaining() == allocatedBytes)
        val view = storage.slice()
        val stride = allocatedBytes / (inputs + 1)
        slots = Array(inputs + 1) { index ->
            val slot = FramePreparationMemory.region(view, index * stride, frameBytes)
            if (index < inputs) slot.asReadOnlyBuffer() else slot
        }
    }

    fun input(index: Int, bytes: Int): ByteBuffer {
        require(index in 0 until inputs)
        return view(index, bytes)
    }

    fun output(bytes: Int): ByteBuffer = view(inputs, bytes)

    private fun view(index: Int, bytes: Int): ByteBuffer {
        check(!closed) { "scratch session closed" }
        require(bytes in 1..frameBytes)
        return slots[index].apply {
            clear()
            limit(bytes)
            order(ByteOrder.BIG_ENDIAN)
        }
    }

    private fun invalidate() {
        closed = true
        slots.forEach { it.limit(0) }
    }

    companion object {
        const val MAX_BYTES = 4096 * 2160 * 3
        fun storageBytes(frameBytes: Int, inputs: Int): Int {
            require(frameBytes in 1..MAX_BYTES && inputs in 2..FrameCapabilityProbe.INPUTS)
            return FramePreparationMemory.aligned(frameBytes) * (inputs + 1)
        }

        /** 托管分配在交付前完整清零。只读输入和可写输出永久分槽，无须按尺寸或档位再次清零。 */
        fun withMemory(frameBytes: Int, inputs: Int, run: (FrameScratchArena) -> Unit) {
            FramePreparationMemory.withStorage(storageBytes(frameBytes, inputs)) { storage ->
                val arena = FrameScratchArena(storage, frameBytes, inputs)
                try {
                    run(arena)
                } finally {
                    arena.invalidate()
                }
            }
        }
    }
}
