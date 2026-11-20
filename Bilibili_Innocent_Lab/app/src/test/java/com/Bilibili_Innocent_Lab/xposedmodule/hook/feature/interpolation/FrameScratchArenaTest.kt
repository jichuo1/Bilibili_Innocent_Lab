package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ReadOnlyBufferException

class FrameScratchArenaTest {
    @Test fun oddSizedFramesUseDisjointAlignedRangesOfOneAllocation() {
        val size = 99
        val total = FrameScratchArena.storageBytes(size, 4)
        assertEquals(5 * 128, total)
        val storage = ByteBuffer.allocateDirect(total)
        val arena = FrameScratchArena(storage, size, 4)
        // 通过底层偏移区分四份输入，验证它们没有别名。
        repeat(4) { index -> storage.put(index * 128, (index + 1).toByte()) }
        repeat(4) { index ->
            val input = arena.input(index, size)
            assertTrue(input.isReadOnly && input.isDirect)
            assertEquals(index + 1, input.get(0).toInt())
            assertThrows(ReadOnlyBufferException::class.java) { input.put(0, 42) }
        }
        val output = arena.output(size)
        output.put(0, 42)
        assertEquals(42, storage.get(4 * 128).toInt())
        assertEquals(total, arena.allocatedBytes)
    }

    @Test fun reusingInputAndOutputRestoresBoundsAndByteOrder() {
        val size = 200
        val arena = FrameScratchArena(ByteBuffer.allocateDirect(FrameScratchArena.storageBytes(size, 2)), size, 2)
        val input = arena.input(1, size)
        input.order(ByteOrder.LITTLE_ENDIAN).position(80)
        val output = arena.output(size)
        output.put(7, 42)
        output.position(85)
        assertSame(input, arena.input(1, 12))
        assertSame(output, arena.output(8))
        assertEquals(0, input.position())
        assertEquals(12, input.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, input.order())
        assertEquals(0, output.position())
        assertEquals(8, output.limit())
        assertEquals(42, output.get(7).toInt())
    }

    @Test fun invalidSessionOrAccessIsRejectedBeforeUse() {
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena.storageBytes(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena.storageBytes(Int.MAX_VALUE, 4) }
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena.storageBytes(10, 5) }
        val total = FrameScratchArena.storageBytes(100, 2)
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena(ByteBuffer.allocate(total), 100, 2) }
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena(ByteBuffer.allocateDirect(total - 1), 100, 2) }
        val arena = FrameScratchArena(ByteBuffer.allocateDirect(total), 100, 2)
        assertThrows(IllegalArgumentException::class.java) { arena.input(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { arena.output(101) }
        assertEquals(3 * 128, total)
    }
}
